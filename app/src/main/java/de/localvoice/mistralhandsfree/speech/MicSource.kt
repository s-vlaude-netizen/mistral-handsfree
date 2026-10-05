package de.localvoice.mistralhandsfree.speech

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.max

/**
 * The microphone, as [MistralSpeechToText] needs it: 16-bit mono samples, read
 * in small blocks. Small enough to be replaced by a scripted fake in tests.
 */
interface MicSource {

    /** Starts recording. false if the microphone is held by another app. */
    fun start(): Boolean

    /**
     * Blocks until [size] samples are in [buffer] and returns how many arrived, or
     * a negative number if recording failed - which is also what happens to a
     * read that is waiting when [stop] is called.
     */
    fun read(buffer: ShortArray, size: Int): Int

    fun stop()

    fun release()
}

/** The outcome of trying to open the microphone. */
sealed interface MicOpenResult {
    class Ready(val source: MicSource) : MicOpenResult

    /** Recording was refused - in practice, the microphone permission is missing. */
    data object Denied : MicOpenResult

    /** This device cannot record in the requested format. */
    data object Unsupported : MicOpenResult
}

fun interface MicSourceFactory {
    fun open(sampleRate: Int): MicOpenResult
}

/** The real thing: an [AudioRecord] on the voice-recognition source. */
class AudioRecordMic private constructor(private val record: AudioRecord) : MicSource {

    override fun start(): Boolean {
        record.startRecording()
        return record.recordingState == AudioRecord.RECORDSTATE_RECORDING
    }

    override fun read(buffer: ShortArray, size: Int): Int = record.read(buffer, 0, size)

    override fun stop() {
        runCatching { record.stop() }
    }

    override fun release() {
        runCatching { record.release() }
    }

    companion object : MicSourceFactory {
        override fun open(sampleRate: Int): MicOpenResult {
            val minBuffer = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) return MicOpenResult.Unsupported

            // At least a second of buffer, so that a hiccup in the reading thread loses no audio.
            val bufferBytes = max(minBuffer, sampleRate * 2)
            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes,
                )
            } catch (_: SecurityException) {
                return MicOpenResult.Denied
            } catch (_: IllegalArgumentException) {
                return MicOpenResult.Unsupported
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return MicOpenResult.Denied
            }
            return MicOpenResult.Ready(AudioRecordMic(record))
        }
    }
}
