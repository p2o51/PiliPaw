package com.gpo.pilipala.ui.shell

import com.gpo.pilipala.ui.components.sideRailFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RailFlipTest {
    @Test fun releasedPastTheMidlineItFlips() {
        assertTrue(RailFlip.landsRight(startRight = false, position = 0.55f, velocityPxPerMs = 0f))
        assertFalse(RailFlip.landsRight(startRight = true, position = 0.45f, velocityPxPerMs = 0f))
    }

    @Test fun shortOfTheMidlineItSpringsBack() {
        assertFalse(RailFlip.landsRight(startRight = false, position = 0.3f, velocityPxPerMs = 0.2f))
        assertTrue(RailFlip.landsRight(startRight = true, position = 0.7f, velocityPxPerMs = -0.2f))
    }

    @Test fun aThrowTowardTheOtherEdgeFlipsFromAnywhere() {
        assertTrue(RailFlip.landsRight(startRight = false, position = 0.1f, velocityPxPerMs = 0.8f))
        assertFalse(RailFlip.landsRight(startRight = true, position = 0.9f, velocityPxPerMs = -0.8f))
        // A throw back toward its own edge does not.
        assertFalse(RailFlip.landsRight(startRight = false, position = 0.3f, velocityPxPerMs = -2f))
    }

    @Test fun theContentGivesWayAsTheRailCrosses() {
        assertEquals(88f, RailFlip.contentLeft(0f, 88f), 0f)
        assertEquals(44f, RailFlip.contentLeft(0.5f, 88f), 0f)
        assertEquals(0f, RailFlip.contentLeft(1f, 88f), 0f)
    }

    @Test fun theRailIsForLargeWindowsOnly() {
        assertTrue(sideRailFor(1280, 800))
        assertTrue(sideRailFor(840, 1200))
        assertFalse(sideRailFor(800, 1280))
        assertFalse(sideRailFor(412, 915))
        // A phone on its side keeps the bottom bar.
        assertFalse(sideRailFor(915, 412))
        assertTrue(sideRailFor(700, 600))
    }
}
