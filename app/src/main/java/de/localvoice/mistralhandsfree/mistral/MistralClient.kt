package de.localvoice.mistralhandsfree.mistral

import de.localvoice.mistralhandsfree.domain.PromptMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Sampling settings for one chat request. Null means "let the model use its default". */
data class ChatParams(
    val model: String,
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxTokens: Int? = null,
)

/** A model that can be used for chat, as listed by `GET /v1/models`. */
data class ChatModel(
    val id: String,
    val description: String?,
    val contextLength: Int?,
    val reasoning: Boolean,
)

/**
 * What the account can use right now. The audio model ids are looked up here
 * instead of being hard-coded, so a renamed or retired voice model does not
 * break speech output.
 */
data class ModelCatalog(
    val chatModels: List<ChatModel>,
    val speechModelId: String?,
    val transcriptionModelId: String?,
)

/** Chat completions and the model list. */
class MistralClient(private val http: MistralHttp) {

    /**
     * Streams the answer to [messages] as text deltas.
     *
     * Transient failures before the first word are retried by [MistralHttp];
     * once text is flowing, a failure ends the flow with a [MistralException]
     * and what was received so far stays valid.
     */
    fun streamChat(messages: List<PromptMessage>, params: ChatParams): Flow<String> = flow {
        val body = buildJsonObject {
            put("model", params.model)
            put("stream", true)
            putJsonArray("messages") {
                for (message in messages) {
                    addJsonObject {
                        put("role", message.role)
                        put("content", message.content)
                    }
                }
            }
            params.temperature?.let { put("temperature", it) }
            params.topP?.let { put("top_p", it) }
            params.maxTokens?.let { put("max_tokens", it) }
        }
        // Built inside the flow so that a missing key surfaces to the collector
        // like every other failure instead of throwing at call time.
        val request = http.request("chat/completions")
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody(JSON))
            .build()
        emitAll(http.streamEvents(request, ::parseChatEvent))
    }

    /**
     * Lists the models, which doubles as the cheapest authenticated call there
     * is - so it is also how a freshly entered key is checked ([keyOverride]).
     */
    suspend fun listModels(keyOverride: String? = null): ModelCatalog =
        parseModelCatalog(http.getJson("models", keyOverride))

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** One `data:` payload of a chat stream. */
internal fun parseChatEvent(event: SseEvent): Parsed<String> {
    val data = event.data.trim()
    if (data == "[DONE]") return Parsed(done = true)

    val root = parseJsonOrNull(data).obj()
        ?: throw MistralException(MistralException.Kind.PROTOCOL, "Unreadable chunk in the answer stream")
    throwIfErrorObject(root)

    val delta = root["choices"].arr()?.firstOrNull().obj()?.get("delta").obj()
    val text = contentText(delta?.get("content"))
    return if (text.isEmpty()) Parsed() else Parsed(listOf(text))
}

/**
 * Mistral can report a failure inside an already running stream, as an object
 * with `"object": "error"` (or an OpenAI-style `error` member) instead of a
 * normal chunk.
 */
internal fun throwIfErrorObject(root: JsonObject) {
    // `"error": null` shows up in ordinary chunks of some APIs - only a real value counts.
    val errorMember = root["error"]
    val isError = root["object"].str() == "error" || (errorMember != null && errorMember !is JsonNull)
    if (!isError) return
    val message = ErrorBody.fromJson(root) ?: "Mistral reported an error"
    val code = root["code"].int() ?: root["error"].obj()?.get("code").int()
    throw MistralException(
        kind = if (code != null) kindForStatus(code) else MistralException.Kind.UNKNOWN,
        message = message,
        httpCode = code,
        serverMessage = message,
    )
}

internal fun parseModelCatalog(root: JsonElement): ModelCatalog {
    val items = root.obj()?.get("data").arr()
        ?: throw MistralException(MistralException.Kind.PROTOCOL, "The model list has an unexpected shape")

    // `internal` marks models that are not meant for customers.
    val cards = items.mapNotNull { it.obj() }.filter { it["internal"].bool() != true }

    fun JsonObject.can(capability: String): Boolean =
        this["capabilities"].obj()?.get(capability).bool() == true

    val chatModels = cards
        .filter { it.can("completion_chat") }
        .mapNotNull { card ->
            val id = card["id"].str() ?: return@mapNotNull null
            ChatModel(
                id = id,
                description = card["description"].str()?.takeIf { it.isNotBlank() },
                contextLength = card["max_context_length"].int(),
                reasoning = card.can("reasoning"),
            )
        }
        .distinctBy { it.id }
        // "-latest" aliases first: they are what people mean, and they keep working.
        .sortedWith(compareByDescending<ChatModel> { it.id.endsWith("-latest") }.thenBy { it.id })

    fun pick(ids: List<String>): String? =
        ids.filter { it.endsWith("-latest") }.minOrNull() ?: ids.maxOrNull()

    val speech = pick(
        cards.filter { it.can("audio_speech") }.mapNotNull { it["id"].str() },
    )
    // The realtime (websocket) transcription model is a different API - exclude it.
    val transcription = pick(
        cards.filter { it.can("audio_transcription") && !it.can("audio_transcription_realtime") }
            .mapNotNull { it["id"].str() },
    )

    return ModelCatalog(chatModels, speech, transcription)
}
