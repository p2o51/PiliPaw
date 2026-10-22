package com.gpo.pilipala.feature.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniAxisTest {
    private val d = MiniDims(circle = 48f, row = 100f, detachExtra = 40f)
    private val max = 360f

    private fun rest(raw: Float, v: Float = 0f, canMerge: Boolean = true) = MiniAxis.release(raw, v, d, max, canMerge)

    @Test fun rubberBandsOnlyPastTheEnds() {
        assertEquals(200f, MiniAxis.rubber(200f, 48f, max), 0f)
        assertEquals(max + 30f, MiniAxis.rubber(max + 100f, 48f, max), 0.001f)
        assertEquals(48f - 3f, MiniAxis.rubber(38f, 48f, max), 0.001f)
    }

    @Test fun nearTheBarSpringsBack() {
        assertEquals(MiniRelease.Rest(MiniRest.Row), rest(110f))
        assertEquals(MiniRelease.Rest(MiniRest.Row), rest(127f))
        assertEquals(MiniRelease.Rest(MiniRest.Row), rest(80f))
    }

    @Test fun seventyPercentToDetachMakesAWindowAtLeastDetachSize() {
        assertEquals(MiniRelease.Rest(MiniRest.Window(140f)), rest(129f))
        assertEquals(MiniRelease.Rest(MiniRest.Window(220f)), rest(220f))
    }

    @Test fun aWindowPastTheLargestSpringsBackToIt() {
        assertEquals(MiniRelease.Rest(MiniRest.Window(max)), rest(max + 20f))
    }

    @Test fun farPastTheLargestNeverOpensTheVideo() {
        assertEquals(MiniRelease.Rest(MiniRest.Window(max)), rest(max + 37f))
        assertEquals(MiniRelease.Rest(MiniRest.Window(max)), rest(max * 3f, v = 5000f))
    }

    @Test fun pastHalfwayDownMerges() {
        assertEquals(MiniRelease.Rest(MiniRest.Merged), rest(70f))
        assertEquals(MiniRelease.Rest(MiniRest.Row), rest(70f, canMerge = false))
    }

    @Test fun aFlickCarries() {
        // 120 alone springs back; spreading fast carries it past 70% of the way.
        assertEquals(MiniRelease.Rest(MiniRest.Window(140f)), rest(120f, v = 200f))
        assertEquals(MiniRelease.Rest(MiniRest.Merged), rest(80f, v = -200f))
    }

    @Test fun mergeAndDetachFractions() {
        assertEquals(0f, MiniAxis.merge(100f, d), 0f)
        assertEquals(1f, MiniAxis.merge(48f, d), 0f)
        assertEquals(0.5f, MiniAxis.detach(120f, d), 0.001f)
    }

    @Test fun pictureFollowsTheVideosShape() {
        assertEquals(160f to 90f, MiniAxis.pictureSize(160f, 16f / 9f))
        assertEquals(90f to 160f, MiniAxis.pictureSize(160f, 9f / 16f))
        // A vertical video's largest window is bounded by the height.
        assertEquals(500f, MiniAxis.maxAxis(376f, 500f, 9f / 16f), 0.001f)
        assertEquals(376f, MiniAxis.maxAxis(376f, 500f, 16f / 9f), 0.001f)
    }

    @Test fun docking() {
        val line = 1000f
        // Dropped with its bottom near the nav bar.
        assertTrue(MiniAxis.docks(bottom = 980f, projectedBottom = 980f, velocityY = 0f, dockLine = line, near = 24f, past = 30f, upFast = 200f))
        // Same place but thrown upward fast: it flies off instead.
        assertFalse(MiniAxis.docks(980f, 900f, -400f, line, 24f, 30f, 200f))
        // Far above but thrown down past the line.
        assertTrue(MiniAxis.docks(600f, 1040f, 2000f, line, 24f, 30f, 200f))
        assertFalse(MiniAxis.docks(600f, 700f, 100f, line, 24f, 30f, 200f))
    }
}
