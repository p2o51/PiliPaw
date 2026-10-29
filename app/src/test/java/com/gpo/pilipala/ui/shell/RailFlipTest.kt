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

class RailVisibilityTest {
    private fun of(top: Boolean, beneath: Boolean, phase: com.gpo.pilipala.navigation.transition.BackPhase) = RailVisibility.of(top, beneath, phase)

    @Test fun inOnlyWhenTheBackLandsOnTheShell() {
        val p = com.gpo.pilipala.navigation.transition.BackPhase.entries.associateBy { it.name }
        // Shell on top: in, whatever runs.
        assertEquals(RailVisibility.In, of(top = true, beneath = false, p.getValue("Idle")))
        // A video over the shell: out; a back gesture to the shell follows the finger; its commit comes in.
        assertEquals(RailVisibility.Out, of(top = false, beneath = true, p.getValue("Idle")))
        assertEquals(RailVisibility.FollowGesture, of(top = false, beneath = true, p.getValue("Gesture")))
        assertEquals(RailVisibility.In, of(top = false, beneath = true, p.getValue("Committing")))
        // Video over search results over the shell, after the pop (Revealing): search results is
        // on top and the shell beneath it, but this back lands on search results: out.
        assertEquals(RailVisibility.Out, of(top = false, beneath = true, p.getValue("Revealing")))
        // Before the pop the entry beneath is search results: out all the way.
        assertEquals(RailVisibility.Out, of(top = false, beneath = false, p.getValue("Gesture")))
        assertEquals(RailVisibility.Out, of(top = false, beneath = false, p.getValue("Committing")))
        // Back to the shell, popped: the shell is on top.
        assertEquals(RailVisibility.In, of(top = true, beneath = false, p.getValue("Revealing")))
    }
}
