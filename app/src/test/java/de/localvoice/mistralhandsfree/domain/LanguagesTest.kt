package de.localvoice.mistralhandsfree.domain

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguagesTest {

    private val german = "de-DE"

    @Test
    fun `the primary language is the part before the region, whatever the separator`() {
        assertEquals("de", Languages.primary("de-DE"))
        assertEquals("de", Languages.primary("de_AT"))
        assertEquals("en", Languages.primary("EN"))
    }

    @Test
    fun `like the phone and automatic both use the phone's language where one is needed`() {
        assertEquals(german, Languages.effectiveTag(Languages.PHONE, german))
        assertEquals(german, Languages.effectiveTag(Languages.AUTOMATIC, german))
        assertEquals("en-US", Languages.effectiveTag("en-US", german))
    }

    @Test
    fun `voxtral gets a two letter code for a fixed language and none for automatic`() {
        assertEquals("en", Languages.transcriptionCode("en-US", german))
        assertEquals("pt", Languages.transcriptionCode("pt_BR", german))
        assertEquals("de", Languages.transcriptionCode(Languages.PHONE, german))
        assertNull("automatic lets Voxtral detect the language", Languages.transcriptionCode(Languages.AUTOMATIC, german))
    }

    @Test
    fun `a language voxtral cannot be told about is left for it to detect`() {
        // The field takes exactly two letters; anything else would be rejected with a 422.
        assertNull(Languages.transcriptionCode("fil-PH", german))
        assertNull(Languages.transcriptionCode("1x", german))
    }

    @Test
    fun `a language outside voxtral's list is not forced on it`() {
        // Polish, Turkish and Swedish are fine for the phone's recognizer and for the model,
        // but Mistral lists 13 languages for Voxtral and these are not among them.
        for (tag in listOf("pl-PL", "tr-TR", "sv-SE", "fi-FI")) {
            assertNull(tag, Languages.transcriptionCode(tag, german))
            assertFalse(tag, Languages.voxtralListens(tag, german))
        }
    }

    @Test
    fun `the phone's language is only passed on if voxtral lists it`() {
        assertEquals("de", Languages.transcriptionCode(Languages.PHONE, "de-DE"))
        assertNull(Languages.transcriptionCode(Languages.PHONE, "pl-PL"))
    }

    @Test
    fun `every language that voxtral lists yields a code the api accepts`() {
        val accepted = Regex("^\\w{2}$")
        assertEquals(13, Languages.VOXTRAL_LANGUAGES.size)
        for (language in Languages.VOXTRAL_LANGUAGES) {
            assertEquals(language, Languages.transcriptionCode(language, german))
            assertTrue(language, accepted.matches(language))
        }
        // Every language of the picker that is on that list works with a region, too.
        for (choice in Languages.choices.filter { Languages.primary(it.tag) in Languages.VOXTRAL_LANGUAGES }) {
            val code = Languages.transcriptionCode(choice.tag, german)
            assertNotNull(choice.tag, code)
            assertTrue("${choice.tag} -> $code", accepted.matches(code!!))
        }
    }

    @Test
    fun `automatic asks nothing of voxtral`() {
        assertTrue(Languages.voxtralListens(Languages.AUTOMATIC, "pl-PL"))
        assertTrue(Languages.voxtralListens("de-DE", "pl-PL"))
        assertFalse(Languages.voxtralListens(Languages.PHONE, "pl-PL"))
    }

    @Test
    fun `the picker offers each language once, under its own name`() {
        val tags = Languages.choices.map { it.tag.lowercase() }
        assertEquals(tags.distinct(), tags)
        assertTrue(Languages.choices.all { it.name.isNotBlank() })
        assertEquals("Deutsch", Languages.choiceFor("de-de")?.name)
        assertNull(Languages.choiceFor("fi-FI"))
    }

    @Test
    fun `the model is told the name of the language in english`() {
        assertEquals("German", Languages.englishName("de-DE"))
        assertEquals("English", Languages.englishName("en-GB"))
        assertEquals("Portuguese", Languages.englishName("pt_BR"))
    }

    @Test
    fun `a tag that means nothing has no name`() {
        assertNull(Languages.englishName("xx"))
        assertNull(Languages.englishName(""))
    }

    @Test
    fun `messages name the language in the language of the phone`() {
        assertEquals("Englisch", Languages.displayName("en-US", Locale.GERMAN))
        assertEquals("English", Languages.displayName("en-US", Locale.ENGLISH))
        assertEquals("Deutsch", Languages.displayName("de-DE", Locale.GERMAN))
    }

    @Test
    fun `the picker shows a language outside the list under its own name`() {
        assertEquals("Deutsch", Languages.pickerName("de-DE"))
        assertEquals("Suomi", Languages.pickerName("fi-FI"))
    }

    @Test
    fun `a voice is tried with a sentence in its own language`() {
        assertEquals("Hallo! So klinge ich.", Languages.sampleSentence("de-DE"))
        assertEquals("Hello! This is how I sound.", Languages.sampleSentence("en-US"))
        assertEquals("Hello! This is how I sound.", Languages.sampleSentence("fi-FI"))
    }

    @Test
    fun `every language of the picker except english has a sentence of its own`() {
        val english = Languages.sampleSentence("en-US")
        for (choice in Languages.choices.filterNot { Languages.primary(it.tag) == "en" }) {
            assertNotEquals(choice.tag, english, Languages.sampleSentence(choice.tag))
        }
    }

    @Test
    fun `a tag with an underscore or spaces still names its language`() {
        // Java's Locale only understands hyphens and would silently produce "no language".
        assertEquals("pt", Languages.locale("pt_BR").language)
        assertEquals("BR", Languages.locale(" pt_BR ").country)
        assertEquals("Portuguese", Languages.englishName("pt_BR"))
    }

    @Test
    fun `a bare language gets the region that the picker offers`() {
        assertEquals("en-US", Languages.canonical("en"))
        assertEquals("de-DE", Languages.canonical("DE"))
        assertEquals("pt-BR", Languages.canonical("pt"))
        // A region, or a language the picker does not know, stays as it is.
        assertEquals("en-AU", Languages.canonical("en-AU"))
        assertEquals("pt-PT", Languages.canonical("pt_PT"))
        assertEquals("fi", Languages.canonical("fi"))
    }

    @Test
    fun `fixed languages are told apart from the two automatic settings`() {
        assertTrue(Languages.isExplicit("en-US"))
        assertFalse(Languages.isExplicit(Languages.PHONE))
        assertFalse(Languages.isExplicit(Languages.AUTOMATIC))
        assertFalse(Languages.isExplicit("  "))
    }
}
