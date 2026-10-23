package com.gpo.pilipala.core.util

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

class BoundedInflaterTest {
    private fun compress(data: ByteArray, raw: Boolean = false): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, raw)
        return try {
            ByteArrayOutputStream().also { out ->
                DeflaterOutputStream(out, deflater).use { it.write(data) }
            }.toByteArray()
        } finally { deflater.end() }
    }

    @Test fun handlesZlibAndRawAtExactLimit() {
        val data = "弹幕内容".repeat(2000).toByteArray()
        for (raw in listOf(false, true)) {
            assertArrayEquals(data, inflateBounded(compress(data, raw), data.size, raw))
        }
    }

    @Test fun rejectsExpansionBeyondBudget() {
        val compressed = compress(ByteArray(1_000_000))
        assertThrows(IOException::class.java) { inflateBounded(compressed, 4096) }
    }

    @Test(timeout = 1000) fun rejectsTruncatedAndEmptyInputInsteadOfSpinning() {
        val compressed = compress("subtitle".repeat(100).toByteArray())
        assertThrows(IOException::class.java) { inflateBounded(compressed.dropLast(2).toByteArray(), 4096) }
        assertThrows(IOException::class.java) { inflateBounded(byteArrayOf(), 4096) }
    }

    @Test fun acceptsAnEmptyCompressedStream() {
        assertArrayEquals(byteArrayOf(), inflateBounded(compress(byteArrayOf()), 1024))
    }
}
