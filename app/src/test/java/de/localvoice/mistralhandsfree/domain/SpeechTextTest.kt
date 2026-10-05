package de.localvoice.mistralhandsfree.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextTest {

    @Test
    fun `strips markdown emphasis`() {
        assertEquals(
            "The important part is here.",
            SpeechText.forSpeech("**The important** part is `here`."),
        )
    }

    @Test
    fun `keeps underscores inside words`() {
        assertEquals("Call snake_case now", SpeechText.forSpeech("Call snake_case now"))
        assertEquals("really", SpeechText.forSpeech("_really_"))
    }

    @Test
    fun `reads a link as its text and drops bare addresses`() {
        assertEquals("See the docs for more.", SpeechText.forSpeech("See [the docs](https://example.com/x?y=1) for more."))
        assertEquals("Go to now.", SpeechText.forSpeech("Go to https://example.com/very/long/path now."))
    }

    @Test
    fun `removes emoji`() {
        assertEquals("Great!", SpeechText.forSpeech("Great! 😀🎉"))
        assertEquals("Done", SpeechText.forSpeech("Done ✅"))
    }

    @Test
    fun `turns bullet lists into plain lines`() {
        assertEquals("First\nSecond", SpeechText.forSpeech("- First\n- Second"))
        assertEquals("First\nSecond", SpeechText.forSpeech("* First\n* Second"))
    }

    @Test
    fun `drops headings and quote markers`() {
        assertEquals("Title\nQuoted", SpeechText.forSpeech("## Title\n> Quoted"))
    }

    @Test
    fun `leaves out code blocks`() {
        assertEquals("Try this: then run it.", SpeechText.forSpeech("Try this: ```\nprintln(1)\n``` then run it."))
    }

    @Test
    fun `turns table pipes into spaces`() {
        assertEquals("a b", SpeechText.forSpeech("a | b"))
    }

    @Test
    fun `strips template leftovers for display too`() {
        assertEquals("Done.", SpeechText.forSpeech("Done.<|im_end|>"))
        assertEquals("Done.", SpeechText.forDisplay("Done.<|im_end|>"))
    }

    @Test
    fun `leaves normal text alone`() {
        val text = "Today is Friday, and it is raining."
        assertEquals(text, SpeechText.forSpeech(text))
        assertEquals("It costs 5 € and 3 > 2.", SpeechText.forSpeech("It costs 5 € and 3 > 2."))
    }
}
