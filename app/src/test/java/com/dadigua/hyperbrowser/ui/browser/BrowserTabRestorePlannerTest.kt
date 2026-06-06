package com.dadigua.hyperbrowser.ui.browser

import com.dadigua.hyperbrowser.browser.SavedBrowserTab
import com.dadigua.hyperbrowser.browser.SavedBrowserTabs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserTabRestorePlannerTest {
    @Test
    fun restoreUsesSavedTabsAndSelectedTabWhenNoLaunchUrl() {
        val plan = planBrowserTabRestore(
            savedTabs = SavedBrowserTabs(
                selectedTabId = "tab-2",
                tabs = listOf(
                    savedTab("tab-1", "https://one.example"),
                    savedTab("tab-2", "https://two.example")
                )
            ),
            launchUrl = null,
            fallbackUrl = "hyper://home"
        )

        assertEquals(listOf("tab-1", "tab-2"), plan.tabs.map { it.id })
        assertEquals("tab-2", plan.selectedSavedTabId)
        assertFalse(plan.selectLastTab)
        assertEquals(listOf(false, true), plan.tabs.map { it.loadImmediately })
    }

    @Test
    fun externalLaunchUrlIsAppendedWithoutDroppingSavedTabs() {
        val plan = planBrowserTabRestore(
            savedTabs = SavedBrowserTabs(
                selectedTabId = "tab-1",
                tabs = listOf(savedTab("tab-1", "https://saved.example"))
            ),
            launchUrl = "https://external.example",
            fallbackUrl = "hyper://home"
        )

        assertEquals(
            listOf("https://saved.example", "https://external.example"),
            plan.tabs.map { it.url }
        )
        assertEquals("tab-1", plan.selectedSavedTabId)
        assertTrue(plan.selectLastTab)
        assertNull(plan.tabs.last().id)
        assertEquals(listOf(false, true), plan.tabs.map { it.loadImmediately })
    }

    @Test
    fun externalLaunchUrlIsOnlyTabWhenNoSavedTabsExist() {
        val plan = planBrowserTabRestore(
            savedTabs = SavedBrowserTabs(selectedTabId = null, tabs = emptyList()),
            launchUrl = "https://external.example",
            fallbackUrl = "hyper://home"
        )

        assertEquals(listOf("https://external.example"), plan.tabs.map { it.url })
        assertTrue(plan.selectLastTab)
        assertEquals(listOf(true), plan.tabs.map { it.loadImmediately })
    }

    @Test
    fun fallbackHomeIsUsedWhenNoSavedTabsOrLaunchUrlExist() {
        val plan = planBrowserTabRestore(
            savedTabs = SavedBrowserTabs(selectedTabId = null, tabs = emptyList()),
            launchUrl = null,
            fallbackUrl = "hyper://home"
        )

        assertEquals(listOf("hyper://home"), plan.tabs.map { it.url })
        assertFalse(plan.selectLastTab)
        assertEquals(listOf(true), plan.tabs.map { it.loadImmediately })
    }

    @Test
    fun invalidSavedSelectedIdIsIgnored() {
        val plan = planBrowserTabRestore(
            savedTabs = SavedBrowserTabs(
                selectedTabId = "missing",
                tabs = listOf(savedTab("tab-1", "https://saved.example"))
            ),
            launchUrl = null,
            fallbackUrl = "hyper://home"
        )

        assertNull(plan.selectedSavedTabId)
        assertEquals(listOf("tab-1"), plan.tabs.map { it.id })
        assertEquals(listOf(true), plan.tabs.map { it.loadImmediately })
    }

    @Test
    fun transientSavedUrlsAreSkippedDuringRestore() {
        val plan = planBrowserTabRestore(
            savedTabs = SavedBrowserTabs(
                selectedTabId = "resource",
                tabs = listOf(
                    savedTab("resource", "resource://android/assets/home.html"),
                    savedTab("real", "https://saved.example")
                )
            ),
            launchUrl = null,
            fallbackUrl = "hyper://home"
        )

        assertEquals(listOf("real"), plan.tabs.map { it.id })
        assertNull(plan.selectedSavedTabId)
    }

    private fun savedTab(id: String, url: String): SavedBrowserTab =
        SavedBrowserTab(
            id = id,
            title = url,
            url = url,
            input = url,
            iconPath = null
        )
}
