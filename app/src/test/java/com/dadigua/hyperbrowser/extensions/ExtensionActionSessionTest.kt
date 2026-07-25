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
        assertTrue(shouldAcceptExtensionAction(Any(), null))
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

    @Test
    fun rejectsTabUpdatesThatTheHostCannotRepresent() {
        assertTrue(supportsExtensionTabUpdate(null, null, null))
        assertFalse(supportsExtensionTabUpdate(true, null, null))
        assertFalse(supportsExtensionTabUpdate(null, true, null))
        assertFalse(supportsExtensionTabUpdate(null, null, false))
    }

    @Test
    fun acceptsOnlyDefaultNonSpecialExtensionTabs() {
        assertTrue(supportsExtensionTabCreate(null, null, null, null))
        assertTrue(supportsExtensionTabCreate("firefox-default", false, false, false))
        assertFalse(supportsExtensionTabCreate("firefox-container-1", false, false, false))
        assertFalse(supportsExtensionTabCreate(null, true, false, false))
        assertFalse(supportsExtensionTabCreate(null, false, true, false))
        assertFalse(supportsExtensionTabCreate(null, false, false, true))
    }
}
