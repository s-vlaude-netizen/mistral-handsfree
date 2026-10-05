package de.localvoice.mistralhandsfree.domain

/** Who spoke. */
enum class Role { USER, ASSISTANT }

/**
 * One line of the conversation.
 *
 * @param streaming true while the answer is still growing token by token.
 */
data class ChatMessage(
    val id: Long,
    val role: Role,
    val text: String,
    val timestampMs: Long,
    val streaming: Boolean = false,
)
