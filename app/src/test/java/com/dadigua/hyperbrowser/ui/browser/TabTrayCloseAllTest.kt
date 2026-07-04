package com.dadigua.hyperbrowser.ui.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabTrayCloseAllTest {
    @Test
    fun closeAllRequiresMoreThanOneTab() {
        assertFalse(canCloseAllTabs(0))
        assertFalse(canCloseAllTabs(1))
        assertTrue(canCloseAllTabs(2))
    }
}
