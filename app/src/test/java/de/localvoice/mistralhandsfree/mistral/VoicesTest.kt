package de.localvoice.mistralhandsfree.mistral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicesTest {

    private fun voice(id: String, languages: List<String>, preset: Boolean = true) =
        Voice(id, id, languages, null, preset)

    @Test
    fun `prefers a built-in voice that speaks the language`() {
        val voices = listOf(
            voice("english", listOf("en")),
            voice("mine", listOf("de"), preset = false),
            voice("german", listOf("de", "en")),
        )
        assertEquals("german", pickVoice(voices, "de-DE")?.id)
    }

    @Test
    fun `takes a custom voice if no built-in one speaks the language`() {
        val voices = listOf(voice("english", listOf("en")), voice("mine", listOf("fr"), preset = false))
        assertEquals("mine", pickVoice(voices, "fr-CA")?.id)
    }

    @Test
    fun `a voice for another language is not a substitute`() {
        // Mistral's built-in voices are American and British English and French. German
        // text read by one of them is the "English voice, German text" that people complain about.
        val voices = listOf(voice("english", listOf("en")), voice("french", listOf("fr")), voice("mine", emptyList(), preset = false))
        assertNull(pickVoice(voices, "de-DE"))
        assertNull(pickVoice(voices, "ja-JP"))
    }

    @Test
    fun `matches regardless of case and separator`() {
        val voices = listOf(voice("german", listOf("DE_at")))
        assertEquals("german", pickVoice(voices, "de-CH")?.id)
    }

    @Test
    fun `a voice for the region is preferred over one for the language alone`() {
        val voices = listOf(voice("american", listOf("en_us")), voice("british", listOf("en_gb")))
        assertEquals("british", pickVoice(voices, "en-GB")?.id)
        assertEquals("american", pickVoice(voices, "en-US")?.id)
        // No region match: any voice that speaks the language will do.
        assertEquals("american", pickVoice(voices, "en-AU")?.id)
    }

    @Test
    fun `no voices means no choice`() {
        assertNull(pickVoice(emptyList(), "en-US"))
    }

    @Test
    fun `a voice speaks a language if it lists it, whatever the region`() {
        val british = voice("jane", listOf("en_gb"))
        assertTrue(british.speaks("en-US"))
        assertTrue(british.speaks("EN"))
        assertEquals(false, british.speaks("de-DE"))
        assertEquals(false, voice("none", emptyList()).speaks("en"))
    }

    // ------------------------------------------------------------- the decision

    @Test
    fun `a voice the user picked is used whatever it speaks`() {
        val voices = listOf(voice("english", listOf("en")))
        assertEquals(VoiceChoice.Chosen("english"), chooseVoice("english", voices, "de-DE"))
        assertEquals("even if the list is not there", VoiceChoice.Chosen("x"), chooseVoice("x", emptyList(), "de-DE"))
    }

    @Test
    fun `automatic picks the voice that speaks the language`() {
        val voices = listOf(voice("english", listOf("en")), voice("french", listOf("fr")))
        val choice = chooseVoice("", voices, "fr-FR")
        assertTrue(choice is VoiceChoice.Matching)
        assertEquals("french", (choice as VoiceChoice.Matching).voice.id)
    }

    @Test
    fun `automatic says so when no voice speaks the language`() {
        val voices = listOf(voice("english", listOf("en")), voice("french", listOf("fr")))
        assertEquals(VoiceChoice.NoneForLanguage, chooseVoice("", voices, "de-DE"))
    }

    @Test
    fun `automatic says so when there are no voices at all`() {
        assertEquals(VoiceChoice.NoVoices, chooseVoice("", emptyList(), "de-DE"))
    }
}
