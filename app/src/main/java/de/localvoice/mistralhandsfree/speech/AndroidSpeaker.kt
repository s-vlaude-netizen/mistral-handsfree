package de.localvoice.mistralhandsfree.speech

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import de.localvoice.mistralhandsfree.R
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Speech output through the Android TTS engine.
 *
 * Two pitfalls shape the design:
 *
 * First, Android lists voices in [TextToSpeech.getVoices] even when their data
 * is not on the device. Select such a voice and speak() cheerfully reports
 * success - and nothing is heard. Hence the check for
 * [TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED].
 *
 * Second, the callbacks of cancelled utterances arrive late. A plain counter
 * would be counted down by them and declare the utterance that is playing right
 * now to be finished. So utterances are tracked individually by their id: a
 * callback whose id is no longer outstanding goes nowhere.
 *
 * Unlike the basis of this app, voices that need the network are fine here -
 * Mistral is online anyway - they are just ranked behind installed ones.
 */
class AndroidSpeaker(
    private val context: Context,
    private val locale: Locale = Locale.getDefault(),
    private val speechRate: Float = 1.0f,
    private val pitch: Float = 1.0f,
) : Speaker {

    private var tts: TextToSpeech? = null

    /** Ids of the utterances that are still outstanding. */
    private val outstanding: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    private val pendingFlow = MutableStateFlow(0)
    private val utteranceCounter = AtomicLong(0)

    private val _warning = MutableStateFlow<String?>(null)
    override val warning: StateFlow<String?> = _warning.asStateFlow()

    private val _diagnostics = MutableStateFlow(context.getString(R.string.tts_not_started))
    override val diagnostics: StateFlow<String> = _diagnostics.asStateFlow()

    private val _busy = MutableStateFlow(false)
    override val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val focus = AudioFocus(context, AudioFocus.SPEECH_ATTRIBUTES)

    override suspend fun prepare(): Boolean {
        tts?.let { return true }
        // The constructor reports success only later, through the listener.
        val holder = arrayOfNulls<TextToSpeech>(1)
        val status: Int = suspendCancellableCoroutine { continuation ->
            holder[0] = TextToSpeech(context) { code ->
                if (continuation.isActive) continuation.resume(code)
            }
        }
        val engine = holder[0]?.takeIf { status == TextToSpeech.SUCCESS } ?: run {
            runCatching { holder[0]?.shutdown() }
            _warning.value = context.getString(R.string.tts_no_engine)
            _diagnostics.value = context.getString(R.string.tts_no_engine_short)
            return false
        }

        engine.setAudioAttributes(AudioFocus.SPEECH_ATTRIBUTES)
        val languageStatus = engine.setLanguage(locale)
        val languageMissing = languageStatus == TextToSpeech.LANG_MISSING_DATA ||
            languageStatus == TextToSpeech.LANG_NOT_SUPPORTED

        val voice = selectUsableVoice(engine)
        if (voice != null) runCatching { engine.setVoice(voice) }

        _diagnostics.value = context.getString(
            R.string.tts_diagnostics,
            engine.defaultEngine ?: context.getString(R.string.unknown),
            voice?.name ?: context.getString(R.string.tts_engine_default_voice),
            locale.toLanguageTag(),
        )

        _warning.value = when {
            voice != null -> null
            languageMissing -> context.getString(R.string.tts_language_missing, locale.displayLanguage)
            else -> context.getString(R.string.tts_no_voice, locale.displayLanguage)
        }

        engine.setSpeechRate(speechRate)
        engine.setPitch(pitch)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId != null && utteranceId in outstanding) _busy.value = true
            }

            override fun onDone(utteranceId: String?) = release(utteranceId)

            @Deprecated("Superseded by the platform, but must still be overridden.")
            override fun onError(utteranceId: String?) = release(utteranceId)

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "Speech output reports error $errorCode")
                if (utteranceId != null && utteranceId in outstanding) {
                    _warning.value = context.getString(R.string.tts_failed, errorCode)
                }
                release(utteranceId)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) = release(utteranceId)
        })

        tts = engine
        return true
    }

    /**
     * Strikes an utterance off the list.
     *
     * Ids that are not listed any more belong to cancelled utterances and are
     * ignored - otherwise their late callback would declare the output that is
     * playing right now to be finished.
     */
    private fun release(utteranceId: String?) {
        if (utteranceId == null || !outstanding.remove(utteranceId)) return
        val left = outstanding.size
        pendingFlow.value = left
        if (left == 0) {
            _busy.value = false
            focus.release()
        }
    }

    /**
     * A voice that fits the language and whose data is actually installed;
     * among those, the best quality and an on-device one before a network one.
     */
    private fun selectUsableVoice(engine: TextToSpeech): Voice? {
        val voices: Set<Voice> = runCatching { engine.voices }.getOrNull().orEmpty()
        return voices
            .filter { it.locale.language == locale.language }
            .filter { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
            .maxWithOrNull(
                compareBy<Voice> { !it.isNetworkConnectionRequired }.thenBy { it.quality },
            )
    }

    override fun enqueue(text: String) = speak(text, TextToSpeech.QUEUE_ADD)

    override fun speakNow(text: String) = speak(text, TextToSpeech.QUEUE_FLUSH)

    private fun speak(text: String, queueMode: Int) {
        val engine = tts ?: run {
            _warning.value = context.getString(R.string.tts_not_ready)
            return
        }
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (queueMode == TextToSpeech.QUEUE_FLUSH) {
            outstanding.clear()
            pendingFlow.value = 0
        }
        val id = "utt-" + utteranceCounter.incrementAndGet()
        outstanding.add(id)
        pendingFlow.value = outstanding.size
        _busy.value = true
        focus.acquire()
        val status = engine.speak(clean, queueMode, Bundle(), id)
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "speak() rejected: $status")
            _warning.value = context.getString(R.string.tts_rejected, status)
            release(id)
        }
    }

    override suspend fun awaitIdle() {
        pendingFlow.map { it == 0 }.first { it }
    }

    override fun stop() {
        // Empty the list first, then stop: the callbacks of the cancelled
        // utterances then go nowhere.
        outstanding.clear()
        pendingFlow.value = 0
        _busy.value = false
        runCatching { tts?.stop() }
        focus.release()
    }

    override fun shutdown() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
    }

    private companion object {
        const val TAG = "AndroidSpeaker"
    }
}
