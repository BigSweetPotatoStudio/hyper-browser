package com.dadigua.hyperbrowser.ui.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class PendingSwipeCloseTokenTest {
    private val firstToken = UUID.fromString("10000000-0000-0000-0000-000000000001")
    private val secondToken = UUID.fromString("20000000-0000-0000-0000-000000000002")

    @Test
    fun matchingTimerCanFinalizeCurrentPendingClose() {
        assertTrue(isCurrentPendingSwipeClose(firstToken, firstToken))
    }

    @Test
    fun staleTimerCannotFinalizeNewPendingClose() {
        assertFalse(isCurrentPendingSwipeClose(firstToken, secondToken))
        assertFalse(isCurrentPendingSwipeClose(firstToken, null))
    }
}
