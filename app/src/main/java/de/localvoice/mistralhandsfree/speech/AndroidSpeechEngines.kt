package de.localvoice.mistralhandsfree.speech

import android.content.Context
import de.localvoice.mistralhandsfree.R
import de.localvoice.mistralhandsfree.data.AppSettings
import de.localvoice.mistralhandsfree.data.SttEngine
import de.localvoice.mistralhandsfree.data.TtsEngine
import de.localvoice.mistralhandsfree.mistral.MistralAudio
import de.localvoice.mistralhandsfree.mistral.MistralRepository
import de.localvoice.mistralhandsfree.mistral.pickVoice
import de.localvoice.mistralhandsfree.session.TextSource
import java.util.Locale
import kotlinx.coroutines.CoroutineScope

/** The real engines: the system's and Mistral's. */
class AndroidSpeechEngines(
    private val context: Context,
    private val mistral: MistralRepository,
    private val scope: CoroutineScope,
    private val text: TextSource,
) : SpeechEngines {

    override fun recognizer(settings: AppSettings): SpeechToText = when (settings.sttEngine) {
        SttEngine.SYSTEM -> AndroidSpeechToText(
            context = context,
            languageTag = settings.sttLanguageTag,
            preferOnDevice = settings.preferOnDevice,
            pauseMs = settings.pauseMs,
        )

        SttEngine.MISTRAL -> MistralSpeechToText(
            context = context,
            audio = mistral.audio,
            transcriptionModel = {
                mistral.catalog.value?.transcriptionModelId ?: MistralAudio.DEFAULT_STT_MODEL
            },
            pauseMs = settings.pauseMs,
        )
    }

    override suspend fun speaker(settings: AppSettings): SpeakerSetup {
        val device = AndroidSpeaker(
            context = context,
            locale = Locale.forLanguageTag(settings.ttsLanguageTag),
            speechRate = settings.speechRate,
            pitch = settings.pitch,
        )
        if (settings.ttsEngine == TtsEngine.SYSTEM) return SpeakerSetup(device)

        val voice = settings.mistralVoiceId.ifBlank {
            pickVoice(mistral.voicesOrLoad(), settings.ttsLanguageTag)?.id.orEmpty()
        }
        // No voice to speak with: say so, and carry on with the phone's own.
        if (voice.isEmpty()) return SpeakerSetup(device, text.get(R.string.tts_no_mistral_voice))

        return SpeakerSetup(
            MistralSpeaker(
                context = context,
                audio = mistral.audio,
                modelId = { mistral.catalog.value?.speechModelId ?: MistralAudio.DEFAULT_TTS_MODEL },
                voiceId = { voice },
                fallback = device,
                scope = scope,
            ),
        )
    }
}
