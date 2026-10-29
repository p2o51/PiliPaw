package com.gpo.pilipala.feature.sources

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The side rail's mini player: the strip under the pill, its blocks, the dock flight's target, the window's corners. */
class RailStripTest {
    // A 1280 × 800 window at density 1: the rail's 88 column on the left, its 68 pill at 10.
    private val pill = Rect(10f, 274f, 78f, 526f)
    private val px = MiniPx(gap = 3f, inner = 6f, windowCorner = 16f, margin = 12f, neckInset = 8f, stripEnd = 14f)
    private val d = MiniDims(circle = 48f, row = 68f, detachExtra = 40f)
    private val bounds = Rect(12f, 36f, 1268f, 788f)

    private fun assertRect(e: Rect, a: Rect, tol: Float = 0.6f) {
        assertEquals(e.left, a.left, tol); assertEquals(e.top, a.top, tol)
        assertEquals(e.right, a.right, tol); assertEquals(e.bottom, a.bottom, tol)
    }

    private fun strip(nav: Rect? = pill, sides: Boolean = true) =
        railStripRect(nav, railLeft = 0f, railWidth = 88f, h = 800f, stripW = 68f, keyH = 40f, gap = 3f, navGap = 8f, bottomMargin = 16f, sides = sides)

    @Test fun theStripHangsUnderThePillAsWideAsIt() {
        val s = strip()
        assertRect(Rect(10f, 534f, 78f, 534f + 38.25f + 3 * 43f), s)
    }

    @Test fun withoutThePillTheStripSitsAtTheBottomOfTheRailsColumn() {
        val s = strip(nav = null)
        assertEquals(10f, s.left, 0.01f)
        assertEquals(800f - 16f, s.bottom, 0.01f)
        val right = railStripRect(null, 1192f, 88f, 800f, 68f, 40f, 3f, 8f, 16f)
        assertEquals(1202f, right.left, 0.01f)
    }

    @Test fun theStripIsThePictureThenPauseMinusAndPlus() {
        val s = strip()
        val g = miniGeo(68f, d, s, null, 16f / 9f, Offset.Unspecified, bounds, pill, px, orient = MiniOrient.StripLeft)
        assertRect(Rect(10f, 534f, 78f, 572.25f), g.tile)
        assertEquals(3f, g.pause.top - g.tile.bottom, 0.01f)
        assertEquals(3f, g.left.top - g.pause.bottom, 0.01f)
        assertEquals(3f, g.right.top - g.left.bottom, 0.01f)
        assertEquals(s.bottom, g.right.bottom, 0.01f)
        assertEquals(40f, g.pause.height, 0.01f)
        assertEquals(68f, g.pause.width, 0.01f)
        assertEquals(14f, g.tileTopCorner, 0.01f)
        assertEquals(6f, g.tileCorner, 0.01f)
        assertRect(s, g.hit)
    }

    @Test fun aLiveStripIsThePictureAndPause() {
        val s = strip(sides = false)
        val g = miniGeo(68f, d, s, null, 16f / 9f, Offset.Unspecified, bounds, pill, px, sides = false, orient = MiniOrient.StripLeft)
        assertEquals(s.bottom, g.pause.bottom, 0.01f)
        assertEquals(0f, g.left.height, 0.01f)
    }

    @Test fun mergedTheCircleSitsAtThePillsEndAndItsHitAreaLeavesTheTabsAlone() {
        val circle = railCircle(pill, 48f, Density(1f))
        assertRect(Rect(20f, 522f, 68f, 570f), circle)
        val g = miniGeo(48f, d, strip(), circle, 16f / 9f, Offset.Unspecified, bounds, pill, px, orient = MiniOrient.StripLeft)
        assertRect(circle, g.tile)
        assertTrue(g.hit.top >= circle.top - 0.01f)
        assertEquals(24f, g.tileTopCorner, 0.01f)
    }

    @Test fun mergingTheStripPoursUpIntoThePill() {
        val circle = railCircle(pill, 48f, Density(1f))
        val g = miniGeo(58f, d, strip(), circle, 16f / 9f, Offset.Unspecified, bounds, pill, px, orient = MiniOrient.StripLeft)
        val n = assertNotNull(g.neck).let { g.neck!! }
        assertTrue(n.up)
        assertEquals(pill.bottom - 8f, n.top, 0.01f)
        assertEquals(g.tile.center.y, n.bottom, 0.01f)
        // The row never draws its downward goo for a strip.
        assertNull(miniGeo(58f, d, strip(), circle, 16f / 9f, Offset.Unspecified, bounds, Rect(10f, 600f, 78f, 700f), px, orient = MiniOrient.StripLeft).neck)
    }

    @Test fun aWindowGrowsOutOfTheStripIntoTheWindowNotOverTheRail() {
        val g = miniGeo(260f, d, strip(), null, 16f / 9f, Offset.Unspecified, bounds, pill, px, orient = MiniOrient.StripLeft)
        assertTrue(g.windowed)
        assertEquals(12f, g.tile.left, 0.6f)
        assertEquals(534f, g.tile.top, 0.6f)
        val r = miniGeo(260f, d, railStripRect(pill.translate(1192f, 0f), 1192f, 88f, 800f, 68f, 40f, 3f, 8f, 16f), null, 16f / 9f, Offset.Unspecified, bounds, pill.translate(1192f, 0f), px, orient = MiniOrient.StripRight)
        assertEquals(1268f, r.tile.right, 0.6f)
    }

    @Test fun theDockFlightLandsOnTheStripsPicture() {
        val density = Density(1f)
        val tile = dockTileOf(pill, 1280f, 800f, tablet = true, density = density, rail = RailSpot(0f, 88f, right = false))
        assertRect(Rect(10f, 534f, 78f, 572.25f), tile)
        // A page without the rail: the strip's picture at the bottom of the rail's column.
        val bottom = dockTileOf(null, 1280f, 800f, tablet = true, density = density, rail = RailSpot(1192f, 88f, right = true))
        assertEquals(1202f, bottom.left, 0.01f)
        assertEquals(68f, bottom.width, 0.01f)
        // Without the rail it is the row's tile, as before.
        val row = dockTileOf(Rect(500f, 700f, 780f, 760f), 1280f, 800f, tablet = true, density = density)
        assertEquals(52f, row.height, 0.6f)
    }

    @Test fun aWindowMergesIntoTheStripFromTheRailsBottomCornerOnly() {
        assertTrue(MiniGestures.startsStripMerge(2, Offset(-40f, 5f), railRight = false))
        assertFalse(MiniGestures.startsStripMerge(2, Offset(0f, 40f), railRight = false))
        assertFalse(MiniGestures.startsStripMerge(3, Offset(-40f, 5f), railRight = false))
        assertFalse(MiniGestures.startsStripMerge(0, Offset(-40f, 5f), railRight = false))
        assertTrue(MiniGestures.startsStripMerge(3, Offset(40f, 5f), railRight = true))
        assertFalse(MiniGestures.startsStripMerge(2, Offset(40f, 5f), railRight = true))
    }

    @Test fun theStripsDrags() {
        // Into the window from the picture: a floating window under the finger.
        assertEquals(MiniDragIntent.PopOut, MiniGestures.stripDragIntent(Offset(30f, 4f), windowed = false, merged = false, onPicture = true, railRight = false))
        assertEquals(MiniDragIntent.PopOut, MiniGestures.stripDragIntent(Offset(-30f, 4f), false, false, true, true))
        // Down closes it, from anywhere on the strip.
        assertEquals(MiniDragIntent.SwipeDown, MiniGestures.stripDragIntent(Offset(3f, 30f), false, false, true, false))
        assertEquals(MiniDragIntent.SwipeDown, MiniGestures.stripDragIntent(Offset(-5f, 30f), false, false, false, true))
        // Toward the screen edge it only gives; never closes.
        assertEquals(MiniDragIntent.Nudge, MiniGestures.stripDragIntent(Offset(-30f, 4f), false, false, true, false))
        assertEquals(MiniDragIntent.Nudge, MiniGestures.stripDragIntent(Offset(30f, 4f), false, false, true, true))
        // Into the window from a key: it gives too (only the picture lifts off).
        assertEquals(MiniDragIntent.Nudge, MiniGestures.stripDragIntent(Offset(30f, 4f), false, false, false, false))
        // Up: nothing (the rail's).
        assertEquals(MiniDragIntent.None, MiniGestures.stripDragIntent(Offset(3f, -30f), false, false, true, false))
        assertEquals(MiniDragIntent.DockSwipe, MiniGestures.stripDragIntent(Offset(30f, 3f), false, true, true, false))
        assertEquals(MiniDragIntent.MoveWindow, MiniGestures.stripDragIntent(Offset(3f, 30f), true, false, true, false))
        assertEquals(30f, MiniGestures.intoWindow(Offset(-30f, 0f), railRight = true), 0f)
    }

    @Test fun theStripClosesPulledFarOrFlungDown() {
        val h = 170f
        assertTrue(MiniGestures.swipeDownCloses(dy = 70f, velocityY = 0f, height = h, minSpeed = 1000f))
        assertFalse(MiniGestures.swipeDownCloses(dy = 40f, velocityY = 200f, height = h, minSpeed = 1000f))
        assertTrue(MiniGestures.swipeDownCloses(dy = 20f, velocityY = 1500f, height = h, minSpeed = 1000f))
        // Flung down after being pulled back up above its place: it stays.
        assertFalse(MiniGestures.swipeDownCloses(dy = -5f, velocityY = 1500f, height = h, minSpeed = 1000f))
    }

    @Test fun aWindowOnTheSideTheRailTakesGoesToTheMirroredCorner() {
        // The rail moved right: the right-hand corners mirror, the left ones stay.
        assertEquals(0, MiniGestures.cornerAfterRailFlip(1, railRight = true))
        assertEquals(2, MiniGestures.cornerAfterRailFlip(3, railRight = true))
        assertEquals(2, MiniGestures.cornerAfterRailFlip(2, railRight = true))
        // Back to the left.
        assertEquals(1, MiniGestures.cornerAfterRailFlip(0, railRight = false))
        assertEquals(3, MiniGestures.cornerAfterRailFlip(3, railRight = false))
    }

    @Test fun theWindowsCornersLeaveTheRailsColumnOut() {
        // restArea as MiniBarHost builds it beside a left rail: the left corners start past the column.
        val area = Rect(88f + 12f, 36f, 1280f - 12f, 800f - 16f)
        val c = MiniGestures.cornerCenter(2, 320f, 180f, area)
        assertEquals(100f + 160f, c.x, 0.01f)
        assertEquals(784f - 90f, c.y, 0.01f)
    }
}
