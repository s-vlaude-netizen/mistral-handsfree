package de.localvoice.mistralhandsfree.speech

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.localvoice.mistralhandsfree.R
import de.localvoice.mistralhandsfree.data.AppSettings
import de.localvoice.mistralhandsfree.data.SttEngine
import de.localvoice.mistralhandsfree.data.TtsEngine
import de.localvoice.mistralhandsfree.domain.Languages
import de.localvoice.mistralhandsfree.mistral.MistralAudio
import de.localvoice.mistralhandsfree.mistral.MistralClient
import de.localvoice.mistralhandsfree.mistral.MistralHttp
import de.localvoice.mistralhandsfree.mistral.MistralRepository
import de.localvoice.mistralhandsfree.session.TextSource
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The step between the settings and the two Mistral services: which language
 * Voxtral is told about, and which voice (if any) reads the answers.
 *
 * The audio is synthetic and the server is a local one, but everything in between
 * is the real code: the endpoint detection, the WAV upload as a multipart request,
 * the voice list and the choice from it.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSpeechEnginesTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer
    private val transcriptions = CopyOnWriteArrayList<String>()
    private val voiceRequests = AtomicInteger()

    @Volatile
    private var voices: String = """{"items":[
        {"id":"v-marie","name":"Marie","languages":["fr","en"],"type":"preset"},
        {"id":"v-paul","name":"Paul","languages":["en_us"],"type":"preset"}],"total":2}"""

    @Before
    fun setUp() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/v1/audio/transcriptions" -> {
                        // The WAV is binary; only the text of the form fields matters here.
                        transcriptions += request.body.readUtf8()
                        json("""{"model":"voxtral-mini-latest","text":"hello there","usage":{}}""")
                    }

                    request.path.orEmpty().startsWith("/v1/audio/voices") -> {
                        voiceRequests.incrementAndGet()
                        json(voices)
                    }

                    else -> MockResponse().setResponseCode(404)
                }
            }
            start()
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun engines(): AndroidSpeechEngines {
        val http = MistralHttp(OkHttpClient(), { "test-key" }, server.url("/v1/"), "test", sleep = {})
        return AndroidSpeechEngines(
            context = app,
            mistral = MistralRepository(MistralClient(http), MistralAudio(http)),
            scope = CoroutineScope(SupervisorJob()),
            text = TextSource.of(app),
            mic = MicSourceFactory { MicOpenResult.Ready(SpeakThenPause()) },
        )
    }

    /** A second of speech, then quiet: 20 ms frames, like the real thing. */
    private class SpeakThenPause : MicSource {
        private var frame = 0
        override fun start() = true
        override fun read(buffer: ShortArray, size: Int): Int {
            val speech = frame++ < 50
            for (i in 0 until size) {
                // A 200 Hz tone at about -18 dBFS stands in for speech; the rest is near-silence.
                buffer[i] = if (speech) (6000 * sin(2 * PI * 200 * i / 16_000)).toInt().toShort() else (i % 3 - 1).toShort()
            }
            return size
        }

        override fun stop() = Unit
        override fun release() = Unit
    }

    /** What the user says into the microphone, as the recognizer for these settings hears it. */
    private fun listen(settings: AppSettings): SttResult = runBlocking { engines().recognizer(settings).listenOnce() }

    /**
     * The value of the form field [name] of the one upload, or null if it was not sent.
     * Every part has its headers (Content-Disposition, Content-Length) before a blank line.
     */
    private fun field(name: String): String? {
        val upload = transcriptions.single()
        return Regex("name=\"$name\"\r\n(?:[A-Za-z-]+: [^\r\n]*\r\n)*\r\n([^\r\n]*)\r\n").find(upload)?.groupValues?.get(1)
    }

    // ------------------------------------------------------------------ listening

    @Test
    fun `voxtral is told the language that was chosen`() {
        val result = listen(AppSettings(sttEngine = SttEngine.MISTRAL, language = "en-US"))

        assertEquals(SttResult.Text("hello there"), result)
        // The reported bug: left to guess, it heard German in English speech.
        assertEquals("en", field("language"))
    }

    @Test
    fun `the language of the model and the language heard are the same setting`() {
        listen(AppSettings(sttEngine = SttEngine.MISTRAL, language = "fr-CA"))

        assertEquals("fr", field("language"))
    }

    @Test
    @Config(qualifiers = "+de")
    fun `like the phone means the phone's language`() {
        listen(AppSettings(sttEngine = SttEngine.MISTRAL, language = Languages.PHONE))

        assertEquals("de", field("language"))
    }

    @Test
    fun `automatic lets voxtral detect the language`() {
        listen(AppSettings(sttEngine = SttEngine.MISTRAL, language = Languages.AUTOMATIC))

        assertEquals("the upload was read", "voxtral-mini-latest", field("model"))
        assertNull(field("language"))
    }

    @Test
    fun `a language voxtral does not list is not forced on it`() {
        listen(AppSettings(sttEngine = SttEngine.MISTRAL, language = "pl-PL"))

        assertEquals("the upload was read", "voxtral-mini-latest", field("model"))
        assertNull(field("language"))
    }

    // ---------------------------------------------------------------------- voice

    private fun speaker(settings: AppSettings): SpeakerSetup = runBlocking { engines().speaker(settings) }

    @Test
    fun `a language with a mistral voice is read by it`() {
        val setup = speaker(AppSettings(ttsEngine = TtsEngine.MISTRAL, language = "en-US"))

        assertNull(setup.notice)
        assertTrue(setup.speaker is MistralSpeaker)
    }

    @Test
    fun `a language without a mistral voice is read by the phone, and the user is told`() {
        val setup = speaker(AppSettings(ttsEngine = TtsEngine.MISTRAL, language = "de-DE"))

        // The old behaviour: an English voice reading German.
        assertTrue(setup.speaker is AndroidSpeaker)
        val notice = setup.notice
        assertNotNull(notice)
        assertEquals(app.getString(R.string.tts_no_mistral_voice_for, "German"), notice)
    }

    @Test
    fun `a voice picked by hand is used whatever it speaks, without asking for the list`() {
        val setup = speaker(AppSettings(ttsEngine = TtsEngine.MISTRAL, language = "de-DE", mistralVoiceId = "v-paul"))

        assertNull(setup.notice)
        assertTrue(setup.speaker is MistralSpeaker)
        assertEquals("the voice list is not needed", 0, voiceRequests.get())
    }

    @Test
    fun `an account without voices is read by the phone, and the user is told`() {
        voices = """{"items":[],"total":0}"""

        val setup = speaker(AppSettings(ttsEngine = TtsEngine.MISTRAL, language = "en-US"))

        assertTrue(setup.speaker is AndroidSpeaker)
        assertEquals(app.getString(R.string.tts_no_mistral_voice), setup.notice)
    }

    @Test
    fun `the phone's voice needs no list and no notice`() {
        val setup = speaker(AppSettings(ttsEngine = TtsEngine.SYSTEM, language = "de-DE"))

        assertNull(setup.notice)
        assertTrue(setup.speaker is AndroidSpeaker)
        assertEquals(0, voiceRequests.get())
    }
}
