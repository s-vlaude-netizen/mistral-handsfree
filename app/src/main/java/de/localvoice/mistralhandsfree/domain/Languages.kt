package de.localvoice.mistralhandsfree.domain

import java.util.Locale

/**
 * The language of a conversation.
 *
 * One setting decides three things at once, because they only work together:
 * what the recognizer listens for, which language the model is told to answer
 * in, and which voice reads the answer. When these were separate (or, for the
 * model, not settable at all) it was easy to end up with English speech
 * recognition, a German answer and an English voice.
 *
 * The setting is a plain string: [PHONE], [AUTOMATIC] or a BCP 47 tag such as
 * "en-US".
 */
object Languages {

    /** Follow the language of the phone. */
    const val PHONE = ""

    /**
     * Follow what the user says: Voxtral detects the language and the model
     * answers in kind. The parts that need one fixed language - the phone's
     * recognizer and voice, and the choice of a Mistral voice - use the phone's.
     */
    const val AUTOMATIC = "auto"

    /** A language offered by name. [name] is written in that language, as in a language menu. */
    class Choice(val tag: String, val name: String)

    /**
     * Languages with a name in the picker. The first eleven cover the nine that Voxtral
     * speaks (English, German, French, Spanish, Italian, Portuguese, Dutch, Hindi,
     * Arabic); the others are common ones that the phone's recognizer and voice and
     * the model handle. Any other language can still be entered as a tag.
     */
    val choices: List<Choice> = listOf(
        Choice("en-US", "English (US)"),
        Choice("en-GB", "English (UK)"),
        Choice("de-DE", "Deutsch"),
        Choice("fr-FR", "Français"),
        Choice("es-ES", "Español"),
        Choice("it-IT", "Italiano"),
        Choice("pt-BR", "Português (Brasil)"),
        Choice("pt-PT", "Português (Portugal)"),
        Choice("nl-NL", "Nederlands"),
        Choice("hi-IN", "हिन्दी"),
        Choice("ar-SA", "العربية"),
        Choice("ja-JP", "日本語"),
        Choice("ko-KR", "한국어"),
        Choice("zh-CN", "中文"),
        Choice("ru-RU", "Русский"),
        Choice("pl-PL", "Polski"),
        Choice("tr-TR", "Türkçe"),
        Choice("sv-SE", "Svenska"),
    )

    fun choiceFor(tag: String): Choice? = choices.firstOrNull { it.tag.equals(tag, ignoreCase = true) }

    /** The phone's language as a BCP 47 tag. Read when needed: it can change while the app runs. */
    fun deviceTag(): String = Locale.getDefault().toLanguageTag()

    /**
     * The [Locale] for a tag. Java only understands the hyphen form, and silently turns
     * "pt_BR" into no language at all - and people do type tags with an underscore.
     */
    fun locale(tag: String): Locale = Locale.forLanguageTag(tag.trim().replace('_', '-'))

    /**
     * "en" becomes "en-US", "de" becomes "de-DE": a bare language gets the region that
     * the picker offers for it, since recognizers and voices do better with a full tag.
     * Anything with a region, or outside the picker, stays as it is.
     */
    fun canonical(tag: String): String {
        val trimmed = tag.trim().replace('_', '-')
        if ('-' in trimmed) return trimmed
        return choices.firstOrNull { primary(it.tag) == trimmed.lowercase(Locale.ROOT) }?.tag ?: trimmed
    }

    /** "de" for "de-DE" or "de_AT". */
    fun primary(tag: String): String = tag.substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)

    /** Is [setting] a concrete language, as opposed to "like the phone" or "automatic"? */
    fun isExplicit(setting: String): Boolean = setting != PHONE && setting != AUTOMATIC && setting.isNotBlank()

    /** The one language to use where a fixed one is needed: the recognizer, the phone's voice, picking a voice. */
    fun effectiveTag(setting: String, deviceTag: String): String =
        if (isExplicit(setting)) setting else deviceTag

    /**
     * The languages Voxtral transcribes, according to Mistral's documentation, as the
     * two-letter codes of its `language` field.
     */
    val VOXTRAL_LANGUAGES: Set<String> =
        setOf("en", "zh", "hi", "es", "ar", "fr", "pt", "ru", "de", "ja", "ko", "it", "nl")

    /**
     * The code for the `language` field of Voxtral's transcription, or null to let
     * it detect the language.
     *
     * The field takes exactly two letters ("en"); a tag like "en-US" is rejected. A language
     * outside Voxtral's list gets no code either: the documentation promises nothing for it,
     * and a hint it cannot use is not worth the risk of a rejected request.
     */
    fun transcriptionCode(setting: String, deviceTag: String): String? {
        if (setting == AUTOMATIC) return null
        return primary(effectiveTag(setting, deviceTag)).takeIf { it in VOXTRAL_LANGUAGES }
    }

    /** Does Voxtral list the language of the setting? "Automatic" asks nothing of it. */
    fun voxtralListens(setting: String, deviceTag: String): Boolean =
        setting == AUTOMATIC || primary(effectiveTag(setting, deviceTag)) in VOXTRAL_LANGUAGES

    /** The language's name in English ("German"), for the instruction to the model. Null if the tag means nothing. */
    fun englishName(tag: String): String? {
        val name = locale(tag).getDisplayLanguage(Locale.ENGLISH)
        return name.takeIf { it.isNotBlank() && !it.equals(primary(tag), ignoreCase = true) }
    }

    /** The language's name in [inLocale] ("Deutsch" on a German phone), for messages to the user. */
    fun displayName(tag: String, inLocale: Locale = Locale.getDefault()): String {
        val name = locale(tag).getDisplayLanguage(inLocale)
        return if (name.isBlank()) tag else name.replaceFirstChar { it.titlecase(inLocale) }
    }

    /** What the setting is called in the picker: the language's own name, or the tag if it has none. */
    fun pickerName(tag: String): String = choiceFor(tag)?.name ?: displayName(tag, locale(tag))

    /** A sentence to try a voice with - in the language that voice is meant for. */
    fun sampleSentence(tag: String): String = when (primary(tag)) {
        "de" -> "Hallo! So klinge ich."
        "fr" -> "Bonjour ! Voilà comment je parle."
        "es" -> "¡Hola! Así es como sueno."
        "it" -> "Ciao! Ecco come suono."
        "pt" -> "Olá! É assim que eu soo."
        "nl" -> "Hallo! Zo klink ik."
        "hi" -> "नमस्ते! मेरी आवाज़ ऐसी है।"
        "ar" -> "مرحباً! هذا هو صوتي."
        "ja" -> "こんにちは！私の声はこんな感じです。"
        "ko" -> "안녕하세요! 제 목소리는 이렇습니다."
        "zh" -> "你好！这就是我的声音。"
        "ru" -> "Привет! Вот как я звучу."
        "pl" -> "Cześć! Tak brzmię."
        "tr" -> "Merhaba! Sesim böyle."
        "sv" -> "Hej! Så här låter jag."
        else -> "Hello! This is how I sound."
    }
}
