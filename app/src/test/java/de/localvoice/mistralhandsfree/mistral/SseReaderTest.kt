package de.localvoice.mistralhandsfree.mistral

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseReaderTest {

    private fun reader(text: String) = SseReader(Buffer().writeUtf8(text))

    private fun SseReader.all(): List<SseEvent> = generateSequence { next() }.toList()

    @Test
    fun `reads consecutive events`() {
        val events = reader("data: one\n\ndata: two\n\n").all()
        assertEquals(listOf(SseEvent(null, "one"), SseEvent(null, "two")), events)
    }

    @Test
    fun `joins several data lines with a newline`() {
        assertEquals(listOf(SseEvent(null, "a\nb")), reader("data: a\ndata: b\n\n").all())
    }

    @Test
    fun `reads the event name`() {
        assertEquals(
            listOf(SseEvent("speech.audio.delta", "{}")),
            reader("event: speech.audio.delta\ndata: {}\n\n").all(),
        )
    }

    @Test
    fun `ignores comments and unknown fields`() {
        val events = reader(": keep-alive\nid: 7\nretry: 100\nfoo: bar\ndata: x\n\n").all()
        assertEquals(listOf(SseEvent(null, "x")), events)
    }

    @Test
    fun `understands CRLF line endings`() {
        assertEquals(listOf(SseEvent("e", "x")), reader("event: e\r\ndata: x\r\n\r\n").all())
    }

    @Test
    fun `strips only one leading space of the value`() {
        assertEquals(listOf(SseEvent(null, " two")), reader("data:  two\n\n").all())
        assertEquals(listOf(SseEvent(null, "tight")), reader("data:tight\n\n").all())
    }

    @Test
    fun `does not deliver an event without data`() {
        assertEquals(listOf(SseEvent(null, "x")), reader("event: lonely\n\ndata: x\n\n").all())
    }

    @Test
    fun `delivers a final event even without the closing blank line`() {
        assertEquals(listOf(SseEvent(null, "last")), reader("data: last").all())
    }

    @Test
    fun `an empty stream has no events`() {
        assertNull(reader("").next())
    }

    @Test
    fun `keeps the done marker as plain data`() {
        assertEquals(listOf(SseEvent(null, "[DONE]")), reader("data: [DONE]\n\n").all())
    }
}
