package de.localvoice.mistralhandsfree.llm

import de.localvoice.mistralhandsfree.data.AppSettings
import de.localvoice.mistralhandsfree.domain.ChatMessage
import de.localvoice.mistralhandsfree.domain.ConversationWindow
import de.localvoice.mistralhandsfree.mistral.ChatParams
import de.localvoice.mistralhandsfree.mistral.MistralClient
import kotlinx.coroutines.flow.Flow

/**
 * The chat model behind Mistral's API.
 *
 * Holds no conversation state of its own: the API is stateless, so each call
 * sends the (trimmed) history again. Settings are read per call, so a change of
 * model or temperature applies from the very next turn without rebuilding anything.
 */
class MistralLlmEngine(
    private val client: MistralClient,
    private val settings: () -> AppSettings,
) : LlmEngine {

    override val displayName: String get() = settings().model

    override fun generate(history: List<ChatMessage>): Flow<String> {
        val current = settings()
        val messages = ConversationWindow.build(current.systemPrompt, history)
        return client.streamChat(
            messages = messages,
            params = ChatParams(
                model = current.model,
                temperature = current.temperature.toDouble(),
                maxTokens = current.maxTokens.takeIf { it > 0 },
            ),
        )
    }
}
