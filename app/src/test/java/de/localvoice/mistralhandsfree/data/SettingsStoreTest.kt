package de.localvoice.mistralhandsfree.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.localvoice.mistralhandsfree.domain.Languages
import de.localvoice.mistralhandsfree.domain.SystemPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The settings as they are stored on the phone - including what earlier versions
 * left there. The first version saved the instruction to the model in the language
 * of the phone as if the user had written it, and a language for the recognizer and
 * one for the voice; none of that may override the language setting.
 */
@RunWith(AndroidJUnit4::class)
class SettingsStoreTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val prefs get() = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // What an earlier version saved on a German phone, word for word.
    private val oldGermanDefault = """Du bist ein Sprachassistent in einem Freisprech-Gespräch. Der Nutzer spricht mit dir und hört deine Antwort vorgelesen. Antworte in der Sprache, in der der Nutzer spricht. Fasse dich kurz und natürlich: Zwei oder drei Sätze genügen meistens, es sei denn, der Nutzer möchte mehr. Kein Markdown, keine Listen, keine Überschriften, keine Emojis und keine Internetadressen – alles wird vorgelesen. Schreibe Zahlen und Symbole so, wie man sie ausspricht, wenn das Missverständnisse vermeidet."""

    @Before
    fun clearSettings() {
        prefs.edit().clear().commit()
    }

    private fun store(phone: String = "de-DE") = SettingsStore(app, deviceTag = { phone })

    private fun legacy(system: String? = oldGermanDefault, stt: String? = "de-DE", tts: String? = "de-DE") {
        prefs.edit().apply {
            system?.let { putString("system_prompt", it) }
            stt?.let { putString("stt_lang", it) }
            tts?.let { putString("tts_lang", it) }
        }.commit()
    }

    @Test
    fun `a new installation follows the phone and uses the built-in instruction`() {
        val settings = store().current

        assertEquals(Languages.PHONE, settings.language)
        assertEquals("", settings.systemPrompt)
    }

    @Test
    fun `an english choice of an earlier version is carried over`() {
        // The state of the person who reported it: German phone, English chosen for the voice.
        legacy(stt = "de-DE", tts = "en")

        val settings = store().current

        assertEquals("en-US", settings.language)
        assertEquals("the stored German instruction counts as no instruction", "", settings.systemPrompt)
    }

    @Test
    fun `a language chosen for the recognizer only is carried over too`() {
        legacy(stt = "fr-FR", tts = "de-DE")

        assertEquals("fr-FR", store().current.language)
    }

    @Test
    fun `when both differ from the phone the voice wins - it is what you hear`() {
        legacy(stt = "fr-FR", tts = "es-ES")

        assertEquals("es-ES", store().current.language)
    }

    @Test
    fun `languages equal to the phone's were never a choice`() {
        legacy(stt = "de-DE", tts = "de-DE")
        assertEquals(Languages.PHONE, store().current.language)

        // The same stored values on an English phone are a choice: German.
        assertEquals("de-DE", store("en-US").current.language)
    }

    @Test
    fun `an instruction the user wrote is kept`() {
        legacy(system = "Talk like a pirate.")

        assertEquals("Talk like a pirate.", store().current.systemPrompt)
    }

    @Test
    fun `the built-in instruction is not written to the phone`() {
        val store = store()

        store.update { it.copy(systemPrompt = SystemPrompt.DEFAULT, handsFree = false) }

        assertEquals("", prefs.getString("system_prompt", null))
    }

    @Test
    fun `an instruction the user wrote is written and read back`() {
        store().update { it.copy(systemPrompt = "Talk like a pirate.") }

        assertEquals("Talk like a pirate.", prefs.getString("system_prompt", null))
        assertEquals("Talk like a pirate.", store().current.systemPrompt)
    }

    @Test
    fun `saving replaces the two old language entries by one`() {
        legacy(stt = "fr-FR", tts = "es-ES")
        val store = store()

        store.update { it.copy(handsFree = false) }

        assertFalse(prefs.contains("stt_lang"))
        assertFalse(prefs.contains("tts_lang"))
        assertEquals("es-ES", prefs.getString("language", null))
        assertEquals("the choice survives a restart", "es-ES", store().current.language)
    }

    @Test
    fun `a chosen language is stored and read back`() {
        store().update { it.copy(language = "fr-FR") }
        assertEquals("fr-FR", store().current.language)

        store().update { it.copy(language = Languages.AUTOMATIC) }
        assertEquals(Languages.AUTOMATIC, store().current.language)

        // Back to "like the phone" is a stored choice too, and must not be mistaken for a missing one.
        legacy(stt = "fr-FR", tts = "fr-FR")
        store().update { it.copy(language = Languages.PHONE) }
        assertEquals(Languages.PHONE, store().current.language)
        assertTrue(prefs.contains("language"))
    }
}
