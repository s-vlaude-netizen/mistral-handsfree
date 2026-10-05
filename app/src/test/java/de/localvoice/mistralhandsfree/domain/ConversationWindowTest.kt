package de.localvoice.mistralhandsfree.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationWindowTest {

    private var nextId = 0L

    private fun user(text: String) = ChatMessage(nextId++, Role.USER, text, 0)
    private fun assistant(text: String) = ChatMessage(nextId++, Role.ASSISTANT, text, 0)

    @Test
    fun `puts the system prompt first and maps the roles`() {
        val out = ConversationWindow.build(
            "Be brief.",
            listOf(user("Hi"), assistant("Hello!"), user("What time is it?")),
        )
        assertEquals(
            listOf(
                PromptMessage("system", "Be brief."),
                PromptMessage("user", "Hi"),
                PromptMessage("assistant", "Hello!"),
                PromptMessage("user", "What time is it?"),
            ),
            out,
        )
    }

    @Test
    fun `leaves out a blank system prompt`() {
        val out = ConversationWindow.build("   ", listOf(user("Hi")))
        assertEquals(listOf(PromptMessage("user", "Hi")), out)
    }

    @Test
    fun `drops answers that never said anything`() {
        val out = ConversationWindow.build("", listOf(user("Hi"), assistant(""), user("Hello?")))
        // The two user turns end up merged into one.
        assertEquals(listOf(PromptMessage("user", "Hi\nHello?")), out)
    }

    @Test
    fun `merges consecutive messages of the same role`() {
        val out = ConversationWindow.build("", listOf(user("One"), user("Two"), assistant("Three")))
        assertEquals(
            listOf(PromptMessage("user", "One\nTwo"), PromptMessage("assistant", "Three")),
            out,
        )
    }

    @Test
    fun `strips template leftovers from earlier answers`() {
        val out = ConversationWindow.build("", listOf(user("Hi"), assistant("Hello.<|im_end|>"), user("Again")))
        assertEquals("Hello.", out[1].content)
    }

    @Test
    fun `drops the oldest turns once the budget is used up`() {
        val history = listOf(
            user("a".repeat(100)), assistant("b".repeat(100)),
            user("c".repeat(100)), assistant("d".repeat(100)),
            user("e".repeat(10)),
        )
        val out = ConversationWindow.build("sys", history, maxChars = 250)
        // Budget after the system prompt is 247: the first two turns have to go.
        assertEquals(listOf("system", "user", "assistant", "user"), out.map { it.role })
        assertEquals("c".repeat(100), out[1].content)
        assertEquals("e".repeat(10), out.last().content)
    }

    @Test
    fun `never starts with an assistant message after trimming`() {
        val history = listOf(
            user("a".repeat(100)), assistant("b".repeat(100)), user("c".repeat(100)),
        )
        // Dropping only the first user turn would leave the assistant reply as the first message.
        val out = ConversationWindow.build("", history, maxChars = 250)
        assertEquals(listOf("user"), out.map { it.role })
        assertEquals("c".repeat(100), out[0].content)
    }

    @Test
    fun `keeps the newest message even when it alone exceeds the budget`() {
        val out = ConversationWindow.build("system", listOf(user("x".repeat(500))), maxChars = 100)
        assertEquals(listOf("system", "user"), out.map { it.role })
        assertEquals(500, out.last().content.length)
    }

    @Test
    fun `an empty conversation gives just the system prompt`() {
        assertEquals(listOf(PromptMessage("system", "sys")), ConversationWindow.build("sys", emptyList()))
    }
}
