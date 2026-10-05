package de.localvoice.mistralhandsfree.domain

/**
 * The instruction the model gets at the start of every request.
 *
 * It is built from two parts: the text the user wrote (or the built-in one) and a
 * line about the language, taken from the language setting. The line is what makes
 * the language setting reach the model at all.
 */
object SystemPrompt {

    /**
     * The built-in instruction. It is English for every phone on purpose: models
     * follow English instructions best, and an instruction in German nudges the
     * answers towards German whatever the user speaks. The language of the answers
     * is stated separately, by [languageLine].
     */
    const val DEFAULT =
        "You are a voice assistant in a hands-free conversation. The user speaks to you and hears your " +
            "answer read aloud. Keep it short and natural: two or three sentences are usually enough, " +
            "unless the user asks for more. No markdown, no lists, no headings, no emojis and no web " +
            "addresses - everything is spoken. Write numbers and symbols the way they are pronounced " +
            "when that avoids confusion."

    /**
     * What earlier versions saved as if the user had written it: the built-in
     * instruction in the language of the phone, stored on the first change of any
     * setting. Left alone it would keep a German instruction alive for people who
     * have long since switched the language.
     */
    private val SUPERSEDED_DEFAULTS = setOf(
        """You are a voice assistant in a hands-free conversation. The user speaks to you and hears your answer read aloud. Answer in the language the user speaks. Keep it short and natural: two or three sentences are usually enough, unless the user asks for more. No markdown, no lists, no headings, no emojis and no web addresses - everything is spoken. Write numbers and symbols the way they are pronounced when that avoids confusion.""",
        """Du bist ein Sprachassistent in einem Freisprech-Gespräch. Der Nutzer spricht mit dir und hört deine Antwort vorgelesen. Antworte in der Sprache, in der der Nutzer spricht. Fasse dich kurz und natürlich: Zwei oder drei Sätze genügen meistens, es sei denn, der Nutzer möchte mehr. Kein Markdown, keine Listen, keine Überschriften, keine Emojis und keine Internetadressen – alles wird vorgelesen. Schreibe Zahlen und Symbole so, wie man sie ausspricht, wenn das Missverständnisse vermeidet.""",
    )

    /** true if [text] is empty or just one of the built-in instructions - nothing the user wrote. */
    fun isBuiltIn(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.isEmpty() || trimmed == DEFAULT || trimmed in SUPERSEDED_DEFAULTS
    }

    /**
     * The full instruction: [custom] (or the built-in text if there is none) plus the language line.
     *
     * @param language the language setting, see [Languages].
     * @param deviceTag the phone's language, for the setting "like the phone".
     */
    fun build(custom: String, language: String, deviceTag: String): String {
        val base = if (isBuiltIn(custom)) DEFAULT else custom.trim()
        return base + "\n\n" + languageLine(language, deviceTag)
    }

    /** The sentence that tells the model which language to answer in. */
    fun languageLine(language: String, deviceTag: String): String {
        if (language == Languages.AUTOMATIC) return FOLLOW_THE_USER
        val name = Languages.englishName(Languages.effectiveTag(language, deviceTag)) ?: return FOLLOW_THE_USER
        return "The user speaks $name. Always reply in $name, unless they explicitly ask for another language."
    }

    private const val FOLLOW_THE_USER = "Reply in the language the user speaks."
}
