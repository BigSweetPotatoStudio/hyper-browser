package com.dadigua.hyperbrowser.ui.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserTabFocusRequestTest {
    @Test
    fun currentTabFocusRequestDoesNotSwitchOrDismissNativePanel() {
        assertFalse(
            shouldSwitchToFocusedTab(
                requestedTabId = "current",
                requestedOpenerTabId = null,
                requestedIsPopupSession = false,
                selectedTabId = "current",
                selectedOpenerTabId = null,
                selectedIsPopupSession = false
            )
        )
    }

    @Test
    fun unrelatedBackgroundTabCannotOverrideUserSelection() {
        assertFalse(
            shouldSwitchToFocusedTab(
                requestedTabId = "background",
                requestedOpenerTabId = null,
                requestedIsPopupSession = false,
                selectedTabId = "current",
                selectedOpenerTabId = null,
                selectedIsPopupSession = false
            )
        )
    }

    @Test
    fun popupAndItsOpenerCanStillFocusEachOther() {
        assertTrue(
            shouldSwitchToFocusedTab(
                requestedTabId = "popup",
                requestedOpenerTabId = "opener",
                requestedIsPopupSession = true,
                selectedTabId = "opener",
                selectedOpenerTabId = null,
                selectedIsPopupSession = false
            )
        )
        assertTrue(
            shouldSwitchToFocusedTab(
                requestedTabId = "opener",
                requestedOpenerTabId = null,
                requestedIsPopupSession = false,
                selectedTabId = "popup",
                selectedOpenerTabId = "opener",
                selectedIsPopupSession = true
            )
        )
    }

    @Test
    fun ordinaryBackgroundTabWithOpenerMetadataCannotStealFocus() {
        assertFalse(
            shouldSwitchToFocusedTab(
                requestedTabId = "background-link",
                requestedOpenerTabId = "current",
                requestedIsPopupSession = false,
                selectedTabId = "current",
                selectedOpenerTabId = null,
                selectedIsPopupSession = false
            )
        )
    }
}
