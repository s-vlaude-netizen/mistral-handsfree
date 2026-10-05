package de.localvoice.mistralhandsfree.mistral

/**
 * Picks the voice to use when the user has not chosen one.
 *
 * A voice that lists the language of the text is far better than one that does
 * not - Voxtral can speak across languages, but with the accent of the voice.
 * Among matches, the built-in voices come first, then the account's own.
 *
 * @param languageTag a BCP 47 tag such as "de-DE"; only the language part counts.
 */
fun pickVoice(voices: List<Voice>, languageTag: String): Voice? {
    if (voices.isEmpty()) return null
    val language = languageTag.substringBefore('-').substringBefore('_').lowercase()
    fun speaks(voice: Voice) = voice.languages.any {
        it.substringBefore('-').substringBefore('_').equals(language, ignoreCase = true)
    }
    return voices.firstOrNull { it.preset && speaks(it) }
        ?: voices.firstOrNull { speaks(it) }
        ?: voices.firstOrNull { it.preset }
        ?: voices.first()
}
