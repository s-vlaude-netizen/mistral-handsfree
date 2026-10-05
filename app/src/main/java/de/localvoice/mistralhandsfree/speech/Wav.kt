package de.localvoice.mistralhandsfree.speech

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Just enough WAV to upload a recording. */
object Wav {

    private const val HEADER_BYTES = 44

    /** Wraps 16-bit mono PCM samples in a canonical 44-byte WAV header. */
    fun fromPcm16Mono(samples: ShortArray, sampleRate: Int): ByteArray {
        val dataBytes = samples.size * 2
        val buffer = ByteBuffer.allocate(HEADER_BYTES + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + dataBytes)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16) // size of the fmt chunk
        buffer.putShort(1) // PCM
        buffer.putShort(1) // mono
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * 2) // byte rate
        buffer.putShort(2) // block align
        buffer.putShort(16) // bits per sample
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataBytes)
        for (sample in samples) buffer.putShort(sample)
        return buffer.array()
    }
}
