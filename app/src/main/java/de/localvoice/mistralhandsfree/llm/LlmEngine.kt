package de.localvoice.mistralhandsfree.llm

import de.localvoice.mistralhandsfree.domain.ChatMessage
import kotlinx.coroutines.flow.Flow

/** A language model that answers a conversation. */
interface LlmEngine {

    /** Name for the screen, e.g. the model id. */
    val displayName: String

    /**
     * Produces the answer to the conversation. The flow delivers fragments
     * (deltas), not the text so far.
     *
     * @param history ends with the new user message.
     */
    fun generate(history: List<ChatMessage>): Flow<String>
}
