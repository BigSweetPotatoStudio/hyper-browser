package com.dadigua.hyperbrowser.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserMediaServiceRequestStateTest {
    @Test
    fun duplicateRunRequestsStartServiceOnlyOnceWhileStartIsPending() {
        val state = BrowserMediaServiceRequestState()

        assertTrue(state.requestRun().startService)
        assertFalse(state.requestRun().startService)
        assertTrue(state.desiredState().running)
    }

    @Test
    fun stopBeforeAttachIsReconciledAfterForegroundServiceStarts() {
        val state = BrowserMediaServiceRequestState()

        assertTrue(state.requestRun().startService)
        state.requestStop(removeNotification = true)
        state.onServiceAttached()

        assertFalse(state.desiredState().running)
        assertTrue(state.desiredState().removeNotification)
    }

    @Test
    fun resumedPlaybackCancelsPendingStopBeforeServiceAttaches() {
        val state = BrowserMediaServiceRequestState()

        assertTrue(state.requestRun().startService)
        state.requestStop(removeNotification = false)
        assertFalse(state.requestRun().startService)
        state.onServiceAttached()

        assertTrue(state.desiredState().running)
        assertFalse(state.desiredState().removeNotification)
    }

    @Test
    fun activeServiceIsRestartedAfterUnexpectedDetach() {
        val state = BrowserMediaServiceRequestState()

        state.requestRun()
        state.onServiceAttached()

        assertTrue(state.onServiceDetached())
        assertFalse(state.onServiceDetached())
    }
}
