package com.dadigua.hyperbrowser.ui.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserEngineSessionStateTest {
    @Test
    fun engineSessionStateIsPersistedOnlyForWebUrls() {
        assertTrue(shouldPersistEngineSessionStateUri("https://example.com/page"))
        assertTrue(shouldPersistEngineSessionStateUri("http://example.com"))

        assertFalse(shouldPersistEngineSessionStateUri("about:blank"))
        assertFalse(shouldPersistEngineSessionStateUri("hyper://home"))
        assertFalse(shouldPersistEngineSessionStateUri("resource://android/assets/home.html"))
        assertFalse(shouldPersistEngineSessionStateUri("moz-extension://abc/dashboard.html"))
        assertFalse(shouldPersistEngineSessionStateUri(null))
    }

    @Test
    fun privateTabsAreNotPersistedForRestore() {
        assertTrue(shouldPersistBrowserTab(privateMode = false))
        assertFalse(shouldPersistBrowserTab(privateMode = true))
    }

    @Test
    fun restorableLabelIsShownOnlyForUnloadedTabsWithEngineState() {
        assertTrue(shouldShowRestorableLabel(hasController = false, hasEngineState = true))

        assertFalse(shouldShowRestorableLabel(hasController = false, hasEngineState = false))
        assertFalse(shouldShowRestorableLabel(hasController = true, hasEngineState = true))
        assertFalse(shouldShowRestorableLabel(hasController = true, hasEngineState = false))
    }
}
