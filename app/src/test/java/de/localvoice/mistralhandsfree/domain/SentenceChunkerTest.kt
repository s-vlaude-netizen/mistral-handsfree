package de.localvoice.mistralhandsfree.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceChunkerTest {

    @Test
    fun `only cuts at a safe sentence boundary`() {
        val chunker = SentenceChunker(minChunkChars = 5)
        assertTrue(chunker.append("This is a sentence").isEmpty())
        // The full stop alone is not enough - what follows it decides.
        assertTrue(chunker.append(".").isEmpty())
        assertEquals(listOf("This is a sentence."), chunker.append(" And another"))
    }

    @Test
    fun `keeps short fragments together`() {
        val chunker = SentenceChunker(minChunkChars = 20)
        assertTrue(chunker.append("Yes. ").isEmpty())
        assertEquals(
            listOf("Yes. That is exactly what I mean."),
            chunker.append("That is exactly what I mean. "),
        )
    }

    @Test
    fun `does not cut at ordinal numbers`() {
        val chunker = SentenceChunker(minChunkChars = 5)
        assertTrue(chunker.append("On the 3. of May it starts").isEmpty())
        assertEquals(listOf("On the 3. of May it starts."), chunker.append(". "))
    }

    @Test
    fun `does not cut inside a decimal number`() {
        val chunker = SentenceChunker(minChunkChars = 5)
        assertTrue(chunker.append("Pi is roughly 3.14159 and so on").isEmpty())
    }

    @Test
    fun `cuts overlong text at a word boundary`() {
        val chunker = SentenceChunker(minChunkChars = 5, maxChunkChars = 30)
        val chunks = chunker.append("word ".repeat(20))
        assertTrue(chunks.isNotEmpty())
        chunks.forEach { assertTrue(it.length <= 30) }
    }

    @Test
    fun `flush returns the rest and empties the buffer`() {
        val chunker = SentenceChunker()
        chunker.append("No punctuation at the end")
        assertEquals("No punctuation at the end", chunker.flush())
        assertNull(chunker.flush())
    }

    @Test
    fun `cuts at line breaks`() {
        val chunker = SentenceChunker(minChunkChars = 5)
        assertEquals(listOf("First line"), chunker.append("First line\nSecond"))
    }

    @Test
    fun `works when the text arrives one character at a time`() {
        val chunker = SentenceChunker(minChunkChars = 10)
        val out = mutableListOf<String>()
        "Hello there, friend. How are you doing today? Fine.".forEach { out += chunker.append(it.toString()) }
        chunker.flush()?.let { out += it }
        assertEquals(listOf("Hello there, friend.", "How are you doing today?", "Fine."), out)
    }
}
