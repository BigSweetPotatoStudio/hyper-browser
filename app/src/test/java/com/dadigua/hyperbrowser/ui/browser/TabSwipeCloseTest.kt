package com.dadigua.hyperbrowser.ui.browser

import androidx.compose.material3.SwipeToDismissBoxValue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabSwipeCloseTest {
    @Test
    fun settledSwipeKeepsTabOpen() {
        assertFalse(shouldCloseTabAfterSwipe(SwipeToDismissBoxValue.Settled))
    }

    @Test
    fun completedSwipeClosesTabInEitherDirection() {
        assertTrue(shouldCloseTabAfterSwipe(SwipeToDismissBoxValue.StartToEnd))
        assertTrue(shouldCloseTabAfterSwipe(SwipeToDismissBoxValue.EndToStart))
    }
}
