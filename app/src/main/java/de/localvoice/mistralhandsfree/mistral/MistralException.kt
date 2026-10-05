package de.localvoice.mistralhandsfree.mistral

/**
 * Everything that can go wrong talking to Mistral, reduced to what the app has
 * to tell apart: it reacts differently to a rejected key (ask for a new one) than
 * to a rate limit (wait and try again) or a dead connection (just say so).
 *
 * [serverMessage] is what Mistral itself said, when it said anything. It is
 * shown to the user for the kinds where it is the only useful information,
 * e.g. a refused request or a moderation block.
 */
class MistralException(
    val kind: Kind,
    message: String,
    val httpCode: Int? = null,
    val retryAfterMs: Long? = null,
    val serverMessage: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {

    enum class Kind {
        /** 401: the key is wrong, revoked or missing. */
        UNAUTHORIZED,

        /** 403: no permission for this endpoint, or the content was blocked. */
        FORBIDDEN,

        /** 429: too many requests. Usually temporary. */
        RATE_LIMITED,

        /** 400/422: the request itself was refused. */
        BAD_REQUEST,

        /** 5xx: Mistral has a problem. */
        SERVER,

        /** No connection, timeout, or the connection broke mid-answer. */
        NETWORK,

        /** The server answered, but not in a way this client understands. */
        PROTOCOL,

        /** Any other HTTP status. */
        UNKNOWN,
    }

    /** Worth trying again without the user doing anything. */
    val isTransient: Boolean
        get() = kind == Kind.RATE_LIMITED || kind == Kind.SERVER || kind == Kind.NETWORK
}
