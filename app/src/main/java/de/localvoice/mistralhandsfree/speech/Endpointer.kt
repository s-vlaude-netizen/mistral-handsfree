package de.localvoice.mistralhandsfree.speech

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Decides, frame by frame, when someone starts and stops talking - the silence
 * detection behind [MistralSpeechToText].
 *
 * It is a plain energy detector with an adaptive background level, not a neural
 * VAD. That is a deliberate trade: no model file, no native library, and its
 * behaviour can be reasoned about and tested. What keeps it usable:
 *
 *  * **Adaptive threshold.** Speech must be [ON_MARGIN_DB] above the background,
 *    and the background is learned from the frames that are not speech. So a
 *    humming fridge raises the bar, a quiet room lowers it.
 *  * **Hysteresis.** Once speech has started, a lower bar keeps it going, so
 *    the quiet tail of a word does not count as a pause.
 *  * **Pause, not silence.** An utterance ends only after [pauseMs] of
 *    consecutive non-speech. Shorter gaps - taking a breath, thinking - are
 *    part of the same turn.
 *  * **Blips are dropped.** A click or a cough that never adds up to
 *    [minSpeechMs] of speech is forgotten instead of being sent off.
 *  * **Stuck detection.** Several seconds of non-stop "speech" without one
 *    quiet frame is a TV or a fan that was switched on, not a person. The
 *    background level is then raised to match and listening goes on.
 *
 * The class only judges; it does not store audio. The caller keeps the samples
 * and cuts them with [speechStartFrame] and [lastVoicedFrame].
 *
 * All durations are in milliseconds and are converted to whole frames.
 */
class Endpointer(
    sampleRate: Int = 16_000,
    private val frameMs: Int = FRAME_MS,
    pauseMs: Int = 1_200,
    minSpeechMs: Int = 120,
    noSpeechTimeoutMs: Int = 8_000,
    maxUtteranceMs: Int = 60_000,
    startMs: Int = 80,
    stuckMs: Int = 6_000,
) {

    enum class Verdict {
        /** Nothing to report; keep feeding frames. */
        LISTENING,

        /** Speech has just begun. Reported once per utterance. */
        SPEECH_STARTED,

        /** The speaker has finished: a pause of [pauseMs] followed speech. */
        UTTERANCE_COMPLETE,

        /** Nobody spoke for [noSpeechTimeoutMs]. */
        NO_SPEECH,

        /** The utterance hit [maxUtteranceMs]; treat it as complete. */
        TOO_LONG,
    }

    private val pauseFrames = framesFor(pauseMs)
    private val minSpeechFrames = framesFor(minSpeechMs)
    private val noSpeechFrames = framesFor(noSpeechTimeoutMs)
    private val maxUtteranceFrames = framesFor(maxUtteranceMs)
    private val startFrames = framesFor(startMs)
    private val stuckFrames = framesFor(stuckMs)

    /** Samples per frame, for the caller to size its read buffer. */
    val frameSamples: Int = sampleRate * frameMs / 1000

    private var index = -1 // index of the frame being judged
    private var noiseDb = INITIAL_NOISE_DB
    private var speaking = false

    private var voicedRun = 0 // consecutive speech frames up to now
    private var silentRun = 0 // consecutive non-speech frames since the last speech frame
    private var voicedTotal = 0 // speech frames within the current utterance

    /** Index of the first frame of the current (or last finished) utterance, or -1. */
    var speechStartFrame: Int = -1
        private set

    /** Index of the last frame that counted as speech, or -1. */
    var lastVoicedFrame: Int = -1
        private set

    /** Level of the latest frame in dBFS (0 = full scale), for the meter. */
    var levelDb: Float = MIN_DB
        private set

    /** Current background estimate in dBFS - exposed for tests and diagnostics. */
    val backgroundDb: Float get() = noiseDb

    /** The latest frame's loudness mapped to 0..1 for the animation. */
    val level: Float get() = ((levelDb - LEVEL_FLOOR_DB) / (LEVEL_CEIL_DB - LEVEL_FLOOR_DB)).coerceIn(0f, 1f)

    /** Set once the outcome is known; from then on every frame gets the same answer. */
    private var finalVerdict: Verdict? = null

    /**
     * Judges the next frame.
     *
     * [Verdict.UTTERANCE_COMPLETE], [Verdict.NO_SPEECH] and [Verdict.TOO_LONG] are
     * final: the caller is expected to stop there, and if it feeds more frames
     * anyway they are ignored and the same verdict comes back.
     *
     * @param length number of valid samples in [frame]; normally all of them.
     */
    fun push(frame: ShortArray, length: Int = frame.size): Verdict {
        finalVerdict?.let { return it }
        val verdict = judge(frame, length)
        if (verdict == Verdict.UTTERANCE_COMPLETE || verdict == Verdict.NO_SPEECH || verdict == Verdict.TOO_LONG) {
            finalVerdict = verdict
        }
        return verdict
    }

    private fun judge(frame: ShortArray, length: Int): Verdict {
        index++
        val db = frameDb(frame, length)
        levelDb = db

        val onThreshold = max(noiseDb + ON_MARGIN_DB, MIN_ON_DB)
        val offThreshold = max(noiseDb + OFF_MARGIN_DB, MIN_OFF_DB)
        val voiced = db >= if (speaking) offThreshold else onThreshold

        if (voiced) {
            voicedRun++
            silentRun = 0
        } else {
            voicedRun = 0
            silentRun++
            learnBackground(db)
        }

        if (!speaking) {
            if (voicedRun >= startFrames) {
                speaking = true
                speechStartFrame = index - voicedRun + 1
                voicedTotal = voicedRun
                lastVoicedFrame = index
                return Verdict.SPEECH_STARTED
            }
            return if (index + 1 >= noSpeechFrames) Verdict.NO_SPEECH else Verdict.LISTENING
        }

        if (voiced) {
            voicedTotal++
            lastVoicedFrame = index
        }

        // Speech that never pauses is not speech. Make the background match it and carry on.
        if (voicedRun >= stuckFrames) {
            noiseDb = (db - 3f).coerceIn(MIN_DB, MAX_NOISE_DB)
            resetUtterance()
            return Verdict.LISTENING
        }

        if (index - speechStartFrame + 1 >= maxUtteranceFrames) return Verdict.TOO_LONG

        if (silentRun >= pauseFrames) {
            if (voicedTotal >= minSpeechFrames) return Verdict.UTTERANCE_COMPLETE
            resetUtterance() // a click or a cough: forget it
        }
        return Verdict.LISTENING
    }

    private fun resetUtterance() {
        speaking = false
        voicedRun = 0
        silentRun = 0
        voicedTotal = 0
        speechStartFrame = -1
        lastVoicedFrame = -1
    }

    /**
     * Follows the background level: quickly down (a noise has stopped), slowly up
     * (so that a pause between two words is not taken for a new, louder room).
     */
    private fun learnBackground(db: Float) {
        val rate = if (db < noiseDb) FALL_RATE else RISE_RATE
        noiseDb = (noiseDb + (db - noiseDb) * rate).coerceIn(MIN_DB, MAX_NOISE_DB)
    }

    private fun framesFor(ms: Int): Int = max(1, (ms + frameMs / 2) / frameMs)

    companion object {
        const val FRAME_MS = 20

        /** Below this a frame is digital silence as far as we are concerned. */
        const val MIN_DB = -90f

        /** What a quiet phone microphone idles at; the starting guess for the background. */
        private const val INITIAL_NOISE_DB = -60f

        /** Never assume the background is louder than this - speech has to stand out from something. */
        private const val MAX_NOISE_DB = -38f

        /** How far above the background a frame must be to start speech ... */
        private const val ON_MARGIN_DB = 12f

        /** ... and how far to keep it going. */
        private const val OFF_MARGIN_DB = 8f

        /** Absolute floors, so thermal noise in a dead-quiet room never counts as speech. */
        private const val MIN_ON_DB = -48f
        private const val MIN_OFF_DB = -52f

        private const val FALL_RATE = 0.2f
        private const val RISE_RATE = 0.02f

        private const val LEVEL_FLOOR_DB = -60f
        private const val LEVEL_CEIL_DB = -20f

        /** RMS of [length] samples of 16-bit audio, in dB relative to full scale. */
        fun frameDb(frame: ShortArray, length: Int = frame.size): Float {
            if (length <= 0) return MIN_DB
            var sum = 0.0
            for (i in 0 until length) {
                val s = frame[i].toDouble()
                sum += s * s
            }
            val rms = sqrt(sum / length) / 32768.0
            return if (rms <= 1e-5) MIN_DB else (20.0 * log10(rms)).toFloat().coerceAtLeast(MIN_DB)
        }
    }
}
