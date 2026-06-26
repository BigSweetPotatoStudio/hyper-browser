package com.dadigua.hyperbrowser.ui.browser

import com.dadigua.hyperbrowser.browser.BrowserBookmark
import com.dadigua.hyperbrowser.browser.BrowserHistoryEntry
import com.dadigua.hyperbrowser.data.WebAppDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSearchPageTest {
    @Test
    fun blankQueryReturnsNoSuggestions() {
        val suggestions = buildBrowserSearchSuggestions(
            queryText = "   ",
            history = listOf(history("https://docs.example", "Docs")),
            bookmarks = listOf(bookmark("https://docs.example", "Docs")),
            webApps = listOf(webApp("docs", "Docs", "https://docs.example")),
            bookmarkSource = "Bookmark",
            webAppSource = "WebApp",
            historySource = "History"
        )

        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun webAppNameMatchesSearchSuggestions() {
        val suggestions = buildBrowserSearchSuggestions(
            queryText = "mail",
            history = emptyList(),
            bookmarks = emptyList(),
            webApps = listOf(webApp("mail", "Mail", "https://mail.example/inbox")),
            bookmarkSource = "Bookmark",
            webAppSource = "WebApp",
            historySource = "History"
        )

        assertEquals(1, suggestions.size)
        assertEquals(BrowserSearchSuggestionKind.WebApp, suggestions.single().kind)
        assertEquals("mail", suggestions.single().appId)
        assertEquals("WebApp", suggestions.single().source)
    }

    @Test
    fun bookmarkStillSuppressesDuplicateHistoryButKeepsMatchingWebApp() {
        val suggestions = buildBrowserSearchSuggestions(
            queryText = "docs",
            history = listOf(history("https://docs.example", "Docs history")),
            bookmarks = listOf(bookmark("https://docs.example", "Docs bookmark")),
            webApps = listOf(webApp("docs", "Docs app", "https://docs.example")),
            bookmarkSource = "Bookmark",
            webAppSource = "WebApp",
            historySource = "History"
        )

        assertEquals(
            listOf(BrowserSearchSuggestionKind.Bookmark, BrowserSearchSuggestionKind.WebApp),
            suggestions.map { it.kind }
        )
    }

    @Test
    fun internalHistoryUrlsAreNotSuggested() {
        val suggestions = buildBrowserSearchSuggestions(
            queryText = "settings",
            history = listOf(history("hyper://settings", "Settings")),
            bookmarks = emptyList(),
            webApps = emptyList(),
            bookmarkSource = "Bookmark",
            webAppSource = "WebApp",
            historySource = "History"
        )

        assertTrue(suggestions.isEmpty())
    }

    private fun bookmark(url: String, title: String): BrowserBookmark =
        BrowserBookmark(url = url, title = title, createdAt = 1L)

    private fun history(url: String, title: String): BrowserHistoryEntry =
        BrowserHistoryEntry(url = url, title = title, visitedAt = 1L)

    private fun webApp(id: String, name: String, startUrl: String): WebAppDefinition =
        WebAppDefinition(
            id = id,
            name = name,
            startUrl = startUrl,
            iconPath = null,
            themeColor = 0,
            displayMode = "standalone",
            createdAt = 1L,
            lastOpenedAt = 1L
        )
}
