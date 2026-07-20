package com.dadigua.hyperbrowser.extensions

import org.junit.Assert.assertFalse
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
}
