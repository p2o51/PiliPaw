package com.gpo.pilipala.feature.listen

import org.junit.Assert.assertEquals
import org.junit.Test

class PinchProgressTest {
    @Test
    fun noSpreadYetIsNoProgress() {
        assertEquals(0f, pinchProgress(0f, 120f, outward = false))
        assertEquals(0f, pinchProgress(0f, 120f, outward = true))
    }

    @Test
    fun pinchInClosesAtSixtyPercentLost() {
        assertEquals(0.5f, pinchProgress(100f, 70f, outward = false), 1e-4f)
        assertEquals(1f, pinchProgress(100f, 40f, outward = false))
        // Spreading during a pinch in is no progress, not negative.
        assertEquals(0f, pinchProgress(100f, 150f, outward = false))
    }

    @Test
    fun spreadOutOpensAtTwiceAndAFifth() {
        assertEquals(0.5f, pinchProgress(100f, 160f, outward = true), 1e-4f)
        assertEquals(1f, pinchProgress(100f, 300f, outward = true))
        assertEquals(0f, pinchProgress(100f, 80f, outward = true))
    }
}
