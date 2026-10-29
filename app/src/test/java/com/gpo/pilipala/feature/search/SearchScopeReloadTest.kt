package com.gpo.pilipala.feature.search

import com.gpo.pilipala.core.source.VideoSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchScopeReloadTest {
    @Test fun comingBackFromAVideoKeepsTheResults() {
        // The page composes again with the same scope it loaded with.
        assertFalse(scopeReloads(was = null, now = null, hasResults = true))
        assertFalse(scopeReloads(was = VideoSource.YouTube, now = VideoSource.YouTube, hasResults = true))
    }

    @Test fun theFirstScopeSeenDoesNotReload() {
        assertFalse(scopeReloads(was = ScopeUnset, now = null, hasResults = true))
    }

    @Test fun aRealChangeOfScopeReloads() {
        assertTrue(scopeReloads(was = null, now = VideoSource.Bilibili, hasResults = true))
        assertTrue(scopeReloads(was = VideoSource.Bilibili, now = null, hasResults = true))
        // Nothing loaded yet: the first load picks the scope up anyway.
        assertFalse(scopeReloads(was = null, now = VideoSource.Bilibili, hasResults = false))
    }
}
