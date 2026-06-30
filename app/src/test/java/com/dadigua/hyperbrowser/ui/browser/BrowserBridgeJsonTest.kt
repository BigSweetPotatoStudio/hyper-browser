package com.dadigua.hyperbrowser.ui.browser

import com.dadigua.hyperbrowser.browser.BrowserSettings
import com.dadigua.hyperbrowser.update.UpdateDownloadState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserBridgeJsonTest {
    @Test
    fun okItemsKeepsItemsJsonAsString() {
        val itemsJson = JSONArray()
            .put(JSONObject().put("title", "Docs"))
            .toString()

        val response = okItems(itemsJson)

        assertTrue(response.getBoolean("ok"))
        assertEquals(itemsJson, response.getString("itemsJson"))
    }

    @Test
    fun browserSettingsJsonIncludesBrowserAndSyncFields() {
        val json = BrowserSettings(
            searchEngineId = BrowserSettings.SEARCH_ENGINE_CUSTOM,
            customSearchUrl = "https://search.example?q=%s",
            toolbarPosition = BrowserSettings.TOOLBAR_POSITION_FLOATING_DOT,
            floatingDotXRatio = 0.25f,
            floatingDotYRatio = 0.75f,
            websiteDisplayMode = BrowserSettings.WEBSITE_DISPLAY_DESKTOP,
            backgroundVideoEnhancementEnabled = true,
            openNewTabsInCurrentTab = true,
            dohEnabled = true,
            dohProviderUrl = "https://resolver.example/dns-query",
            httpsOnlyEnabled = true,
            privacyProtectionLevel = BrowserSettings.PRIVACY_PROTECTION_STRICT,
            localePreference = BrowserSettings.LOCALE_CHINESE,
            webDavSyncEnabled = true,
            webDavSyncUrl = "https://dav.example/HyperBrowserSync",
            webDavSyncUsername = "user",
            webDavSyncPassword = "password",
            webDavSyncDeviceName = "phone",
            webDavSyncDeviceId = "device-1"
        ).toJson()

        assertEquals(BrowserSettings.SEARCH_ENGINE_CUSTOM, json.getString("searchEngineId"))
        assertEquals("自定义", json.getString("searchEngineName"))
        assertEquals("https://search.example?q=%s", json.getString("customSearchUrl"))
        assertEquals(BrowserSettings.TOOLBAR_POSITION_FLOATING_DOT, json.getString("toolbarPosition"))
        assertEquals(0.25, json.getDouble("floatingDotXRatio"), 0.001)
        assertEquals(0.75, json.getDouble("floatingDotYRatio"), 0.001)
        assertEquals(BrowserSettings.WEBSITE_DISPLAY_DESKTOP, json.getString("websiteDisplayMode"))
        assertTrue(json.getBoolean("backgroundVideoEnhancementEnabled"))
        assertTrue(json.getBoolean("openNewTabsInCurrentTab"))
        assertTrue(json.getBoolean("dohEnabled"))
        assertEquals("https://resolver.example/dns-query", json.getString("dohProviderUrl"))
        assertTrue(json.getBoolean("httpsOnlyEnabled"))
        assertEquals(BrowserSettings.PRIVACY_PROTECTION_STRICT, json.getString("privacyProtectionLevel"))
        assertEquals(BrowserSettings.LOCALE_CHINESE, json.getString("localePreference"))
        assertTrue(json.getBoolean("webDavSyncEnabled"))
        assertEquals("https://dav.example/HyperBrowserSync", json.getString("webDavSyncUrl"))
        assertEquals("user", json.getString("webDavSyncUsername"))
        assertEquals("password", json.getString("webDavSyncPassword"))
        assertEquals("phone", json.getString("webDavSyncDeviceName"))
        assertEquals("device-1", json.getString("webDavSyncDeviceId"))
    }

    @Test
    fun updateDownloadStateJsonIncludesProgressAndMessage() {
        val json = UpdateDownloadState(
            status = UpdateDownloadState.STATUS_DOWNLOADING,
            versionCode = 17L,
            versionName = "0.1.8-beta.3",
            bytesDownloaded = 1024L,
            totalBytes = 2048L,
            message = "Downloading"
        ).toJson()

        assertEquals(UpdateDownloadState.STATUS_DOWNLOADING, json.getString("status"))
        assertEquals(17L, json.getLong("versionCode"))
        assertEquals("0.1.8-beta.3", json.getString("versionName"))
        assertEquals(1024L, json.getLong("bytesDownloaded"))
        assertEquals(2048L, json.getLong("totalBytes"))
        assertEquals("Downloading", json.getString("message"))
    }
}
