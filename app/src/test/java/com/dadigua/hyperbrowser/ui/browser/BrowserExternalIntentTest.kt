package com.dadigua.hyperbrowser.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserExternalIntentTest {
    @Test
    fun extractFirstHttpUrlReturnsFirstHttpUrl() {
        assertEquals(
            "https://example.com/path?q=1",
            extractFirstHttpUrl("read this https://example.com/path?q=1 and http://later.example")
        )
    }

    @Test
    fun extractFirstHttpUrlTrimsCommonTrailingPunctuation() {
        assertEquals("https://example.com/a", extractFirstHttpUrl("(https://example.com/a)."))
        assertEquals("http://example.com/b", extractFirstHttpUrl("\"http://example.com/b\","))
        assertEquals("https://example.com/c", extractFirstHttpUrl("https://example.com/c]"))
        assertEquals("https://example.com/tag", extractFirstHttpUrl("<https://example.com/tag>"))
        assertEquals("https://example.com/news", extractFirstHttpUrl("https://example.com/news\u3002"))
        assertEquals("https://example.com/share", extractFirstHttpUrl("See https://example.com/share\uFF09\u3002"))
        assertEquals("https://example.com/quote", extractFirstHttpUrl("\u201CHere: https://example.com/quote\u201D"))
    }

    @Test
    fun extractFirstHttpUrlIgnoresNonHttpText() {
        assertNull(extractFirstHttpUrl("ftp://example.com https example.com"))
        assertNull(extractFirstHttpUrl(""))
    }
}
