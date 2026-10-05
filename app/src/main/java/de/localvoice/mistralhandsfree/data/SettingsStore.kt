package de.localvoice.mistralhandsfree.data

import android.content.Context
import de.localvoice.mistralhandsfree.domain.Languages
import de.localvoice.mistralhandsfree.domain.SystemPrompt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Who turns the user's speech into text. */
enum class SttEngine {
    /** The recognizer built into the phone. Free and quick to start. */
    SYSTEM,

    /** Mistral's Voxtral. The app watches for the pause itself, so its length is up to the user. */
    MISTRAL,
}

/** Who reads the answer aloud. */
enum class TtsEngine {
    /** The voice built into the phone. Free, works offline. */
    SYSTEM,

    /** Mistral's Voxtral TTS. Much more natural, billed per character. */
    MISTRAL,
}

/** Everything the user can set. */
data class AppSettings(
    /** Chat model id, e.g. "mistral-small-latest". */
    val model: String = DEFAULT_MODEL,
    val temperature: Float = 0.7f,
    /** Upper bound for the length of one answer, in tokens. */
    val maxTokens: Int = 1024,
    /** What the user wrote as the instruction to the model. Empty means the built-in one. */
    val systemPrompt: String = "",
    /**
     * The language of the conversation: what is listened for, what the model answers in
     * and which voice reads it. [Languages.PHONE], [Languages.AUTOMATIC] or a tag like "en-US".
     */
    val language: String = Languages.PHONE,

    /** Listen again automatically after every answer - the actual hands-free behaviour. */
    val handsFree: Boolean = true,
    /** Clear the conversation every time live mode starts. */
    val freshStart: Boolean = false,

    val sttEngine: SttEngine = SttEngine.SYSTEM,
    /** System recognizer only: keep the audio on the phone instead of sending it to Google. */
    val preferOnDevice: Boolean = false,
    /** How long a pause ends your turn, in milliseconds. */
    val pauseMs: Int = DEFAULT_PAUSE_MS,

    val ttsEngine: TtsEngine = TtsEngine.SYSTEM,
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f,
    /** Voxtral voice id; empty means "pick one that matches the language". */
    val mistralVoiceId: String = "",
) {
    companion object {
        const val DEFAULT_MODEL = "mistral-small-latest"
        const val DEFAULT_PAUSE_MS = 1200
        const val MIN_PAUSE_MS = 600
        const val MAX_PAUSE_MS = 3000
    }
}

/**
 * Settings in SharedPreferences - small, synchronous and dependency-free. The
 * current state is available as a [StateFlow] for the UI.
 *
 * The API key is deliberately not in here; see
 * [de.localvoice.mistralhandsfree.auth.ApiKeyStore].
 */
class SettingsStore(
    context: Context,
    private val deviceTag: () -> String = Languages::deviceTag,
) : SettingsSource {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    override val current: AppSettings get() = _settings.value

    override fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        write(next)
        _settings.value = next
    }

    private fun read(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            model = prefs.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() } ?: d.model,
            temperature = prefs.getFloat(KEY_TEMPERATURE, d.temperature),
            maxTokens = prefs.getInt(KEY_MAX_TOKENS, d.maxTokens),
            // The built-in instruction is not stored, so that it can improve. Earlier versions
            // stored it anyway (in the language of the phone); those copies count as "none".
            systemPrompt = prefs.getString(KEY_SYSTEM_PROMPT, null)
                ?.takeUnless(SystemPrompt::isBuiltIn).orEmpty(),
            language = prefs.getString(KEY_LANGUAGE, null) ?: legacyLanguage(),
            handsFree = prefs.getBoolean(KEY_HANDS_FREE, d.handsFree),
            freshStart = prefs.getBoolean(KEY_FRESH_START, d.freshStart),
            sttEngine = enumOf(prefs.getString(KEY_STT_ENGINE, null), d.sttEngine),
            preferOnDevice = prefs.getBoolean(KEY_PREFER_ON_DEVICE, d.preferOnDevice),
            pauseMs = prefs.getInt(KEY_PAUSE_MS, d.pauseMs)
                .coerceIn(AppSettings.MIN_PAUSE_MS, AppSettings.MAX_PAUSE_MS),
            ttsEngine = enumOf(prefs.getString(KEY_TTS_ENGINE, null), d.ttsEngine),
            speechRate = prefs.getFloat(KEY_SPEECH_RATE, d.speechRate),
            pitch = prefs.getFloat(KEY_PITCH, d.pitch),
            mistralVoiceId = prefs.getString(KEY_VOICE_ID, null).orEmpty(),
        )
    }

    private fun write(s: AppSettings) {
        prefs.edit().apply {
            putString(KEY_MODEL, s.model)
            putFloat(KEY_TEMPERATURE, s.temperature)
            putInt(KEY_MAX_TOKENS, s.maxTokens)
            putString(KEY_SYSTEM_PROMPT, if (SystemPrompt.isBuiltIn(s.systemPrompt)) "" else s.systemPrompt)
            putString(KEY_LANGUAGE, s.language)
            putBoolean(KEY_HANDS_FREE, s.handsFree)
            putBoolean(KEY_FRESH_START, s.freshStart)
            putString(KEY_STT_ENGINE, s.sttEngine.name)
            putBoolean(KEY_PREFER_ON_DEVICE, s.preferOnDevice)
            putInt(KEY_PAUSE_MS, s.pauseMs)
            putString(KEY_TTS_ENGINE, s.ttsEngine.name)
            putFloat(KEY_SPEECH_RATE, s.speechRate)
            putFloat(KEY_PITCH, s.pitch)
            putString(KEY_VOICE_ID, s.mistralVoiceId)
            // Replaced by KEY_LANGUAGE.
            remove(KEY_STT_LANG)
            remove(KEY_TTS_LANG)
        }.apply()
    }

    /**
     * Earlier versions had one language for the recognizer and one for the voice, both
     * saved with the phone's language as the default. Whichever of them differs from the
     * phone's language was a choice the user made, so that is carried over.
     */
    private fun legacyLanguage(): String {
        val phone = Languages.primary(deviceTag())
        return listOf(KEY_TTS_LANG, KEY_STT_LANG)
            .mapNotNull { key -> prefs.getString(key, null)?.trim()?.takeIf { it.isNotEmpty() } }
            .firstOrNull { Languages.primary(it) != phone }
            ?.let(Languages::canonical)
            ?: Languages.PHONE
    }

    private inline fun <reified E : Enum<E>> enumOf(name: String?, fallback: E): E =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: fallback

    private companion object {
        const val KEY_MODEL = "model"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_MAX_TOKENS = "max_tokens"
        const val KEY_SYSTEM_PROMPT = "system_prompt"
        const val KEY_LANGUAGE = "language"
        const val KEY_HANDS_FREE = "hands_free"
        const val KEY_FRESH_START = "fresh_start"
        const val KEY_STT_ENGINE = "stt_engine"
        const val KEY_STT_LANG = "stt_lang"
        const val KEY_PREFER_ON_DEVICE = "prefer_on_device"
        const val KEY_PAUSE_MS = "pause_ms"
        const val KEY_TTS_ENGINE = "tts_engine"
        const val KEY_TTS_LANG = "tts_lang"
        const val KEY_SPEECH_RATE = "speech_rate"
        const val KEY_PITCH = "pitch"
        const val KEY_VOICE_ID = "voice_id"
    }
}
