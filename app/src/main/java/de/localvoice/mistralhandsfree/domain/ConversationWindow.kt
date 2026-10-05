package de.localvoice.mistralhandsfree.domain

/** One message as it goes over the wire. [role] is "system", "user" or "assistant". */
data class PromptMessage(val role: String, val content: String) {
    companion object {
        const val SYSTEM = "system"
        const val USER = "user"
        const val ASSISTANT = "assistant"
    }
}

/**
 * Builds the message list for one request from the on-screen conversation.
 *
 * Mistral's API is stateless: every request has to carry the whole context.
 * A long hands-free conversation would grow without bound - slower, costlier
 * and eventually over the model's context limit - so the oldest turns are
 * dropped once the budget is used up. The system prompt always stays.
 */
object ConversationWindow {

    /**
     * About 12k tokens. Voice answers are short, so this is many dozens of turns,
     * and it stays well inside the context window of every current chat model.
     */
    const val DEFAULT_BUDGET_CHARS = 48_000

    fun build(
        systemPrompt: String,
        history: List<ChatMessage>,
        maxChars: Int = DEFAULT_BUDGET_CHARS,
    ): List<PromptMessage> {
        val turns = mergeSameRole(
            history.mapNotNull { message ->
                val text = SpeechText.forDisplay(message.text)
                if (text.isEmpty()) {
                    null // e.g. an answer that was cancelled before the first word
                } else {
                    PromptMessage(
                        role = if (message.role == Role.USER) PromptMessage.USER else PromptMessage.ASSISTANT,
                        content = text,
                    )
                }
            },
        )

        val kept = trimToBudget(turns, maxChars - systemPrompt.length)

        val out = ArrayList<PromptMessage>(kept.size + 1)
        if (systemPrompt.isNotBlank()) out += PromptMessage(PromptMessage.SYSTEM, systemPrompt.trim())
        out += kept
        return out
    }

    /**
     * Two user messages in a row happen when an answer was interrupted before it
     * said anything. Models and APIs are happier with strictly alternating roles.
     */
    private fun mergeSameRole(turns: List<PromptMessage>): List<PromptMessage> {
        val out = ArrayList<PromptMessage>(turns.size)
        for (turn in turns) {
            val last = out.lastOrNull()
            if (last != null && last.role == turn.role) {
                out[out.lastIndex] = last.copy(content = last.content + "\n" + turn.content)
            } else {
                out += turn
            }
        }
        return out
    }

    /**
     * Drops the oldest turns until the rest fits. The newest message is never
     * dropped - a request without the question is pointless - and the window
     * never starts with an assistant message, which would be a reply to nothing.
     */
    private fun trimToBudget(turns: List<PromptMessage>, budget: Int): List<PromptMessage> {
        var start = 0
        var total = turns.sumOf { it.content.length }
        while (start < turns.lastIndex && total > budget) {
            total -= turns[start].content.length
            start++
        }
        while (start < turns.lastIndex && turns[start].role == PromptMessage.ASSISTANT) start++
        return turns.subList(start, turns.size)
    }
}
