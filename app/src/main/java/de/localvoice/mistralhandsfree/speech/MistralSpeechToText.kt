package de.localvoice.mistralhandsfree.speech

import android.util.Log
import de.localvoice.mistralhandsfree.R
import de.localvoice.mistralhandsfree.mistral.MistralException
import de.localvoice.mistralhandsfree.mistral.userMessage
import de.localvoice.mistralhandsfree.session.TextSource
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Speech recognition with Mistral's Voxtral, and with silence detection that
 * belongs to this app rather than to the phone's recognizer.
 *
 * The microphone is read directly. An [Endpointer] watches the level: once
 * someone has spoken and then [pauseMs] of quiet have passed, the turn is over,
 * the recording is trimmed to the speech (plus a little lead-in) and uploaded
 * for transcription. Nothing has to be pressed, and - unlike with the system
 * recognizer - the pause really is the length that was set.
 *
 * Compared with the system recognizer this costs a little API usage and a
 * fraction of a second for the upload, and there is no live partial text. In
 * return there is no start-up beep between turns, no dependence on Google's
 * recognizer, and the language is detected automatically.
 *
 * @param transcribe sends a WAV file to Mistral and returns the text.
 */
class MistralSpeechToText(
    private val text: TextSource,
    private val mic: MicSourceFactory,
    private val transcribe: suspend (wav: ByteArray) -> String,
    private val pauseMs: Int,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SpeechToText {

    private val _partialText = MutableStateFlow("")
    override val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level.asStateFlow()

    private val _processing = MutableStateFlow(false)
    override val processing: StateFlow<Boolean> = _processing.asStateFlow()

    @Volatile
    private var aborted = false

    @Volatile
    private var source: MicSource? = null

    private sealed interface Capture {
        class Speech(val samples: ShortArray) : Capture
        data object NoSpeech : Capture
        data object Aborted : Capture
        class Error(val message: String, val recoverable: Boolean) : Capture
    }

    override suspend fun listenOnce(): SttResult = withContext(io) {
        aborted = false
        _partialText.value = ""
        _level.value = 0f
        _processing.value = false

        val captured = try {
            capture()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Recording failed", t)
            Capture.Error(text.get(R.string.stt_mic_failed), recoverable = true)
        }
        _level.value = 0f

        when (captured) {
            is Capture.Error -> SttResult.Failure(captured.message, captured.recoverable)
            Capture.Aborted, Capture.NoSpeech -> SttResult.Silence
            is Capture.Speech -> transcribe(captured.samples)
        }
    }

    private suspend fun transcribe(samples: ShortArray): SttResult {
        _processing.value = true
        try {
            val wav = Wav.fromPcm16Mono(samples, SAMPLE_RATE)
            val recognized = transcribe(wav).trim()
            // Very short or punctuation-only results are what a model makes of a click or breath.
            return if (recognized.count { it.isLetterOrDigit() } < 2) SttResult.Silence else SttResult.Text(recognized)
        } catch (e: MistralException) {
            Log.w(TAG, "Transcription failed: ${e.message}")
            val rejectedKey = e.kind == MistralException.Kind.UNAUTHORIZED
            return SttResult.Failure(
                e.userMessage(text),
                // A rejected key will not fix itself; everything else may be gone next round.
                recoverable = !rejectedKey,
                needsSignIn = rejectedKey,
            )
        } finally {
            _processing.value = false
        }
    }

    private suspend fun capture(): Capture {
        val endpointer = Endpointer(sampleRate = SAMPLE_RATE, pauseMs = pauseMs)
        val frameSamples = endpointer.frameSamples

        val microphone = when (val opened = mic.open(SAMPLE_RATE)) {
            is MicOpenResult.Ready -> opened.source
            MicOpenResult.Denied -> return Capture.Error(text.get(R.string.stt_no_permission), recoverable = false)
            MicOpenResult.Unsupported -> return Capture.Error(text.get(R.string.stt_mic_failed), recoverable = false)
        }

        source = microphone
        try {
            if (!microphone.start()) {
                // Another app holds the microphone exclusively.
                return Capture.Error(text.get(R.string.stt_mic_busy), recoverable = true)
            }

            var pcm = ShortArray(frameSamples * INITIAL_FRAMES)
            var length = 0
            val frame = ShortArray(frameSamples)

            while (currentCoroutineContext().isActive && !aborted) {
                val read = microphone.read(frame, frameSamples)
                if (read < 0) {
                    // abort() stops the recorder to release a read that is waiting for
                    // the next frame; that read then fails, which is not a fault.
                    if (aborted) return Capture.Aborted
                    return Capture.Error(text.get(R.string.stt_mic_failed), recoverable = true)
                }
                if (read == 0) continue

                if (length + read > pcm.size) pcm = pcm.copyOf(max(pcm.size * 2, length + read))
                System.arraycopy(frame, 0, pcm, length, read)
                length += read

                val verdict = endpointer.push(frame, read)
                _level.value = endpointer.level
                when (verdict) {
                    Endpointer.Verdict.UTTERANCE_COMPLETE,
                    Endpointer.Verdict.TOO_LONG,
                    -> return Capture.Speech(cut(pcm, length, endpointer, frameSamples))

                    Endpointer.Verdict.NO_SPEECH -> return Capture.NoSpeech
                    Endpointer.Verdict.SPEECH_STARTED,
                    Endpointer.Verdict.LISTENING,
                    -> Unit
                }
            }
            return Capture.Aborted
        } finally {
            source = null
            microphone.stop()
            microphone.release()
        }
    }

    /**
     * The speech with a little lead-in (the first consonant must not be clipped)
     * and a short tail - not the whole pause that ended the turn, which would
     * only make the upload larger and the transcription slower.
     */
    private fun cut(pcm: ShortArray, length: Int, endpointer: Endpointer, frameSamples: Int): ShortArray {
        val firstFrame = max(0, endpointer.speechStartFrame - PREROLL_FRAMES)
        val lastFrame = endpointer.lastVoicedFrame + TAIL_FRAMES
        val from = min(firstFrame * frameSamples, length)
        val to = min((lastFrame + 1) * frameSamples, length)
        return pcm.copyOfRange(from, max(from, to))
    }

    override fun abort() {
        aborted = true
        // Unblocks a read that is waiting for the next frame.
        source?.stop()
    }

    override fun destroy() {
        abort()
    }

    companion object {
        private const val TAG = "MistralSpeechToText"
        const val SAMPLE_RATE = 16_000

        /** Room for ten seconds up front; grows by doubling after that. */
        private const val INITIAL_FRAMES = 500

        /** 300 ms before the first speech frame. */
        const val PREROLL_FRAMES = 15

        /** 400 ms after the last speech frame. */
        const val TAIL_FRAMES = 20
    }
}
