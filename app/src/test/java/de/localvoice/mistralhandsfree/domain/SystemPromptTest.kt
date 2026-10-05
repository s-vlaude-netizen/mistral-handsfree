package de.localvoice.mistralhandsfree.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptTest {

    private val german = "de-DE"

    // The German instruction that earlier versions saved on a German phone, word for word.
    private val oldGerman = """Du bist ein Sprachassistent in einem Freisprech-Gespräch. Der Nutzer spricht mit dir und hört deine Antwort vorgelesen. Antworte in der Sprache, in der der Nutzer spricht. Fasse dich kurz und natürlich: Zwei oder drei Sätze genügen meistens, es sei denn, der Nutzer möchte mehr. Kein Markdown, keine Listen, keine Überschriften, keine Emojis und keine Internetadressen – alles wird vorgelesen. Schreibe Zahlen und Symbole so, wie man sie ausspricht, wenn das Missverständnisse vermeidet."""
    private val oldEnglish = """You are a voice assistant in a hands-free conversation. The user speaks to you and hears your answer read aloud. Answer in the language the user speaks. Keep it short and natural: two or three sentences are usually enough, unless the user asks for more. No markdown, no lists, no headings, no emojis and no web addresses - everything is spoken. Write numbers and symbols the way they are pronounced when that avoids confusion."""

    @Test
    fun `tells the model which language to answer in`() {
        val prompt = SystemPrompt.build("", "en-US", german)

        assertTrue(prompt.startsWith(SystemPrompt.DEFAULT))
        assertTrue(prompt, prompt.endsWith("The user speaks English. Always reply in English, unless they explicitly ask for another language."))
    }

    @Test
    fun `an english setting wins over a german phone`() {
        // The reported bug: English chosen, German answers.
        val prompt = SystemPrompt.build(oldGerman, "en-US", german)

        assertFalse("no German instruction left", prompt.contains("Antworte"))
        assertTrue(prompt.contains("Always reply in English"))
        assertFalse(prompt.contains("German"))
    }

    @Test
    fun `like the phone means the language of the phone`() {
        val prompt = SystemPrompt.build("", Languages.PHONE, german)

        assertTrue(prompt.contains("The user speaks German. Always reply in German"))
    }

    @Test
    fun `automatic lets the model follow the user`() {
        val prompt = SystemPrompt.build("", Languages.AUTOMATIC, german)

        assertTrue(prompt.endsWith("Reply in the language the user speaks."))
        assertFalse(prompt.contains("Always reply in"))
    }

    @Test
    fun `a tag that means nothing is treated like automatic`() {
        assertEquals(
            SystemPrompt.build("", Languages.AUTOMATIC, german),
            SystemPrompt.build("", "xx", german),
        )
    }

    @Test
    fun `the user's own instruction replaces the built-in one but keeps the language line`() {
        val prompt = SystemPrompt.build("  Talk like a pirate.  ", "fr-FR", german)

        assertEquals(
            "Talk like a pirate.\n\nThe user speaks French. Always reply in French, unless they explicitly ask for another language.",
            prompt,
        )
    }

    @Test
    fun `the instructions that earlier versions saved are not taken for the user's own`() {
        assertTrue(SystemPrompt.isBuiltIn(oldGerman))
        assertTrue(SystemPrompt.isBuiltIn(oldEnglish))
        assertTrue(SystemPrompt.isBuiltIn("  $oldGerman \n"))
        assertTrue(SystemPrompt.isBuiltIn(SystemPrompt.DEFAULT))
        assertTrue(SystemPrompt.isBuiltIn(""))
        assertTrue(SystemPrompt.isBuiltIn("   "))
        assertFalse(SystemPrompt.isBuiltIn("Talk like a pirate."))
        assertFalse("a small edit makes it the user's own", SystemPrompt.isBuiltIn(oldGerman + " Sei nett."))
    }

    @Test
    fun `the built-in instruction leaves the language to the language line`() {
        assertFalse(SystemPrompt.DEFAULT.contains("language", ignoreCase = true))
    }

    @Test
    fun `the built-in instruction is English whatever the phone speaks`() {
        // German instructions pull the answers towards German, which is what went wrong.
        assertFalse(SystemPrompt.DEFAULT.contains("Antworte"))
        assertTrue(SystemPrompt.DEFAULT.startsWith("You are a voice assistant"))
    }
}
