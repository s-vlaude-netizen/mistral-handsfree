package de.localvoice.mistralhandsfree.speech

import android.media.AudioFormat
import android.media.AudioTrack
import java.io.IOException
import kotlin.math.max

/**
 * Where synthesized speech ends up: the loudspeaker.
 *
 * Just enough of an audio track for [MistralSpeaker] - and small enough to be
 * replaced by a recording fake in tests.
 */
interface PcmOutput {

    /** Writes mono 32-bit float samples. Blocks until they are accepted. */
    @Throws(IOException::class)
    fun write(samples: FloatArray)

    /** How many frames have actually come out of the speaker so far. */
    val playedFrames: Long

    /** Stops, discards what has not been played, and frees the device. */
    fun release()
}

fun interface PcmOutputFactory {
    fun open(sampleRate: Int): PcmOutput
}

/** The real thing: a streaming [AudioTrack]. */
class AudioTrackOutput(sampleRate: Int) : PcmOutput {

    private val track: AudioTrack

    init {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT,
        )
        // Half a second of float samples: enough to ride out a late network chunk.
        val bufferBytes = max(minBuffer, sampleRate * BYTES_PER_SAMPLE / 2)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioFocus.SPEECH_ATTRIBUTES)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
    }

    override fun write(samples: FloatArray) {
        var offset = 0
        while (offset < samples.size) {
            // Blocking write: waits for room in the buffer, which is what paces the playback.
            val written = track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written < 0) throw IOException("AudioTrack.write failed: $written")
            offset += written
        }
    }

    override val playedFrames: Long
        get() = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL

    override fun release() {
        runCatching { track.pause(); track.flush() }
        runCatching { track.release() }
    }

    private companion object {
        const val BYTES_PER_SAMPLE = 4
    }
}
