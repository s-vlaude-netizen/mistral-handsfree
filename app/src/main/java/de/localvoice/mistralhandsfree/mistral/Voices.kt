package de.localvoice.mistralhandsfree.mistral

import de.localvoice.mistralhandsfree.domain.Languages

/**
 * The Mistral voice that speaks [languageTag], or null if none does.
 *
 * A voice that lists the language is far better than one that does not: Voxtral
 * can speak across languages, but with the accent of the voice. Mistral's built-in
 * voices are American and British English and French, so for German there is
 * none - which is why this does not fall back to "some voice": German text read by
 * an English voice is exactly what people do not want to hear.
 *
 * Among matches a voice that lists the full tag ("en-GB") beats one that lists only
 * the language, and the built-in voices come before the account's own.
 *
 * @param languageTag a BCP 47 tag such as "de-DE".
 */
fun pickVoice(voices: List<Voice>, languageTag: String): Voice? {
    val full = normalized(languageTag)
    fun speaksExactly(voice: Voice) = voice.languages.any { normalized(it) == full }
    return voices.firstOrNull { it.preset && speaksExactly(it) }
        ?: voices.firstOrNull { speaksExactly(it) }
        ?: voices.firstOrNull { it.preset && it.speaks(languageTag) }
        ?: voices.firstOrNull { it.speaks(languageTag) }
}

/** Does the voice list the language of [languageTag]? Regions do not matter here. */
fun Voice.speaks(languageTag: String): Boolean {
    val language = Languages.primary(languageTag)
    return languages.any { Languages.primary(it) == language }
}

private fun normalized(tag: String) = tag.replace('_', '-').lowercase()

/** Which voice to speak with, and why. */
sealed interface VoiceChoice {

    /** The user picked this voice themselves; it is used whatever it speaks. */
    data class Chosen(val id: String) : VoiceChoice

    /** "Automatic": the voice that speaks the language. */
    data class Matching(val voice: Voice) : VoiceChoice

    /** "Automatic", but none of the account's voices speaks the language. */
    data object NoneForLanguage : VoiceChoice

    /** The account has no voices to choose from (or they could not be loaded). */
    data object NoVoices : VoiceChoice
}

/**
 * @param selectedId the voice the user picked; blank for "Automatic".
 * @param voices the account's voices.
 */
fun chooseVoice(selectedId: String, voices: List<Voice>, languageTag: String): VoiceChoice {
    if (selectedId.isNotBlank()) return VoiceChoice.Chosen(selectedId)
    if (voices.isEmpty()) return VoiceChoice.NoVoices
    val voice = pickVoice(voices, languageTag) ?: return VoiceChoice.NoneForLanguage
    return VoiceChoice.Matching(voice)
}
