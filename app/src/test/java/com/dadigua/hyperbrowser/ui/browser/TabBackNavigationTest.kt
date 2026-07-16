package com.dadigua.hyperbrowser.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TabBackNavigationTest {
    @Test
    fun returnsExistingOpenerForChildTab() {
        assertEquals(
            "parent",
            openerTabForRootBack(
                currentTabId = "child",
                openerTabId = "parent",
                openTabIds = listOf("parent", "child")
            )
        )
    }

    @Test
    fun ignoresMissingBlankOrSelfOpeners() {
        assertNull(openerTabForRootBack("child", null, listOf("parent", "child")))
        assertNull(openerTabForRootBack("child", " ", listOf("parent", "child")))
        assertNull(openerTabForRootBack("child", "missing", listOf("parent", "child")))
        assertNull(openerTabForRootBack("child", "child", listOf("child")))
    }
}
