package de.localvoice.mistralhandsfree.llm

import de.localvoice.mistralhandsfree.data.AppSettings
import de.localvoice.mistralhandsfree.domain.ChatMessage
import de.localvoice.mistralhandsfree.domain.Languages
import de.localvoice.mistralhandsfree.domain.Role
import de.localvoice.mistralhandsfree.domain.SystemPrompt
import de.localvoice.mistralhandsfree.mistral.MistralClient
import de.localvoice.mistralhandsfree.mistral.MistralHttp
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the model is actually sent. The language setting has no effect on the
 * answers unless it reaches the request - which it once did not.
 */
class MistralLlmEngineTest {

    private lateinit var server: MockWebServer

    // The instruction that earlier versions saved on a German phone, word for word.
    private val oldGermanDefault = """Du bist ein Sprachassistent in einem Freisprech-Gespräch. Der Nutzer spricht mit dir und hört deine Antwort vorgelesen. Antworte in der Sprache, in der der Nutzer spricht. Fasse dich kurz und natürlich: Zwei oder drei Sätze genügen meistens, es sei denn, der Nutzer möchte mehr. Kein Markdown, keine Listen, keine Überschriften, keine Emojis und keine Internetadressen – alles wird vorgelesen. Schreibe Zahlen und Symbole so, wie man sie ausspricht, wenn das Missverständnisse vermeidet."""

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun engine(settings: AppSettings, phone: String = "de-DE"): MistralLlmEngine {
        val http = MistralHttp(
            client = OkHttpClient(),
            apiKey = { "test-key" },
            baseUrl = server.url("/v1/"),
            userAgent = "test",
            sleep = {},
        )
        return MistralLlmEngine(MistralClient(http), settings = { settings }, deviceTag = { phone })
    }

    /** Sends one question and returns the system message of the request. */
    private fun systemMessage(settings: AppSettings, phone: String = "de-DE"): String = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: [DONE]\n\n"))
        engine(settings, phone).generate(listOf(ChatMessage(1, Role.USER, "Hello", 0))).toList()
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val first = body["messages"]!!.jsonArray[0].jsonObject
        assertEquals("system", first["role"]!!.jsonPrimitive.content)
        first["content"]!!.jsonPrimitive.content
    }

    @Test
    fun `an english conversation on a german phone is asked for in english`() {
        val prompt = systemMessage(AppSettings(language = "en-US"))

        assertTrue(prompt, prompt.contains("Always reply in English"))
        assertFalse(prompt, prompt.contains("German"))
    }

    @Test
    fun `the german instruction saved by an earlier version does not pull the answers to german`() {
        // The reported bug: English chosen, and still German answers. This text was stored as the
        // user's own the first time any setting was changed on a German phone.
        val prompt = systemMessage(AppSettings(systemPrompt = oldGermanDefault, language = "en-US"))

        assertFalse(prompt, prompt.contains("Antworte"))
        assertTrue(prompt.startsWith(SystemPrompt.DEFAULT))
        assertTrue(prompt.endsWith("Always reply in English, unless they explicitly ask for another language."))
    }

    @Test
    fun `like the phone asks for the language of the phone`() {
        assertTrue(systemMessage(AppSettings(language = Languages.PHONE), phone = "fr-FR").contains("Always reply in French"))
    }

    @Test
    fun `automatic asks the model to follow the user`() {
        val prompt = systemMessage(AppSettings(language = Languages.AUTOMATIC))

        assertTrue(prompt.endsWith("Reply in the language the user speaks."))
        assertFalse(prompt.contains("Always reply in"))
    }

    @Test
    fun `the user's own instruction is kept, with the language added`() {
        val prompt = systemMessage(AppSettings(systemPrompt = "Talk like a pirate.", language = "de-DE"))

        assertTrue(prompt.startsWith("Talk like a pirate."))
        assertFalse(prompt.contains(SystemPrompt.DEFAULT))
        assertTrue(prompt.contains("Always reply in German"))
    }

    @Test
    fun `a change of language applies from the next question`() {
        var settings = AppSettings(language = "en-US")
        val http = MistralHttp(OkHttpClient(), { "k" }, server.url("/v1/"), "test", sleep = {})
        val engine = MistralLlmEngine(MistralClient(http), settings = { settings }, deviceTag = { "de-DE" })
        fun ask(): String = runBlocking {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: [DONE]\n\n"))
            engine.generate(listOf(ChatMessage(1, Role.USER, "Hi", 0))).toList()
            val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content
        }

        assertTrue(ask().contains("Always reply in English"))
        settings = settings.copy(language = "es-ES")
        assertTrue(ask().contains("Always reply in Spanish"))
    }
}
