package com.gpo.pilipala.feature.sources

import com.gpo.pilipala.feature.sources.HostAction.Release
import com.gpo.pilipala.feature.sources.HostAction.ResetDock
import com.gpo.pilipala.feature.sources.PlayerSlot.YouTube
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerHostMachineTest {
    private val a = PlayerSlot.Bili(Any())
    private val b = PlayerSlot.Bili(Any())

    @Test fun leavingAPageDocksIt() {
        val m = PlayerHostMachine()
        m.opened(a)
        assertEquals(emptyList<HostAction>(), m.dock(a))
        assertEquals(a, m.docked)
    }

    @Test fun openingAnotherBiliVideoReleasesTheDockedOne() {
        val m = PlayerHostMachine()
        m.opened(a); m.dock(a)
        assertEquals(listOf(ResetDock, Release(a)), m.opened(b))
        assertNull(m.docked)
    }

    @Test fun takingTheDockedOneBackReleasesNothing() {
        val m = PlayerHostMachine()
        m.opened(a); m.dock(a)
        assertEquals(listOf(ResetDock), m.opened(a))
        assertNull(m.docked)
    }

    @Test fun aPageCoveredByTheNextVideoDoesNotDock() {
        val m = PlayerHostMachine()
        m.opened(a)
        m.opened(b) // the next video's page composes before the covered page leaves
        assertNull(m.dock(a))
        assertNull(m.docked)
    }

    @Test fun coveredPageLeavingFirstIsReleasedByTheNextPage() {
        val m = PlayerHostMachine()
        m.opened(a)
        m.dock(a) // the covered page left before the next page composed
        assertEquals(listOf(ResetDock, Release(a)), m.opened(b))
    }

    @Test fun biliDockedThenYoutubeOpens() {
        val m = PlayerHostMachine()
        m.opened(a); m.dock(a)
        assertEquals(listOf(ResetDock, Release(a)), m.opened(YouTube))
        assertEquals(YouTube, m.front)
    }

    @Test fun youtubeDockedThenBiliOpens() {
        val m = PlayerHostMachine()
        m.opened(YouTube); m.dock(YouTube)
        assertEquals(listOf(ResetDock, Release(YouTube)), m.opened(a))
    }

    @Test fun youtubeOpenOverABiliPageThenBiliOpensClosesYoutube() {
        val m = PlayerHostMachine()
        m.opened(YouTube)
        assertEquals(listOf(ResetDock, Release(YouTube)), m.opened(a))
    }

    @Test fun biliPageUnderYoutubeDoesNotDockWhenLeft() {
        val m = PlayerHostMachine()
        m.opened(a)
        m.opened(YouTube) // a page stays under YouTube's player
        m.dock(YouTube)
        assertNull(m.dock(a))
        assertEquals(YouTube, m.docked)
    }

    @Test fun playingAPageReleasesTheDockedYoutube() {
        val m = PlayerHostMachine()
        m.opened(a)
        m.opened(YouTube); m.dock(YouTube)
        assertEquals(listOf(Release(YouTube)), m.started(a))
        assertNull(m.docked)
        assertEquals(a, m.front)
    }

    @Test fun playingFromTheMiniBarChangesNothing() {
        val m = PlayerHostMachine()
        m.opened(a); m.dock(a)
        assertEquals(emptyList<HostAction>(), m.started(a))
        assertEquals(a, m.docked)
    }

    @Test fun youtubeReopenedFromItsBar() {
        val m = PlayerHostMachine()
        m.opened(YouTube); m.dock(YouTube)
        assertEquals(listOf(ResetDock), m.opened(YouTube))
        assertEquals(emptyList<HostAction>(), m.dock(YouTube)!!)
    }

    @Test fun releasedClearsBoth() {
        val m = PlayerHostMachine()
        m.opened(a); m.dock(a)
        m.released(a)
        assertNull(m.docked)
        m.opened(b); m.released(b)
        assertNull(m.front)
    }

    @Test fun sameControllerIsTheSameSlot() {
        val p = Any()
        assertEquals(PlayerSlot.Bili(p), PlayerSlot.Bili(p))
    }

    @Test fun openedFromTheWindowGoesBackToIt() {
        val m = PlayerHostMachine()
        m.opened(a); m.dock(a)
        m.openingFromDock("window, bottom right")
        m.opened(a) // the page takes the docked player back
        assertEquals(listOf<HostAction>(HostAction.Restore("window, bottom right")), m.dock(a))
        // Once only: the next time it docks as the plain bar.
        m.opened(a)
        assertEquals(emptyList<HostAction>(), m.dock(a))
    }

    @Test fun youtubeOpenedFromItsWindowGoesBackToIt() {
        val m = PlayerHostMachine()
        m.opened(YouTube); m.dock(YouTube)
        m.openingFromDock("circle")
        m.opened(YouTube)
        assertEquals(listOf<HostAction>(HostAction.Restore("circle")), m.dock(YouTube))
    }

    @Test fun aFreshOpenFromACardDocksAsTheBar() {
        val m = PlayerHostMachine()
        m.opened(a); m.dock(a)
        // No tap on the docked picture: a card opened the same video.
        m.opened(a)
        assertEquals(emptyList<HostAction>(), m.dock(a))
    }

    @Test fun anotherVideoForgetsTheForm() {
        val m = PlayerHostMachine()
        m.opened(a); m.dock(a)
        m.openingFromDock("window")
        m.opened(b) // a different video opened meanwhile
        assertNull(m.remembered)
        assertEquals(emptyList<HostAction>(), m.dock(b))
    }

    @Test fun releasingForgetsTheForm() {
        val m = PlayerHostMachine()
        m.opened(a); m.dock(a)
        m.openingFromDock("window")
        m.opened(a)
        m.released(a)
        assertNull(m.remembered)
    }
}
