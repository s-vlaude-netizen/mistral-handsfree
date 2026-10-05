package de.localvoice.mistralhandsfree.mistral

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/*
 * The responses are read as JSON trees rather than into data classes. Mistral's
 * schema bends in places - a message's `content` is a string in one reply and
 * an array of typed chunks in the next - and a tree walk copes with that
 * without a custom serializer for every spot.
 */

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject

internal fun JsonElement?.arr(): JsonArray? = this as? JsonArray

/** The text of a JSON string; null for anything else, including `null`. */
internal fun JsonElement?.str(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

internal fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

internal fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull

/**
 * The text carried by a message `content`.
 *
 * Plain models send a string. Models with reasoning send an array of chunks,
 * mixing `{"type":"thinking",...}` with `{"type":"text","text":...}`. Only the
 * text chunks are the answer: the thinking must neither be shown as the reply
 * nor read aloud.
 */
internal fun contentText(content: JsonElement?): String = when (content) {
    null, is JsonNull -> ""
    is JsonPrimitive -> content.content
    is JsonArray -> buildString {
        for (chunk in content) {
            val obj = chunk.obj() ?: continue
            if (obj["type"].str() == "text") append(obj["text"].str().orEmpty())
        }
    }

    else -> ""
}

internal fun parseJsonOrNull(text: String): JsonElement? = try {
    MistralJson.parseToJsonElement(text)
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

/** Pulls the human-readable message out of the various shapes Mistral's errors come in. */
internal object ErrorBody {

    fun message(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        val root = parseJsonOrNull(trimmed)
        if (root == null) {
            // Not JSON. Never surface an HTML error page from a proxy in front of the API.
            return if (trimmed.startsWith("<")) null else trimmed.take(MAX_LENGTH)
        }
        return fromJson(root)?.take(MAX_LENGTH)
    }

    /** Also used for error objects that arrive in the middle of a stream. */
    fun fromJson(root: JsonElement): String? {
        val obj = root.obj() ?: return null
        obj["message"].str()?.let { return it }
        obj["error"].obj()?.get("message").str()?.let { return it }
        obj["error"].str()?.let { return it }

        val detail = obj["detail"]
        detail.str()?.let { return it }
        // FastAPI-style validation errors: a list of {loc: [...], msg: "..."}.
        detail.arr()?.let { items ->
            val parts = items.mapNotNull { item ->
                val entry = item.obj() ?: return@mapNotNull item.str()
                val msg = entry["msg"].str() ?: return@mapNotNull null
                val where = entry["loc"].arr()?.mapNotNull { it.str() ?: it.int()?.toString() }
                    ?.joinToString(".")
                if (where.isNullOrEmpty()) msg else "$where: $msg"
            }
            if (parts.isNotEmpty()) return parts.take(3).joinToString("; ")
        }
        return null
    }

    private const val MAX_LENGTH = 300
}
