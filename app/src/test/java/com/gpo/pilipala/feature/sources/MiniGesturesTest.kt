package com.gpo.pilipala.feature.sources

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class MiniGesturesTest {
    private fun kind(max: Int, travel: Float = 0f, ms: Long = 100, dragged: Boolean = false) =
        MiniGestures.classify(max, travel, ms, dragged, slop = 8f, tapTimeoutMs = 300)

    @Test fun oneStillQuickFingerIsATap() = assertEquals(MiniGestureKind.Tap, kind(1))

    @Test fun aSecondFingerAnywhereMakesItAPinchEvenIfItLiftsFirst() {
        assertEquals(MiniGestureKind.Pinch, kind(2))
        // Still and quick, but two fingers went down: never a tap.
        assertEquals(MiniGestureKind.Pinch, kind(2, travel = 0f, ms = 50))
    }

    @Test fun movedOrHeldIsNoTap() {
        assertEquals(MiniGestureKind.None, kind(1, travel = 9f))
        assertEquals(MiniGestureKind.None, kind(1, ms = 400))
        assertEquals(MiniGestureKind.Drag, kind(1, travel = 40f, dragged = true))
    }

    @Test fun aFlungWindowRestsInTheNearestCorner() {
        val area = Rect(0f, 0f, 400f, 800f)
        assertEquals(Offset(50f, 25f), MiniGestures.restingCenter(Offset(120f, 100f), 100f, 50f, area))
        assertEquals(Offset(350f, 775f), MiniGestures.restingCenter(Offset(300f, 1200f), 100f, 50f, area))
        assertEquals(Offset(350f, 25f), MiniGestures.restingCenter(Offset(900f, -300f), 100f, 50f, area))
    }

    @Test fun aDragUpOffThePicturePopsItOut() {
        fun intent(x: Float, y: Float, windowed: Boolean = false, merged: Boolean = false, onPicture: Boolean = true) =
            MiniGestures.dragIntent(Offset(x, y), windowed, merged, onPicture)
        assertEquals(MiniDragIntent.PopOut, intent(2f, -12f))
        assertEquals(MiniDragIntent.SwipeAway, intent(12f, -2f))
        assertEquals(MiniDragIntent.SwipeAway, intent(-12f, 3f))
        // Up from a side block, or down: nothing.
        assertEquals(MiniDragIntent.None, intent(2f, -12f, onPicture = false))
        assertEquals(MiniDragIntent.None, intent(2f, 12f))
        assertEquals(MiniDragIntent.MoveWindow, intent(0f, 12f, windowed = true))
        assertEquals(MiniDragIntent.None, intent(12f, 0f, merged = true))
    }

    @Test fun onlyAThrowAtTheNavBarDocks() {
        val nav = Rect(16f, 760f, 376f, 828f)
        val fast = 1000f
        // Thrown down hard so its centre lands in the nav bar: docks.
        assertEquals(true, MiniGestures.docksOnNav(Offset(190f, 800f), Offset(100f, 1500f), nav, fast))
        // Dragged slowly down to the bottom and let go, even right over the nav bar: stays a window.
        assertEquals(false, MiniGestures.docksOnNav(Offset(200f, 795f), Offset(0f, 300f), nav, fast))
        assertEquals(false, MiniGestures.docksOnNav(Offset(200f, 795f), Offset(0f, 0f), nav, fast))
        // A fast flick toward a bottom corner above the nav bar: a corner.
        assertEquals(false, MiniGestures.docksOnNav(Offset(320f, 720f), Offset(200f, 1800f), nav, fast))
        // Fast but mostly sideways: no.
        assertEquals(false, MiniGestures.docksOnNav(Offset(300f, 790f), Offset(2500f, 1200f), nav, fast))
        // No nav bar on this page: never.
        assertEquals(false, MiniGestures.docksOnNav(Offset(200f, 790f), Offset(0f, 3000f), null, fast))
    }

    @Test fun flungDownOntoTheNavItRestsInABottomCornerAboveIt() {
        // The rest area's bottom is above the nav bar: a throw far past it still lands inside.
        val area = Rect(12f, 40f, 388f, 700f)
        val c = MiniGestures.restingCenter(Offset(300f, 2000f), 160f, 90f, area)
        assertEquals(Offset(308f, 655f), c)
    }

    @Test fun aResizedWindowKeepsItsCorner() {
        val area = Rect(0f, 0f, 400f, 800f)
        assertEquals(3, MiniGestures.nearestCorner(Offset(300f, 700f), 100f, 50f, area))
        // Bigger, same corner: its bottom right edge stays put.
        assertEquals(Offset(300f, 750f), MiniGestures.cornerCenter(3, 200f, 100f, area))
    }

    @Test fun scalingAboutTheFingersKeepsThePointUnderThem() {
        val c = MiniGestures.scaleAbout(Offset(100f, 100f), Offset(150f, 100f), 2f)
        assertEquals(Offset(50f, 100f), c)
    }
}
