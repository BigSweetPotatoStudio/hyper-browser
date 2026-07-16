package com.dadigua.hyperbrowser.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class FindInPageBarTest {
    @Test
    fun blankQueryHasNoResultLabel() {
        assertEquals("", findInPageResultLabel(FindInPageUiState(), "No matches"))
    }

    @Test
    fun searchingQueryShowsPendingState() {
        val state = FindInPageUiState(query = "browser", searching = true)

        assertEquals("…", findInPageResultLabel(state, "No matches"))
    }

    @Test
    fun missingQueryShowsNoMatches() {
        val state = FindInPageUiState(query = "missing", found = false)

        assertEquals("No matches", findInPageResultLabel(state, "No matches"))
    }

    @Test
    fun foundQueryShowsCurrentAndTotal() {
        val state = FindInPageUiState(query = "browser", found = true, current = 2, total = 5)

        assertEquals("2/5", findInPageResultLabel(state, "No matches"))
    }

    @Test
    fun unknownTotalStillShowsCurrentMatch() {
        val state = FindInPageUiState(query = "browser", found = true, current = 3, total = -1)

        assertEquals("3", findInPageResultLabel(state, "No matches"))
    }
}
