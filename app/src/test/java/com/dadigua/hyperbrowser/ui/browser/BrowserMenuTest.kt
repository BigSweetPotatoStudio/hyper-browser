package com.dadigua.hyperbrowser.ui.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserMenuTest {
    @Test
    fun manageExtensionsShownWhenInstalledExtensionsAreDisabled() {
        assertTrue(shouldShowManageExtensionsEmptyState(enabledCount = 0, installedCount = 1))
    }

    @Test
    fun manageExtensionsHiddenWhenNoExtensionsAreInstalled() {
        assertFalse(shouldShowManageExtensionsEmptyState(enabledCount = 0, installedCount = 0))
    }

    @Test
    fun manageExtensionsEmptyStateNotUsedWhenExtensionsAreEnabled() {
        assertFalse(shouldShowManageExtensionsEmptyState(enabledCount = 1, installedCount = 1))
    }
}
