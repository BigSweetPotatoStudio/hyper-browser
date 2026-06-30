package com.dadigua.hyperbrowser.ui.browser

import com.dadigua.hyperbrowser.data.InstalledExtensionState
import com.dadigua.hyperbrowser.extensions.AmoAddonListing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionSearchStateTest {
    @Test
    fun startingSearchClearsVisibleResults() {
        val state = extensionSearchStartedState("Searching AMO...")

        assertTrue(state.results.isEmpty())
        assertEquals("Searching AMO...", state.message)
    }

    @Test
    fun failedSearchClearsVisibleResults() {
        val state = extensionSearchFailedState("AMO search failed.")

        assertTrue(state.results.isEmpty())
        assertEquals("AMO search failed.", state.message)
    }

    @Test
    fun emptySuccessShowsNoAndroidAddonsMessage() {
        val state = extensionSearchCompletedState(
            results = emptyList(),
            installed = emptyList(),
            noAndroidAddonsMessage = "No Android add-ons found.",
            allMatchesInstalledMessage = "All matches installed.",
            foundWithInstalledMessage = ::foundMessage
        )

        assertTrue(state.results.isEmpty())
        assertEquals("No Android add-ons found.", state.message)
    }

    @Test
    fun allInstalledSuccessShowsInstalledMessage() {
        val results = listOf(addon("first"), addon("second"))

        val state = extensionSearchCompletedState(
            results = results,
            installed = listOf(installed("first"), installed("second")),
            noAndroidAddonsMessage = "No Android add-ons found.",
            allMatchesInstalledMessage = "All matches installed.",
            foundWithInstalledMessage = ::foundMessage
        )

        assertEquals(results, state.results)
        assertEquals("All matches installed.", state.message)
    }

    @Test
    fun mixedInstalledSuccessShowsAvailableAndInstalledCounts() {
        val results = listOf(addon("first"), addon("second"))

        val state = extensionSearchCompletedState(
            results = results,
            installed = listOf(installed("first")),
            noAndroidAddonsMessage = "No Android add-ons found.",
            allMatchesInstalledMessage = "All matches installed.",
            foundWithInstalledMessage = ::foundMessage
        )

        assertEquals(results, state.results)
        assertEquals("1 add-ons found, 1 already installed", state.message)
    }

    @Test
    fun allInstallableSuccessNeedsNoMessage() {
        val results = listOf(addon("first"))

        val state = extensionSearchCompletedState(
            results = results,
            installed = emptyList(),
            noAndroidAddonsMessage = "No Android add-ons found.",
            allMatchesInstalledMessage = "All matches installed.",
            foundWithInstalledMessage = ::foundMessage
        )

        assertEquals(results, state.results)
        assertNull(state.message)
    }

    private fun foundMessage(installableCount: Int, installedMatches: Int): String =
        "$installableCount add-ons found, $installedMatches already installed"

    private fun addon(guid: String): AmoAddonListing =
        AmoAddonListing(
            name = guid,
            slug = guid,
            guid = guid,
            version = "1.0",
            userCount = 1,
            xpiUrl = "https://example.com/$guid.xpi",
            permissions = emptyList(),
            minAndroidVersion = null,
            maxAndroidVersion = null
        )

    private fun installed(guid: String): InstalledExtensionState =
        InstalledExtensionState(
            guid = guid,
            name = guid,
            version = "1.0",
            enabled = true,
            source = "test",
            permissionsSnapshot = "",
            xpiPath = null,
            installedAt = 1L
        )
}
