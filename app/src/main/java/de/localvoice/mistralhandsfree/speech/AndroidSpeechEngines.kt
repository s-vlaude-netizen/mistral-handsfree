package de.localvoice.mistralhandsfree.speech

import android.content.Context
import de.localvoice.mistralhandsfree.R
import de.localvoice.mistralhandsfree.data.AppSettings
import de.localvoice.mistralhandsfree.data.SttEngine
import de.localvoice.mistralhandsfree.data.TtsEngine
import de.localvoice.mistralhandsfree.domain.Languages
import de.localvoice.mistralhandsfree.mistral.MistralAudio
import de.localvoice.mistralhandsfree.mistral.MistralRepository
import de.localvoice.mistralhandsfree.mistral.VoiceChoice
import de.localvoice.mistralhandsfree.mistral.chooseVoice
import de.localvoice.mistralhandsfree.session.TextSource
import kotlinx.coroutines.CoroutineScope

/** The real engines: the system's and Mistral's. */
class AndroidSpeechEngines(
    private val context: Context,
    private val mistral: MistralRepository,
    private val scope: CoroutineScope,
    private val text: TextSource,
    /** Where Voxtral's audio comes from. The real microphone, except in tests. */
    private val mic: MicSourceFactory = AudioRecordMic,
) : SpeechEngines {

    override fun recognizer(settings: AppSettings): SpeechToText {
        val phone = Languages.deviceTag()
        return when (settings.sttEngine) {
            SttEngine.SYSTEM -> AndroidSpeechToText(
                context = context,
                languageTag = Languages.effectiveTag(settings.language, phone),
                preferOnDevice = settings.preferOnDevice,
                pauseMs = settings.pauseMs,
            )

            SttEngine.MISTRAL -> {
                // A language Voxtral lists is passed on: left to guess, it can pick the wrong
                // one for short or accented speech. "Automatic" lets it detect the language.
                val language = Languages.transcriptionCode(settings.language, phone)
                MistralSpeechToText(
                    text = text,
                    mic = mic,
                    transcribe = { wav ->
                        mistral.audio.transcribe(
                            wav = wav,
                            language = language,
                            modelId = mistral.catalog.value?.transcriptionModelId ?: MistralAudio.DEFAULT_STT_MODEL,
                        )
                    },
                    pauseMs = settings.pauseMs,
                )
            }
        }
    }

    override suspend fun speaker(settings: AppSettings): SpeakerSetup {
        val tag = Languages.effectiveTag(settings.language, Languages.deviceTag())
        val device = AndroidSpeaker(
            context = context,
            locale = Languages.locale(tag),
            speechRate = settings.speechRate,
            pitch = settings.pitch,
        )
        if (settings.ttsEngine == TtsEngine.SYSTEM) return SpeakerSetup(device)

        // The voice list is only needed when the user has not picked a voice themselves.
        val choice = if (settings.mistralVoiceId.isNotBlank()) {
            VoiceChoice.Chosen(settings.mistralVoiceId)
        } else {
            chooseVoice("", mistral.voicesOrLoad(), tag)
        }
        val voice = when (choice) {
            is VoiceChoice.Chosen -> choice.id
            is VoiceChoice.Matching -> choice.voice.id
            // Mistral's own voices are American and British English and French. Reading German
            // with one of them is accented German, so the phone's voice, which speaks it
            // natively, reads instead - and the user is told why and what else they can do.
            VoiceChoice.NoneForLanguage ->
                return SpeakerSetup(device, text.get(R.string.tts_no_mistral_voice_for, Languages.displayName(tag)))

            VoiceChoice.NoVoices -> return SpeakerSetup(device, text.get(R.string.tts_no_mistral_voice))
        }

        // Looked up per sentence rather than once: the model list may arrive after this speaker was made.
        fun modelId() = mistral.catalog.value?.speechModelId ?: MistralAudio.DEFAULT_TTS_MODEL

        return SpeakerSetup(
            MistralSpeaker(
                text = text,
                synthesize = { sentence -> mistral.audio.streamSpeech(sentence, voice, modelId()) },
                describe = { "${modelId()} · ${voice.take(8)}" },
                fallback = device,
                scope = scope,
                outputFactory = PcmOutputFactory { rate -> AudioTrackOutput(rate) },
                focus = AudioFocus(context, AudioFocus.SPEECH_ATTRIBUTES),
            ),
        )
    }
}
