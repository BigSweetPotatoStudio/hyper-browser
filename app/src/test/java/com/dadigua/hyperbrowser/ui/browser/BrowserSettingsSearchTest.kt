package com.dadigua.hyperbrowser.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSettingsSearchTest {
    @Test
    fun blankQueryKeepsAllSections() {
        val sections = listOf(
            SettingsSectionInfo("toolbar", listOf("Toolbar position")),
            SettingsSectionInfo("display", listOf("Website display mode"))
        )

        assertEquals(sections, filterSettingsSections(sections, "   "))
    }

    @Test
    fun optionTermsMatchEvenWhenTheyAreNotCurrentValues() {
        val sections = listOf(
            SettingsSectionInfo("toolbar", listOf("Toolbar position", "Top", "Bottom", "Floating dot")),
            SettingsSectionInfo("display", listOf("Website display mode", "Mobile", "Tablet", "Desktop"))
        )

        assertEquals(
            listOf("toolbar"),
            filterSettingsSections(sections, "bottom").map { it.key }
        )
        assertEquals(
            listOf("display"),
            filterSettingsSections(sections, "desktop").map { it.key }
        )
    }

    @Test
    fun queryMatchingIgnoresCaseAndOuterWhitespace() {
        assertTrue(
            settingsSectionMatchesQuery(
                terms = listOf("New tab behavior", "Open in current tab"),
                query = "  CURRENT TAB  "
            )
        )
    }

    @Test
    fun unrelatedQueryDoesNotMatch() {
        assertFalse(
            settingsSectionMatchesQuery(
                terms = listOf("Privacy protection", "Strict", "Tracking"),
                query = "download"
            )
        )
    }
}
