package de.localvoice.mistralhandsfree.mistral

import okio.BufferedSource

/** One server-sent event: the optional `event:` name and the joined `data:` lines. */
data class SseEvent(val event: String?, val data: String)

/**
 * Reads a `text/event-stream` body event by event.
 *
 * Follows the WHATWG rules that matter for APIs like this one: events end at a
 * blank line, several `data:` lines are joined with a newline, lines starting
 * with a colon are comments, and an event without data is not delivered. The
 * `id:` and `retry:` fields are irrelevant here and ignored.
 *
 * [next] blocks on the underlying source, so call it from an IO thread.
 */
class SseReader(private val source: BufferedSource) {

    /** The next event, or null once the stream has ended. */
    fun next(): SseEvent? {
        var eventName: String? = null
        val data = StringBuilder()
        var hasData = false

        while (true) {
            val line = source.readUtf8Line()
                ?: return if (hasData) SseEvent(eventName, data.toString()) else null

            if (line.isEmpty()) {
                if (hasData) return SseEvent(eventName, data.toString())
                eventName = null // blank line without data: reset and keep reading
                continue
            }
            if (line.startsWith(":")) continue // comment / keep-alive

            val colon = line.indexOf(':')
            val field = if (colon < 0) line else line.substring(0, colon)
            var value = if (colon < 0) "" else line.substring(colon + 1)
            if (value.startsWith(" ")) value = value.substring(1)

            when (field) {
                "data" -> {
                    if (hasData) data.append('\n')
                    data.append(value)
                    hasData = true
                }

                "event" -> eventName = value
                // "id", "retry" and unknown fields are ignored on purpose.
            }
        }
    }
}
