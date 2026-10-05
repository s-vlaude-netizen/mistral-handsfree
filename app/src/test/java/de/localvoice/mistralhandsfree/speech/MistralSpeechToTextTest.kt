package de.localvoice.mistralhandsfree.speech

import de.localvoice.mistralhandsfree.R
import de.localvoice.mistralhandsfree.mistral.MistralException
import de.localvoice.mistralhandsfree.session.TextSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the Voxtral listening loop on scripted microphone audio: 20 ms frames of
 * silence and of a tone standing in for speech, whose level (dBFS) is known.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MistralSpeechToTextTest {

    private val quiet = -70f
    private val speech = -30f

    /** 20 ms of a 200 Hz tone (four full periods) at [db] dBFS RMS. */
    private fun frame(db: Float): ShortArray {
        val amplitude = 32768.0 * 10.0.pow(db / 20.0) * sqrt(2.0)
        return ShortArray(320) { i ->
            (amplitude * sin(2 * PI * 200.0 * i / 16_000.0)).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun audio(vararg parts: Pair<Float, Int>): List<ShortArray> =
        parts.flatMap { (db, count) -> List(count) { frame(db) } }

    // ------------------------------------------------------------------ fakes

    private class ScriptedMic(frames: List<ShortArray>, private val idle: ShortArray) : MicSource {
        private val iterator = frames.iterator()
        var startResult = true
        var started = false
        var stopped = false
        var released = false
        var reads = 0

        /** Called inside every read, with the 1-based number of that read. */
        var onRead: (Int) -> Unit = {}

        /** The read with this number fails. */
        var failAt = -1

        override fun start(): Boolean {
            started = true
            return startResult
        }

        override fun read(buffer: ShortArray, size: Int): Int {
            reads++
            onRead(reads)
            if (stopped) return -3 // what AudioRecord says to a read that outlives stop()
            if (reads == failAt) return -1
            val next = if (iterator.hasNext()) iterator.next() else idle
            System.arraycopy(next, 0, buffer, 0, size)
            return size
        }

        override fun stop() {
            stopped = true
        }

        override fun release() {
            released = true
        }
    }

    private inner class Rig(
        pauseMs: Int = 1200,
        opens: List<() -> MicOpenResult>,
        val onTranscribe: suspend (ByteArray) -> String = { "Hello world" },
        testScope: TestScope,
    ) {
        val uploads = mutableListOf<ByteArray>()
        private val queue = ArrayDeque(opens)

        val stt = MistralSpeechToText(
            text = TEXT,
            mic = MicSourceFactory { queue.removeFirst()() },
            transcribe = { wav ->
                uploads += wav
                onTranscribe(wav)
            },
            pauseMs = pauseMs,
            io = StandardTestDispatcher(testScope.testScheduler),
        )
    }

    private fun TestScope.rig(
        mic: ScriptedMic,
        pauseMs: Int = 1200,
        onTranscribe: suspend (ByteArray) -> String = { "Hello world" },
    ) = Rig(pauseMs, listOf({ MicOpenResult.Ready(mic) }), onTranscribe, this)

    private fun mic(vararg parts: Pair<Float, Int>) = ScriptedMic(audio(*parts), frame(quiet))

    private fun samplesOf(wav: ByteArray): ShortArray {
        val buffer = ByteBuffer.wrap(wav, 44, wav.size - 44).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray((wav.size - 44) / 2) { buffer.getShort(44 + it * 2) }
    }

    private fun frameLevels(samples: ShortArray): List<Float> =
        samples.toList().chunked(320).map { Endpointer.frameDb(it.toShortArray()) }

    // ------------------------------------------------------------- the happy path

    @Test
    fun `listens until the pause, then returns the transcript`() = runTest {
        val mic = mic(quiet to 30, speech to 40, quiet to 100)
        val rig = rig(mic)

        val result = rig.stt.listenOnce()

        assertEquals(SttResult.Text("Hello world"), result)
        assertEquals(1, rig.uploads.size)
        assertTrue(mic.started)
        // It stopped reading as soon as the turn was over, not at the end of the script.
        assertEquals("30 + 40 speech/quiet frames + the 60-frame pause", 30 + 40 + 60, mic.reads)
        assertTrue(mic.stopped && mic.released)
    }

    @Test
    fun `uploads the speech with a lead-in and a short tail, not the whole recording`() = runTest {
        val mic = mic(quiet to 30, speech to 40, quiet to 100)
        val rig = rig(mic)

        rig.stt.listenOnce()

        val samples = samplesOf(rig.uploads.single())
        val levels = frameLevels(samples)
        // 15 frames (300 ms) of lead-in, the 40 speech frames, 20 frames (400 ms) of tail.
        assertEquals(15 + 40 + 20, levels.size)
        assertTrue("lead-in is quiet", levels.subList(0, 15).all { it < -60f })
        assertTrue("then the speech, whole", levels.subList(15, 55).all { it > -33f && it < -27f })
        assertTrue("then a quiet tail", levels.subList(55, 75).all { it < -60f })
    }

    @Test
    fun `sends a valid WAV file`() = runTest {
        val mic = mic(speech to 40, quiet to 100)
        val rig = rig(mic)
        rig.stt.listenOnce()

        val wav = rig.uploads.single()
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals(16_000, ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getInt(24))
    }

    @Test
    fun `speech from the first frame keeps its beginning`() = runTest {
        val mic = mic(speech to 40, quiet to 100)
        val rig = rig(mic)
        rig.stt.listenOnce()

        val levels = frameLevels(samplesOf(rig.uploads.single()))
        // There is no room for a lead-in; the very first frame must still be speech.
        assertTrue(levels.first() > -33f)
        assertEquals(40 + 20, levels.size)
    }

    @Test
    fun `the pause that was set decides when to stop reading`() = runTest {
        val short = mic(speech to 40, quiet to 200)
        rig(short, pauseMs = 600).stt.listenOnce()
        assertEquals(40 + 30, short.reads)

        val long = mic(speech to 40, quiet to 200)
        rig(long, pauseMs = 3000).stt.listenOnce()
        assertEquals(40 + 150, long.reads)
    }

    @Test
    fun `shows the live level`() = runTest {
        val mic = mic(quiet to 10, speech to 40, quiet to 100)
        val rig = rig(mic)
        var peak = 0f
        mic.onRead = { peak = maxOf(peak, rig.stt.level.value) }

        rig.stt.listenOnce()

        assertTrue("peak $peak", peak > 0.6f)
        assertEquals("back to zero when done", 0f, rig.stt.level.value, 0f)
    }

    @Test
    fun `a second round works after the first`() = runTest {
        val first = mic(speech to 40, quiet to 100)
        val second = mic(speech to 40, quiet to 100)
        val rig = Rig(
            opens = listOf({ MicOpenResult.Ready(first) }, { MicOpenResult.Ready(second) }),
            testScope = this,
        )
        assertEquals(SttResult.Text("Hello world"), rig.stt.listenOnce())
        assertEquals(SttResult.Text("Hello world"), rig.stt.listenOnce())
        assertTrue(first.released && second.released)
        assertEquals(2, rig.uploads.size)
    }

    // ---------------------------------------------------------------- nothing said

    @Test
    fun `nobody speaking is silence, and nothing is uploaded`() = runTest {
        val mic = mic(quiet to 1000)
        val rig = rig(mic)

        assertEquals(SttResult.Silence, rig.stt.listenOnce())
        assertTrue(rig.uploads.isEmpty())
        assertEquals("gave up after the 8 s timeout", 400, mic.reads)
        assertTrue(mic.released)
    }

    @Test
    fun `a transcript that is only noise is treated as silence`() = runTest {
        for (garbage in listOf("", "   ", "...", "?", "a")) {
            val rig = rig(mic(speech to 40, quiet to 100), onTranscribe = { garbage })
            assertEquals("'$garbage'", SttResult.Silence, rig.stt.listenOnce())
        }
    }

    @Test
    fun `a short real answer is kept`() = runTest {
        val rig = rig(mic(speech to 10, quiet to 100), onTranscribe = { " No. " })
        assertEquals(SttResult.Text("No."), rig.stt.listenOnce())
    }

    // ------------------------------------------------------------- Mistral errors

    @Test
    fun `a rejected key asks the user to sign in again`() = runTest {
        val rig = rig(
            mic(speech to 40, quiet to 100),
            onTranscribe = { throw MistralException(MistralException.Kind.UNAUTHORIZED, "no", 401) },
        )
        val result = rig.stt.listenOnce() as SttResult.Failure
        assertTrue(result.needsSignIn)
        assertFalse(result.recoverable)
        assertEquals(TEXT.get(R.string.err_unauthorized), result.message)
    }

    @Test
    fun `any other Mistral error may pass on its own`() = runTest {
        val rig = rig(
            mic(speech to 40, quiet to 100),
            onTranscribe = { throw MistralException(MistralException.Kind.RATE_LIMITED, "slow", 429) },
        )
        val result = rig.stt.listenOnce() as SttResult.Failure
        assertFalse(result.needsSignIn)
        assertTrue(result.recoverable)
        assertEquals(TEXT.get(R.string.err_rate_limited), result.message)
    }

    @Test
    fun `shows that you were heard while the upload runs`() = runTest {
        val gate = CompletableDeferred<String>()
        val rig = rig(mic(speech to 40, quiet to 100), onTranscribe = { gate.await() })
        assertFalse(rig.stt.processing.value)

        var result: SttResult? = null
        val job = launch { result = rig.stt.listenOnce() }
        runCurrent()
        assertTrue("working on the text", rig.stt.processing.value)
        assertNull(result)

        gate.complete("Done talking")
        job.join()
        assertFalse(rig.stt.processing.value)
        assertEquals(SttResult.Text("Done talking"), result)
    }

    @Test
    fun `processing is switched off again after a failed upload`() = runTest {
        val rig = rig(
            mic(speech to 40, quiet to 100),
            onTranscribe = { throw MistralException(MistralException.Kind.NETWORK, "down") },
        )
        rig.stt.listenOnce()
        assertFalse(rig.stt.processing.value)
    }

    // ----------------------------------------------------------------- microphone

    @Test
    fun `without permission it fails for good`() = runTest {
        val rig = Rig(opens = listOf({ MicOpenResult.Denied }), testScope = this)
        val result = rig.stt.listenOnce() as SttResult.Failure
        assertFalse(result.recoverable)
        assertEquals(TEXT.get(R.string.stt_no_permission), result.message)
    }

    @Test
    fun `on a device that cannot record this way it fails for good`() = runTest {
        val rig = Rig(opens = listOf({ MicOpenResult.Unsupported }), testScope = this)
        val result = rig.stt.listenOnce() as SttResult.Failure
        assertFalse(result.recoverable)
        assertEquals(TEXT.get(R.string.stt_mic_failed), result.message)
    }

    @Test
    fun `when another app holds the microphone it can be retried`() = runTest {
        val mic = mic(speech to 10).also { it.startResult = false }
        val rig = rig(mic)
        val result = rig.stt.listenOnce() as SttResult.Failure
        assertTrue(result.recoverable)
        assertEquals(TEXT.get(R.string.stt_mic_busy), result.message)
        assertTrue("the microphone is let go", mic.released)
    }

    @Test
    fun `a read that fails is reported and the microphone is let go`() = runTest {
        val mic = mic(quiet to 100).also { it.failAt = 5 }
        val rig = rig(mic)
        val result = rig.stt.listenOnce() as SttResult.Failure
        assertTrue(result.recoverable)
        assertEquals(TEXT.get(R.string.stt_mic_failed), result.message)
        assertTrue(mic.released)
    }

    @Test
    fun `an exception while recording is a failure, not a crash`() = runTest {
        val mic = mic(quiet to 100).also { it.onRead = { n -> if (n == 3) error("driver exploded") } }
        val rig = rig(mic)
        val result = rig.stt.listenOnce() as SttResult.Failure
        assertTrue(result.recoverable)
        assertTrue(mic.released)
    }

    // ----------------------------------------------------------- abort and cancel

    @Test
    fun `abort ends the round quietly, without an upload`() = runTest {
        val mic = mic(quiet to 5, speech to 40, quiet to 100)
        val rig = rig(mic)
        // Abort arrives from another thread while the loop is mid-way through the speech.
        mic.onRead = { n -> if (n == 20) rig.stt.abort() }

        val result = rig.stt.listenOnce()

        assertEquals("an abort is not an error", SttResult.Silence, result)
        assertTrue(rig.uploads.isEmpty())
        assertTrue(mic.stopped && mic.released)
    }

    @Test
    fun `cancelling the coroutine lets go of the microphone`() = runTest {
        val mic = mic(quiet to 1000)
        val rig = rig(mic)
        val job = launch { rig.stt.listenOnce() }
        mic.onRead = { n -> if (n == 10) job.cancel() }

        job.join()

        assertTrue(job.isCancelled)
        assertTrue(mic.released)
        assertTrue("stopped soon after the cancel", mic.reads < 20)
    }

    @Test
    fun `an abort from an earlier round does not spoil the next one`() = runTest {
        val first = mic(quiet to 5, speech to 40, quiet to 100)
        val second = mic(speech to 40, quiet to 100)
        val rig = Rig(
            opens = listOf({ MicOpenResult.Ready(first) }, { MicOpenResult.Ready(second) }),
            testScope = this,
        )
        first.onRead = { n -> if (n == 10) rig.stt.abort() }
        assertEquals(SttResult.Silence, rig.stt.listenOnce())

        assertEquals(SttResult.Text("Hello world"), rig.stt.listenOnce())
    }

    private companion object {
        val TEXT = TextSource { id, args -> if (args.isEmpty()) "<$id>" else "<$id:${args.joinToString(",")}>" }
    }
}
