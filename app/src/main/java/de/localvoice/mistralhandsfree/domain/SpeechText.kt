package de.localvoice.mistralhandsfree.domain

/**
 * Cleans model output so that it can be read aloud.
 *
 * Even with a "no markdown" instruction, chat models slip into formatting now
 * and then. A speech engine would read it out character by character
 * ("asterisk asterisk"), spell out URLs, or announce every emoji by name.
 */
object SpeechText {

    private val CONTROL_TOKENS = listOf(
        "<|im_end|>", "<|im_start|>", "<|endoftext|>", "</s>", "[INST]", "[/INST]",
    )

    private val CODE_BLOCK = Regex("```[\\s\\S]*?```")
    private val MARKDOWN_LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
    private val URL = Regex("https?://\\S+")
    private val EMPHASIS_MARKS = Regex("[*`~]+")

    /** "_word_" is emphasis, "snake_case" is not: only strip underscores at word edges. */
    private val EDGE_UNDERSCORES = Regex("(?<![\\p{L}\\p{N}])_+|_+(?![\\p{L}\\p{N}])")
    private val HEADING = Regex("(?m)^\\s{0,3}#{1,6}\\s*")
    private val BLOCKQUOTE = Regex("(?m)^\\s*>+\\s?")
    private val BULLET = Regex("(?m)^\\s*[-•*]\\s+")
    private val TABLE_PIPES = Regex("\\|+")

    /**
     * Misc. symbols/dingbats, variation selectors, joiners and the emoji planes.
     *
     * The astral range is written as code points (`\x{...}`): regexes match whole
     * code points, so a pattern built from the two UTF-16 halves of an emoji
     * would silently match nothing.
     */
    private val EMOJI = Regex("[\\u2600-\\u27BF\\uFE0F\\u200D\\u20E3\\x{1F000}-\\x{1FBFF}]")

    /** For the voice. */
    fun forSpeech(raw: String): String {
        var text = stripControlTokens(raw)
        text = text.replace(CODE_BLOCK, " ")
        text = text.replace(MARKDOWN_LINK, "$1")
        text = text.replace(URL, " ")
        text = text.replace(HEADING, "")
        text = text.replace(BLOCKQUOTE, "")
        // Bullets before emphasis: "* item" would otherwise lose its marker and keep the gap.
        text = text.replace(BULLET, "")
        text = text.replace(EMPHASIS_MARKS, "")
        text = text.replace(EDGE_UNDERSCORES, "")
        text = text.replace(TABLE_PIPES, " ")
        text = text.replace(EMOJI, "")
        text = text.replace(Regex("[ \\t]+"), " ")
        text = text.replace(Regex(" ?\\n ?"), "\n")
        text = text.replace(Regex("\\n{2,}"), "\n")
        return text.trim()
    }

    /** For the screen: only strip template leftovers, formatting stays. */
    fun forDisplay(raw: String): String = stripControlTokens(raw).trim()

    private fun stripControlTokens(raw: String): String {
        var text = raw
        for (token in CONTROL_TOKENS) text = text.replace(token, "")
        return text
    }
}
