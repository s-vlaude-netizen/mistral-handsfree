package de.localvoice.mistralhandsfree.mistral

import de.localvoice.mistralhandsfree.domain.PromptMessage
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Runs the real client code against a local HTTP server - request building,
 * server-sent events, error mapping, retries and cancellation are all exercised
 * for real, only the Mistral side is played by [MockWebServer].
 */
class MistralClientTest {

    private lateinit var server: MockWebServer
    private val sleeps = mutableListOf<Long>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        sleeps.clear()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(key: String? = "test-key"): MistralClient {
        val http = MistralHttp(
            client = OkHttpClient(),
            apiKey = { key },
            baseUrl = server.url("/v1/"),
            userAgent = "test-agent",
            sleep = { sleeps += it },
        )
        return MistralClient(http)
    }

    private fun sse(vararg data: String): String = data.joinToString("") { "data: $it\n\n" }

    private fun delta(text: String) =
        """{"id":"1","object":"chat.completion.chunk","model":"m","choices":[{"index":0,"delta":{"content":"$text"},"finish_reason":null}]}"""

    private val question = listOf(
        PromptMessage("system", "Be brief."),
        PromptMessage("user", "Hello"),
    )

    private suspend fun collect(client: MistralClient): List<String> =
        client.streamChat(question, ChatParams(model = "mistral-small-latest")).toList()

    private fun sseResponse(body: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)

    // ------------------------------------------------------------- streaming

    @Test
    fun `streams the text deltas and stops at DONE`() = runBlocking {
        server.enqueue(
            sseResponse(
                sse(
                    """{"choices":[{"index":0,"delta":{"role":"assistant","content":""}}]}""",
                    delta("Hel"),
                    delta("lo"),
                    """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"total_tokens":5}}""",
                    "[DONE]",
                    delta("never read"),
                ),
            ),
        )

        assertEquals(listOf("Hel", "lo"), collect(client()))
    }

    @Test
    fun `sends the request the API expects`() = runBlocking {
        server.enqueue(sseResponse(sse("[DONE]")))

        client().streamChat(
            question,
            ChatParams(model = "mistral-large-latest", temperature = 0.5, topP = 0.9, maxTokens = 300),
        ).toList()

        val request = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(request)
        request!!
        assertEquals("POST", request.method)
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        assertEquals("test-agent", request.getHeader("User-Agent"))
        assertEquals("text/event-stream", request.getHeader("Accept"))

        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("mistral-large-latest", body["model"]!!.jsonPrimitive.content)
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)
        assertEquals("0.5", body["temperature"]!!.jsonPrimitive.content)
        assertEquals("0.9", body["top_p"]!!.jsonPrimitive.content)
        assertEquals("300", body["max_tokens"]!!.jsonPrimitive.content)
        val messages: JsonArray = body["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("Hello", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `leaves out sampling settings that were not given`() = runBlocking {
        server.enqueue(sseResponse(sse("[DONE]")))
        client().streamChat(question, ChatParams(model = "m")).toList()

        val body: JsonObject = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertTrue("temperature" !in body)
        assertTrue("top_p" !in body)
        assertTrue("max_tokens" !in body)
    }

    @Test
    fun `reads the answer out of chunked content and skips the thinking`() = runBlocking {
        val thinking = """{"choices":[{"index":0,"delta":{"content":[{"type":"thinking","thinking":[{"type":"text","text":"let me think"}]}]}}]}"""
        val answer = """{"choices":[{"index":0,"delta":{"content":[{"type":"text","text":"Forty-two."}]}}]}"""
        server.enqueue(sseResponse(sse(thinking, answer, "[DONE]")))

        assertEquals(listOf("Forty-two."), collect(client()))
    }

    @Test
    fun `ignores null content and chunks without choices`() = runBlocking {
        server.enqueue(
            sseResponse(
                sse(
                    """{"choices":[{"index":0,"delta":{"content":null}}]}""",
                    """{"usage":{"total_tokens":3}}""",
                    """{"choices":[],"error":null}""",
                    delta("ok"),
                    "[DONE]",
                ),
            ),
        )
        assertEquals(listOf("ok"), collect(client()))
    }

    @Test
    fun `an answer that ends without DONE still completes`() = runBlocking {
        server.enqueue(sseResponse(sse(delta("a"), delta("b"))))
        assertEquals(listOf("a", "b"), collect(client()))
    }

    @Test
    fun `an error object in the middle of the stream ends it with an exception`() = runBlocking {
        server.enqueue(
            sseResponse(sse(delta("Hi"), """{"object":"error","message":"model overloaded","code":503}""")),
        )

        val received = mutableListOf<String>()
        try {
            client().streamChat(question, ChatParams("m")).collect { received += it }
            fail("expected an exception")
        } catch (e: MistralException) {
            assertEquals(MistralException.Kind.SERVER, e.kind)
            assertEquals("model overloaded", e.serverMessage)
        }
        assertEquals(listOf("Hi"), received)
    }

    @Test
    fun `an unreadable chunk is a protocol error`() = runBlocking {
        server.enqueue(sseResponse("data: this is not json\n\n"))
        try {
            collect(client())
            fail("expected an exception")
        } catch (e: MistralException) {
            assertEquals(MistralException.Kind.PROTOCOL, e.kind)
        }
    }

    // ---------------------------------------------------------------- errors

    @Test
    fun `a rejected key is UNAUTHORIZED with the server's message and is not retried`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"Invalid API Key"}"""))
        try {
            collect(client())
            fail("expected an exception")
        } catch (e: MistralException) {
            assertEquals(MistralException.Kind.UNAUTHORIZED, e.kind)
            assertEquals(401, e.httpCode)
            assertEquals("Invalid API Key", e.serverMessage)
        }
        assertEquals(1, server.requestCount)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `validation errors are BAD_REQUEST with a readable message and are not retried`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(422).setBody(
                """{"detail":[{"type":"missing","loc":["body","model"],"msg":"Field required"}]}""",
            ),
        )
        try {
            collect(client())
            fail("expected an exception")
        } catch (e: MistralException) {
            assertEquals(MistralException.Kind.BAD_REQUEST, e.kind)
            assertEquals("body.model: Field required", e.serverMessage)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a 403 carries the reason`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"Content blocked by moderation"}"""))
        try {
            collect(client())
            fail("expected an exception")
        } catch (e: MistralException) {
            assertEquals(MistralException.Kind.FORBIDDEN, e.kind)
            assertEquals("Content blocked by moderation", e.serverMessage)
        }
    }

    @Test
    fun `rate limits are retried after the time the server asks for`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "2").setBody("""{"message":"slow down"}"""))
        server.enqueue(sseResponse(sse(delta("fine"), "[DONE]")))

        assertEquals(listOf("fine"), collect(client()))
        assertEquals(2, server.requestCount)
        assertEquals(listOf(2000L), sleeps)
    }

    @Test
    fun `gives up on a rate limit after the last attempt`() = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(429).setBody("""{"message":"slow down"}""")) }
        try {
            collect(client())
            fail("expected an exception")
        } catch (e: MistralException) {
            assertEquals(MistralException.Kind.RATE_LIMITED, e.kind)
        }
        assertEquals(3, server.requestCount)
        // Without a Retry-After header: 1 s, then 2 s.
        assertEquals(listOf(1000L, 2000L), sleeps)
    }

    @Test
    fun `server errors are retried`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"message":"unavailable"}"""))
        server.enqueue(sseResponse(sse(delta("back"), "[DONE]")))

        assertEquals(listOf("back"), collect(client()))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `an HTML error page from a proxy is not shown to the user`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("<html><body>Bad gateway</body></html>"))
        try {
            collect(client())
            fail("expected an exception")
        } catch (e: MistralException) {
            assertEquals(null, e.serverMessage)
        }
    }

    @Test
    fun `without a key nothing is sent`() = runBlocking {
        try {
            collect(client(key = null))
            fail("expected an exception")
        } catch (e: MistralException) {
            assertEquals(MistralException.Kind.UNAUTHORIZED, e.kind)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a connection that breaks mid-answer is a network error`() = runBlocking {
        server.enqueue(
            sseResponse(sse(delta("start")) + "x".repeat(2000))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        try {
            collect(client())
            fail("expected an exception")
        } catch (e: MistralException) {
            assertEquals(MistralException.Kind.NETWORK, e.kind)
        }
    }

    @Test
    fun `an unreachable server is a network error after retries`() = runBlocking {
        val deadUrl = server.url("/v1/")
        server.shutdown()
        val http = MistralHttp(OkHttpClient(), { "k" }, deadUrl, sleep = { sleeps += it })
        try {
            MistralClient(http).streamChat(question, ChatParams("m")).toList()
            fail("expected an exception")
        } catch (e: MistralException) {
            assertEquals(MistralException.Kind.NETWORK, e.kind)
        }
        assertEquals(2, sleeps.size)
    }

    // ---------------------------------------------------------- cancellation

    @Test
    fun `cancelling the collector interrupts a read that is waiting for data`() = runBlocking {
        // A slow answer: the first event arrives quickly, the rest trickles in for minutes.
        val firstEvent = sse(delta("first"))
        val trickle = ": keep-alive\n".repeat(4000)
        server.enqueue(sseResponse(firstEvent + trickle).throttleBody(60, 100, TimeUnit.MILLISECONDS))

        val gotFirst = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            client().streamChat(question, ChatParams("m")).collect {
                gotFirst.complete(Unit)
            }
        }
        withTimeout(10_000) { gotFirst.await() }

        val started = System.nanoTime()
        job.cancel()
        withTimeout(5_000) { job.join() }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("cancel should not wait for the stream to end (took $tookMs ms)", tookMs < 3_000)
    }

    // ---------------------------------------------------------------- models

    private val modelsJson = """
        {"object":"list","data":[
          {"id":"mistral-small-2506","object":"model","capabilities":{"completion_chat":true},"description":"Small","max_context_length":131072},
          {"id":"mistral-small-latest","object":"model","capabilities":{"completion_chat":true,"function_calling":true},"description":"Small latest","max_context_length":131072},
          {"id":"magistral-medium-latest","object":"model","capabilities":{"completion_chat":true,"reasoning":true}},
          {"id":"mistral-embed","object":"model","capabilities":{"completion_chat":false}},
          {"id":"secret-model","object":"model","internal":true,"capabilities":{"completion_chat":true}},
          {"id":"voxtral-mini-tts-2603","object":"model","capabilities":{"completion_chat":false,"audio_speech":true}},
          {"id":"voxtral-mini-tts-2512","object":"model","capabilities":{"completion_chat":false,"audio_speech":true}},
          {"id":"voxtral-mini-latest","object":"model","capabilities":{"completion_chat":false,"audio_transcription":true}},
          {"id":"voxtral-mini-transcribe-realtime-2602","object":"model","capabilities":{"audio_transcription":true,"audio_transcription_realtime":true}}
        ]}
    """.trimIndent()

    @Test
    fun `lists chat models with the latest aliases first and the audio models picked out`() = runBlocking {
        server.enqueue(MockResponse().setBody(modelsJson))

        val catalog = client().listModels()

        assertEquals(
            listOf("magistral-medium-latest", "mistral-small-latest", "mistral-small-2506"),
            catalog.chatModels.map { it.id },
        )
        assertTrue(catalog.chatModels.first { it.id == "magistral-medium-latest" }.reasoning)
        assertEquals(131072, catalog.chatModels.first { it.id == "mistral-small-latest" }.contextLength)
        // No "-latest" for speech: the newest dated model wins.
        assertEquals("voxtral-mini-tts-2603", catalog.speechModelId)
        // The realtime (websocket) model is a different API and must not be chosen.
        assertEquals("voxtral-mini-latest", catalog.transcriptionModelId)

        assertEquals("/v1/models", server.takeRequest().path)
    }

    @Test
    fun `checking a new key uses that key and not the stored one`() = runBlocking {
        server.enqueue(MockResponse().setBody(modelsJson))
        client(key = "stored-key").listModels(keyOverride = "candidate-key")
        assertEquals("Bearer candidate-key", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `a model list in an unexpected shape is a protocol error`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"models":[]}"""))
        try {
            client().listModels()
            fail("expected an exception")
        } catch (e: MistralException) {
            assertEquals(MistralException.Kind.PROTOCOL, e.kind)
        }
    }

    @Test
    fun `a bad key while listing models is UNAUTHORIZED`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"Invalid API Key"}"""))
        try {
            client().listModels(keyOverride = "wrong")
            fail("expected an exception")
        } catch (e: MistralException) {
            assertEquals(MistralException.Kind.UNAUTHORIZED, e.kind)
        }
    }

    @Test
    fun `many requests in parallel do not interfere`() = runBlocking {
        val count = 8
        repeat(count) { server.enqueue(sseResponse(sse(delta("x"), "[DONE]"))) }
        val client = client()
        val results = withContext(Dispatchers.Default) {
            (1..count).map { async { collect(client) } }.map { it.await() }
        }
        assertTrue(results.all { it == listOf("x") })
    }
}
