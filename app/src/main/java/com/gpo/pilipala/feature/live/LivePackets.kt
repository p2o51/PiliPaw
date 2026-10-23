package com.gpo.pilipala.feature.live

import com.gpo.pilipala.core.util.inflateBounded
import java.io.IOException
import java.nio.ByteBuffer

/** Validates framing before slicing or advancing; nested zlib packets share one budget. */
internal object LivePackets {
    private const val MaxBytes = 16 * 1024 * 1024
    private const val MaxDepth = 4

    fun decode(data: ByteArray, onPacket: (op: Int, body: ByteArray) -> Unit) {
        var remaining = MaxBytes
        fun parse(bytes: ByteArray, depth: Int) {
            if (depth > MaxDepth || bytes.size > MaxBytes) throw IOException("Live packet limit exceeded")
            val buffer = ByteBuffer.wrap(bytes)
            var offset = 0
            while (offset < bytes.size) {
                if (bytes.size - offset < 16) throw IOException("Truncated live header")
                val length = buffer.getInt(offset)
                val header = buffer.getShort(offset + 4).toInt() and 0xffff
                val version = buffer.getShort(offset + 6).toInt() and 0xffff
                val op = buffer.getInt(offset + 8)
                if (header < 16 || length < header || length > bytes.size - offset) {
                    throw IOException("Invalid live packet length")
                }
                val body = bytes.copyOfRange(offset + header, offset + length)
                if (op == 5 && version == 2) {
                    if (remaining <= 0) throw IOException("Live decompression budget exhausted")
                    val inflated = inflateBounded(body, remaining)
                    remaining -= inflated.size
                    parse(inflated, depth + 1)
                } else if (op == 8 || op == 5 && version in 0..1) {
                    onPacket(op, body)
                }
                offset += length
            }
        }
        parse(data, 0)
    }
}
