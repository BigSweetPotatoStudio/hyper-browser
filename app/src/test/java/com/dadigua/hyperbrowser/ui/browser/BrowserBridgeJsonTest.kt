package com.dadigua.hyperbrowser.ui.browser

import com.dadigua.hyperbrowser.browser.BrowserBookmark
import com.dadigua.hyperbrowser.browser.BrowserHistoryEntry
import com.dadigua.hyperbrowser.data.WebAppDefinition
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BrowserBridgeJsonTest {
    @Test
    fun searchSuggestionsIncludeWebAppsBetweenBookmarksAndHistory() {
        val suggestions = JSONArray(
            searchSuggestionsJsonString(
                bookmarks = listOf(BrowserBookmark("https://docs.example", "Docs", 1L)),
                history = listOf(BrowserHistoryEntry("https://blog.example", "Blog", 2L)),
                webApps = listOf(
                    WebAppDefinition(
                        id = "mail",
                        name = "Mail",
                        startUrl = "https://mail.example",
                        scopeUrl = "https://mail.example/",
                        iconPath = null,
                        themeColor = 0,
                        displayMode = "standalone",
                        createdAt = 3L,
                        lastOpenedAt = 4L
                    )
                )
            )
        )

        assertEquals("bookmark", suggestions.getJSONObject(0).getString("source"))
        assertEquals("app", suggestions.getJSONObject(1).getString("source"))
        assertEquals("mail", suggestions.getJSONObject(1).getString("id"))
        assertEquals("history", suggestions.getJSONObject(2).getString("source"))
    }

    @Test
    fun searchSuggestionsIgnoreInternalHistoryUrls() {
        val suggestions = JSONArray(
            searchSuggestionsJsonString(
                bookmarks = emptyList(),
                history = listOf(BrowserHistoryEntry("hyper://settings", "Settings", 1L)),
                webApps = emptyList()
            )
        )

        assertEquals(0, suggestions.length())
    }

    @Test
    fun searchSuggestionsSkipBlankWebApps() {
        val suggestions = JSONArray(
            searchSuggestionsJsonString(
                bookmarks = emptyList(),
                history = emptyList(),
                webApps = listOf(
                    WebAppDefinition(
                        id = "",
                        name = "Broken",
                        startUrl = "https://broken.example",
                        scopeUrl = "https://broken.example/",
                        iconPath = null,
                        themeColor = 0,
                        displayMode = "standalone",
                        createdAt = 1L,
                        lastOpenedAt = 1L
                    ),
                    WebAppDefinition(
                        id = "blank-url",
                        name = "Blank",
                        startUrl = "",
                        scopeUrl = "",
                        iconPath = null,
                        themeColor = 0,
                        displayMode = "standalone",
                        createdAt = 1L,
                        lastOpenedAt = 1L
                    )
                )
            )
        )

        assertFalse(suggestions.length() > 0)
    }
}
