package com.dadigua.hyperbrowser.browser

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserTabPersistenceCodecTest {
    @Test
    fun encodeDecodeRoundTripKeepsSelectedTabAndRecoverableFields() {
        val state = SavedBrowserTabs(
            selectedTabId = "tab-2",
            tabs = listOf(
                SavedBrowserTab(
                    id = "tab-1",
                    title = "Home",
                    url = "hyper://home",
                    input = "hyper://home",
                    iconPath = null
                ),
                SavedBrowserTab(
                    id = "tab-2",
                    title = "Example Domain",
                    url = "https://example.com/",
                    input = "example.com",
                    iconPath = "/data/user/0/com.dadigua.hyperbrowser/files/favicon.png",
                    loaded = false
                )
            )
        )

        val decoded = BrowserTabPersistenceCodec.decode(BrowserTabPersistenceCodec.encode(state))

        assertEquals("tab-2", decoded.selectedTabId)
        assertEquals(state.tabs, decoded.tabs)
    }

    @Test
    fun decodeFallsBackToUrlWhenInputIsMissing() {
        val raw = JSONObject()
            .put("selectedTabId", "tab-1")
            .put(
                "tabs",
                JSONArray().put(
                    JSONObject()
                        .put("id", "tab-1")
                        .put("title", "Example")
                        .put("url", "https://example.com/")
                )
            )
            .toString()

        val decoded = BrowserTabPersistenceCodec.decode(raw)

        assertEquals("https://example.com/", decoded.tabs.single().input)
        assertTrue(decoded.tabs.single().loaded)
    }

    @Test
    fun decodeSkipsTabsWithoutIdOrUrl() {
        val raw = JSONObject()
            .put("selectedTabId", "tab-1")
            .put(
                "tabs",
                JSONArray()
                    .put(JSONObject().put("id", "").put("url", "https://missing-id.example"))
                    .put(JSONObject().put("id", "missing-url").put("url", ""))
                    .put(JSONObject().put("id", "tab-1").put("url", "https://example.com/"))
            )
            .toString()

        val decoded = BrowserTabPersistenceCodec.decode(raw)

        assertEquals(listOf("tab-1"), decoded.tabs.map { it.id })
    }

    @Test
    fun decodeInvalidJsonReturnsEmptyState() {
        val decoded = BrowserTabPersistenceCodec.decode("not json")

        assertNull(decoded.selectedTabId)
        assertEquals(emptyList<SavedBrowserTab>(), decoded.tabs)
    }

    @Test
    fun encodeSkipsTabsWithoutIdOrUrl() {
        val encoded = BrowserTabPersistenceCodec.encode(
            SavedBrowserTabs(
                selectedTabId = "valid",
                tabs = listOf(
                    SavedBrowserTab(id = "", title = "No ID", url = "https://example.com", input = ""),
                    SavedBrowserTab(id = "no-url", title = "No URL", url = "", input = ""),
                    SavedBrowserTab(id = "valid", title = "Valid", url = "https://example.com/", input = "")
                )
            )
        )

        val decoded = BrowserTabPersistenceCodec.decode(encoded)

        assertEquals(1, decoded.tabs.size)
        assertEquals("valid", decoded.tabs.single().id)
        assertEquals("https://example.com/", decoded.tabs.single().input)
    }

    @Test
    fun encodeDecodePreservesLoadedFlag() {
        val encoded = BrowserTabPersistenceCodec.encode(
            SavedBrowserTabs(
                selectedTabId = "lazy",
                tabs = listOf(
                    SavedBrowserTab(
                        id = "lazy",
                        title = "Lazy",
                        url = "https://lazy.example/",
                        input = "https://lazy.example/",
                        loaded = false
                    )
                )
            )
        )

        val decoded = BrowserTabPersistenceCodec.decode(encoded)

        assertFalse(decoded.tabs.single().loaded)
    }
}
