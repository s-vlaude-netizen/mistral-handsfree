package de.localvoice.mistralhandsfree.mistral

import java.io.Closeable
import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal val MistralJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/** A response whose headers have arrived; the body has not been read yet. */
internal class Exchange(val call: Call, val response: Response) : Closeable {
    override fun close() = response.close()
}

/** What one server-sent event contributed to a stream. */
internal class Parsed<T>(val items: List<T> = emptyList(), val done: Boolean = false)

/** What the reading side of a stream hands over to whoever collects it. */
private sealed interface Signal<out T> {
    class Item<T>(val value: T) : Signal<T>
    class Failed(val error: MistralException) : Signal<Nothing>
}

/** One round of reading a stream. */
private sealed interface Step<out T> {
    class Items<T>(val items: List<T>, val done: Boolean) : Step<T>
    data object End : Step<Nothing>
}

/**
 * The part of the Mistral API client that every endpoint shares: authentication,
 * mapping HTTP failures to [MistralException], retrying what is worth retrying,
 * and reading server-sent events.
 *
 * Kept free of Android classes on purpose, so it can be tested against a local
 * HTTP server.
 *
 * @param apiKey asked for on every request rather than captured once, so that
 * signing out or replacing the key takes effect immediately.
 * @param sleep how backoff waits; replaced in tests so they do not take real time.
 */
class MistralHttp(
    internal val client: OkHttpClient,
    private val apiKey: () -> String?,
    val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
    private val userAgent: String = "HandsfreeForMistral",
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {

    /**
     * A request builder with authentication set.
     *
     * @param keyOverride used to check a key the user has just typed in, before
     * it is stored.
     */
    internal fun request(path: String, keyOverride: String? = null): Request.Builder {
        val token = keyOverride ?: apiKey()
        if (token.isNullOrBlank()) {
            throw MistralException(MistralException.Kind.UNAUTHORIZED, "No API key set")
        }
        val url = baseUrl.resolve(path)
            ?: throw MistralException(MistralException.Kind.PROTOCOL, "Bad path: $path")
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", userAgent)
    }

    /**
     * Sends [request] and returns once the headers are in and the status is a
     * success. Transient failures (rate limit, 5xx, no connection) are retried;
     * anything else is thrown as [MistralException] immediately.
     *
     * Retrying is only safe because nothing has been read from the body yet - a
     * retry can never repeat text that was already spoken.
     */
    internal suspend fun open(request: Request, maxAttempts: Int = DEFAULT_ATTEMPTS): Exchange {
        var attempt = 0
        while (true) {
            attempt++
            val call = client.newCall(request)
            val failure: MistralException = try {
                val response = call.await()
                if (response.isSuccessful) return Exchange(call, response)
                response.toException()
            } catch (e: IOException) {
                MistralException(
                    MistralException.Kind.NETWORK,
                    "Network error: ${e.message ?: e.javaClass.simpleName}",
                    cause = e,
                )
            }
            if (!failure.isTransient || attempt >= maxAttempts) throw failure
            sleep(backoffMs(failure, attempt))
        }
    }

    /** GET [path] and parse the body as JSON. */
    internal suspend fun getJson(path: String, keyOverride: String? = null): JsonElement {
        val request = request(path, keyOverride)
            .header("Accept", "application/json")
            .get()
            .build()
        open(request).use { return readJson(it) }
    }

    /** Reads a small JSON body off the main thread. */
    internal suspend fun readJson(exchange: Exchange): JsonElement = withContext(Dispatchers.IO) {
        val text = try {
            exchange.response.body?.string().orEmpty()
        } catch (e: IOException) {
            throw MistralException(
                MistralException.Kind.NETWORK,
                "Network error: ${e.message ?: e.javaClass.simpleName}",
                cause = e,
            )
        }
        try {
            MistralJson.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw MistralException(
                MistralException.Kind.PROTOCOL,
                "Mistral sent something that is not JSON",
                cause = e,
            )
        }
    }

    /**
     * Opens [request] and turns the server-sent events of the answer into a flow.
     *
     * Two things here are easy to get wrong:
     *
     * *Cancellation.* A coroutine cannot be cancelled out of a thread that is
     * blocked reading a socket; it only notices when the read returns - which,
     * while a model is thinking and sends nothing but keep-alives, can be a long
     * time. So a second coroutine sits suspended next to the reader (suspended
     * code *is* reached by cancellation) and cancels the HTTP call when its time
     * comes, which makes the blocked read fail at once.
     *
     * *Order of failure and data.* If the reader simply threw, text that is still
     * buffered would be thrown away with it. A failure is therefore sent as a
     * value behind the text it follows, and raised only when the collector
     * gets to it.
     */
    internal fun <T : Any> streamEvents(
        request: Request,
        parse: (SseEvent) -> Parsed<T>,
    ): Flow<T> = channelFlow<Signal<T>> {
        val exchange = try {
            open(request)
        } catch (e: MistralException) {
            send(Signal.Failed(e))
            return@channelFlow
        }

        var finished = false
        val watcher = launch {
            try {
                awaitCancellation()
            } finally {
                if (!finished) exchange.call.cancel()
            }
        }
        try {
            val source = exchange.response.body?.source()
            if (source == null) {
                send(Signal.Failed(MistralException(MistralException.Kind.PROTOCOL, "Empty response")))
                return@channelFlow
            }
            val reader = SseReader(source)
            var failure: MistralException? = null
            while (true) {
                val step = try {
                    nextStep(reader, exchange, parse)
                } catch (e: MistralException) {
                    failure = e
                    break
                }
                when (step) {
                    Step.End -> break
                    is Step.Items -> {
                        for (item in step.items) send(Signal.Item(item))
                        if (step.done) break
                    }
                }
            }
            failure?.let { send(Signal.Failed(it)) }
        } finally {
            finished = true
            watcher.cancel()
            exchange.close()
        }
    }.flowOn(Dispatchers.IO).map { signal ->
        when (signal) {
            is Signal.Item -> signal.value
            is Signal.Failed -> throw signal.error
        }
    }

    /** Reads and parses the next event. Blocks. */
    private fun <T : Any> nextStep(
        reader: SseReader,
        exchange: Exchange,
        parse: (SseEvent) -> Parsed<T>,
    ): Step<T> {
        val event = try {
            reader.next()
        } catch (e: IOException) {
            throw brokenConnection(exchange, e)
        } ?: return Step.End
        val parsed = parse(event)
        return Step.Items(parsed.items, parsed.done)
    }

    /** A read that fails because we cancelled the call is a cancellation, not an error. */
    private fun brokenConnection(exchange: Exchange, cause: IOException): Exception =
        if (exchange.call.isCanceled()) {
            CancellationException("Request cancelled").apply { initCause(cause) }
        } else {
            MistralException(
                MistralException.Kind.NETWORK,
                "The connection to Mistral broke: ${cause.message ?: cause.javaClass.simpleName}",
                cause = cause,
            )
        }

    private fun backoffMs(failure: MistralException, attempt: Int): Long =
        when (failure.kind) {
            MistralException.Kind.RATE_LIMITED ->
                (failure.retryAfterMs ?: (1_000L * attempt)).coerceIn(500L, 8_000L)

            else -> 400L * attempt
        }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.mistral.ai/v1/"

        /** One try plus two retries. */
        const val DEFAULT_ATTEMPTS = 3
    }
}

/** Suspends until the response headers arrive; cancelling the coroutine cancels the call. */
@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            // If the coroutine was cancelled in the meantime nobody will read this response.
            continuation.resume(response) { _, _, _ -> response.close() }
        }
    })
}

/** Turns a failed response into an exception, reading (and closing) its body. */
internal fun Response.toException(): MistralException {
    val text = try {
        body?.string().orEmpty()
    } catch (_: IOException) {
        ""
    } finally {
        close()
    }
    val serverMessage = ErrorBody.message(text)
    val retryAfterMs = header("Retry-After")
        ?.trim()
        ?.toDoubleOrNull()
        ?.let { (it * 1000).toLong().coerceIn(0L, 60_000L) }
    return MistralException(
        kind = kindForStatus(code),
        message = "HTTP $code" + (serverMessage?.let { ": $it" } ?: ""),
        httpCode = code,
        retryAfterMs = retryAfterMs,
        serverMessage = serverMessage,
    )
}

internal fun kindForStatus(code: Int): MistralException.Kind = when (code) {
    401 -> MistralException.Kind.UNAUTHORIZED
    403 -> MistralException.Kind.FORBIDDEN
    429 -> MistralException.Kind.RATE_LIMITED
    400, 404, 413, 422 -> MistralException.Kind.BAD_REQUEST
    in 500..599 -> MistralException.Kind.SERVER
    else -> MistralException.Kind.UNKNOWN
}
