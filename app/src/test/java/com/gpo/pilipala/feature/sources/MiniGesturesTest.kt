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

    @Test fun aWindowSwipedOrThrownSidewaysOnlyMovesToACorner() {
        // A sideways drag on a window moves it, it never swipes it away.
        assertEquals(MiniDragIntent.MoveWindow, MiniGestures.dragIntent(Offset(60f, 2f), windowed = true, merged = false, onPicture = true))
        assertEquals(MiniDragIntent.MoveWindow, MiniGestures.dragIntent(Offset(-60f, 2f), windowed = true, merged = false, onPicture = false))
        val area = Rect(0f, 0f, 400f, 800f)
        // Bottom left, thrown hard right (far off the screen): the bottom right corner.
        assertEquals(3, MiniGestures.flungCorner(Offset(50f, 775f), Offset(9000f, 0f), 100f, 50f, area, bias = 625f))
        // Bottom right, thrown hard left: the bottom left corner.
        assertEquals(2, MiniGestures.flungCorner(Offset(350f, 775f), Offset(-9000f, 100f), 100f, 50f, area, bias = 625f))
        // Top right, a light flick up and left that falls short still picks the top left.
        assertEquals(0, MiniGestures.flungCorner(Offset(350f, 25f), Offset(-700f, -50f), 100f, 50f, area, bias = 625f))
        // Let go still: where it is.
        assertEquals(3, MiniGestures.flungCorner(Offset(330f, 760f), Offset.Zero, 100f, 50f, area, bias = 625f))
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
        // The merged circle: sideways closes it, up or down is the page's scroll.
        assertEquals(MiniDragIntent.DockSwipe, intent(12f, 0f, merged = true))
        assertEquals(MiniDragIntent.DockSwipe, intent(-12f, 4f, merged = true, onPicture = false))
        assertEquals(MiniDragIntent.None, intent(2f, -12f, merged = true))
    }

    @Test fun theMergedCircleSwipeArmsAtItsRange() {
        assertEquals(0.5f, MiniGestures.dockSwipeProgress(-28f, 56f), 0f)
        assertEquals(1f, MiniGestures.dockSwipeProgress(56f, 56f), 0f)
        assertEquals(1.5f, MiniGestures.dockSwipeProgress(400f, 56f), 0f)
    }

    @Test fun theKeysReachOutwardOnly() {
        val play = Rect(108f, 168f, 140f, 200f)
        val close = Rect(260f, 168f, 292f, 200f)
        assertEquals(MiniKey.Close, MiniGestures.keyAt(Offset(276f, 184f), play, close, 8f))
        // Just outside the window's edge: still the key.
        assertEquals(MiniKey.Close, MiniGestures.keyAt(Offset(298f, 205f), play, close, 8f))
        assertEquals(MiniKey.Play, MiniGestures.keyAt(Offset(102f, 205f), play, close, 8f))
        // Just inside the picture past the key: not the key.
        assertEquals(null, MiniGestures.keyAt(Offset(255f, 184f), play, close, 8f))
        assertEquals(null, MiniGestures.keyAt(Offset(276f, 163f), play, close, 8f))
    }

    @Test fun aDragStartingOnCloseDoesNotClose() {
        assertEquals(false, MiniGestures.keyFires(MiniGestureKind.Drag, MiniKey.Close, 1_000, settling = false))
        assertEquals(false, MiniGestures.keyFires(MiniGestureKind.Pinch, MiniKey.Close, 1_000, settling = false))
        assertEquals(false, MiniGestures.keyFires(MiniGestureKind.None, MiniKey.Close, 1_000, settling = false))
    }

    @Test fun aTapOnCloseCloses() {
        assertEquals(true, MiniGestures.keyFires(MiniGestureKind.Tap, MiniKey.Close, 1_000, settling = false))
        assertEquals(true, MiniGestures.keyFires(MiniGestureKind.Tap, MiniKey.Play, 0, settling = true))
    }

    @Test fun closeIgnoresATapRightAfterTheWindowMoved() {
        assertEquals(false, MiniGestures.keyFires(MiniGestureKind.Tap, MiniKey.Close, 120, settling = false))
        assertEquals(false, MiniGestures.keyFires(MiniGestureKind.Tap, MiniKey.Close, 1_000, settling = true))
    }

    @Test fun popOutThenOneDownwardDragFromTheBottomCornerMerges() {
        // Pulled up off the bar, past halfway: a window.
        val p = MiniGestures.popProgress(60f, 48f)
        assertEquals(1f, p, 0f)
        assertEquals(true, MiniGestures.popKeeps(p))
        // Let go low on the screen: its corner (bottom right) is known at release.
        val area = Rect(12f, 40f, 388f, 700f)
        val corner = MiniGestures.nearestCorner(Offset(300f, 650f), 140f, 79f, area)
        assertEquals(3, corner)
        // Still settling (its live size dipped under the window's): it is a window all the same,
        assertEquals(true, MiniGestures.isWindow(restIsWindow = true, liveWindowed = false))
        // and the very next drag down merges.
        assertEquals(true, MiniGestures.startsMerge(corner, Offset(1f, 12f)))
    }

    @Test fun aPopOutDraggedBackDownGoesBackIntoTheBar() {
        assertEquals(0.25f, MiniGestures.popProgress(12f, 48f), 0f)
        assertEquals(false, MiniGestures.popKeeps(MiniGestures.popProgress(12f, 48f)))
        assertEquals(0f, MiniGestures.popProgress(-20f, 48f), 0f)
    }

    @Test fun onlyADownwardDragFromABottomCornerMerges() {
        assertEquals(true, MiniGestures.startsMerge(3, Offset(2f, 12f)))
        assertEquals(true, MiniGestures.startsMerge(2, Offset(-3f, 10f)))
        // From a top corner a downward drag only moves the window.
        assertEquals(false, MiniGestures.startsMerge(1, Offset(0f, 12f)))
        assertEquals(false, MiniGestures.startsMerge(0, Offset(0f, 12f)))
        // Sideways or upward from a bottom corner: a plain drag.
        assertEquals(false, MiniGestures.startsMerge(3, Offset(12f, 4f)))
        assertEquals(false, MiniGestures.startsMerge(3, Offset(0f, -12f)))
        assertEquals(false, MiniGestures.startsMerge(null, Offset(0f, 12f)))
    }

    @Test fun theMergeFollowsTheFingerAndReverses() {
        assertEquals(0.5f, MiniGestures.mergeProgress(50f, 100f), 0f)
        assertEquals(1f, MiniGestures.mergeProgress(250f, 100f), 0f)
        // Dragged back up past where it started: undone.
        assertEquals(0f, MiniGestures.mergeProgress(-30f, 100f), 0f)
    }

    @Test fun letGoItDocksPastFortyPercentOrMovingDown() {
        assertEquals(true, MiniGestures.mergeCompletes(0.45f, 0f, 500f))
        assertEquals(false, MiniGestures.mergeCompletes(0.3f, 100f, 500f))
        assertEquals(true, MiniGestures.mergeCompletes(0.2f, 600f, 500f))
        // Moving up at the end: back to the corner.
        assertEquals(false, MiniGestures.mergeCompletes(0.35f, -800f, 500f))
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
