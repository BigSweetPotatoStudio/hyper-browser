package com.dadigua.hyperbrowser.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookmarkEditPayloadTest {
    @Test
    fun trimsTitleAndUrlBeforeSaving() {
        val payload = bookmarkEditPayload("  Example  ", "  https://example.com  ")

        assertEquals(BookmarkEditPayload("Example", "https://example.com"), payload)
    }

    @Test
    fun blankUrlCannotBeSaved() {
        assertNull(bookmarkEditPayload("Example", "   "))
    }
}
