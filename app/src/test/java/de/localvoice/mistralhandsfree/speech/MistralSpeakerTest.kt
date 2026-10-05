package de.localvoice.mistralhandsfree.speech

import de.localvoice.mistralhandsfree.R
import de.localvoice.mistralhandsfree.mistral.MistralException
import de.localvoice.mistralhandsfree.session.TextSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Mistral voice is mostly queueing and timing, and none of it can be heard
 * from a unit test - so the loudspeaker is replaced by a recorder and the
 * network by scripted flows, and what comes out is read from the log.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MistralSpeakerTest {

    // ------------------------------------------------------------------ fakes

    private class FakeOutput(private val log: MutableList<String>) : PcmOutput {
        val written = mutableListOf<FloatArray>()
        var released = false

        /** Frames reported as played; -1 means "everything written has been played already". */
        var played = -1L
        var failOnWrite = false

        override fun write(samples: FloatArray) {
            if (failOnWrite) throw java.io.IOException("device lost")
            written += samples
            log += "play:" + samples.joinToString(",")
        }

        override val playedFrames: Long
            get() = if (played < 0) written.sumOf { it.size }.toLong() else played

        override fun release() {
            released = true
            log += "release"
        }
    }

    private class FakeFocus : FocusControl {
        var acquires = 0
        var releases = 0
        override fun acquire() {
            acquires++
        }

        override fun release() {
            releases++
        }
    }

    private class FakeFallback(private val log: MutableList<String>) : Speaker {
        val spoken = mutableListOf<String>()
        var stops = 0
        var prepared = false
        var shutDown = false
        override val busy = MutableStateFlow(false)
        override val warning = MutableStateFlow<String?>(null)
        override val diagnostics = MutableStateFlow("device voice")

        override suspend fun prepare(): Boolean {
            prepared = true
            return true
        }

        override fun enqueue(text: String) {
            spoken += text
            log += "fallback:$text"
        }

        override fun speakNow(text: String) = enqueue(text)
        override suspend fun awaitIdle() = Unit
        override fun stop() {
            stops++
        }

        override fun shutdown() {
            shutDown = true
        }
    }

    private class Rig(testScope: TestScope) {
        val dispatcher = StandardTestDispatcher(testScope.testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val log = mutableListOf<String>()
        val outputs = mutableListOf<FakeOutput>()
        val focus = FakeFocus()
        val fallback = FakeFallback(log)

        /** What each sentence "synthesizes" to; anything unlisted yields a single sample of 0.5. */
        val behaviour = mutableMapOf<String, () -> Flow<FloatArray>>()

        /** The sentences for which synthesis has begun, in order. */
        val started = mutableListOf<String>()
        var openFailure: Throwable? = null

        val speaker = MistralSpeaker(
            text = TEXT,
            synthesize = { sentence ->
                started += sentence
                (behaviour[sentence] ?: { flowOf(floatArrayOf(0.5f)) })()
            },
            describe = { "model · voice" },
            fallback = fallback,
            scope = scope,
            outputFactory = PcmOutputFactory {
                openFailure?.let { throw it }
                FakeOutput(log).also { outputs += it }
            },
            focus = focus,
            io = dispatcher,
        )

        val output: FakeOutput get() = outputs.last()
    }

    /*
     * A note on the clock: `advanceUntilIdle()` fast-forwards virtual time, which also
     * runs the 20-second synthesis timeout. Tests that leave a request hanging on
     * purpose therefore use `runCurrent()`, which settles only what is due right now.
     */
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun TestScope.rig(): Rig = Rig(this).also { scopes += it.scope }

    private fun chunk(vararg samples: Float) = floatArrayOf(*samples)

    // ----------------------------------------------------------------- playing

    @Test
    fun `plays the sentences in order into one continuous output`() = runTest {
        val rig = rig()
        rig.behaviour["one"] = { flowOf(chunk(1f, 2f), chunk(3f)) }
        rig.behaviour["two"] = { flowOf(chunk(4f)) }

        rig.speaker.enqueue("one")
        rig.speaker.enqueue("two")
        advanceUntilIdle()

        assertEquals(listOf("play:1.0,2.0", "play:3.0", "play:4.0"), rig.log)
        assertEquals("one output for both sentences", 1, rig.outputs.size)
        assertTrue(rig.speaker.busy.value)
    }

    @Test
    fun `synthesizes the next sentence while the first is still being fetched`() = runTest {
        val rig = rig()
        val firstDone = CompletableDeferred<Unit>()
        rig.behaviour["one"] = {
            flow {
                emit(chunk(1f))
                firstDone.await() // sentence one is still coming in...
                emit(chunk(2f))
            }
        }
        rig.behaviour["two"] = { flowOf(chunk(3f)) }

        rig.speaker.enqueue("one")
        rig.speaker.enqueue("two")
        runCurrent()

        // ...yet sentence two has been requested already, and its audio is waiting.
        assertEquals(listOf("one", "two"), rig.started)
        assertEquals(listOf("play:1.0"), rig.log)

        firstDone.complete(Unit)
        runCurrent()
        assertEquals(listOf("play:1.0", "play:2.0", "play:3.0"), rig.log)
    }

    @Test
    fun `never has more than two sentences in flight`() = runTest {
        val rig = rig()
        val gates = (1..4).associate { "s$it" to CompletableDeferred<Unit>() }
        gates.forEach { (sentence, gate) ->
            rig.behaviour[sentence] = { flow { gate.await(); emit(chunk(0.1f)) } }
        }

        gates.keys.forEach { rig.speaker.enqueue(it) }
        runCurrent()
        assertEquals(listOf("s1", "s2"), rig.started)

        gates.getValue("s1").complete(Unit)
        runCurrent()
        assertEquals(listOf("s1", "s2", "s3"), rig.started)
    }

    @Test
    fun `ignores blank text`() = runTest {
        val rig = rig()
        rig.speaker.enqueue("   ")
        advanceUntilIdle()
        assertTrue(rig.started.isEmpty())
        assertFalse(rig.speaker.busy.value)
        assertEquals(0, rig.focus.acquires)
    }

    // --------------------------------------------------------------- fallback

    @Test
    fun `a sentence that fails is read by the device voice, in its place`() = runTest {
        val rig = rig()
        rig.behaviour["one"] = { flowOf(chunk(1f)) }
        rig.behaviour["bad"] = { flow { throw MistralException(MistralException.Kind.RATE_LIMITED, "slow") } }
        rig.behaviour["three"] = { flowOf(chunk(3f)) }

        listOf("one", "bad", "three").forEach(rig.speaker::enqueue)
        advanceUntilIdle()

        assertEquals(listOf("play:1.0", "fallback:bad", "play:3.0"), rig.log)
        assertNotNull(rig.speaker.warning.value)
    }

    @Test
    fun `a sentence cut off after it began is not started over in another voice`() = runTest {
        val rig = rig()
        rig.behaviour["half"] = {
            flow {
                emit(chunk(1f))
                throw MistralException(MistralException.Kind.SERVER, "boom")
            }
        }

        rig.speaker.enqueue("half")
        advanceUntilIdle()

        assertEquals(listOf("play:1.0"), rig.log)
        assertTrue(rig.fallback.spoken.isEmpty())
        assertNotNull(rig.speaker.warning.value)
    }

    @Test
    fun `an answer without any audio counts as a failure`() = runTest {
        val rig = rig()
        rig.behaviour["quiet"] = { flowOf() }

        rig.speaker.enqueue("quiet")
        advanceUntilIdle()

        assertEquals(listOf("quiet"), rig.fallback.spoken)
    }

    @Test
    fun `a request that never answers is given up on`() = runTest {
        val rig = rig()
        rig.behaviour["hang"] = { flow { awaitCancellation() } }

        rig.speaker.enqueue("hang")
        advanceTimeBy(MistralSpeaker.SYNTHESIS_TIMEOUT_MS - 1)
        runCurrent()
        assertTrue("still waiting just before the deadline", rig.fallback.spoken.isEmpty())

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("hang"), rig.fallback.spoken)
    }

    @Test
    fun `an output that cannot be opened falls back to the device voice`() = runTest {
        val rig = rig()
        rig.openFailure = UnsupportedOperationException("no float output")

        rig.speaker.enqueue("hello")
        advanceUntilIdle()

        assertEquals(listOf("hello"), rig.fallback.spoken)
        assertNotNull(rig.speaker.warning.value)
    }

    @Test
    fun `an output that dies while playing falls back for that sentence`() = runTest {
        val rig = rig()
        rig.speaker.enqueue("first")
        advanceUntilIdle()
        rig.output.failOnWrite = true

        rig.speaker.enqueue("second")
        advanceUntilIdle()

        assertEquals(listOf("second"), rig.fallback.spoken)
    }

    @Test
    fun `warns about the fallback only once`() = runTest {
        val rig = rig()
        rig.behaviour["a"] = { flow { throw MistralException(MistralException.Kind.RATE_LIMITED, "x") } }
        rig.behaviour["b"] = { flow { throw MistralException(MistralException.Kind.FORBIDDEN, "y") } }

        rig.speaker.enqueue("a")
        advanceUntilIdle()
        val first = rig.speaker.warning.value
        assertNotNull(first)

        rig.speaker.enqueue("b")
        advanceUntilIdle()

        assertEquals("the second failure does not replace the message", first, rig.speaker.warning.value)
        assertEquals(listOf("a", "b"), rig.fallback.spoken)
    }

    @Test
    fun `passes on a warning of the device voice`() = runTest {
        val rig = rig()
        rig.speaker.prepare()
        runCurrent()
        rig.fallback.warning.value = "no device voice installed"
        runCurrent()
        assertEquals("no device voice installed", rig.speaker.warning.value)
    }

    // ---------------------------------------------------------- idle and stop

    @Test
    fun `is idle once everything has come out of the speaker`() = runTest {
        val rig = rig()
        rig.behaviour["one"] = { flowOf(chunk(1f, 1f, 1f)) }
        rig.speaker.enqueue("one")
        advanceUntilIdle()
        rig.output.played = 0 // written, but not yet played

        val idle = async { rig.speaker.awaitIdle() }
        advanceTimeBy(200)
        assertFalse("audio is still playing", idle.isCompleted)

        rig.output.played = 3
        advanceTimeBy(100)
        assertTrue(idle.isCompleted)
        // Resources are given back between turns.
        assertTrue(rig.output.released)
        assertFalse(rig.speaker.busy.value)
        assertEquals(1, rig.focus.releases)
    }

    @Test
    fun `a stalled audio driver cannot hold the conversation up for ever`() = runTest {
        val rig = rig()
        rig.speaker.enqueue("one")
        advanceUntilIdle()
        rig.output.played = 0 // never advances

        val idle = async { rig.speaker.awaitIdle() }
        advanceTimeBy(5_000)
        runCurrent()

        assertTrue(idle.isCompleted)
    }

    @Test
    fun `the next turn gets a fresh output`() = runTest {
        val rig = rig()
        rig.speaker.enqueue("one")
        advanceUntilIdle()
        rig.speaker.awaitIdle()
        assertTrue(rig.output.released)

        rig.speaker.enqueue("two")
        advanceUntilIdle()
        assertEquals(2, rig.outputs.size)
        assertFalse(rig.output.released)
    }

    @Test
    fun `stop cancels requests in flight and silences the output at once`() = runTest {
        val rig = rig()
        var cancelled = false
        rig.behaviour["slow"] = {
            flow {
                try {
                    emit(chunk(1f))
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        }
        rig.speaker.enqueue("slow")
        rig.speaker.enqueue("waiting")
        runCurrent()
        assertEquals(listOf("play:1.0"), rig.log)

        rig.speaker.stop()
        runCurrent()

        assertTrue("the HTTP request is cancelled, not left running", cancelled)
        assertTrue(rig.output.released)
        assertEquals(1, rig.fallback.stops)
        assertFalse(rig.speaker.busy.value)
        assertTrue(rig.focus.releases >= 1)
        // Nothing of the second sentence is played afterwards.
        assertEquals(listOf("play:1.0", "release"), rig.log)
    }

    @Test
    fun `can speak again after a stop`() = runTest {
        val rig = rig()
        rig.behaviour["slow"] = { flow { awaitCancellation() } }
        rig.speaker.enqueue("slow")
        runCurrent()
        rig.speaker.stop()
        runCurrent()

        rig.speaker.enqueue("next")
        runCurrent()

        assertEquals(listOf("play:0.5"), rig.log)
        rig.speaker.awaitIdle()
        assertFalse(rig.speaker.busy.value)
    }

    @Test
    fun `speaking something right now drops what was queued`() = runTest {
        val rig = rig()
        rig.behaviour["old"] = { flow { awaitCancellation() } }
        rig.speaker.enqueue("old")
        runCurrent()

        rig.speaker.speakNow("new")
        runCurrent()

        assertEquals(listOf("play:0.5"), rig.log)
    }

    @Test
    fun `nothing is spoken after shutdown`() = runTest {
        val rig = rig()
        rig.speaker.shutdown()
        rig.speaker.enqueue("too late")
        advanceUntilIdle()

        assertTrue(rig.started.isEmpty())
        assertTrue(rig.fallback.shutDown)
    }

    // -------------------------------------------------------------- start-up

    @Test
    fun `prepares the device voice too and describes itself`() = runTest {
        val rig = rig()
        assertTrue(rig.speaker.prepare())
        assertTrue(rig.fallback.prepared)
        assertEquals(TEXT.get(R.string.tts_diagnostics_mistral, "model · voice"), rig.speaker.diagnostics.value)
        assertNull(rig.speaker.warning.value)
    }

    private companion object {
        val TEXT = TextSource { id, args -> if (args.isEmpty()) "<$id>" else "<$id:${args.joinToString(",")}>" }
    }
}
