package de.localvoice.mistralhandsfree.mistral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `falls back to a built-in voice when nothing matches`() {
        val voices = listOf(voice("custom", listOf("fr"), preset = false), voice("english", listOf("en")))
        assertEquals("english", pickVoice(voices, "ja-JP")?.id)
    }

    @Test
    fun `falls back to the first voice when nothing else is left`() {
        val voices = listOf(voice("a", emptyList(), preset = false), voice("b", emptyList(), preset = false))
        assertEquals("a", pickVoice(voices, "de")?.id)
    }

    @Test
    fun `matches regardless of case and separator`() {
        val voices = listOf(voice("german", listOf("DE_at")))
        assertEquals("german", pickVoice(voices, "de-CH")?.id)
    }

    @Test
    fun `no voices means no choice`() {
        assertNull(pickVoice(emptyList(), "en-US"))
    }
}
