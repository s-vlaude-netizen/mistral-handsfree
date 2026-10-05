package de.localvoice.mistralhandsfree.speech

import android.util.Log
import de.localvoice.mistralhandsfree.R
import de.localvoice.mistralhandsfree.mistral.MistralAudio
import de.localvoice.mistralhandsfree.mistral.MistralException
import de.localvoice.mistralhandsfree.mistral.userMessage
import de.localvoice.mistralhandsfree.session.TextSource
import java.io.IOException
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

/**
 * Speech output with Mistral's Voxtral TTS.
 *
 * Every sentence is one request, and the requests run ahead of the playback:
 * while sentence one is being spoken, sentence two is already being synthesized.
 * The audio of all sentences goes into one continuous output, so there is no gap
 * where one ends and the next begins.
 *
 * If Mistral cannot speak a sentence - rate limit, a moderation block, a timeout,
 * no connection - that sentence is spoken by [fallback] instead (normally the
 * phone's own voice), so a problem costs a change of voice rather than silence.
 * Order is kept: the fallback only speaks once everything before it has played.
 *
 * @param synthesize turns one sentence into 24 kHz mono float audio ([MistralAudio.SPEECH_SAMPLE_RATE]).
 * @param describe what to show in the diagnostics line: model and voice.
 */
class MistralSpeaker(
    private val text: TextSource,
    private val synthesize: (sentence: String) -> Flow<FloatArray>,
    private val describe: () -> String,
    private val fallback: Speaker,
    private val scope: CoroutineScope,
    private val outputFactory: PcmOutputFactory,
    private val focus: FocusControl,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : Speaker {

    /** One sentence on its way: the text, and the audio as it arrives. */
    private class Item(val text: String) {
        val audio = Channel<FloatArray>(Channel.UNLIMITED)

        @Volatile
        var failure: Throwable? = null

        @Volatile
        var synthesis: Job? = null
    }

    private val lock = Any()
    private val queue = ArrayDeque<Item>()
    private var consumer: Job? = null

    @Volatile
    private var current: Item? = null

    @Volatile
    private var closed = false

    private val slots = Semaphore(MAX_PARALLEL_SYNTHESIS)
    private val pending = MutableStateFlow(0)

    private val _busy = MutableStateFlow(false)
    override val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _warning = MutableStateFlow<String?>(null)
    override val warning: StateFlow<String?> = _warning.asStateFlow()

    private val _diagnostics = MutableStateFlow(text.get(R.string.tts_not_started))
    override val diagnostics: StateFlow<String> = _diagnostics.asStateFlow()

    @Volatile
    private var output: PcmOutput? = null

    /** Frames written to the current [output]; compared with how many it has played. */
    @Volatile
    private var framesWritten = 0L

    private var warnedAboutFallback = false
    private var relayJob: Job? = null

    override suspend fun prepare(): Boolean {
        fallback.prepare() // its own warning explains if the device voice is unusable
        relayJob?.cancel()
        relayJob = scope.launch {
            fallback.warning.collect { message -> if (message != null) _warning.value = message }
        }
        _diagnostics.value = text.get(R.string.tts_diagnostics_mistral, describe())
        return true // Mistral's voice is assumed to work until a request says otherwise
    }

    override fun enqueue(text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || closed) return
        val item = Item(clean)
        synchronized(lock) {
            queue.addLast(item)
            pending.update { it + 1 }
            _busy.value = true
            item.synthesis = scope.launch(io) { synthesizeInto(item) }
            // Started under the lock, and the consumer takes the lock first thing:
            // it cannot finish and clear this field before it has been assigned.
            if (consumer == null) consumer = scope.launch(io) { consume() }
        }
        focus.acquire()
    }

    override fun speakNow(text: String) {
        stop()
        enqueue(text)
    }

    /** Fetches the audio of one sentence into [Item.audio]. */
    private suspend fun synthesizeInto(item: Item) {
        try {
            slots.withPermit {
                withTimeout(SYNTHESIS_TIMEOUT_MS) {
                    synthesize(item.text).collect { item.audio.send(it) }
                }
            }
        } catch (e: TimeoutCancellationException) {
            item.failure = e
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            item.failure = t
        } finally {
            item.audio.close()
        }
    }

    /** Plays the queue, one sentence after the other, until it is empty. */
    private suspend fun consume() {
        while (true) {
            val item = synchronized(lock) {
                val next = queue.removeFirstOrNull()
                if (next == null) consumer = null
                next
            } ?: return
            try {
                play(item)
            } finally {
                pending.update { max(0, it - 1) }
            }
        }
    }

    private suspend fun play(item: Item) {
        current = item
        var gotAudio = false
        try {
            for (chunk in item.audio) {
                writeFully(ensureOutput(), chunk)
                gotAudio = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // e.g. the audio output could not be opened, or was released under our feet
            item.failure = item.failure ?: t
        } finally {
            current = null
        }
        if (!gotAudio && item.failure == null) item.failure = IOException("Mistral sent no audio")

        val failure = item.failure ?: return
        reportFailure(failure)
        // Cut off in the middle of a sentence: the rest is lost, and starting
        // the sentence over in another voice would be worse than a short sentence.
        if (gotAudio) return

        awaitDrained() // everything before this sentence plays out first
        fallback.enqueue(item.text)
        fallback.awaitIdle()
    }

    private fun reportFailure(failure: Throwable) {
        Log.w(TAG, "Mistral voice failed: ${failure.message}")
        if (warnedAboutFallback) return
        warnedAboutFallback = true
        val reason = (failure as? MistralException)?.userMessage(text) ?: (failure.message ?: "")
        _warning.value = text.get(R.string.tts_mistral_failed, reason)
    }

    private fun ensureOutput(): PcmOutput {
        output?.let { return it }
        framesWritten = 0
        return outputFactory.open(MistralAudio.SPEECH_SAMPLE_RATE).also { output = it }
    }

    private fun writeFully(target: PcmOutput, samples: FloatArray) {
        target.write(samples)
        framesWritten += samples.size // mono: one sample is one frame
    }

    /** Waits until everything written has actually come out of the speaker. */
    private suspend fun awaitDrained() {
        val out = output ?: return
        val target = framesWritten
        val rate = MistralAudio.SPEECH_SAMPLE_RATE
        try {
            val remaining = max(0L, target - out.playedFrames)
            // The deadline keeps a stalled audio driver from hanging the conversation.
            var budgetMs = remaining * 1000 / rate + DRAIN_SLACK_MS
            while (currentCoroutineContext().isActive && out.playedFrames < target && budgetMs > 0) {
                delay(POLL_MS)
                budgetMs -= POLL_MS
            }
        } catch (_: IllegalStateException) {
            // The output was released while we were waiting - that means stop() ran.
        }
    }

    private fun releaseOutput() {
        val out = output
        output = null
        framesWritten = 0
        out?.release()
    }

    override suspend fun awaitIdle() {
        pending.first { it == 0 }
        awaitDrained()
        fallback.awaitIdle()
        val finished = synchronized(lock) { queue.isEmpty() && pending.value == 0 }
        if (finished) {
            releaseOutput()
            _busy.value = false
            focus.release()
        }
    }

    override fun stop() {
        val dropped: List<Item>
        synchronized(lock) {
            dropped = queue.toList()
            queue.clear()
            consumer?.cancel()
            consumer = null
            pending.value = 0
            _busy.value = false
        }
        dropped.forEach { it.synthesis?.cancel() }
        current?.synthesis?.cancel()
        releaseOutput() // also frees a write() that is blocked on a full buffer
        fallback.stop()
        focus.release()
    }

    override fun shutdown() {
        closed = true
        stop()
        relayJob?.cancel()
        fallback.shutdown()
    }

    companion object {
        private const val TAG = "MistralSpeaker"

        /** Sentences synthesized at the same time; more would only trip rate limits. */
        const val MAX_PARALLEL_SYNTHESIS = 2

        /** One sentence is a few seconds of audio; if it takes this long something is wrong. */
        const val SYNTHESIS_TIMEOUT_MS = 20_000L

        private const val DRAIN_SLACK_MS = 1_000L
        private const val POLL_MS = 20L
    }
}
