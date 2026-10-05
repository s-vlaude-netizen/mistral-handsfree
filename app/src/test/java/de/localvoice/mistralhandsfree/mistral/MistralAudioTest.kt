package de.localvoice.mistralhandsfree.mistral

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class MistralAudioTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun audio(): MistralAudio = MistralAudio(
        MistralHttp(OkHttpClient(), { "test-key" }, server.url("/v1/"), "test-agent", sleep = {}),
    )

    private fun floatBytes(vararg values: Float): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    private fun delta(bytes: ByteArray): String {
        val encoded = Base64.getEncoder().encodeToString(bytes)
        return "event: speech.audio.delta\ndata: {\"type\":\"speech.audio.delta\",\"audio_data\":\"$encoded\"}\n\n"
    }

    private val done = "event: speech.audio.done\ndata: {\"type\":\"speech.audio.done\",\"usage\":{\"prompt_tokens\":1}}\n\n"

    private fun eventStream(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)

    private fun List<FloatArray>.flatten(): FloatArray = fold(FloatArray(0)) { acc, part -> acc + part }

    // ---------------------------------------------------------------- speech

    @Test
    fun `decodes float samples even when a sample is split between two events`() = runBlocking {
        val all = floatBytes(0.25f, -0.5f, 1.0f) // 12 bytes
        // Cut in the middle of the first sample's successor: 5 bytes, then the rest.
        server.enqueue(eventStream(delta(all.copyOfRange(0, 5)) + delta(all.copyOfRange(5, 12)) + done))

        val chunks = audio().streamSpeech("Hello", "voice-1").toList()

        assertArrayEquals(floatArrayOf(0.25f, -0.5f, 1.0f), chunks.flatten(), 0f)
        assertEquals(listOf(1, 2), chunks.map { it.size })
    }

    @Test
    fun `sends the speech request with raw pcm and streaming`() = runBlocking {
        server.enqueue(eventStream(done))

        audio().streamSpeech("Guten Tag", "voice-xyz", "voxtral-mini-tts-2603").toList()

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/v1/audio/speech", request.path)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("voxtral-mini-tts-2603", body["model"]!!.jsonPrimitive.content)
        assertEquals("Guten Tag", body["input"]!!.jsonPrimitive.content)
        assertEquals("voice-xyz", body["voice_id"]!!.jsonPrimitive.content)
        assertEquals("pcm", body["response_format"]!!.jsonPrimitive.content)
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)
    }

    @Test
    fun `stops at the done event`() = runBlocking {
        server.enqueue(eventStream(delta(floatBytes(0.1f)) + done + delta(floatBytes(0.9f))))
        val chunks = audio().streamSpeech("x", "v").toList()
        assertArrayEquals(floatArrayOf(0.1f), chunks.flatten(), 0f)
    }

    @Test
    fun `ignores events it does not know and data it cannot read`() = runBlocking {
        server.enqueue(
            eventStream(
                "event: ping\ndata: {}\n\n" +
                    "data: not json at all\n\n" +
                    delta(floatBytes(0.5f)) +
                    done,
            ),
        )
        assertArrayEquals(floatArrayOf(0.5f), audio().streamSpeech("x", "v").toList().flatten(), 0f)
    }

    @Test
    fun `relies on the event line when the payload has no type`() = runBlocking {
        val encoded = Base64.getEncoder().encodeToString(floatBytes(0.75f))
        server.enqueue(eventStream("event: speech.audio.delta\ndata: {\"audio_data\":\"$encoded\"}\n\n$done"))
        assertArrayEquals(floatArrayOf(0.75f), audio().streamSpeech("x", "v").toList().flatten(), 0f)
    }

    @Test
    fun `an error in the speech stream ends it with an exception`() = runBlocking {
        server.enqueue(eventStream("data: {\"object\":\"error\",\"message\":\"voice not found\",\"code\":404}\n\n"))
        try {
            audio().streamSpeech("x", "v").toList()
            fail("expected an exception")
        } catch (e: MistralException) {
            assertEquals("voice not found", e.serverMessage)
            assertEquals(MistralException.Kind.BAD_REQUEST, e.kind)
        }
    }

    @Test
    fun `a moderation block is FORBIDDEN and not retried`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"Input flagged by moderation"}"""))
        try {
            audio().streamSpeech("x", "v").toList()
            fail("expected an exception")
        } catch (e: MistralException) {
            assertEquals(MistralException.Kind.FORBIDDEN, e.kind)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `undecodable audio is a protocol error`() = runBlocking {
        server.enqueue(eventStream("data: {\"type\":\"speech.audio.delta\",\"audio_data\":\"***not base64***\"}\n\n"))
        try {
            audio().streamSpeech("x", "v").toList()
            fail("expected an exception")
        } catch (e: MistralException) {
            assertEquals(MistralException.Kind.PROTOCOL, e.kind)
        }
    }

    // -------------------------------------------------------- transcription

    @Test
    fun `uploads the recording as multipart and returns the trimmed text`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"model":"voxtral-mini-latest","text":"  Hallo Welt  ","language":"de","usage":{}}"""))

        val text = audio().transcribe(byteArrayOf(1, 2, 3, 4), language = "de", modelId = "voxtral-mini-latest")

        assertEquals("Hallo Welt", text)
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/v1/audio/transcriptions", request.path)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val body = request.body.readUtf8()
        assertTrue(body, body.contains("name=\"model\""))
        assertTrue(body, body.contains("voxtral-mini-latest"))
        assertTrue(body, body.contains("name=\"language\""))
        assertTrue(body, body.contains("name=\"file\"; filename=\"speech.wav\""))
        assertTrue(body, body.contains("Content-Type: audio/wav"))
    }

    @Test
    fun `lets the model detect the language when none is given`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"text":"ok"}"""))
        audio().transcribe(byteArrayOf(1))
        assertTrue(!server.takeRequest().body.readUtf8().contains("name=\"language\""))
    }

    @Test
    fun `a transcription failure is mapped like every other`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"Invalid API Key"}"""))
        try {
            audio().transcribe(byteArrayOf(1))
            fail("expected an exception")
        } catch (e: MistralException) {
            assertEquals(MistralException.Kind.UNAUTHORIZED, e.kind)
        }
    }

    // ----------------------------------------------------------------- voices

    private fun voicesPage(total: Int, vararg ids: String): String {
        val items = ids.joinToString(",") {
            """{"id":"$it","name":"Voice $it","languages":["en","de"],"gender":"female","type":"preset"}"""
        }
        return """{"items":[$items],"total":$total,"page":1,"page_size":100,"total_pages":2}"""
    }

    @Test
    fun `lists voices page by page`() = runBlocking {
        val firstPage = (1..100).map { "v$it" }.toTypedArray()
        server.enqueue(MockResponse().setBody(voicesPage(130, *firstPage)))
        server.enqueue(MockResponse().setBody(voicesPage(130, "w1", "w2")))

        val voices = audio().listVoices()

        assertEquals(102, voices.size)
        assertEquals("/v1/audio/voices?limit=100&offset=0", server.takeRequest().path)
        assertEquals("/v1/audio/voices?limit=100&offset=100", server.takeRequest().path)
        assertEquals(Voice("w1", "Voice w1", listOf("en", "de"), "female", preset = true), voices[100])
    }

    @Test
    fun `a custom voice is not a preset`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"items":[{"id":"c","name":"Mine","languages":[],"type":"custom"}],"total":1}"""),
        )
        val voice = audio().listVoices().single()
        assertTrue(!voice.preset)
        assertEquals(emptyList<String>(), voice.languages)
    }

    // ---------------------------------------------------------------- decoder

    @Test
    fun `the decoder replaces NaN and clamps out-of-range samples`() {
        val decoder = Float32LeDecoder()
        val out = decoder.feed(floatBytes(Float.NaN, 3.5f, -9f, 0.5f))
        assertArrayEquals(floatArrayOf(0f, 1f, -1f, 0.5f), out, 0f)
    }

    @Test
    fun `the decoder carries leftover bytes into the next call`() {
        val decoder = Float32LeDecoder()
        val bytes = floatBytes(0.5f, -0.25f)
        assertEquals(0, decoder.feed(bytes.copyOfRange(0, 3)).size)
        assertArrayEquals(floatArrayOf(0.5f), decoder.feed(bytes.copyOfRange(3, 6)), 0f)
        assertArrayEquals(floatArrayOf(-0.25f), decoder.feed(bytes.copyOfRange(6, 8)), 0f)
    }
}
