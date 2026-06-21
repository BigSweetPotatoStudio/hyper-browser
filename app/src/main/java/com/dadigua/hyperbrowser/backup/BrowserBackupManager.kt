package com.dadigua.hyperbrowser.backup

import android.graphics.Color
import com.dadigua.hyperbrowser.browser.BrowserBookmark
import com.dadigua.hyperbrowser.browser.BrowserProfileStore
import com.dadigua.hyperbrowser.browser.FaviconRepository
import com.dadigua.hyperbrowser.data.WebAppDefinition
import com.dadigua.hyperbrowser.webapp.WebAppRepository
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class BrowserBackupManager(
    private val profileStore: BrowserProfileStore,
    private val webAppRepository: WebAppRepository,
    private val faviconStore: FaviconRepository
) {
    fun exportJson(): String {
        val bookmarks = JSONArray()
        profileStore.observeBookmarks().value.forEach { bookmark ->
            bookmarks.put(
                JSONObject()
                    .put("title", bookmark.title)
                    .put("url", bookmark.url)
                    .put("createdAt", bookmark.createdAt)
                    .put("iconDataUrl", faviconStore.iconDataUrl(bookmark.iconPath, bookmark.url))
            )
        }

        val webApps = JSONArray()
        webAppRepository.observeAll().value.forEach { webApp ->
            val hasCustomIcon = faviconStore.isCustomIconPath(webApp.iconPath)
            webApps.put(
                JSONObject()
                    .put("id", webApp.id)
                    .put("name", webApp.name)
                    .put("startUrl", webApp.startUrl)
                    .put("scopeUrl", webApp.scopeUrl)
                    .put("themeColor", webApp.themeColor)
                    .put("displayMode", webApp.displayMode)
                    .put("createdAt", webApp.createdAt)
                    .put("lastOpenedAt", webApp.lastOpenedAt)
                    .put("iconMode", if (hasCustomIcon) "custom" else "site")
                    .put("iconDataUrl", if (hasCustomIcon) faviconStore.iconDataUrl(webApp.iconPath) else null)
            )
        }

        return JSONObject()
            .put("type", BACKUP_TYPE)
            .put("version", BACKUP_VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put("bookmarks", bookmarks)
            .put("webApps", webApps)
            .toString(2)
    }

    fun importJson(raw: String): BrowserBackupImportResult {
        val root = JSONObject(raw)
        val bookmarks = parseBookmarks(root.optJSONArray("bookmarks") ?: JSONArray())
        val webApps = parseWebApps(root.optJSONArray("webApps") ?: JSONArray())
        val importedBookmarks = profileStore.mergeBookmarks(bookmarks)
        val importedWebApps = webAppRepository.mergeImported(webApps)
        return BrowserBackupImportResult(
            bookmarks = importedBookmarks,
            webApps = importedWebApps
        )
    }

    private fun parseBookmarks(array: JSONArray): List<BrowserBookmark> =
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val url = item.optString("url").trim()
                if (url.isBlank()) continue
                val iconPath = faviconStore.saveIconDataUrl(url, item.optString("iconDataUrl").ifBlank { null })
                add(
                    BrowserBookmark(
                        url = url,
                        title = item.optString("title").trim().ifBlank { url },
                        createdAt = item.optLong("createdAt", System.currentTimeMillis()),
                        iconPath = iconPath
                    )
                )
            }
        }

    private fun parseWebApps(array: JSONArray): List<WebAppDefinition> =
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val startUrl = item.optString("startUrl").trim()
                if (startUrl.isBlank()) continue
                val iconPath = when (item.optString("iconMode")) {
                    "none", "site" -> null
                    else -> faviconStore.saveCustomIconDataUrl(startUrl, item.optString("iconDataUrl").ifBlank { null })
                }
                add(
                    WebAppDefinition(
                        id = item.optString("id").trim().ifBlank { UUID.randomUUID().toString() },
                        name = item.optString("name").trim().ifBlank { startUrl },
                        startUrl = startUrl,
                        scopeUrl = item.optString("scopeUrl").trim(),
                        iconPath = iconPath,
                        themeColor = item.optInt("themeColor", Color.rgb(18, 109, 106)),
                        displayMode = item.optString("displayMode", "standalone"),
                        createdAt = item.optLong("createdAt", System.currentTimeMillis()),
                        lastOpenedAt = item.optLong("lastOpenedAt", System.currentTimeMillis())
                    )
                )
            }
        }

    private companion object {
        const val BACKUP_TYPE = "hyper-browser-backup"
        const val BACKUP_VERSION = 1
    }
}

data class BrowserBackupImportResult(
    val bookmarks: Int,
    val webApps: Int
)
