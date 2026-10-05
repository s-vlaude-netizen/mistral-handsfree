package de.localvoice.mistralhandsfree.speech

import de.localvoice.mistralhandsfree.speech.Endpointer.Verdict
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The endpointer is judged on synthetic audio: constant tones of a known level
 * (in dB relative to full scale) stand in for speech and for background noise.
 * A frame is 20 ms, so "60 frames" is 1.2 s.
 */
class EndpointerTest {

    /** 20 ms of a 200 Hz tone (exactly four periods) whose RMS is [db] dBFS. */
    private fun frame(db: Float): ShortArray {
        val amplitude = 32768.0 * 10.0.pow(db / 20.0) * sqrt(2.0)
        return ShortArray(320) { i ->
            (amplitude * sin(2 * PI * 200.0 * i / 16_000.0)).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    /**
     * Plays the part of the recording loop: feeds frames, and stops for good at the
     * first final verdict, exactly like the real caller does.
     */
    private class Run(val endpointer: Endpointer, private val frameOf: (Float) -> ShortArray) {
        val verdicts = mutableListOf<Verdict>()
        private var over = false

        fun feed(db: Float, frames: Int) {
            repeat(frames) {
                if (over) return
                val verdict = endpointer.push(frameOf(db))
                verdicts += verdict
                if (verdict == Verdict.UTTERANCE_COMPLETE || verdict == Verdict.NO_SPEECH || verdict == Verdict.TOO_LONG) {
                    over = true
                }
            }
        }

        fun indexOf(verdict: Verdict) = verdicts.indexOf(verdict)
        fun count(verdict: Verdict) = verdicts.count { it == verdict }
    }

    private fun run(endpointer: Endpointer = Endpointer()) = Run(endpointer, ::frame)

    private val quiet = -70f
    private val speech = -30f

    @Test
    fun `a final verdict stays final`() {
        val e = Endpointer()
        repeat(10) { e.push(frame(speech)) }
        var verdict = Verdict.LISTENING
        repeat(100) { verdict = e.push(frame(quiet)) }
        assertEquals(Verdict.UTTERANCE_COMPLETE, verdict)
        val lastVoiced = e.lastVoicedFrame

        // Loud frames after the end change nothing: not the verdict, not the cut points.
        repeat(20) { assertEquals(Verdict.UTTERANCE_COMPLETE, e.push(frame(speech))) }
        assertEquals(lastVoiced, e.lastVoicedFrame)
    }

    @Test
    fun `a measured frame has the level it was built with`() {
        assertEquals(-30f, Endpointer.frameDb(frame(-30f)), 0.3f)
        assertEquals(-60f, Endpointer.frameDb(frame(-60f)), 0.6f)
        assertEquals(Endpointer.MIN_DB, Endpointer.frameDb(ShortArray(320)), 0f)
        assertEquals(Endpointer.MIN_DB, Endpointer.frameDb(ShortArray(0)), 0f)
    }

    @Test
    fun `silence alone ends with a timeout`() {
        val r = run()
        r.feed(quiet, 500)
        assertEquals(399, r.verdicts.indexOfFirst { it != Verdict.LISTENING })
        assertEquals(Verdict.NO_SPEECH, r.verdicts[399])
        assertEquals(0, r.count(Verdict.SPEECH_STARTED))
    }

    @Test
    fun `speech followed by the pause completes the utterance`() {
        val r = run()
        r.feed(quiet, 10)
        r.feed(speech, 50) // one second of speech
        r.feed(quiet, 80)

        assertEquals(13, r.indexOf(Verdict.SPEECH_STARTED)) // the 4th speech frame
        assertEquals(1, r.count(Verdict.UTTERANCE_COMPLETE))
        // Last speech frame is 59; 60 quiet frames (1.2 s) later the turn is over.
        assertEquals(119, r.indexOf(Verdict.UTTERANCE_COMPLETE))
        assertEquals(10, r.endpointer.speechStartFrame)
        assertEquals(59, r.endpointer.lastVoicedFrame)
    }

    @Test
    fun `speech from the very first frame is found too`() {
        val r = run()
        r.feed(speech, 30)
        r.feed(quiet, 70)
        assertEquals(3, r.indexOf(Verdict.SPEECH_STARTED))
        assertEquals(0, r.endpointer.speechStartFrame)
        assertEquals(1, r.count(Verdict.UTTERANCE_COMPLETE))
    }

    @Test
    fun `a pause shorter than the limit stays inside the same turn`() {
        val r = run()
        r.feed(quiet, 10)
        r.feed(speech, 25)
        r.feed(quiet, 40) // 0.8 s: taking a breath
        r.feed(speech, 25)
        r.feed(quiet, 70)

        assertEquals(1, r.count(Verdict.UTTERANCE_COMPLETE))
        assertEquals(10 + 25 + 40 + 25 - 1, r.endpointer.lastVoicedFrame)
        assertEquals(r.endpointer.lastVoicedFrame + 60, r.indexOf(Verdict.UTTERANCE_COMPLETE))
        assertEquals(10, r.endpointer.speechStartFrame)
    }

    @Test
    fun `the pause that ends a turn is the one that was set`() {
        val short = run(Endpointer(pauseMs = 600))
        short.feed(speech, 30)
        short.feed(quiet, 100)
        assertEquals(29 + 30, short.indexOf(Verdict.UTTERANCE_COMPLETE))

        val long = run(Endpointer(pauseMs = 3000))
        long.feed(speech, 30)
        long.feed(quiet, 200)
        assertEquals(29 + 150, long.indexOf(Verdict.UTTERANCE_COMPLETE))
    }

    @Test
    fun `a click is not speech`() {
        val r = run()
        r.feed(quiet, 5)
        r.feed(-20f, 2) // 40 ms, far louder than any speech
        r.feed(quiet, 500)
        assertEquals(0, r.count(Verdict.SPEECH_STARTED))
        assertEquals(Verdict.NO_SPEECH, r.verdicts[399])
    }

    @Test
    fun `a cough that is too short to be a sentence is forgotten`() {
        val r = run()
        r.feed(quiet, 10)
        r.feed(-25f, 5) // 100 ms: starts, but never adds up to 120 ms of speech
        r.feed(quiet, 70)
        assertEquals(0, r.count(Verdict.UTTERANCE_COMPLETE))

        // Listening goes on, and real speech afterwards is handled normally.
        r.feed(speech, 30)
        r.feed(quiet, 70)
        assertEquals(1, r.count(Verdict.UTTERANCE_COMPLETE))
        assertEquals(10 + 5 + 70, r.endpointer.speechStartFrame)
    }

    @Test
    fun `a one-word answer still counts`() {
        val r = run()
        r.feed(quiet, 10)
        r.feed(speech, 8) // 160 ms - "No."
        r.feed(quiet, 70)
        assertEquals(1, r.count(Verdict.UTTERANCE_COMPLETE))
    }

    @Test
    fun `quiet speech above the noise floor is heard`() {
        val r = run()
        r.feed(quiet, 10)
        r.feed(-40f, 30) // soft voice, some distance from the phone
        r.feed(quiet, 70)
        assertEquals(1, r.count(Verdict.UTTERANCE_COMPLETE))
    }

    @Test
    fun `hum below the absolute floor never triggers`() {
        val r = run()
        r.feed(-55f, 500)
        assertEquals(0, r.count(Verdict.SPEECH_STARTED))
        assertEquals(Verdict.NO_SPEECH, r.verdicts[399])
    }

    @Test
    fun `background noise switched on during listening is adapted to`() {
        val r = run(Endpointer(noSpeechTimeoutMs = 60_000))
        r.feed(quiet, 25)
        r.feed(-30f, 400) // a TV comes on: 8 s of constant, loud "something"

        // It looked like speech for a while, but it never completes as an utterance...
        assertEquals(0, r.count(Verdict.UTTERANCE_COMPLETE))
        // ...and the background level has caught up with it.
        assertTrue("background ${r.endpointer.backgroundDb}", r.endpointer.backgroundDb > -40f)

        // A person talking over it is still recognized, and the turn ends at their pause.
        r.feed(-10f, 30)
        r.feed(-30f, 80)
        assertEquals(1, r.count(Verdict.UTTERANCE_COMPLETE))
    }

    @Test
    fun `an utterance that runs on forever is cut at the maximum`() {
        val r = run(Endpointer(maxUtteranceMs = 2_000))
        r.feed(quiet, 5)
        r.feed(speech, 150)
        // Started at frame 5; 100 frames (2 s) later it is cut.
        assertEquals(104, r.indexOf(Verdict.TOO_LONG))
    }

    @Test
    fun `the level meter follows the loudness`() {
        val e = Endpointer()
        e.push(frame(-70f))
        val low = e.level
        e.push(frame(-40f))
        val mid = e.level
        e.push(frame(-15f))
        val high = e.level
        assertTrue("$low < $mid < $high", low < mid && mid < high)
        assertEquals(0f, low, 0f)
        assertEquals(1f, high, 0f)
    }

    @Test
    fun `partial frames are judged by the samples they contain`() {
        val e = Endpointer()
        val loud = frame(-30f)
        // Only the first 100 samples are valid; the rest of the buffer is leftover garbage.
        val padded = ShortArray(320) { if (it < 100) loud[it] else 0 }
        e.push(padded, length = 100)
        assertEquals(-30f, e.levelDb, 1.5f)
    }
}
