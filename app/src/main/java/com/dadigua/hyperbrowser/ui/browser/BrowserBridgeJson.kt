package com.dadigua.hyperbrowser.ui.browser

import com.dadigua.hyperbrowser.HyperBrowserApp
import com.dadigua.hyperbrowser.browser.BrowserBookmark
import com.dadigua.hyperbrowser.browser.BrowserHistoryEntry
import com.dadigua.hyperbrowser.browser.BrowserSettings
import com.dadigua.hyperbrowser.browser.FaviconRepository
import com.dadigua.hyperbrowser.data.WebAppDefinition
import com.dadigua.hyperbrowser.gecko.GeckoSessionController
import com.dadigua.hyperbrowser.update.AvailableUpdate
import com.dadigua.hyperbrowser.update.UpdateAsset
import com.dadigua.hyperbrowser.update.UpdateCheckResult
import com.dadigua.hyperbrowser.update.UpdateDownloadState
import org.json.JSONArray
import org.json.JSONObject

internal fun ok(data: JSONObject? = null): JSONObject {
    val response = JSONObject().put("ok", true)
    if (data != null) response.put("data", data)
    return response
}
internal fun okData(data: JSONObject): JSONObject =
    JSONObject().put("ok", true).put("data", data)

internal fun okItems(itemsJson: String): JSONObject =
    JSONObject().put("ok", true).put("itemsJson", itemsJson)

private fun JSONObject.putJsonNullable(name: String, value: Any?): JSONObject =
    put(name, value ?: JSONObject.NULL)

internal fun BrowserSettings.toJson(): JSONObject =
    JSONObject()
        .put("searchEngineId", searchEngineId)
        .put("searchEngineName", searchEngineName)
        .put("customSearchUrl", customSearchUrl)
        .put("toolbarPosition", toolbarPosition)
        .put("websiteDisplayMode", BrowserSettings.normalizedWebsiteDisplayMode(websiteDisplayMode))
        .put("backgroundVideoEnhancementEnabled", backgroundVideoEnhancementEnabled)
        .put("openNewTabsInCurrentTab", openNewTabsInCurrentTab)
        .put("dohEnabled", dohEnabled)
        .put("dohProviderUrl", dohProviderUrl)
        .put("httpsOnlyEnabled", httpsOnlyEnabled)
        .put("privacyProtectionLevel", privacyProtectionLevel)
        .put("localePreference", localePreference)
        .put("webDavSyncEnabled", webDavSyncEnabled)
        .put("webDavSyncUrl", webDavSyncUrl)
        .put("webDavSyncUsername", webDavSyncUsername)
        .put("webDavSyncPassword", webDavSyncPassword)
        .put("webDavSyncDeviceName", webDavSyncDeviceName)
        .put("webDavSyncDeviceId", webDavSyncDeviceId)

internal fun UpdateCheckResult.toJson(): JSONObject =
    JSONObject()
        .put("status", status)
        .put("currentVersionCode", currentVersionCode)
        .put("currentVersionName", currentVersionName)
        .put("skippedVersionCode", skippedVersionCode)
        .put("message", message)
        .apply {
            update?.let { put("update", it.toJson()) }
        }

internal fun AvailableUpdate.toJson(): JSONObject =
    JSONObject()
        .put("versionCode", versionCode)
        .put("versionName", versionName)
        .put("notes", notes)
        .put("releaseUrl", releaseUrl)
        .put("asset", asset.toJson())

private fun UpdateAsset.toJson(): JSONObject =
    JSONObject()
        .put("abi", abi)
        .put("url", url)
        .put("sha256", sha256)
        .put("sizeBytes", sizeBytes)

internal fun UpdateDownloadState.toJson(): JSONObject =
    JSONObject()
        .put("status", status)
        .put("versionCode", versionCode)
        .put("versionName", versionName)
        .put("bytesDownloaded", bytesDownloaded)
        .put("totalBytes", totalBytes)
        .put("message", message)

internal fun List<BrowserBookmark>.toBookmarksJsonString(faviconStore: FaviconRepository): String {
    val array = JSONArray()
    forEach { bookmark ->
        array.put(
            JSONObject()
                .put("title", bookmark.title)
                .put("url", bookmark.url)
                .put("createdAt", bookmark.createdAt)
                .putJsonNullable("iconDataUrl", faviconStore.iconDataUrl(bookmark.iconPath, bookmark.url))
        )
    }
    return array.toString()
}

internal fun List<BrowserHistoryEntry>.toHistoryJsonString(faviconStore: FaviconRepository): String {
    val array = JSONArray()
    filterNot { GeckoSessionController.isInternalUrl(it.url) }.forEach { entry ->
        array.put(
            JSONObject()
                .put("title", entry.title)
                .put("url", entry.url)
                .put("visitedAt", entry.visitedAt)
                .put("iconDataUrl", faviconStore.iconDataUrl(entry.iconPath, entry.url))
        )
    }
    return array.toString()
}

internal fun List<WebAppDefinition>.toWebAppsJsonString(app: HyperBrowserApp): String {
    val array = JSONArray()
    forEach { webApp ->
        array.put(
            JSONObject()
                .put("id", webApp.id)
                .put("name", webApp.name)
                .put("startUrl", webApp.startUrl)
                .put("iconPath", webApp.iconPath)
                .put("iconDataUrl", app.webApps.iconDataUrl(webApp))
                .put("siteIconDataUrl", app.webApps.siteIconDataUrl(webApp))
                .put("iconSource", app.webApps.iconSource(webApp))
                .put("themeColor", webApp.themeColor)
                .put("displayMode", webApp.displayMode)
                .put("createdAt", webApp.createdAt)
                .put("lastOpenedAt", webApp.lastOpenedAt)
        )
    }
    return array.toString()
}
