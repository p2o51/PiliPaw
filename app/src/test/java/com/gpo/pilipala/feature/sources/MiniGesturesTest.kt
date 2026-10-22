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
