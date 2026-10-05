package de.localvoice.mistralhandsfree.session

import android.util.Log
import de.localvoice.mistralhandsfree.R
import de.localvoice.mistralhandsfree.auth.ApiKeyStore
import de.localvoice.mistralhandsfree.data.AppSettings
import de.localvoice.mistralhandsfree.data.SettingsSource
import de.localvoice.mistralhandsfree.data.TtsEngine
import de.localvoice.mistralhandsfree.domain.ChatMessage
import de.localvoice.mistralhandsfree.domain.Role
import de.localvoice.mistralhandsfree.domain.SentenceChunker
import de.localvoice.mistralhandsfree.domain.SpeechText
import de.localvoice.mistralhandsfree.domain.VoiceCommands
import de.localvoice.mistralhandsfree.llm.LlmEngine
import de.localvoice.mistralhandsfree.mistral.MistralException
import de.localvoice.mistralhandsfree.mistral.userMessage
import de.localvoice.mistralhandsfree.speech.SpeechEngines
import de.localvoice.mistralhandsfree.speech.SpeechToText
import de.localvoice.mistralhandsfree.speech.Speaker
import de.localvoice.mistralhandsfree.speech.SttResult
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where the loop currently stands. */
enum class LiveState {
    /** Nothing is running. */
    IDLE,

    /** Speech services are being started. */
    PREPARING,

    /** Microphone open. */
    LISTENING,

    /** You were heard; the text is being worked out, or the model has not said a speakable sentence yet. */
    THINKING,

    /** The answer is being read out. */
    SPEAKING,
}

/**
 * The hands-free loop itself: listen, answer, read out, listen again - until the
 * user stops.
 *
 * Lives as long as the application, so that rotating the screen or switching to
 * another app does not cut the conversation off. The foreground service keeps
 * the process alive meanwhile.
 *
 * Nothing is sent by a button: the speech engine decides when a turn is over
 * (the pause after speech), the text goes to Mistral on its own, the answer is
 * spoken as it arrives, and the microphone opens again by itself.
 *
 * Everything that touches Android or the network comes in through the
 * constructor as a small interface, so the loop can be run in tests with
 * scripted engines and a fake model.
 */
class LiveSessionController(
    private val text: TextSource,
    private val settingsStore: SettingsSource,
    private val keyStore: ApiKeyStore,
    private val llm: LlmEngine,
    private val engines: SpeechEngines,
    private val hasMicrophonePermission: () -> Boolean,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(LiveState.IDLE)
    val state: StateFlow<LiveState> = _state.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _statusDetail = MutableStateFlow("")
    val statusDetail: StateFlow<String> = _statusDetail.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Set when Mistral rejected the key (or there is none): the UI offers to sign in again. */
    private val _needsSignIn = MutableStateFlow(false)
    val needsSignIn: StateFlow<Boolean> = _needsSignIn.asStateFlow()

    private val _partial = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = _partial.asStateFlow()

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _speechDiagnostics = MutableStateFlow(text.get(R.string.tts_not_started))
    val speechDiagnostics: StateFlow<String> = _speechDiagnostics.asStateFlow()

    private var stt: SpeechToText? = null
    private var sttSignature: String? = null
    private var speaker: Speaker? = null
    private var speakerSignature: String? = null

    private val idCounter = AtomicLong(0)
    private val pendingTypedInput = AtomicReference<String?>(null)
    private val turnMutex = Mutex()
    private val speakerMutex = Mutex()

    private var loopJob: Job? = null
    private var turnJob: Job? = null
    private var sttRelayJob: Job? = null
    private var speakerRelayJob: Job? = null

    val isRunning: Boolean get() = loopJob?.isActive == true

    // ---------------------------------------------------------------- control

    /** Starts hands-free mode. */
    fun start() {
        if (isRunning) return
        if (!hasMicrophonePermission()) {
            _error.value = text.get(R.string.error_no_mic_permission)
            return
        }
        if (keyStore.load() == null) {
            reportSignInNeeded(text.get(R.string.err_no_key))
            return
        }
        _error.value = null
        _needsSignIn.value = false
        if (settingsStore.current.freshStart) clearConversation()
        // Set right here and not only once the loop runs: the foreground service watches
        // this state and would stop itself again if it saw "idle" in the gap.
        _state.value = LiveState.PREPARING
        loopJob = scope.launch { runLoop() }
    }

    /** Ends hands-free mode; the conversation stays on screen. */
    fun stop() {
        loopJob?.cancel()
        loopJob = null
        turnJob?.cancel()
        turnJob = null
        stt?.abort()
        speaker?.stop()
        _partial.value = ""
        _level.value = 0f
        _statusDetail.value = ""
        _state.value = LiveState.IDLE
    }

    fun toggle() {
        if (isRunning) stop() else start()
    }

    /** Cancels only the current answer; the loop keeps listening afterwards. */
    fun interruptCurrentTurn() {
        speaker?.stop()
        turnJob?.cancel()
    }

    /** Typed input - works with live mode off, too. */
    fun sendTypedMessage(message: String) {
        val clean = message.trim()
        if (clean.isEmpty()) return
        if (keyStore.load() == null) {
            reportSignInNeeded(text.get(R.string.err_no_key))
            return
        }
        if (isRunning) {
            // The loop picks the message up as soon as listening ends.
            pendingTypedInput.set(clean)
            stt?.abort()
        } else {
            scope.launch {
                _error.value = null
                prepareForTurn()
                runTurn(clean, speakAloud = true)
                _state.value = LiveState.IDLE
            }
        }
    }

    fun clearConversation() {
        _messages.value = emptyList()
    }

    fun dismissError() {
        _error.value = null
    }

    /** Called once a working key has been saved: clears the "please sign in" state. */
    fun onSignedIn() {
        _needsSignIn.value = false
        _error.value = null
    }

    /** Something outside the loop found out that the key is no good. */
    fun reportSignInNeeded(message: String) {
        _error.value = message
        _needsSignIn.value = true
    }

    fun shutdown() {
        stop()
        speaker?.shutdown()
        stt?.destroy()
        speaker = null
        stt = null
    }

    /** Speaks a fixed sentence - to check the voice. */
    fun testSpeech() {
        scope.launch {
            _error.value = null
            ensureSpeaker(settingsStore.current)
            speaker?.speakNow(text.get(R.string.test_speech_sentence))
        }
    }

    // ------------------------------------------------------------------- loop

    private suspend fun runLoop() {
        var silentRounds = 0
        var failedRounds = 0
        try {
            prepareForTurn()
            while (currentCoroutineContext().isActive) {
                val typed = pendingTypedInput.getAndSet(null)
                if (typed != null) {
                    silentRounds = 0
                    runTurnInChildJob(typed)
                    if (_needsSignIn.value || !settingsStore.current.handsFree) break
                    continue
                }

                // Settings may have changed since the last round (pause length, engine, ...).
                val settings = settingsStore.current
                ensureStt(settings)
                ensureSpeaker(settings)

                _state.value = LiveState.LISTENING
                _statusDetail.value = text.get(R.string.status_listening)
                val recognizer = stt ?: break
                when (val result = recognizer.listenOnce()) {
                    is SttResult.Text -> {
                        silentRounds = 0
                        failedRounds = 0
                        _partial.value = ""
                        if (VoiceCommands.isStopCommand(result.text)) {
                            _statusDetail.value = text.get(R.string.status_stopped_by_voice)
                            break
                        }
                        runTurnInChildJob(result.text)
                        if (_needsSignIn.value || !settingsStore.current.handsFree) break
                    }

                    SttResult.Silence -> {
                        failedRounds = 0
                        // A typed message may have aborted the listening on purpose.
                        if (pendingTypedInput.get() != null) continue
                        silentRounds++
                        if (silentRounds >= MAX_SILENT_ROUNDS) {
                            _statusDetail.value = text.get(R.string.status_paused_after_silence)
                            break
                        }
                        _statusDetail.value = text.get(R.string.status_nothing_heard)
                    }

                    is SttResult.Failure -> {
                        if (result.needsSignIn) reportSignInNeeded(result.message) else _error.value = result.message
                        failedRounds++
                        // Something that keeps failing is not going to fix itself by being retried
                        // for ever, and each retry opens the microphone or the network again.
                        if (!result.recoverable || failedRounds >= MAX_FAILED_ROUNDS) break
                        delay(RETRY_DELAY_MS)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Live loop aborted", t)
            _error.value = t.message ?: text.get(R.string.error_unexpected)
        } finally {
            _partial.value = ""
            _level.value = 0f
            _state.value = LiveState.IDLE
            loopJob = null
        }
    }

    /**
     * The turn runs in a job of its own, so that "interrupt" hits only the turn
     * and not the whole loop.
     */
    private suspend fun runTurnInChildJob(userText: String) {
        val job = scope.launch { runTurn(userText, speakAloud = true) }
        turnJob = job
        job.join()
        turnJob = null
    }

    private suspend fun runTurn(userText: String, speakAloud: Boolean) = turnMutex.withLock {
        // Some recognizers keep the recording open until told otherwise,
        // and then the voice would not come through.
        if (speakAloud) stt?.abort()
        appendMessage(Role.USER, userText, streaming = false)
        _state.value = LiveState.THINKING
        _statusDetail.value = text.get(R.string.status_thinking)

        val assistantId = appendMessage(Role.ASSISTANT, "", streaming = true)
        val chunker = newChunker(settingsStore.current)
        val collected = StringBuilder()
        var failed = false

        try {
            llm.generate(_messages.value.dropLast(1)).collect { delta ->
                collected.append(delta)
                updateMessage(assistantId, SpeechText.forDisplay(collected.toString()), streaming = true)
                if (speakAloud) chunker.append(delta).forEach { speakChunk(it) }
            }
            if (speakAloud) chunker.flush()?.let { speakChunk(it) }
        } catch (e: CancellationException) {
            val partial = SpeechText.forDisplay(collected.toString())
            if (partial.isEmpty()) removeMessage(assistantId)
            else updateMessage(assistantId, partial, streaming = false)
            throw e
        } catch (e: MistralException) {
            failed = true
            Log.w(TAG, "Mistral request failed: ${e.message}")
            if (e.kind == MistralException.Kind.UNAUTHORIZED) reportSignInNeeded(e.userMessage(text))
            else _error.value = e.userMessage(text)
            // What was said before the failure was still spoken - speak the rest of that sentence too.
            if (speakAloud) chunker.flush()?.let { speakChunk(it) }
        } catch (t: Throwable) {
            failed = true
            Log.e(TAG, "Generation failed", t)
            _error.value = t.message ?: text.get(R.string.error_model_no_answer)
        }

        val finalText = SpeechText.forDisplay(collected.toString())
        when {
            finalText.isNotEmpty() -> updateMessage(assistantId, finalText, streaming = false)
            // Nothing arrived because the request failed: no empty bubble, the banner says why.
            failed -> removeMessage(assistantId)
            else -> updateMessage(assistantId, text.get(R.string.no_answer), streaming = false)
        }

        if (speakAloud) {
            _state.value = LiveState.SPEAKING
            _statusDetail.value = text.get(R.string.status_speaking)
            speaker?.awaitIdle()
            // A moment of calm: otherwise the recognizer grabs the audio device
            // while the speech output is still fading out.
            delay(SETTLE_AFTER_SPEECH_MS)
        }
    }

    private fun speakChunk(chunk: String) {
        val speakable = SpeechText.forSpeech(chunk)
        if (speakable.isEmpty()) return
        _state.value = LiveState.SPEAKING
        _statusDetail.value = text.get(R.string.status_speaking)
        speaker?.enqueue(speakable)
    }

    /**
     * Mistral's voice is billed per character and has a request overhead per
     * sentence, so it gets longer pieces than the free device voice.
     */
    private fun newChunker(settings: AppSettings): SentenceChunker =
        if (settings.ttsEngine == TtsEngine.MISTRAL) SentenceChunker(minChunkChars = 40, maxChunkChars = 300)
        else SentenceChunker()

    // ---------------------------------------------------------- building blocks

    private suspend fun prepareForTurn() {
        _state.value = LiveState.PREPARING
        _statusDetail.value = text.get(R.string.status_preparing_speech)
        val settings = settingsStore.current
        ensureSpeaker(settings)
        ensureStt(settings)
    }

    private fun ensureStt(settings: AppSettings) {
        val signature = listOf(
            settings.sttEngine, settings.sttLanguageTag, settings.preferOnDevice, settings.pauseMs,
        ).joinToString("|")
        if (stt != null && sttSignature == signature) return
        stt?.destroy()
        val created = engines.recognizer(settings)
        stt = created
        sttSignature = signature
        sttRelayJob?.cancel()
        sttRelayJob = scope.launch {
            launch { created.partialText.collect { _partial.value = it } }
            launch { created.level.collect { _level.value = it } }
            launch {
                created.processing.collect { processing ->
                    // Heard you, working on the text: show it instead of staying on "listening".
                    if (processing && _state.value == LiveState.LISTENING) {
                        _state.value = LiveState.THINKING
                        _statusDetail.value = text.get(R.string.status_understanding)
                    }
                }
            }
        }
    }

    private suspend fun ensureSpeaker(settings: AppSettings) = speakerMutex.withLock {
        val signature = listOf(
            settings.ttsEngine, settings.ttsLanguageTag, settings.speechRate, settings.pitch,
            settings.mistralVoiceId,
        ).joinToString("|")
        if (speaker != null && speakerSignature == signature) return@withLock
        speaker?.shutdown()

        val setup = engines.speaker(settings)
        setup.notice?.let { _error.value = it }
        val created = setup.speaker
        val ok = created.prepare()
        speaker = created
        speakerSignature = signature
        if (!ok) _error.value = text.get(R.string.error_speech_output_failed)
        speakerRelayJob?.cancel()
        speakerRelayJob = scope.launch {
            launch { created.warning.collect { it?.let { message -> _error.value = message } } }
            launch { created.diagnostics.collect { _speechDiagnostics.value = it } }
        }
    }

    // ----------------------------------------------------------- conversation

    private fun appendMessage(role: Role, content: String, streaming: Boolean): Long {
        val id = idCounter.incrementAndGet()
        _messages.value = _messages.value + ChatMessage(
            id = id,
            role = role,
            text = content,
            timestampMs = System.currentTimeMillis(),
            streaming = streaming,
        )
        return id
    }

    private fun updateMessage(id: Long, content: String, streaming: Boolean) {
        _messages.value = _messages.value.map {
            if (it.id == id) it.copy(text = content, streaming = streaming) else it
        }
    }

    private fun removeMessage(id: Long) {
        _messages.value = _messages.value.filterNot { it.id == id }
    }

    companion object {
        private const val TAG = "LiveSessionController"

        /** About a minute and a half of nothing, then live mode pauses itself. */
        const val MAX_SILENT_ROUNDS = 10

        /** This many recognizer failures in a row end live mode. */
        const val MAX_FAILED_ROUNDS = 5
        private const val RETRY_DELAY_MS = 800L
        private const val SETTLE_AFTER_SPEECH_MS = 250L
    }
}
