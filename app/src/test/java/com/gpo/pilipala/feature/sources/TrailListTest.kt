package com.gpo.pilipala.feature.sources

import com.gpo.pilipala.core.source.VideoSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailListTest {
    private fun e(k: String, pos: Long = 0) = TrailEntry(k, VideoSource.Bilibili, "t$k", "", pos, 1000, k)

    @Test fun newestFirst() {
        val t = TrailList()
        t.push(e("a")); t.push(e("b")); t.push(e("c"))
        assertEquals(listOf("c", "b", "a"), t.entries.map { it.key })
    }

    @Test fun keepsTwentyDroppingTheOldest() {
        val t = TrailList(20)
        repeat(25) { t.push(e("v$it")) }
        assertEquals(20, t.entries.size)
        assertEquals("v24", t.entries.first().key)
        assertEquals("v5", t.entries.last().key)
    }

    @Test fun aVideoWatchedAgainMovesUpOnceWithItsNewPosition() {
        val t = TrailList()
        t.push(e("a", 10)); t.push(e("b")); t.push(e("a", 99))
        assertEquals(listOf("a", "b"), t.entries.map { it.key })
        assertEquals(99, t.entries.first().positionMs)
    }

    @Test fun takingAnEntryRemovesIt() {
        val t = TrailList()
        t.push(e("a", 42)); t.push(e("b"))
        assertEquals(42L, t.take("a")?.positionMs)
        assertEquals(listOf("b"), t.entries.map { it.key })
        assertNull(t.take("zzz"))
    }

    @Test fun switchingBackPushesTheOneLeft() {
        // Watching c, back to a: c goes on top, a leaves the list.
        val t = TrailList()
        t.push(e("a")); t.push(e("b"))
        t.take("a")
        t.push(e("c"))
        assertEquals(listOf("c", "b"), t.entries.map { it.key })
    }

    @Test fun forgetAndClear() {
        val t = TrailList()
        t.push(e("a")); t.push(e("b"))
        t.forget("a")
        assertEquals(listOf("b"), t.entries.map { it.key })
        t.clear()
        assertTrue(t.entries.isEmpty())
    }
}
