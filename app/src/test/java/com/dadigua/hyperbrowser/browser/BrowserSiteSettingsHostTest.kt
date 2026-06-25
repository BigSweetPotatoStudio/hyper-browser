package com.dadigua.hyperbrowser.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserSiteSettingsHostTest {
    @Test
    fun keepsWwwAndMobileHostsDistinct() {
        assertEquals("www.bilibili.com", browserSiteSettingsHost("https://www.bilibili.com/video/BV1"))
        assertEquals("m.bilibili.com", browserSiteSettingsHost("https://m.bilibili.com/video/BV1"))
    }

    @Test
    fun stripsPortAndTrailingDotButKeepsHostIdentity() {
        assertEquals("www.example.com", browserSiteSettingsHost("https://www.example.com.:8443/path"))
        assertEquals("touch.example.com", browserSiteSettingsHost("https://touch.example.com/path"))
    }

    @Test
    fun ignoresInternalAndBlankUrls() {
        assertEquals("", browserSiteSettingsHost(""))
        assertEquals("", browserSiteSettingsHost("hyper://home"))
    }
}
