package de.localvoice.mistralhandsfree.domain

/**
 * Cuts a token stream into speakable pieces.
 *
 * The model delivers text bit by bit, but the voice should start as soon as the
 * first sentence is complete. So the chunker cuts at sentence boundaries and,
 * if a sentence runs too long, at a word boundary as a last resort.
 *
 * Not thread-safe: one instance per answer, used from a single coroutine.
 */
class SentenceChunker(
    private val minChunkChars: Int = 24,
    private val maxChunkChars: Int = 220,
) {
    private var buffer: String = ""

    /** Appends new text and returns every chunk that is complete by now. */
    fun append(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        buffer += text
        val out = mutableListOf<String>()
        while (true) {
            val cut = findCut() ?: break
            val chunk = buffer.substring(0, cut).trim()
            buffer = buffer.substring(cut)
            if (chunk.isNotEmpty()) out += chunk
        }
        return out
    }

    /** Returns the remainder and empties the buffer. Call at the end of an answer. */
    fun flush(): String? {
        val rest = buffer.trim()
        buffer = ""
        return rest.ifEmpty { null }
    }

    private fun findCut(): Int? {
        for (i in buffer.indices) {
            val c = buffer[i]
            if (c == '\n') {
                if (i + 1 >= minChunkChars) return i + 1
                continue
            }
            if (c !in TERMINATORS) continue
            // A sentence boundary only counts once something follows it - "3.14"
            // or "e.g." must not be cut in the middle.
            val next = buffer.getOrNull(i + 1) ?: continue
            if (!next.isWhitespace()) continue
            if (i + 1 < minChunkChars) continue
            if (c == '.' && looksLikeAbbreviation(i)) continue
            return i + 1
        }
        if (buffer.length >= maxChunkChars) {
            val space = buffer.lastIndexOf(' ', maxChunkChars - 1)
            if (space >= minChunkChars) return space + 1
        }
        return null
    }

    /** "on May 3. we" or "e. g." are not sentence ends. */
    private fun looksLikeAbbreviation(dotIndex: Int): Boolean {
        val before = buffer.getOrNull(dotIndex - 1) ?: return false
        if (before.isDigit()) return true
        val beforeBefore = buffer.getOrNull(dotIndex - 2)
        return beforeBefore == null || beforeBefore.isWhitespace()
    }

    private companion object {
        const val TERMINATORS = ".!?…;:"
    }
}
