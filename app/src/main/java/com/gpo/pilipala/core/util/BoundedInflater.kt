package com.gpo.pilipala.core.util

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.Inflater

/** Network payloads must finish, make progress, and fit the caller's decompression budget. */
internal fun inflateBounded(bytes: ByteArray, maxBytes: Int, raw: Boolean = false): ByteArray {
    require(maxBytes > 0)
    val inflater = Inflater(raw)
    try {
        inflater.setInput(bytes)
        val out = ByteArrayOutputStream(minOf(bytes.size, maxBytes, 16 * 1024))
        val chunk = ByteArray(8192)
        while (!inflater.finished()) {
            val count = inflater.inflate(chunk)
            if (count > maxBytes - out.size()) throw IOException("Inflated payload exceeds $maxBytes bytes")
            if (count == 0 && !inflater.finished()) throw IOException("Incomplete or stalled compressed payload")
            out.write(chunk, 0, count)
        }
        return out.toByteArray()
    } finally {
        inflater.end()
    }
}
