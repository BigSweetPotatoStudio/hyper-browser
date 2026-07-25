package com.dadigua.hyperbrowser.extensions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionActionSessionTest {
    @Test
    fun acceptsGlobalAndCurrentSessionActions() {
        val activeSession = Any()

        assertTrue(shouldAcceptExtensionAction(null, activeSession))
        assertTrue(shouldAcceptExtensionAction(activeSession, activeSession))
    }

    @Test
    fun rejectsActionsFromBackgroundOrDestroyedSessions() {
        assertFalse(shouldAcceptExtensionAction(Any(), Any()))
    }

    @Test
    fun extensionTabsAreActiveUnlessExplicitlyCreatedInBackground() {
        assertTrue(shouldActivateExtensionTab(null))
        assertTrue(shouldActivateExtensionTab(true))
        assertFalse(shouldActivateExtensionTab(false))
    }

    @Test
    fun extensionTabIndexIsClampedToTheCurrentTabList() {
        assertEquals(3, extensionTabInsertionIndex(null, 3))
        assertEquals(0, extensionTabInsertionIndex(-1, 3))
        assertEquals(1, extensionTabInsertionIndex(1, 3))
        assertEquals(3, extensionTabInsertionIndex(8, 3))
    }
}
