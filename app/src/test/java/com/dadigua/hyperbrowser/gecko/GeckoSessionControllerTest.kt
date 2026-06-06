package com.dadigua.hyperbrowser.gecko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeckoSessionControllerTest {
    @Test
    fun normalizeUrlKeepsInternalRoutes() {
        assertEquals(
            GeckoSessionController.HOME_URL,
            GeckoSessionController.normalizeUrl(GeckoSessionController.HOME_URL)
        )
        assertEquals(
            GeckoSessionController.BOOKMARKS_URL,
            GeckoSessionController.normalizeUrl(GeckoSessionController.BOOKMARKS_URL)
        )
        assertEquals(
            GeckoSessionController.HISTORY_URL,
            GeckoSessionController.normalizeUrl(GeckoSessionController.HISTORY_URL)
        )
    }

    @Test
    fun normalizeUrlKeepsLoadableUrls() {
        assertEquals(
            "https://example.com/path",
            GeckoSessionController.normalizeUrl("https://example.com/path")
        )
        assertEquals(
            "http://example.com",
            GeckoSessionController.normalizeUrl("http://example.com")
        )
        assertEquals(
            "moz-extension://abc/dashboard.html",
            GeckoSessionController.normalizeUrl("moz-extension://abc/dashboard.html")
        )
    }

    @Test
    fun normalizeUrlAddsHttpsForDomainLikeInput() {
        assertEquals(
            "https://example.com",
            GeckoSessionController.normalizeUrl("example.com")
        )
        assertEquals(
            "https://sub.example.com/path",
            GeckoSessionController.normalizeUrl(" sub.example.com/path ")
        )
    }

    @Test
    fun normalizeUrlBuildsDefaultSearchUrlForPlainText() {
        assertEquals(
            "https://www.google.com/search?q=hello+world",
            GeckoSessionController.normalizeUrl("hello world")
        )
    }

    @Test
    fun normalizeUrlUsesCustomSearchTemplateWhenValid() {
        assertEquals(
            "https://search.example/?q=hello+world",
            GeckoSessionController.normalizeUrl(
                input = "hello world",
                searchUrlTemplate = "https://search.example/?q=%s"
            )
        )
    }

    @Test
    fun normalizeUrlFallsBackWhenCustomSearchTemplateHasNoPlaceholder() {
        assertEquals(
            "https://www.google.com/search?q=hello+world",
            GeckoSessionController.normalizeUrl(
                input = "hello world",
                searchUrlTemplate = "https://search.example/"
            )
        )
    }

    @Test
    fun initialAboutBlankIsIgnoredWhenAVisibleRestoreUrlExists() {
        assertTrue(
            GeckoSessionController.shouldIgnoreInitialAboutBlank(
                rawUrl = "about:blank",
                visibleUrl = "https://example.com",
                waitingForInitialLocation = true
            )
        )
    }

    @Test
    fun aboutBlankIsNotIgnoredAfterInitialLocationSettles() {
        assertFalse(
            GeckoSessionController.shouldIgnoreInitialAboutBlank(
                rawUrl = "about:blank",
                visibleUrl = "https://example.com",
                waitingForInitialLocation = false
            )
        )
    }

    @Test
    fun initialAboutBlankIsNotIgnoredWhenItIsTheVisibleTarget() {
        assertFalse(
            GeckoSessionController.shouldIgnoreInitialAboutBlank(
                rawUrl = "about:blank",
                visibleUrl = "about:blank",
                waitingForInitialLocation = true
            )
        )
    }
}
