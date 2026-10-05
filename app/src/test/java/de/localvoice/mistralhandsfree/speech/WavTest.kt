package de.localvoice.mistralhandsfree.speech

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class WavTest {

    @Test
    fun `writes a valid 44-byte header followed by the samples`() {
        val samples = shortArrayOf(1, -2, 300, Short.MIN_VALUE, Short.MAX_VALUE)
        val wav = Wav.fromPcm16Mono(samples, 16_000)

        assertEquals(44 + samples.size * 2, wav.size)
        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)

        fun tag(at: Int) = String(wav, at, 4, Charsets.US_ASCII)
        assertEquals("RIFF", tag(0))
        assertEquals(wav.size - 8, buffer.getInt(4)) // RIFF chunk size
        assertEquals("WAVE", tag(8))
        assertEquals("fmt ", tag(12))
        assertEquals(16, buffer.getInt(16)) // fmt chunk size
        assertEquals(1, buffer.getShort(20).toInt()) // PCM
        assertEquals(1, buffer.getShort(22).toInt()) // mono
        assertEquals(16_000, buffer.getInt(24)) // sample rate
        assertEquals(32_000, buffer.getInt(28)) // byte rate
        assertEquals(2, buffer.getShort(32).toInt()) // block align
        assertEquals(16, buffer.getShort(34).toInt()) // bits per sample
        assertEquals("data", tag(36))
        assertEquals(samples.size * 2, buffer.getInt(40)) // data size

        samples.forEachIndexed { i, expected -> assertEquals(expected, buffer.getShort(44 + i * 2)) }
    }

    @Test
    fun `an empty recording is still a valid file`() {
        val wav = Wav.fromPcm16Mono(ShortArray(0), 16_000)
        assertEquals(44, wav.size)
        assertEquals(0, ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
    }
}
