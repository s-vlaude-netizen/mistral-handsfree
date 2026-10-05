package de.localvoice.mistralhandsfree.testing

import de.localvoice.mistralhandsfree.auth.ApiKeyStore
import de.localvoice.mistralhandsfree.data.AppSettings
import de.localvoice.mistralhandsfree.speech.Speaker
import de.localvoice.mistralhandsfree.speech.SpeakerSetup
import de.localvoice.mistralhandsfree.speech.SpeechEngines
import de.localvoice.mistralhandsfree.speech.SpeechToText
import de.localvoice.mistralhandsfree.speech.SttResult
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest

/** A key store that keeps the key in memory - the real one needs the device's Keystore. */
class InMemoryKeyStore(initial: String? = null) : ApiKeyStore {
    @Volatile
    var key: String? = initial
    private val flow = MutableStateFlow(initial != null)
    override val signedIn: StateFlow<Boolean> = flow.asStateFlow()
    override fun load(): String? = key
    override fun save(key: String) {
        this.key = key
        flow.value = true
    }

    override fun clear() {
        key = null
        flow.value = false
    }
}

/** Speech engines for screen tests: nobody ever speaks, and nothing is ever heard. */
class SilentEngines : SpeechEngines {
    override fun recognizer(settings: AppSettings): SpeechToText = object : SpeechToText {
        override val partialText = MutableStateFlow("")
        override val level = MutableStateFlow(0f)
        override val processing = MutableStateFlow(false)
        override suspend fun listenOnce(): SttResult = awaitCancellation()
        override fun abort() = Unit
        override fun destroy() = Unit
    }

    val spoken = CopyOnWriteArrayList<String>()

    override suspend fun speaker(settings: AppSettings) = SpeakerSetup(
        object : Speaker {
            override val busy = MutableStateFlow(false)
            override val warning = MutableStateFlow<String?>(null)
            override val diagnostics = MutableStateFlow("test voice")
            override suspend fun prepare() = true
            override fun enqueue(text: String) {
                spoken += text
            }

            override fun speakNow(text: String) {
                spoken += text
            }

            override suspend fun awaitIdle() = Unit
            override fun stop() = Unit
            override fun shutdown() = Unit
        },
    )
}

/**
 * Plays Mistral's API for screen tests: a request counts only with the right
 * Bearer key, like the real one, and then models, voices and chat are answered
 * from canned data.
 */
class MistralStub(private val validKey: String) : Dispatcher() {

    val requests = CopyOnWriteArrayList<RecordedRequest>()

    /** What the model "says" to the next chat request. */
    @Volatile
    var reply: List<String> = listOf("Hello! ", "This is a test reply. ", "Anything else?")

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        if (request.getHeader("Authorization") != "Bearer $validKey") {
            return MockResponse().setResponseCode(401).setBody("""{"detail":"Invalid API Key"}""")
        }
        val path = request.path.orEmpty()
        return when {
            path == "/v1/models" -> json(MODELS)
            path.startsWith("/v1/audio/voices") -> json(VOICES)
            path == "/v1/chat/completions" -> chat()
            else -> MockResponse().setResponseCode(404).setBody("""{"detail":"Not found"}""")
        }
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun chat(): MockResponse {
        val events = reply.joinToString("") { part ->
            val escaped = part.replace("\\", "\\\\").replace("\"", "\\\"")
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"$escaped\"}}]}\n\n"
        }
        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(events + "data: [DONE]\n\n")
    }

    companion object {
        const val MODELS = """
            {"object":"list","data":[
              {"id":"mistral-large-latest","capabilities":{"completion_chat":true},"description":"Our most capable model","max_context_length":131072},
              {"id":"mistral-medium-latest","capabilities":{"completion_chat":true},"description":"Balanced quality and speed"},
              {"id":"mistral-small-latest","capabilities":{"completion_chat":true},"description":"Fast and affordable"},
              {"id":"magistral-medium-latest","capabilities":{"completion_chat":true,"reasoning":true},"description":"Reasoning model"},
              {"id":"voxtral-mini-tts-2603","capabilities":{"audio_speech":true}},
              {"id":"voxtral-mini-latest","capabilities":{"audio_transcription":true}}
            ]}"""

        const val VOICES = """
            {"items":[
              {"id":"11111111-1111-1111-1111-111111111111","name":"Marie","languages":["fr","en"],"gender":"female","type":"preset"},
              {"id":"22222222-2222-2222-2222-222222222222","name":"Jonas","languages":["de","en"],"gender":"male","type":"preset"}
            ],"total":2,"page":1,"page_size":100,"total_pages":1}"""
    }
}
