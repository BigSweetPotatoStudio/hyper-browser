package com.dadigua.hyperbrowser.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddressBarTabSwipeTest {
    @Test
    fun leftToRightLayoutMapsSwipesToAdjacentTabSteps() {
        assertEquals(1, addressBarTabSwipeStep(-72f, 4f, 56f, rightToLeft = false))
        assertEquals(-1, addressBarTabSwipeStep(72f, 4f, 56f, rightToLeft = false))
    }

    @Test
    fun rightToLeftLayoutReversesSwipeSteps() {
        assertEquals(-1, addressBarTabSwipeStep(-72f, 4f, 56f, rightToLeft = true))
        assertEquals(1, addressBarTabSwipeStep(72f, 4f, 56f, rightToLeft = true))
    }

    @Test
    fun shortOrMostlyVerticalDragDoesNotSwitchTabs() {
        assertNull(addressBarTabSwipeStep(-55f, 0f, 56f, rightToLeft = false))
        assertNull(addressBarTabSwipeStep(-72f, 80f, 56f, rightToLeft = false))
    }

    @Test
    fun adjacentTabSelectionStopsAtListEdges() {
        val tabIds = listOf("first", "second", "third")

        assertEquals("third", adjacentTabId(tabIds, "second", 1))
        assertEquals("first", adjacentTabId(tabIds, "second", -1))
        assertNull(adjacentTabId(tabIds, "first", -1))
        assertNull(adjacentTabId(tabIds, "third", 1))
    }

    @Test
    fun adjacentTabSelectionRejectsUnknownTabsAndInvalidSteps() {
        val tabIds = listOf("first", "second")

        assertNull(adjacentTabId(tabIds, "missing", 1))
        assertNull(adjacentTabId(tabIds, "first", 0))
        assertNull(adjacentTabId(emptyList(), "first", 1))
    }
}
