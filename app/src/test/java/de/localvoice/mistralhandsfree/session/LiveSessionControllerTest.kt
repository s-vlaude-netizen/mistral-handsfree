package de.localvoice.mistralhandsfree.session

import de.localvoice.mistralhandsfree.R
import de.localvoice.mistralhandsfree.auth.ApiKeyStore
import de.localvoice.mistralhandsfree.data.AppSettings
import de.localvoice.mistralhandsfree.data.SettingsSource
import de.localvoice.mistralhandsfree.domain.ChatMessage
import de.localvoice.mistralhandsfree.domain.Role
import de.localvoice.mistralhandsfree.llm.LlmEngine
import de.localvoice.mistralhandsfree.mistral.MistralException
import de.localvoice.mistralhandsfree.speech.Speaker
import de.localvoice.mistralhandsfree.speech.SpeakerSetup
import de.localvoice.mistralhandsfree.speech.SpeechEngines
import de.localvoice.mistralhandsfree.speech.SpeechToText
import de.localvoice.mistralhandsfree.speech.SttResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the whole hands-free loop against scripted engines: what the recognizer
 * "hears", what the model "answers" and where things go wrong are all decided by
 * the test, and time is virtual.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveSessionControllerTest {

    // ------------------------------------------------------------------ fakes

    /** A recognizer that returns scripted results, then waits like a microphone nobody speaks into. */
    private class FakeStt : SpeechToText {
        val script = ArrayDeque<SttResult>()
        override val partialText = MutableStateFlow("")
        override val level = MutableStateFlow(0f)
        override val processing = MutableStateFlow(false)
        var listens = 0
        var aborts = 0
        var destroyed = false
        private var waiting: CompletableDeferred<SttResult>? = null

        override suspend fun listenOnce(): SttResult {
            listens++
            script.removeFirstOrNull()?.let { return it }
            val gate = CompletableDeferred<SttResult>()
            waiting = gate
            try {
                return gate.await()
            } finally {
                waiting = null
            }
        }

        /** Like the real thing: aborting ends the round in progress, without a result. */
        override fun abort() {
            aborts++
            waiting?.complete(SttResult.Silence)
        }

        override fun destroy() {
            destroyed = true
        }
    }

    private class FakeSpeaker : Speaker {
        val spoken = mutableListOf<String>()
        var stops = 0
        var prepared = false
        var prepareResult = true
        override val busy = MutableStateFlow(false)
        override val warning = MutableStateFlow<String?>(null)
        override val diagnostics = MutableStateFlow("fake voice")

        override suspend fun prepare(): Boolean {
            prepared = true
            return prepareResult
        }

        override fun enqueue(text: String) {
            spoken += text
        }

        override fun speakNow(text: String) {
            stops++
            spoken += text
        }

        override suspend fun awaitIdle() = Unit

        override fun stop() {
            stops++
        }

        override fun shutdown() = Unit
    }

    private class FakeLlm : LlmEngine {
        val histories = mutableListOf<List<ChatMessage>>()
        private val replies = ArrayDeque<() -> Flow<String>>()
        override val displayName = "fake"

        fun reply(block: () -> Flow<String>) {
            replies += block
        }

        override fun generate(history: List<ChatMessage>): Flow<String> {
            histories += history
            return (replies.removeFirstOrNull() ?: { flowOf("Okay.") })()
        }
    }

    private class FakeSettings(initial: AppSettings = AppSettings()) : SettingsSource {
        private val flow = MutableStateFlow(initial)
        override val settings: StateFlow<AppSettings> = flow.asStateFlow()
        override val current: AppSettings get() = flow.value
        override fun update(transform: (AppSettings) -> AppSettings) {
            flow.value = transform(flow.value)
        }
    }

    private class FakeKeyStore(var key: String?) : ApiKeyStore {
        private val flow = MutableStateFlow(key != null)
        override val signedIn: StateFlow<Boolean> = flow.asStateFlow()
        override fun load() = key
        override fun save(key: String) {
            this.key = key
            flow.value = true
        }

        override fun clear() {
            key = null
            flow.value = false
        }
    }

    /** Everything wired up, with the fakes at hand. */
    private class Rig(scope: CoroutineScope, microphone: Boolean = true, key: String? = "key") {
        val speaker = FakeSpeaker()
        val llm = FakeLlm()
        val settings = FakeSettings()
        val keys = FakeKeyStore(key)

        /** Every recognizer the controller asked for; the first one is the usual one. */
        val recognizers = mutableListOf<FakeStt>()
        var recognizerSetup: (FakeStt) -> Unit = {}
        var speakerNotice: String? = null

        val stt: FakeStt get() = recognizers.first()

        private val engines = object : SpeechEngines {
            override fun recognizer(settings: AppSettings): SpeechToText =
                FakeStt().also { recognizerSetup(it); recognizers += it }

            override suspend fun speaker(settings: AppSettings) = SpeakerSetup(speaker, speakerNotice)
        }

        val controller = LiveSessionController(
            text = TEXT,
            settingsStore = settings,
            keyStore = keys,
            llm = llm,
            engines = engines,
            hasMicrophonePermission = { microphone },
            scope = scope,
        )
    }

    // ---------------------------------------------------------------- helpers

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        // The loop and its helpers never finish by themselves - they wait for the next utterance.
        scopes.forEach { it.cancel() }
    }

    /**
     * The controller gets a scope of its own on the test's virtual clock. It must not be
     * `backgroundScope`: `advanceUntilIdle()` deliberately leaves background work alone.
     */
    private fun TestScope.rig(microphone: Boolean = true, key: String? = "key"): Rig {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        scopes += scope
        return Rig(scope, microphone, key)
    }

    /** Starts live mode with this recognizer script and lets everything settle. */
    private fun TestScope.startLive(rig: Rig, vararg heard: SttResult) {
        rig.recognizerSetup = { stt -> stt.script.addAll(heard) }
        rig.controller.start()
        advanceUntilIdle()
    }

    private fun Rig.texts(): List<String> = controller.messages.value.map { it.text }
    private fun Rig.roles(): List<Role> = controller.messages.value.map { it.role }

    private val longSentence = "This is a rather long first sentence of the answer. "

    // ------------------------------------------------------------- the basics

    @Test
    fun `listens, answers, speaks, and listens again by itself`() = runTest {
        val rig = rig()
        rig.llm.reply { flowOf("Hi, ", "nice to meet you. ", "How can I help today?") }

        startLive(rig, SttResult.Text("Hello there"))

        assertEquals(listOf(Role.USER, Role.ASSISTANT), rig.roles())
        assertEquals(listOf("Hello there", "Hi, nice to meet you. How can I help today?"), rig.texts())
        assertFalse(rig.controller.messages.value[1].streaming)
        assertEquals("Hi, nice to meet you. How can I help today?", rig.speaker.spoken.joinToString(" "))
        // The microphone is open again, nobody touched anything.
        assertEquals(2, rig.stt.listens)
        assertEquals(LiveState.LISTENING, rig.controller.state.value)
        assertTrue(rig.speaker.prepared)
    }

    @Test
    fun `speaks sentence by sentence while the answer is still arriving`() = runTest {
        val rig = rig()
        val gate = CompletableDeferred<Unit>()
        rig.llm.reply {
            flow {
                emit(longSentence)
                emit("And a second one that has just started")
                gate.await() // the model is still writing
                emit(".")
            }
        }

        startLive(rig, SttResult.Text("Tell me something"))

        // The first sentence was spoken although the answer is not finished.
        assertEquals(listOf(longSentence.trim()), rig.speaker.spoken)
        assertEquals(LiveState.SPEAKING, rig.controller.state.value)
        assertTrue(rig.controller.messages.value[1].streaming)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, rig.speaker.spoken.size)
        assertFalse(rig.controller.messages.value[1].streaming)
    }

    @Test
    fun `sends the earlier turns to the model, but not the empty placeholder`() = runTest {
        val rig = rig()
        rig.llm.reply { flowOf("First answer.") }
        rig.llm.reply { flowOf("Second answer.") }

        startLive(rig, SttResult.Text("First question"), SttResult.Text("Second question"))

        assertEquals(2, rig.llm.histories.size)
        assertEquals(listOf("First question"), rig.llm.histories[0].map { it.text })
        assertEquals(
            listOf("First question", "First answer.", "Second question"),
            rig.llm.histories[1].map { it.text },
        )
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.USER), rig.llm.histories[1].map { it.role })
    }

    @Test
    fun `the foreground service sees the loop as busy from the very first moment`() = runTest {
        val rig = rig()
        rig.controller.start()
        // Not yet run: the service would stop itself if this still read IDLE.
        assertEquals(LiveState.PREPARING, rig.controller.state.value)
        assertTrue(rig.controller.isRunning)
    }

    // ---------------------------------------------------------- ending the loop

    @Test
    fun `saying stop ends the conversation`() = runTest {
        val rig = rig()
        startLive(rig, SttResult.Text("Stop."))

        assertEquals(LiveState.IDLE, rig.controller.state.value)
        assertEquals(TEXT.get(R.string.status_stopped_by_voice), rig.controller.statusDetail.value)
        assertTrue(rig.llm.histories.isEmpty())
        assertTrue(rig.controller.messages.value.isEmpty())
        assertFalse(rig.controller.isRunning)
    }

    @Test
    fun `pauses itself after a long stretch of silence`() = runTest {
        val rig = rig()
        startLive(rig, *Array(LiveSessionController.MAX_SILENT_ROUNDS) { SttResult.Silence })

        assertEquals(LiveSessionController.MAX_SILENT_ROUNDS, rig.stt.listens)
        assertEquals(LiveState.IDLE, rig.controller.state.value)
        assertEquals(TEXT.get(R.string.status_paused_after_silence), rig.controller.statusDetail.value)
    }

    @Test
    fun `speaking in between starts the silence count over`() = runTest {
        val rig = rig()
        val almost = LiveSessionController.MAX_SILENT_ROUNDS - 1
        startLive(
            rig,
            *Array(almost) { SttResult.Silence },
            SttResult.Text("still here"),
            *Array(almost) { SttResult.Silence },
        )
        // Still listening: neither stretch of silence was long enough on its own.
        assertEquals(LiveState.LISTENING, rig.controller.state.value)
        assertTrue(rig.controller.isRunning)
    }

    @Test
    fun `with hands-free off it answers once and stops`() = runTest {
        val rig = rig()
        rig.settings.update { it.copy(handsFree = false) }

        startLive(rig, SttResult.Text("first"), SttResult.Text("second"))

        assertEquals(listOf("first", "Okay."), rig.texts())
        assertEquals(1, rig.stt.listens)
        assertEquals(LiveState.IDLE, rig.controller.state.value)
    }

    @Test
    fun `stop halts listening and speaking right away`() = runTest {
        val rig = rig()
        startLive(rig)
        assertEquals(LiveState.LISTENING, rig.controller.state.value)

        rig.controller.stop()
        advanceUntilIdle()

        assertEquals(LiveState.IDLE, rig.controller.state.value)
        assertFalse(rig.controller.isRunning)
        assertTrue(rig.stt.aborts >= 1)
        assertTrue(rig.speaker.stops >= 1)
    }

    // -------------------------------------------------------- recognizer failures

    @Test
    fun `a failure that can be retried is retried`() = runTest {
        val rig = rig()
        startLive(rig, SttResult.Failure("busy", recoverable = true), SttResult.Text("hello"))

        assertEquals(listOf("hello", "Okay."), rig.texts())
        assertEquals("busy", rig.controller.error.value)
        assertEquals(LiveState.LISTENING, rig.controller.state.value)
    }

    @Test
    fun `a failure that cannot be retried ends live mode with the reason on screen`() = runTest {
        val rig = rig()
        startLive(rig, SttResult.Failure("no microphone", recoverable = false))

        assertEquals(LiveState.IDLE, rig.controller.state.value)
        assertEquals("no microphone", rig.controller.error.value)
        assertEquals(1, rig.stt.listens)
    }

    @Test
    fun `a recognizer that keeps failing is given up on`() = runTest {
        val rig = rig()
        startLive(rig, *Array(10) { SttResult.Failure("busy", recoverable = true) })

        assertEquals(LiveSessionController.MAX_FAILED_ROUNDS, rig.stt.listens)
        assertEquals(LiveState.IDLE, rig.controller.state.value)
    }

    @Test
    fun `a rejected key reported by the recognizer asks for a new one`() = runTest {
        val rig = rig()
        startLive(rig, SttResult.Failure("bad key", recoverable = false, needsSignIn = true))

        assertTrue(rig.controller.needsSignIn.value)
        assertEquals("bad key", rig.controller.error.value)
        assertEquals(LiveState.IDLE, rig.controller.state.value)
    }

    // ---------------------------------------------------------- model failures

    @Test
    fun `a rejected key during an answer asks for a new one and ends live mode`() = runTest {
        val rig = rig()
        rig.llm.reply { flow { throw MistralException(MistralException.Kind.UNAUTHORIZED, "no", 401) } }

        startLive(rig, SttResult.Text("hello"))

        assertTrue(rig.controller.needsSignIn.value)
        assertEquals(TEXT.get(R.string.err_unauthorized), rig.controller.error.value)
        assertEquals(LiveState.IDLE, rig.controller.state.value)
        // The question stays, an empty answer bubble does not.
        assertEquals(listOf("hello"), rig.texts())
        assertEquals(1, rig.stt.listens)
    }

    @Test
    fun `a dropped connection is reported and the conversation carries on`() = runTest {
        val rig = rig()
        rig.llm.reply { flow { throw MistralException(MistralException.Kind.NETWORK, "down") } }

        startLive(rig, SttResult.Text("hello"))

        assertEquals(TEXT.get(R.string.err_network), rig.controller.error.value)
        assertFalse(rig.controller.needsSignIn.value)
        assertEquals(listOf("hello"), rig.texts())
        // Still listening: the next question may well work.
        assertEquals(LiveState.LISTENING, rig.controller.state.value)
        assertEquals(2, rig.stt.listens)
    }

    @Test
    fun `what arrived before a failure stays on screen and is spoken`() = runTest {
        val rig = rig()
        rig.llm.reply {
            flow {
                emit(longSentence)
                emit("And a dangling")
                throw MistralException(MistralException.Kind.SERVER, "boom")
            }
        }

        startLive(rig, SttResult.Text("hello"))

        assertEquals(TEXT.get(R.string.err_server), rig.controller.error.value)
        assertEquals("${longSentence}And a dangling".trim(), rig.texts()[1])
        assertEquals(listOf(longSentence.trim(), "And a dangling"), rig.speaker.spoken)
        assertFalse(rig.controller.messages.value[1].streaming)
    }

    @Test
    fun `an empty answer is marked as such`() = runTest {
        val rig = rig()
        rig.llm.reply { flowOf() }

        startLive(rig, SttResult.Text("hello"))

        assertEquals(TEXT.get(R.string.no_answer), rig.texts()[1])
        assertNull(rig.controller.error.value)
    }

    // ------------------------------------------------------------- interrupting

    @Test
    fun `interrupting keeps what was said and goes back to listening`() = runTest {
        val rig = rig()
        rig.llm.reply {
            flow {
                emit(longSentence)
                emit("and then a half sentence")
                awaitCancellation()
            }
        }
        startLive(rig, SttResult.Text("talk a lot"))
        assertEquals(LiveState.SPEAKING, rig.controller.state.value)
        val stopsBefore = rig.speaker.stops

        rig.controller.interruptCurrentTurn()
        advanceUntilIdle()

        assertTrue(rig.speaker.stops > stopsBefore)
        assertEquals("${longSentence}and then a half sentence".trim(), rig.texts()[1])
        assertFalse(rig.controller.messages.value[1].streaming)
        assertEquals(LiveState.LISTENING, rig.controller.state.value)
        assertEquals(2, rig.stt.listens)
        assertTrue(rig.controller.isRunning)
    }

    @Test
    fun `interrupting before the first word leaves no empty bubble`() = runTest {
        val rig = rig()
        rig.llm.reply { flow { awaitCancellation() } }
        startLive(rig, SttResult.Text("hello"))
        assertEquals(LiveState.THINKING, rig.controller.state.value)

        rig.controller.interruptCurrentTurn()
        advanceUntilIdle()

        assertEquals(listOf("hello"), rig.texts())
        assertEquals(LiveState.LISTENING, rig.controller.state.value)
    }

    // -------------------------------------------------------------- typed input

    @Test
    fun `typing while live mode listens interrupts the listening and answers`() = runTest {
        val rig = rig()
        startLive(rig)
        assertEquals(LiveState.LISTENING, rig.controller.state.value)

        rig.controller.sendTypedMessage("typed question")
        advanceUntilIdle()

        assertEquals(listOf("typed question", "Okay."), rig.texts())
        assertTrue(rig.stt.aborts >= 1)
        assertEquals(LiveState.LISTENING, rig.controller.state.value)
    }

    @Test
    fun `typing with live mode off answers once`() = runTest {
        val rig = rig()
        rig.controller.sendTypedMessage("typed question")
        advanceUntilIdle()

        assertEquals(listOf("typed question", "Okay."), rig.texts())
        assertEquals(listOf("Okay."), rig.speaker.spoken)
        assertEquals(LiveState.IDLE, rig.controller.state.value)
        assertFalse(rig.controller.isRunning)
    }

    @Test
    fun `a blank typed message is ignored`() = runTest {
        val rig = rig()
        rig.controller.sendTypedMessage("   ")
        advanceUntilIdle()
        assertTrue(rig.controller.messages.value.isEmpty())
        assertTrue(rig.llm.histories.isEmpty())
    }

    // ---------------------------------------------------------------- start-up

    @Test
    fun `will not start without microphone permission`() = runTest {
        val rig = rig(microphone = false)
        rig.controller.start()
        advanceUntilIdle()

        assertEquals(TEXT.get(R.string.error_no_mic_permission), rig.controller.error.value)
        assertEquals(LiveState.IDLE, rig.controller.state.value)
        assertFalse(rig.controller.isRunning)
    }

    @Test
    fun `will not start without a key`() = runTest {
        val rig = rig(key = null)
        rig.controller.start()
        advanceUntilIdle()

        assertTrue(rig.controller.needsSignIn.value)
        assertEquals(TEXT.get(R.string.err_no_key), rig.controller.error.value)
        assertFalse(rig.controller.isRunning)
        assertEquals(LiveState.IDLE, rig.controller.state.value)
    }

    @Test
    fun `typing without a key asks to sign in instead of failing`() = runTest {
        val rig = rig(key = null)
        rig.controller.sendTypedMessage("hello")
        advanceUntilIdle()

        assertTrue(rig.controller.needsSignIn.value)
        assertTrue(rig.controller.messages.value.isEmpty())
    }

    @Test
    fun `signing in clears the request to sign in`() = runTest {
        val rig = rig(key = null)
        rig.controller.start()
        assertTrue(rig.controller.needsSignIn.value)

        rig.controller.onSignedIn()
        assertFalse(rig.controller.needsSignIn.value)
        assertNull(rig.controller.error.value)
    }

    @Test
    fun `fresh start clears the earlier conversation`() = runTest {
        val rig = rig()
        startLive(rig, SttResult.Text("old talk"))
        rig.controller.stop()
        advanceUntilIdle()
        assertEquals(2, rig.controller.messages.value.size)

        rig.settings.update { it.copy(freshStart = true) }
        rig.controller.start()

        assertTrue(rig.controller.messages.value.isEmpty())
    }

    @Test
    fun `without fresh start the conversation is kept`() = runTest {
        val rig = rig()
        startLive(rig, SttResult.Text("old talk"))
        rig.controller.stop()
        advanceUntilIdle()

        rig.controller.start()
        assertEquals(2, rig.controller.messages.value.size)
    }

    // -------------------------------------------------------- engines and status

    @Test
    fun `a changed setting gets a new recognizer at the next round`() = runTest {
        val rig = rig()
        startLive(rig, SttResult.Silence)
        assertEquals(1, rig.recognizers.size)

        rig.recognizerSetup = {} // the replacement starts with nothing scripted
        rig.settings.update { it.copy(pauseMs = 2400) }
        rig.stt.abort() // ends the round in progress, as the end of a turn would
        advanceUntilIdle()

        assertEquals(2, rig.recognizers.size)
        assertTrue(rig.recognizers[0].destroyed)
        assertEquals(1, rig.recognizers[1].listens)
        assertEquals(LiveState.LISTENING, rig.controller.state.value)
    }

    @Test
    fun `an unchanged setting keeps the recognizer`() = runTest {
        val rig = rig()
        startLive(rig, SttResult.Silence, SttResult.Silence, SttResult.Silence)
        assertEquals(1, rig.recognizers.size)
        assertEquals(4, rig.stt.listens)
    }

    @Test
    fun `shows that you were heard while the text is being worked out`() = runTest {
        val rig = rig()
        startLive(rig)
        assertEquals(LiveState.LISTENING, rig.controller.state.value)

        rig.stt.processing.value = true
        runCurrent()

        assertEquals(LiveState.THINKING, rig.controller.state.value)
        assertEquals(TEXT.get(R.string.status_understanding), rig.controller.statusDetail.value)
    }

    @Test
    fun `passes on the live level and the partial text`() = runTest {
        val rig = rig()
        startLive(rig)
        rig.stt.level.value = 0.6f
        rig.stt.partialText.value = "hello wor"
        runCurrent()

        assertEquals(0.6f, rig.controller.level.value, 0f)
        assertEquals("hello wor", rig.controller.partialTranscript.value)
    }

    @Test
    fun `a warning from the speaker reaches the screen`() = runTest {
        val rig = rig()
        startLive(rig)
        rig.speaker.warning.value = "voice broke"
        runCurrent()
        assertEquals("voice broke", rig.controller.error.value)
    }

    @Test
    fun `a note about a fallback voice reaches the screen`() = runTest {
        val rig = rig()
        rig.speakerNotice = "using the phone's voice"
        startLive(rig)
        assertEquals("using the phone's voice", rig.controller.error.value)
    }

    @Test
    fun `a speaker that cannot start is reported`() = runTest {
        val rig = rig()
        rig.speaker.prepareResult = false
        startLive(rig)
        assertEquals(TEXT.get(R.string.error_speech_output_failed), rig.controller.error.value)
    }

    @Test
    fun `the voice test speaks a fixed sentence`() = runTest {
        val rig = rig()
        rig.controller.testSpeech()
        advanceUntilIdle()
        assertEquals(listOf(TEXT.get(R.string.test_speech_sentence)), rig.speaker.spoken)
    }

    @Test
    fun `an error can be dismissed`() = runTest {
        val rig = rig(microphone = false)
        rig.controller.start()
        assertTrue(rig.controller.error.value != null)
        rig.controller.dismissError()
        assertNull(rig.controller.error.value)
    }

    @Test
    fun `clearing the conversation empties the transcript`() = runTest {
        val rig = rig()
        startLive(rig, SttResult.Text("hello"))
        rig.controller.clearConversation()
        assertTrue(rig.controller.messages.value.isEmpty())
    }

    @Test
    fun `speech cleanup applies to what is read aloud but not to what is shown`() = runTest {
        val rig = rig()
        rig.llm.reply { flowOf("This is **really** important, said the assistant. ") }

        startLive(rig, SttResult.Text("hello"))

        assertEquals("This is **really** important, said the assistant.", rig.texts()[1])
        assertEquals(listOf("This is really important, said the assistant."), rig.speaker.spoken)
    }

    private companion object {
        /** Returns the id and the arguments, so tests can compare messages without the real strings. */
        val TEXT = TextSource { id, args -> if (args.isEmpty()) "<$id>" else "<$id:${args.joinToString(",")}>" }
    }
}
