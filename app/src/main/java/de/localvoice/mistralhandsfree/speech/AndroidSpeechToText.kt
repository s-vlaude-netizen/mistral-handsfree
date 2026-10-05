package de.localvoice.mistralhandsfree.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import de.localvoice.mistralhandsfree.R
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Speech recognition through the Android system recognizer.
 *
 * The silence detection that makes hands-free possible lives in here: the
 * recognizer decides on its own when the speaker has finished and then delivers
 * the result, so no button is needed to say "send". How long a pause may last
 * before that is passed on as a hint ([pauseMs]); not every recognizer honours
 * it, and Google's often decides for itself. For exact control over the pause
 * use [MistralSpeechToText].
 *
 * With [preferOnDevice] the on-device recognizer is used where there is one
 * (Android 13+) and no audio leaves the phone - at the price of needing the
 * offline language pack. Otherwise the normal system recognizer is used, which
 * is usually the more accurate one and needs no setup.
 *
 * [SpeechRecognizer] is bound to the main thread - every call therefore goes
 * through [Dispatchers.Main].
 */
class AndroidSpeechToText(
    private val context: Context,
    private val languageTag: String,
    private val preferOnDevice: Boolean,
    private val pauseMs: Int,
    private val maxListenMs: Long = 30_000,
) : SpeechToText {

    private val _partialText = MutableStateFlow("")
    override val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level.asStateFlow()

    private val _processing = MutableStateFlow(false)
    override val processing: StateFlow<Boolean> = _processing.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override suspend fun listenOnce(): SttResult = withContext(Dispatchers.Main) {
        _partialText.value = ""
        _level.value = 0f
        _processing.value = false

        val engine = try {
            obtainRecognizer()
        } catch (t: Throwable) {
            Log.e(TAG, "Speech recognition unavailable", t)
            return@withContext SttResult.Failure(
                context.getString(R.string.stt_no_recognizer),
                recoverable = false,
            )
        }

        val result = withTimeoutOrNull(maxListenMs) { awaitResult(engine) }

        _level.value = 0f
        _processing.value = false
        if (result != null) return@withContext result

        // Someone talking for the whole of maxListenMs: take what was understood
        // so far rather than dropping it.
        runCatching { engine.cancel() }
        val partial = _partialText.value.trim()
        if (partial.isNotEmpty()) SttResult.Text(partial) else SttResult.Silence
    }

    private suspend fun awaitResult(engine: SpeechRecognizer): SttResult =
        suspendCancellableCoroutine { continuation ->
            val listener = object : RecognitionListener {
                private var finished = false

                private fun finish(result: SttResult) {
                    if (finished) return
                    finished = true
                    _processing.value = false
                    if (continuation.isActive) continuation.resume(result)
                }

                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit

                override fun onEndOfSpeech() {
                    // The recognizer has decided you are done: show that you were heard.
                    _level.value = 0f
                    if (!finished) _processing.value = true
                }

                override fun onRmsChanged(rmsdB: Float) {
                    // The recognizer reports roughly -2..10 dB; map to 0..1.
                    _level.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                }

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onPartialResults(partialResults: Bundle?) {
                    firstResult(partialResults)?.let { _partialText.value = it }
                }

                override fun onResults(results: Bundle?) {
                    val text = firstResult(results)?.trim().orEmpty()
                    finish(if (text.isEmpty()) SttResult.Silence else SttResult.Text(text))
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onError(error: Int) {
                    finish(mapError(error))
                }
            }

            continuation.invokeOnCancellation {
                runCatching { engine.cancel() }
            }

            engine.setRecognitionListener(listener)
            try {
                engine.startListening(buildIntent())
            } catch (t: Throwable) {
                Log.e(TAG, "startListening failed", t)
                if (continuation.isActive) {
                    continuation.resume(
                        SttResult.Failure(context.getString(R.string.stt_start_failed), true),
                    )
                }
            }
        }

    private fun obtainRecognizer(): SpeechRecognizer {
        recognizer?.let { return it }
        val created = if (
            preferOnDevice &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = created
        return created
    }

    private fun buildIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOnDevice)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // How long silence counts as the end of the utterance (a hint - not every
            // recognizer honours it). Both values are the same on purpose: the "possibly
            // complete" one is what makes a recognizer cut in at a short mid-sentence pause.
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                pauseMs.toLong(),
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                pauseMs.toLong(),
            )
        }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun mapError(error: Int): SttResult = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> SttResult.Silence

        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
            // The recognizer hangs occasionally; rebuild it for the next round.
            recreateRecognizer()
            SttResult.Failure(context.getString(R.string.stt_busy), recoverable = true)
        }

        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            SttResult.Failure(context.getString(R.string.stt_no_permission), recoverable = false)

        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        -> SttResult.Failure(
            context.getString(
                if (preferOnDevice) R.string.stt_language_missing_offline else R.string.stt_language_unsupported,
                languageTag,
            ),
            recoverable = false,
        )

        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        -> SttResult.Failure(context.getString(R.string.stt_network), recoverable = true)

        SpeechRecognizer.ERROR_CLIENT,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        -> {
            recreateRecognizer()
            SttResult.Silence
        }

        else -> SttResult.Failure(
            context.getString(R.string.stt_error_code, error),
            recoverable = true,
        )
    }

    private fun recreateRecognizer() {
        val old = recognizer
        recognizer = null
        runCatching { old?.destroy() }
    }

    override fun abort() {
        onMainThread { runCatching { recognizer?.cancel() } }
    }

    override fun destroy() {
        onMainThread { recreateRecognizer() }
    }

    /** [SpeechRecognizer] tolerates calls only from the main thread. */
    private fun onMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private companion object {
        const val TAG = "AndroidSpeechToText"
    }
}
