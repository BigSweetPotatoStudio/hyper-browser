package com.dadigua.hyperbrowser.ui.browser

import com.dadigua.hyperbrowser.gecko.GeckoPageState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserTabSnapshotTest {
    @Test
    fun snapshotUsesCurrentPageUrlBeforeInput() {
        val saved = savedBrowserTabFromState(
            id = "tab-1",
            input = "example.com",
            iconPath = "/icon.png",
            state = GeckoPageState(title = "Example", url = "https://example.com/")
        )

        requireNotNull(saved)
        assertEquals("tab-1", saved.id)
        assertEquals("Example", saved.title)
        assertEquals("https://example.com/", saved.url)
        assertEquals("https://example.com/", saved.input)
        assertEquals("/icon.png", saved.iconPath)
        assertTrue(saved.loaded)
    }

    @Test
    fun snapshotFallsBackToInputWhenPageUrlIsBlank() {
        val saved = savedBrowserTabFromState(
            id = "tab-1",
            input = "https://pending.example",
            iconPath = null,
            state = GeckoPageState(title = "Pending", url = "")
        )

        requireNotNull(saved)
        assertEquals("https://pending.example", saved.url)
        assertEquals("https://pending.example", saved.input)
    }

    @Test
    fun snapshotKeepsRestoreUrlForLazyTab() {
        val saved = savedBrowserTabFromState(
            id = "tab-1",
            input = "https://entry.example",
            iconPath = null,
            state = GeckoPageState(title = "about:blank", url = "about:blank"),
            loaded = false,
            restoreUrl = "https://detail.example/page",
            restoredTitle = "Saved detail"
        )

        requireNotNull(saved)
        assertEquals("Saved detail", saved.title)
        assertEquals("https://detail.example/page", saved.url)
        assertEquals("https://entry.example", saved.input)
        assertFalse(saved.loaded)
    }

    @Test
    fun snapshotFromRuntimeUsesCommittedRestoreUrlForLoadedTab() {
        val saved = savedBrowserTabFromSnapshot(
            id = "tab-1",
            input = "https://entry.example",
            iconPath = "/icon.png",
            loaded = true,
            restoreUrl = "https://detail.example/page",
            title = "Detail"
        )

        requireNotNull(saved)
        assertEquals("Detail", saved.title)
        assertEquals("https://detail.example/page", saved.url)
        assertEquals("https://detail.example/page", saved.input)
        assertEquals("/icon.png", saved.iconPath)
        assertTrue(saved.loaded)
    }

    @Test
    fun snapshotFromRuntimeKeepsLazyInputSeparateFromRestoreUrl() {
        val saved = savedBrowserTabFromSnapshot(
            id = "tab-1",
            input = "https://entry.example",
            iconPath = null,
            loaded = false,
            restoreUrl = "https://detail.example/page",
            title = "Detail"
        )

        requireNotNull(saved)
        assertEquals("https://detail.example/page", saved.url)
        assertEquals("https://entry.example", saved.input)
        assertFalse(saved.loaded)
    }

    @Test
    fun snapshotUsesUrlAsInputWhenInputIsBlank() {
        val saved = savedBrowserTabFromState(
            id = "tab-1",
            input = "",
            iconPath = null,
            state = GeckoPageState(title = "Example", url = "https://example.com/")
        )

        requireNotNull(saved)
        assertEquals("https://example.com/", saved.input)
    }

    @Test
    fun snapshotReturnsNullForUnrecoverableTab() {
        assertNull(
            savedBrowserTabFromState(
                id = "tab-1",
                input = "",
                iconPath = null,
                state = GeckoPageState(title = "Empty", url = "")
            )
        )
        assertNull(
            savedBrowserTabFromState(
                id = "",
                input = "https://example.com/",
                iconPath = null,
                state = GeckoPageState(title = "Example", url = "https://example.com/")
            )
        )
        assertNull(
            savedBrowserTabFromState(
                id = "tab-1",
                input = "",
                iconPath = null,
                state = GeckoPageState(title = "Blank", url = "about:blank")
            )
        )
    }
}
