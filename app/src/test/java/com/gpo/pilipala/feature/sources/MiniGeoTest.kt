package com.gpo.pilipala.feature.sources

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniGeoTest {
    private val d = MiniDims(circle = 48f, row = 100f, detachExtra = 40f)
    private val bar = Rect(16f, 700f, 376f, 756f)
    private val nav = Rect(16f, 764f, 376f, 832f)
    private val circle = Rect(26f, 774f, 74f, 822f)
    private val bounds = Rect(12f, 40f, 380f, 830f)
    private val px = MiniPx(gap = 3f, inner = 6f, windowCorner = 16f, margin = 12f, neckInset = 8f)

    private fun geo(axis: Float, aspect: Float = 16f / 9f, center: Offset = Offset.Unspecified, circle: Rect? = this.circle) =
        miniGeo(axis, d, bar, circle, aspect, center, bounds, nav, px)

    private fun assertRect(e: Rect, a: Rect, tol: Float = 0.6f) {
        assertEquals(e.left, a.left, tol); assertEquals(e.top, a.top, tol)
        assertEquals(e.right, a.right, tol); assertEquals(e.bottom, a.bottom, tol)
    }

    @Test fun theRowIsFourJoinedBlocksWithASixteenByNineTile() {
        val g = geo(100f)
        assertEquals(100f, g.tile.width, 0.01f)
        assertEquals(bar.height, g.tile.height, 0.01f)
        assertEquals(3f, g.tile.left - g.left.right, 0.01f)
        assertEquals(3f, g.pause.left - g.tile.right, 0.01f)
        assertEquals(bar.right, g.right.right, 0.01f)
        assertEquals(g.left.width, g.pause.width, 0.01f)
        assertEquals(1f, g.sideAlpha, 0f)
        assertRect(bar, g.hit)
        assertFalse(g.windowed)
    }

    @Test fun mergedIsTheCircleAlone() {
        val g = geo(48f)
        assertRect(circle, g.tile)
        assertEquals(24f, g.tileCorner, 0.01f)
        assertEquals(0f, g.sideAlpha, 0f)
        assertRect(circle, g.hit)
    }

    @Test fun halfwayMergingPoursThroughTheGoo() {
        assertNotNull(geo(74f).neck)
        assertNull(geo(100f).neck)
        assertNull(geo(48f).neck)
    }

    @Test fun noNavBarNoMerge() {
        val g = geo(60f, circle = null)
        assertEquals(0f, g.merge, 0f)
    }

    @Test fun detachingIsContinuousIntoTheWindow() {
        val before = geo(d.detach - 0.001f)
        val after = geo(d.detach)
        assertFalse(before.windowed)
        assertTrue(after.windowed)
        assertRect(before.tile, after.tile)
    }

    @Test fun detachingIsContinuousForAVerticalVideoAndAMovedWindow() {
        val c = Offset(200f, 300f)
        val before = geo(d.detach - 0.001f, aspect = 9f / 16f, center = c)
        val after = geo(d.detach, aspect = 9f / 16f, center = c)
        assertRect(before.tile, after.tile)
        // The window has the video's shape: taller than wide.
        assertTrue(after.tile.height > after.tile.width)
    }

    @Test fun leavingTheRowUpwardIsContinuous() {
        assertRect(geo(100f).tile, geo(100.001f).tile)
        assertRect(geo(100f).tile, geo(99.999f).tile)
    }

    @Test fun poppingOutTheTileSitsUnderTheFingerAtOnce() {
        val finger = Offset(200f, 400f)
        val g = miniGeo(101f, d, bar, circle, 16f / 9f, finger, bounds, nav, px, pinned = true)
        assertEquals(finger.x, g.tile.center.x, 0.01f)
        assertEquals(finger.y, g.tile.center.y, 0.01f)
    }

    @Test fun theWindowStaysOnScreen() {
        val g = geo(300f, center = Offset(-500f, 5000f))
        assertTrue(g.tile.left >= bounds.left - 0.01f)
        assertTrue(g.tile.bottom <= bounds.bottom + 0.01f)
        assertRect(g.tile, g.hit)
    }

    @Test fun clampCentresWhatIsTooBig() {
        val c = clampCenter(Offset(0f, 0f), 1000f, 10f, Rect(0f, 0f, 100f, 100f))
        assertEquals(50f, c.x, 0f)
        assertEquals(5f, c.y, 0f)
    }
}
