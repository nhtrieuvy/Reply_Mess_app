package com.drivevoice.app.domain.state

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class ReplyRecording(
    val samples: ShortArray,
    val sampleRate: Int,
) : Closeable {
    fun wave(): ByteArray {
        val pcmSize = samples.size * 2
        return ByteBuffer.allocate(44 + pcmSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(36 + pcmSize).put("WAVEfmt ".toByteArray()).putInt(16)
            putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
            put("data".toByteArray()).putInt(pcmSize)
            samples.forEach { putShort(it) }
        }.array()
    }

    override fun close() { samples.fill(0) }
}
