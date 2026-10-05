package de.localvoice.mistralhandsfree.auth

/** Pure helpers for dealing with a Mistral API key the user typed or pasted. */
object ApiKeys {

    private val WHITESPACE = Regex("\\s+")
    private val PLAUSIBLE = Regex("[A-Za-z0-9_.\\-]{16,200}")

    /**
     * Reduces whatever was pasted to the key itself.
     *
     * People copy more than they mean to: `export MISTRAL_API_KEY=abc`, a whole
     * `Authorization: Bearer abc` header, or the key wrapped in quotes. The key
     * is always the last word, after any `=`.
     */
    fun normalize(raw: String): String {
        val lastWord = raw.trim().split(WHITESPACE).lastOrNull().orEmpty()
        return lastWord
            .substringAfterLast('=')
            .trim('"', '\'', '`', ',', ';')
    }

    /**
     * Cheap sanity check, so that an obviously wrong paste (a URL, a sentence, the
     * clipboard of something else) is rejected on the spot instead of costing a
     * network round trip. Whether the key actually works is for Mistral to say.
     */
    fun looksPlausible(key: String): Boolean = PLAUSIBLE.matches(key)

    /** What to show instead of the secret: only the last four characters. */
    fun mask(key: String): String =
        if (key.length <= 8) "••••••••" else "••••••••" + key.takeLast(4)
}
