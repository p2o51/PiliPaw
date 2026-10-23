package com.gpo.pilipala.feature.live

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.zip.DeflaterOutputStream

class LivePacketsTest {
    private fun packet(body: ByteArray, op: Int = 5, version: Int = 1): ByteArray =
        ByteBuffer.allocate(16 + body.size).putInt(16 + body.size).putShort(16)
            .putShort(version.toShort()).putInt(op).putInt(1).put(body).array()

    private fun compressed(bytes: ByteArray): ByteArray = packet(
        ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(bytes) } }.toByteArray(),
        version = 2,
    )

    @Test fun readsConcatenatedAndCompressedCommandsInOrder() {
        val seen = mutableListOf<Pair<Int, String>>()
        val data = packet("{}".toByteArray(), op = 8) + compressed(packet("one".toByteArray()) + packet("two".toByteArray()))
        LivePackets.decode(data) { op, body -> seen += op to body.toString(Charsets.UTF_8) }
        assertEquals(listOf(8 to "{}", 5 to "one", 5 to "two"), seen)
    }

    @Test(timeout = 1000) fun zeroLengthHeaderCannotLoopForever() {
        assertThrows(IOException::class.java) { LivePackets.decode(ByteArray(16)) { _, _ -> } }
    }

    @Test fun rejectsInvalidLengthsAndTruncation() {
        for (length in listOf(-1, 1, 15, Int.MAX_VALUE)) {
            val data = packet(byteArrayOf())
            ByteBuffer.wrap(data).putInt(length)
            assertThrows(IOException::class.java) { LivePackets.decode(data) { _, _ -> } }
        }
        val shortHeader = packet(byteArrayOf())
        ByteBuffer.wrap(shortHeader).putShort(4, 0)
        assertThrows(IOException::class.java) { LivePackets.decode(shortHeader) { _, _ -> } }
        assertThrows(IOException::class.java) { LivePackets.decode(ByteArray(15)) { _, _ -> } }
    }

    @Test fun nestedCompressionHasADepthLimit() {
        var data = packet("ok".toByteArray())
        repeat(5) { data = compressed(data) }
        assertThrows(IOException::class.java) { LivePackets.decode(data) { _, _ -> } }
    }

    @Test fun compressedPayloadHasASizeLimit() {
        val data = compressed(ByteArray(16 * 1024 * 1024 + 1))
        assertThrows(IOException::class.java) { LivePackets.decode(data) { _, _ -> } }
    }
}
