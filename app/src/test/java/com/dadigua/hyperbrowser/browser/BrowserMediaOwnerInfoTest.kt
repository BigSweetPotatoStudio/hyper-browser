package com.dadigua.hyperbrowser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BrowserMediaOwnerInfoTest {
    @Test
    fun mediaOwnerKeyIgnoresUrlForStableTabOwner() {
        val first = BrowserMediaOwnerInfo(
            id = "tab-1",
            kind = BrowserMediaOwnerKind.BrowserTab,
            url = "https://music.example/playing"
        )
        val second = BrowserMediaOwnerInfo(
            id = "tab-1",
            kind = BrowserMediaOwnerKind.BrowserTab,
            url = "https://other.example/"
        )

        assertEquals(
            first.mediaOwnerKey(fallbackIdentity = 1),
            second.mediaOwnerKey(fallbackIdentity = 2)
        )
    }

    @Test
    fun mediaOwnerKeyKeepsBrowserTabsAndWebAppsSeparate() {
        val tab = BrowserMediaOwnerInfo(id = "same-id", kind = BrowserMediaOwnerKind.BrowserTab)
        val webApp = BrowserMediaOwnerInfo(id = "same-id", kind = BrowserMediaOwnerKind.WebApp)

        assertNotEquals(
            tab.mediaOwnerKey(fallbackIdentity = 1),
            webApp.mediaOwnerKey(fallbackIdentity = 1)
        )
    }

    @Test
    fun mediaOwnerKeyFallsBackToSessionIdentityWhenOwnerIdIsBlank() {
        val owner = BrowserMediaOwnerInfo(id = "", kind = BrowserMediaOwnerKind.BrowserTab)

        assertEquals(
            "BrowserTab:42",
            owner.mediaOwnerKey(fallbackIdentity = 42)
        )
    }
}
