package com.dadigua.hyperbrowser.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.UUID
import kotlin.math.roundToInt

data class BrowserHistoryEntry(
    val url: String,
    val title: String,
    val visitedAt: Long,
    val iconPath: String? = null
)

data class BrowserBookmark(
    val url: String,
    val title: String,
    val createdAt: Long,
    val iconPath: String? = null
)

internal fun normalizeBookmarkUrl(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return ""
    return runCatching {
        val uri = java.net.URI(trimmed)
        val scheme = uri.scheme?.lowercase() ?: return@runCatching trimmed
        val authority = uri.rawAuthority ?: return@runCatching trimmed
        val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        buildString {
            append(scheme).append("://").append(authority).append(path)
            uri.rawQuery?.let { append('?').append(it) }
            uri.rawFragment?.let { append('#').append(it) }
        }
    }.getOrDefault(trimmed)
}

data class BrowserSettings(
    val searchEngineId: String = SEARCH_ENGINE_GOOGLE,
    val customSearchUrl: String = "",
    val toolbarPosition: String = TOOLBAR_POSITION_DYNAMIC_BOTTOM,
    val websiteDisplayMode: String = WEBSITE_DISPLAY_MOBILE,
    val backgroundVideoEnhancementEnabled: Boolean = false,
    val openNewTabsInCurrentTab: Boolean = false,
    val dohEnabled: Boolean = false,
    val dohProviderUrl: String = DEFAULT_DOH_PROVIDER_URL,
    val httpsOnlyEnabled: Boolean = false,
    val privacyProtectionLevel: String = PRIVACY_PROTECTION_STANDARD,
    val localePreference: String = LOCALE_DEFAULT,
    val webDavSyncEnabled: Boolean = false,
    val webDavSyncUrl: String = "",
    val webDavSyncUsername: String = "",
    val webDavSyncPassword: String = "",
    val webDavSyncDeviceName: String = "",
    val webDavSyncDeviceId: String = ""
) {
    val echEnabled: Boolean
        get() = dohEnabled

    val strictPrivacyEnabled: Boolean
        get() = privacyProtectionLevel == PRIVACY_PROTECTION_STRICT

    val searchEngineName: String
        get() = when (searchEngineId) {
            SEARCH_ENGINE_BING -> "Bing"
            SEARCH_ENGINE_CUSTOM -> "自定义"
            else -> "Google"
        }

    val searchUrlTemplate: String
        get() = when (searchEngineId) {
            SEARCH_ENGINE_BING -> "https://www.bing.com/search?q=%s"
            SEARCH_ENGINE_CUSTOM -> customSearchUrl.takeIf { it.contains("%s") }
                ?: DEFAULT_SEARCH_URL_TEMPLATE
            else -> DEFAULT_SEARCH_URL_TEMPLATE
        }

    companion object {
        const val SEARCH_ENGINE_GOOGLE = "google"
        const val SEARCH_ENGINE_BING = "bing"
        const val SEARCH_ENGINE_CUSTOM = "custom"
        const val DEFAULT_SEARCH_URL_TEMPLATE = "https://www.google.com/search?q=%s"
        const val TOOLBAR_POSITION_TOP = "top"
        const val TOOLBAR_POSITION_BOTTOM = "bottom"
        const val TOOLBAR_POSITION_DYNAMIC_BOTTOM = "dynamic_bottom"
        const val DEFAULT_DOH_PROVIDER_URL = "https://mozilla.cloudflare-dns.com/dns-query"
        const val PRIVACY_PROTECTION_NONE = "none"
        const val PRIVACY_PROTECTION_STANDARD = "standard"
        const val PRIVACY_PROTECTION_STRICT = "strict"
        const val LOCALE_DEFAULT = "default"
        const val LOCALE_CHINESE = "zh"
        const val LOCALE_ENGLISH = "en"
        const val WEBSITE_DISPLAY_MOBILE = "mobile"
        const val WEBSITE_DISPLAY_TABLET = "tablet"
        const val WEBSITE_DISPLAY_DESKTOP = "desktop"

        fun normalizedLocalePreference(value: String): String =
            when (value) {
                LOCALE_CHINESE -> LOCALE_CHINESE
                LOCALE_ENGLISH -> LOCALE_ENGLISH
                else -> LOCALE_DEFAULT
            }

        fun normalizedToolbarPosition(value: String): String =
            when (value) {
                TOOLBAR_POSITION_TOP -> TOOLBAR_POSITION_TOP
                TOOLBAR_POSITION_BOTTOM -> TOOLBAR_POSITION_BOTTOM
                TOOLBAR_POSITION_DYNAMIC_BOTTOM -> TOOLBAR_POSITION_DYNAMIC_BOTTOM
                else -> TOOLBAR_POSITION_DYNAMIC_BOTTOM
            }

        fun isBottomToolbarPosition(value: String): Boolean =
            value == TOOLBAR_POSITION_BOTTOM || value == TOOLBAR_POSITION_DYNAMIC_BOTTOM

        fun isDynamicBottomToolbarPosition(value: String): Boolean =
            value == TOOLBAR_POSITION_DYNAMIC_BOTTOM

        fun normalizedWebsiteDisplayMode(value: String?): String =
            when (value) {
                WEBSITE_DISPLAY_MOBILE -> WEBSITE_DISPLAY_MOBILE
                WEBSITE_DISPLAY_TABLET -> WEBSITE_DISPLAY_TABLET
                WEBSITE_DISPLAY_DESKTOP -> WEBSITE_DISPLAY_DESKTOP
                else -> WEBSITE_DISPLAY_MOBILE
            }
    }
}

internal fun browserSiteSettingsHost(url: String): String {
    val trimmed = url.trim()
    if (trimmed.isBlank()) return ""
    return runCatching {
        val uri = URI(trimmed)
        val scheme = uri.scheme?.lowercase() ?: return@runCatching ""
        if (scheme != "http" && scheme != "https") return@runCatching ""
        val host = uri.host ?: return@runCatching ""
        host.lowercase().trim('.')
    }.getOrDefault("")
}

private fun JSONObject.optCleanString(name: String): String? {
    if (!has(name) || isNull(name)) return null
    val value = optString(name).trim()
    return value.takeIf { it.isNotBlank() && it != "null" && it != "undefined" }
}

private fun JSONObject.putCleanNullable(name: String, value: Any?): JSONObject =
    put(name, value ?: JSONObject.NULL)

data class SavedBrowserTab(
    val id: String,
    val title: String,
    val url: String,
    val input: String,
    val iconPath: String? = null,
    val loaded: Boolean = true
)

data class SavedBrowserTabs(
    val selectedTabId: String?,
    val tabs: List<SavedBrowserTab>
)

class BrowserProfileStore(context: Context) {
    private val historyFile = File(context.filesDir, "browser_history.json")
    private val bookmarksFile = File(context.filesDir, "bookmarks.json")
    private val settingsFile = File(context.filesDir, SETTINGS_FILE_NAME)
    private val tabsFile = File(context.filesDir, "browser_tabs.json")
    private val launcherLayoutFile = File(context.filesDir, "launcher.json")
    private val tabSessionStateDir = File(context.filesDir, "browser_tab_states")
    private val tabThumbnailDir = File(context.filesDir, "browser_tab_thumbnails")
    private val faviconStore = FaviconRepository(context)
    private val historyState = MutableStateFlow(loadHistory())
    private val bookmarksState = MutableStateFlow(loadBookmarks())
    private val settingsState = MutableStateFlow(loadSettings())

    fun observeHistory(): StateFlow<List<BrowserHistoryEntry>> = historyState
    fun observeBookmarks(): StateFlow<List<BrowserBookmark>> = bookmarksState
    fun observeSettings(): StateFlow<BrowserSettings> = settingsState

    fun bookmarksSyncJson(): JSONObject {
        readJSONObject(bookmarksFile)?.let { return it }
        saveBookmarks(bookmarksState.value)
        return readJSONObject(bookmarksFile)
            ?: JSONObject()
                .put("schemaVersion", 2)
                .put("bookmarks", JSONObject())
                .put("bookmarkTombstones", JSONObject())
    }

    fun mergeBookmarks(imported: List<BrowserBookmark>): Int {
        val current = bookmarksState.value.associateBy { normalizeBookmarkUrl(it.url) }
        val accepted = imported.mapNotNull { bookmark ->
            val url = normalizeBookmarkUrl(bookmark.url)
            if (url.isBlank()) return@mapNotNull null
            val existing = current[url]
            BrowserBookmark(
                url = url,
                title = bookmark.title.trim().ifBlank { existing?.title ?: url },
                createdAt = bookmark.createdAt.takeIf { it > 0 }
                    ?: existing?.createdAt
                    ?: System.currentTimeMillis(),
                iconPath = bookmark.iconPath ?: existing?.iconPath
            )
        }.distinctBy { it.url }
        if (accepted.isEmpty()) return 0
        val importedUrls = accepted.map { it.url }.toSet()
        val next = accepted + bookmarksState.value.filterNot { normalizeBookmarkUrl(it.url) in importedUrls }
        bookmarksState.value = next
        saveBookmarks(next)
        return accepted.size
    }

    fun loadSavedTabs(): SavedBrowserTabs {
        if (!tabsFile.exists()) return SavedBrowserTabs(selectedTabId = null, tabs = emptyList())
        return BrowserTabPersistenceCodec.decode(tabsFile.readText())
    }

    fun saveTabs(state: SavedBrowserTabs) {
        tabsFile.writeText(BrowserTabPersistenceCodec.encode(state))
    }

    fun loadLauncherLayout(): JSONObject? {
        val layout = readJSONObject(launcherLayoutFile)
        if (layout != null) return launcherJsonToUiLayout(layout)
        return null
    }

    fun launcherSyncJson(): JSONObject? =
        readJSONObject(launcherLayoutFile)?.let { normalizeLauncherJson(it) }

    fun saveLauncherLayout(layout: JSONObject) {
        val stored = if (isLauncherJson(layout)) {
            normalizeLauncherJson(layout)
        } else {
            uiLayoutToLauncherJson(layout, System.currentTimeMillis())
        }
        launcherLayoutFile.writeText(stored.toString())
    }

    fun loadTabSessionState(tabId: String): String? {
        val file = tabSessionStateFile(tabId) ?: return null
        if (!file.exists()) return null
        return runCatching { file.readText().takeIf { it.isNotBlank() } }.getOrNull()
    }

    fun saveTabSessionState(tabId: String, state: String) {
        val file = tabSessionStateFile(tabId) ?: return
        if (state.isBlank()) {
            runCatching { file.delete() }
            return
        }
        tabSessionStateDir.mkdirs()
        file.writeText(state)
    }

    fun deleteTabSessionState(tabId: String) {
        val file = tabSessionStateFile(tabId) ?: return
        runCatching { file.delete() }
    }

    fun pruneTabSessionStates(keptTabIds: Set<String>) {
        if (!tabSessionStateDir.exists()) return
        val keptFileNames = keptTabIds.mapNotNull(::tabSessionStateFileName).toSet()
        tabSessionStateDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name !in keptFileNames) {
                runCatching { file.delete() }
            }
        }
    }

    fun loadTabThumbnail(tabId: String): Bitmap? {
        val file = tabThumbnailFile(tabId) ?: return null
        if (!file.exists() || file.length() <= 0) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    fun saveTabThumbnail(tabId: String, bitmap: Bitmap) {
        if (bitmap.width <= 0 || bitmap.height <= 0) return
        val file = tabThumbnailFile(tabId) ?: return
        tabThumbnailDir.mkdirs()
        val thumbnail = scaledTabThumbnail(bitmap)
        runCatching {
            val saved = file.outputStream().use { out ->
                thumbnail.compress(Bitmap.CompressFormat.JPEG, 82, out)
            }
            if (!saved) file.delete()
        }.onFailure {
            runCatching { file.delete() }
        }
        if (thumbnail !== bitmap) {
            thumbnail.recycle()
        }
    }

    fun deleteTabThumbnail(tabId: String) {
        val file = tabThumbnailFile(tabId) ?: return
        runCatching { file.delete() }
    }

    fun pruneTabThumbnails(keptTabIds: Set<String>) {
        if (!tabThumbnailDir.exists()) return
        val keptFileNames = keptTabIds.mapNotNull(::tabThumbnailFileName).toSet()
        tabThumbnailDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name !in keptFileNames) {
                runCatching { file.delete() }
            }
        }
    }

    fun recordVisit(url: String, title: String, iconPath: String? = null) {
        if (url.isBlank()) return
        if (historyState.value.firstOrNull()?.url == url) {
            val next = historyState.value.toMutableList()
            next[0] = next[0].copy(
                title = title.ifBlank { url },
                visitedAt = System.currentTimeMillis(),
                iconPath = iconPath ?: next[0].iconPath
            )
            historyState.value = next
            saveHistory(next)
            return
        }
        val entry = BrowserHistoryEntry(
            url = url,
            title = title.ifBlank { url },
            visitedAt = System.currentTimeMillis(),
            iconPath = iconPath
        )
        val next = (listOf(entry) + historyState.value.filterNot { it.url == url }).take(100)
        historyState.value = next
        saveHistory(next)
    }

    fun toggleBookmark(url: String, title: String, iconPath: String? = null) {
        val cleanUrl = normalizeBookmarkUrl(url)
        if (cleanUrl.isBlank()) return
        val current = bookmarksState.value
        val existing = current.firstOrNull { normalizeBookmarkUrl(it.url) == cleanUrl }
        val next = if (existing != null) {
            current.filterNot { normalizeBookmarkUrl(it.url) == cleanUrl }
        } else {
            listOf(
                BrowserBookmark(
                    url = cleanUrl,
                    title = title.ifBlank { cleanUrl },
                    createdAt = System.currentTimeMillis(),
                    iconPath = iconPath
                )
            ) + current
        }
        bookmarksState.value = next
        saveBookmarks(next)
    }

    fun isBookmarked(url: String): Boolean {
        val cleanUrl = normalizeBookmarkUrl(url)
        return cleanUrl.isNotBlank() && bookmarksState.value.any { normalizeBookmarkUrl(it.url) == cleanUrl }
    }

    fun updateBookmarkIcon(url: String, iconPath: String) {
        if (url.isBlank() || iconPath.isBlank()) return
        val cleanUrl = normalizeBookmarkUrl(url)
        val next = bookmarksState.value.map { bookmark ->
            if (normalizeBookmarkUrl(bookmark.url) == cleanUrl && bookmark.iconPath != iconPath) {
                bookmark.copy(iconPath = iconPath)
            } else {
                bookmark
            }
        }
        if (next != bookmarksState.value) {
            bookmarksState.value = next
            saveBookmarks(next)
        }
    }

    fun removeBookmark(url: String) {
        val cleanUrl = normalizeBookmarkUrl(url)
        if (cleanUrl.isBlank()) return
        val next = bookmarksState.value.filterNot { normalizeBookmarkUrl(it.url) == cleanUrl }
        if (next == bookmarksState.value) return
        bookmarksState.value = next
        saveBookmarks(next)
    }

    fun editBookmark(oldUrl: String, title: String, url: String) {
        saveBookmark(oldUrl = oldUrl, title = title, url = url)
    }

    fun saveBookmark(
        oldUrl: String?,
        title: String,
        url: String,
        iconPath: String? = null
    ): List<BrowserBookmark> {
        val cleanUrl = normalizeBookmarkUrl(url)
        if (cleanUrl.isBlank()) return bookmarksState.value
        val current = bookmarksState.value
        val oldCleanUrl = normalizeBookmarkUrl(oldUrl.orEmpty())
        val existing = current.firstOrNull { oldCleanUrl.isNotBlank() && normalizeBookmarkUrl(it.url) == oldCleanUrl }
            ?: current.firstOrNull { normalizeBookmarkUrl(it.url) == cleanUrl }
        val resolvedTitle = title.trim().ifBlank {
            existing?.title ?: cleanUrl
        }
        val saved = BrowserBookmark(
            url = cleanUrl,
            title = resolvedTitle,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            iconPath = iconPath ?: existing?.iconPath
        )
        val duplicateUrls = setOfNotNull(cleanUrl, oldCleanUrl.takeIf { it.isNotBlank() })
        val insertAt = current.indexOfFirst { normalizeBookmarkUrl(it.url) in duplicateUrls }.takeIf { it >= 0 } ?: 0
        val next = current.filterNot { normalizeBookmarkUrl(it.url) in duplicateUrls }.toMutableList()
        next.add(insertAt.coerceIn(0, next.size), saved)
        bookmarksState.value = next
        saveBookmarks(next)
        return next
    }

    fun removeHistoryEntry(url: String) {
        if (url.isBlank()) return
        val next = historyState.value.filterNot { it.url == url }
        historyState.value = next
        saveHistory(next)
    }

    fun clearHistory() {
        historyState.value = emptyList()
        saveHistory(emptyList())
    }

    fun updateSearchEngine(searchEngineId: String, customSearchUrl: String) {
        val next = settingsState.value.copy(
            searchEngineId = when (searchEngineId) {
                BrowserSettings.SEARCH_ENGINE_BING -> BrowserSettings.SEARCH_ENGINE_BING
                BrowserSettings.SEARCH_ENGINE_CUSTOM -> BrowserSettings.SEARCH_ENGINE_CUSTOM
                else -> BrowserSettings.SEARCH_ENGINE_GOOGLE
            },
            customSearchUrl = customSearchUrl.trim()
        )
        settingsState.value = next
        saveSettings(next)
    }

    fun updateToolbarPosition(toolbarPosition: String) {
        val next = settingsState.value.copy(
            toolbarPosition = BrowserSettings.normalizedToolbarPosition(toolbarPosition)
        )
        settingsState.value = next
        saveSettings(next)
    }

    fun updateWebsiteDisplayMode(mode: String) {
        val next = settingsState.value.copy(
            websiteDisplayMode = BrowserSettings.normalizedWebsiteDisplayMode(mode)
        )
        settingsState.value = next
        saveSettings(next)
    }

    fun updateBackgroundVideoEnhancement(enabled: Boolean) {
        val next = settingsState.value.copy(backgroundVideoEnhancementEnabled = enabled)
        settingsState.value = next
        saveSettings(next)
    }

    fun updateOpenNewTabsInCurrentTab(enabled: Boolean) {
        val next = settingsState.value.copy(openNewTabsInCurrentTab = enabled)
        settingsState.value = next
        saveSettings(next)
    }

    fun updateLocalePreference(localePreference: String) {
        val next = settingsState.value.copy(
            localePreference = BrowserSettings.normalizedLocalePreference(localePreference)
        )
        settingsState.value = next
        saveSettings(next)
    }

    fun updatePrivacySettings(
        dohEnabled: Boolean,
        dohProviderUrl: String,
        httpsOnlyEnabled: Boolean,
        privacyProtectionLevel: String
    ) {
        val next = settingsState.value.copy(
            dohEnabled = dohEnabled,
            dohProviderUrl = dohProviderUrl.trim().takeIf { it.startsWith("https://") }
                ?: BrowserSettings.DEFAULT_DOH_PROVIDER_URL,
            httpsOnlyEnabled = httpsOnlyEnabled,
            privacyProtectionLevel = when (privacyProtectionLevel) {
                BrowserSettings.PRIVACY_PROTECTION_NONE -> BrowserSettings.PRIVACY_PROTECTION_NONE
                BrowserSettings.PRIVACY_PROTECTION_STRICT -> BrowserSettings.PRIVACY_PROTECTION_STRICT
                else -> BrowserSettings.PRIVACY_PROTECTION_STANDARD
            }
        )
        settingsState.value = next
        saveSettings(next)
    }

    fun updateWebDavSyncSettings(
        enabled: Boolean,
        url: String,
        username: String,
        password: String,
        deviceName: String
    ): BrowserSettings {
        val current = settingsState.value
        val next = current.copy(
            webDavSyncEnabled = enabled,
            webDavSyncUrl = url.trim(),
            webDavSyncUsername = username.trim(),
            webDavSyncPassword = password,
            webDavSyncDeviceName = deviceName.trim(),
            webDavSyncDeviceId = current.webDavSyncDeviceId.ifBlank { UUID.randomUUID().toString() }
        )
        settingsState.value = next
        saveSettings(next)
        return next
    }

    private fun loadHistory(): List<BrowserHistoryEntry> {
        if (!historyFile.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(historyFile.readText())
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        BrowserHistoryEntry(
                            url = item.getString("url"),
                            title = item.optString("title"),
                            visitedAt = item.optLong("visitedAt"),
                            iconPath = item.optString("iconPath").ifBlank { null }
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun loadBookmarks(): List<BrowserBookmark> {
        val loaded = when {
            bookmarksFile.exists() -> loadBookmarksFromSyncFile(bookmarksFile)
            else -> emptyList()
        }
        val deduped = loaded.distinctBy { it.url }
        if (deduped != loaded || (!bookmarksFile.exists() && deduped.isNotEmpty())) saveBookmarks(deduped)
        return deduped
    }

    private fun loadBookmarksFromSyncFile(file: File): List<BrowserBookmark> =
        runCatching {
            val root = JSONObject(file.readText())
            val records = root.optJSONObject("bookmarks") ?: JSONObject()
            buildList {
                val keys = records.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val item = records.optJSONObject(key) ?: continue
                    val url = normalizeBookmarkUrl(item.optString("url").ifBlank { key })
                    if (url.isBlank()) continue
                    val iconPath = item.optCleanString("iconPath")
                        ?: faviconStore.saveIconDataUrl(url, item.optCleanString("iconDataUrl"))
                    add(
                        BrowserBookmark(
                            url = url,
                            title = item.optString("title").trim().ifBlank { url },
                            createdAt = item.optLong("createdAt").takeIf { it > 0 }
                                ?: item.optLong("updatedAt").takeIf { it > 0 }
                                ?: System.currentTimeMillis(),
                            iconPath = iconPath
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())

    private fun loadSettings(): BrowserSettings {
        return loadBrowserSettings(settingsFile)
    }

    private fun saveHistory(items: List<BrowserHistoryEntry>) {
        val array = JSONArray()
        items.forEach {
            array.put(
                JSONObject()
                    .put("url", it.url)
                    .put("title", it.title)
                    .put("visitedAt", it.visitedAt)
                    .put("iconPath", it.iconPath)
            )
        }
        historyFile.writeText(array.toString())
    }

    private fun saveBookmarks(items: List<BrowserBookmark>) {
        val now = System.currentTimeMillis()
        val existing = readJSONObject(bookmarksFile)
        val existingBookmarks = existing?.optJSONObject("bookmarks") ?: JSONObject()
        val tombstones = existing?.optJSONObject("bookmarkTombstones")?.deepCopy() ?: JSONObject()
        val nextBookmarks = JSONObject()
        val activeKeys = items.mapNotNull { normalizeBookmarkUrl(it.url).takeIf { url -> url.isNotBlank() } }.toSet()
        val existingKeys = existingBookmarks.keys().asSequence().toList()

        existingKeys.forEach { key ->
            if (key !in activeKeys && !tombstones.has(key)) {
                val rev = existingBookmarks.optJSONObject(key)?.optJSONObject("rev")
                tombstones.put(
                    key,
                    JSONObject()
                        .put("deletedAt", java.time.Instant.ofEpochMilli(now).toString())
                        .put("rev", syncRevisionJson(now, rev?.optString("deviceId").orEmpty()))
                )
            }
        }

        items.forEach { bookmark ->
            val url = normalizeBookmarkUrl(bookmark.url)
            if (url.isBlank()) return@forEach
            val existingRecord = existingBookmarks.optJSONObject(url)
            val iconDataUrl = faviconStore.iconDataUrl(bookmark.iconPath, url)
            val title = bookmark.title.trim().ifBlank { url }
            val createdAt = bookmark.createdAt.takeIf { it > 0 }
                ?: existingRecord?.optLong("createdAt")?.takeIf { it > 0 }
                ?: now
            val changed = existingRecord == null ||
                existingRecord.optString("url") != url ||
                existingRecord.optString("title") != title ||
                existingRecord.optCleanString("iconDataUrl") != iconDataUrl
            val existingRev = existingRecord?.optJSONObject("rev")
            val rev = if (changed) {
                syncRevisionJson(now, existingRev?.optString("deviceId").orEmpty())
            } else {
                existingRev?.deepCopy() ?: syncRevisionJson(createdAt, "")
            }
            nextBookmarks.put(
                url,
                JSONObject()
                    .put("url", url)
                    .put("title", title)
                    .put("createdAt", createdAt)
                    .put("updatedAt", if (changed) now else existingRecord.optLong("updatedAt").takeIf { it > 0 } ?: createdAt)
                    .putCleanNullable("iconDataUrl", iconDataUrl)
                    .put("rev", rev)
            )
            tombstones.remove(url)
        }

        bookmarksFile.writeText(
            JSONObject()
                .put("schemaVersion", 2)
                .put("bookmarks", nextBookmarks)
                .put("bookmarkTombstones", tombstones)
                .toString()
        )
    }

    private fun saveSettings(settings: BrowserSettings) {
        settingsFile.writeText(
            JSONObject()
                .put("searchEngineId", settings.searchEngineId)
                .put("customSearchUrl", settings.customSearchUrl)
                .put("toolbarPosition", settings.toolbarPosition)
                .put("websiteDisplayMode", BrowserSettings.normalizedWebsiteDisplayMode(settings.websiteDisplayMode))
                .put("backgroundVideoEnhancementEnabled", settings.backgroundVideoEnhancementEnabled)
                .put("openNewTabsInCurrentTab", settings.openNewTabsInCurrentTab)
                .put("dohEnabled", settings.dohEnabled)
                .put("dohProviderUrl", settings.dohProviderUrl)
                .put("httpsOnlyEnabled", settings.httpsOnlyEnabled)
                .put("privacyProtectionLevel", settings.privacyProtectionLevel)
                .put("localePreference", settings.localePreference)
                .put("webDavSyncEnabled", settings.webDavSyncEnabled)
                .put("webDavSyncUrl", settings.webDavSyncUrl)
                .put("webDavSyncUsername", settings.webDavSyncUsername)
                .put("webDavSyncPassword", settings.webDavSyncPassword)
                .put("webDavSyncDeviceName", settings.webDavSyncDeviceName)
                .put("webDavSyncDeviceId", settings.webDavSyncDeviceId)
                .put("privacySettingsVersion", CURRENT_PRIVACY_SETTINGS_VERSION)
                .toString()
        )
    }

    private fun readJSONObject(file: File): JSONObject? {
        if (!file.exists()) return null
        return runCatching { JSONObject(file.readText()) }.getOrNull()
    }

    private fun JSONObject.deepCopy(): JSONObject =
        JSONObject(toString())

    private fun syncRevisionJson(counter: Long, deviceId: String): JSONObject =
        JSONObject()
            .put("counter", counter.coerceAtLeast(0L))
            .put("deviceId", deviceId.trim())

    private fun isLauncherJson(layout: JSONObject): Boolean =
        layout.has("pages") || layout.has("rev") || launcherDockContainsCells(layout.optJSONArray("dock"))

    private fun launcherDockContainsCells(dock: JSONArray?): Boolean =
        dock != null && dock.length() > 0 && dock.opt(0) is JSONObject

    private fun launcherJsonToUiLayout(layout: JSONObject): JSONObject {
        if (!isLauncherJson(layout)) return layout
        val clean = normalizeLauncherJson(layout)
        val cells = JSONArray()
        val pages = clean.optJSONArray("pages") ?: JSONArray()
        for (pageIndex in 0 until pages.length()) {
            val page = pages.optJSONObject(pageIndex) ?: continue
            val pageCells = sortLauncherCells(page.optJSONArray("cells"))
            for (cellIndex in 0 until pageCells.length()) {
                val cell = pageCells.optJSONObject(cellIndex) ?: continue
                val id = cell.optString("id").trim()
                if (id.isBlank()) continue
                cells.put(
                    JSONObject()
                        .put("id", id)
                        .put("page", pageIndex)
                        .put("row", 0)
                        .put("column", 0)
                        .put("index", launcherIndex(cell, cellIndex))
                )
            }
        }

        val dock = JSONArray()
        val dockCells = sortLauncherCells(clean.optJSONArray("dock"))
        for (index in 0 until dockCells.length()) {
            val id = dockCells.optJSONObject(index)?.optString("id")?.trim().orEmpty()
            if (id.isNotBlank()) dock.put(id)
        }

        val folders = JSONArray()
        val sourceFolders = clean.optJSONArray("folders") ?: JSONArray()
        for (index in 0 until sourceFolders.length()) {
            val folder = sourceFolders.optJSONObject(index) ?: continue
            val id = folder.optString("id").trim()
            if (id.isBlank()) continue
            val childIds = JSONArray()
            val childCells = sortLauncherCells(folder.optJSONArray("cells"))
            for (childIndex in 0 until childCells.length()) {
                val childId = childCells.optJSONObject(childIndex)?.optString("id")?.trim().orEmpty()
                if (childId.isNotBlank()) childIds.put(childId)
            }
            folders.put(
                JSONObject()
                    .put("id", id)
                    .put("title", folder.optString("title").ifBlank { "Folder" })
                    .put("childIds", childIds)
            )
        }

        return JSONObject()
            .put("version", 4)
            .put("cells", cells)
            .put("dock", dock)
            .put("folders", folders)
    }

    private fun uiLayoutToLauncherJson(layout: JSONObject, counter: Long): JSONObject {
        val pagesByIndex = linkedMapOf<Int, MutableList<JSONObject>>()
        val cells = layout.optJSONArray("cells") ?: JSONArray()
        for (index in 0 until cells.length()) {
            val cell = cells.optJSONObject(index) ?: continue
            val id = cell.optString("id").trim()
            if (id.isBlank()) continue
            val page = launcherIndexField(cell, "page", 0)
            pagesByIndex.getOrPut(page) { mutableListOf() }
                .add(launcherCellJson(id, launcherIndex(cell, index)))
        }

        val pages = JSONArray()
        pagesByIndex.toSortedMap().values.forEach { pageCells ->
            pages.put(JSONObject().put("cells", JSONArray(pageCells.sortedWith(compareBy<JSONObject> { launcherIndex(it, 0) }.thenBy { it.optString("id") }))))
        }

        val dock = JSONArray()
        val uiDock = layout.optJSONArray("dock") ?: JSONArray()
        for (index in 0 until uiDock.length()) {
            val id = uiDock.optString(index).trim()
            if (id.isNotBlank()) dock.put(launcherCellJson(id, index))
        }

        val folders = JSONArray()
        val uiFolders = layout.optJSONArray("folders") ?: JSONArray()
        for (index in 0 until uiFolders.length()) {
            val folder = uiFolders.optJSONObject(index) ?: continue
            val id = folder.optString("id").trim()
            if (id.isBlank()) continue
            val childCells = JSONArray()
            val childIds = folder.optJSONArray("childIds") ?: JSONArray()
            for (childIndex in 0 until childIds.length()) {
                val childId = childIds.optString(childIndex).trim()
                if (childId.isNotBlank()) childCells.put(launcherCellJson(childId, childIndex))
            }
            folders.put(
                JSONObject()
                    .put("id", id)
                    .put("title", folder.optString("title").ifBlank { "Folder" })
                    .put("cells", childCells)
            )
        }

        val deviceId = readJSONObject(launcherLayoutFile)
            ?.optJSONObject("rev")
            ?.optString("deviceId")
            .orEmpty()
        return JSONObject()
            .apply { if (pages.length() > 0) put("pages", pages) }
            .apply { if (dock.length() > 0) put("dock", dock) }
            .apply { if (folders.length() > 0) put("folders", folders) }
            .put("rev", syncRevisionJson(counter, deviceId))
    }

    private fun normalizeLauncherJson(layout: JSONObject): JSONObject {
        val pages = JSONArray()
        val sourcePages = layout.optJSONArray("pages") ?: JSONArray()
        for (index in 0 until sourcePages.length()) {
            val page = sourcePages.optJSONObject(index) ?: continue
            val cells = sortLauncherCells(page.optJSONArray("cells"))
            if (cells.length() > 0) pages.put(JSONObject().put("cells", cells))
        }

        val dock = sortLauncherCells(layout.optJSONArray("dock"))
        val folders = JSONArray()
        val sourceFolders = layout.optJSONArray("folders") ?: JSONArray()
        for (index in 0 until sourceFolders.length()) {
            val folder = sourceFolders.optJSONObject(index) ?: continue
            val id = folder.optString("id").trim()
            if (id.isBlank()) continue
            folders.put(
                JSONObject()
                    .put("id", id)
                    .put("title", folder.optString("title").ifBlank { "Folder" })
                    .put("cells", sortLauncherCells(folder.optJSONArray("cells")))
            )
        }

        return JSONObject()
            .apply { if (pages.length() > 0) put("pages", pages) }
            .apply { if (dock.length() > 0) put("dock", dock) }
            .apply { if (folders.length() > 0) put("folders", folders) }
            .put("rev", layout.optJSONObject("rev")?.deepCopy() ?: syncRevisionJson(0L, ""))
    }

    private fun sortLauncherCells(cells: JSONArray?): JSONArray {
        val result = JSONArray()
        val values = buildList {
            if (cells == null) return@buildList
            for (index in 0 until cells.length()) {
                when (val raw = cells.opt(index)) {
                    is JSONObject -> {
                        val id = raw.optString("id").trim()
                        if (id.isNotBlank()) add(launcherCellJson(id, launcherIndex(raw, index)))
                    }
                    is String -> if (raw.trim().isNotBlank()) add(launcherCellJson(raw.trim(), index))
                }
            }
        }.sortedWith(compareBy<JSONObject> { launcherIndex(it, 0) }.thenBy { it.optString("id") })
        values.forEach { result.put(it) }
        return result
    }

    private fun launcherCellJson(id: String, index: Int): JSONObject =
        JSONObject()
            .put("id", id)
            .put("index", index.coerceAtLeast(0))

    private fun launcherIndex(cell: JSONObject, fallback: Int): Int =
        launcherIndexField(cell, "index", fallback)

    private fun launcherIndexField(cell: JSONObject, name: String, fallback: Int): Int {
        val value = cell.opt(name)
        return when (value) {
            is Number -> value.toInt().coerceAtLeast(0)
            is String -> value.toIntOrNull()?.coerceAtLeast(0) ?: fallback
            else -> fallback
        }
    }

    private fun tabSessionStateFile(tabId: String): File? {
        val fileName = tabSessionStateFileName(tabId) ?: return null
        return File(tabSessionStateDir, fileName)
    }

    private fun tabThumbnailFile(tabId: String): File? {
        val fileName = tabThumbnailFileName(tabId) ?: return null
        return File(tabThumbnailDir, fileName)
    }

    private fun tabSessionStateFileName(tabId: String): String? =
        tabFileName(tabId, extension = "session")

    private fun tabThumbnailFileName(tabId: String): String? =
        tabFileName(tabId, extension = "jpg")

    private fun tabFileName(tabId: String, extension: String): String? {
        if (tabId.isBlank()) return null
        val safeId = tabId.map { char ->
            when {
                char.isLetterOrDigit() || char == '-' || char == '_' || char == '.' -> char
                else -> '_'
            }
        }.joinToString("")
        return "$safeId.$extension"
    }

    private fun scaledTabThumbnail(bitmap: Bitmap): Bitmap {
        val scale = minOf(
            TAB_THUMBNAIL_MAX_WIDTH.toFloat() / bitmap.width,
            TAB_THUMBNAIL_MAX_HEIGHT.toFloat() / bitmap.height,
            1f
        )
        if (scale >= 1f) return bitmap
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true
        )
    }

    companion object {
        private const val SETTINGS_FILE_NAME = "browser_settings.json"
        private const val CURRENT_PRIVACY_SETTINGS_VERSION = 1
        private const val TAB_THUMBNAIL_MAX_WIDTH = 480
        private const val TAB_THUMBNAIL_MAX_HEIGHT = 720

        fun loadBrowserSettings(context: Context): BrowserSettings =
            loadBrowserSettings(File(context.filesDir, SETTINGS_FILE_NAME))

        private fun loadBrowserSettings(file: File): BrowserSettings {
            if (!file.exists()) return BrowserSettings()
            return runCatching {
                val item = JSONObject(file.readText())
                val privacySettingsVersion = item.optInt("privacySettingsVersion", 0)
                BrowserSettings(
                    searchEngineId = item.optString("searchEngineId", BrowserSettings.SEARCH_ENGINE_GOOGLE),
                    customSearchUrl = item.optString("customSearchUrl"),
                    toolbarPosition = BrowserSettings.normalizedToolbarPosition(
                        item.optString("toolbarPosition", BrowserSettings.TOOLBAR_POSITION_DYNAMIC_BOTTOM)
                    ),
                    websiteDisplayMode = BrowserSettings.normalizedWebsiteDisplayMode(
                        item.optString("websiteDisplayMode", BrowserSettings.WEBSITE_DISPLAY_MOBILE)
                    ),
                    backgroundVideoEnhancementEnabled = item.optBoolean("backgroundVideoEnhancementEnabled", false),
                    openNewTabsInCurrentTab = item.optBoolean("openNewTabsInCurrentTab", false),
                    dohEnabled = item.optBoolean("dohEnabled", false),
                    dohProviderUrl = item.optString(
                        "dohProviderUrl",
                        BrowserSettings.DEFAULT_DOH_PROVIDER_URL
                    ).takeIf { it.startsWith("https://") } ?: BrowserSettings.DEFAULT_DOH_PROVIDER_URL,
                    httpsOnlyEnabled = if (privacySettingsVersion > 0) {
                        item.optBoolean("httpsOnlyEnabled", false)
                    } else {
                        false
                    },
                    privacyProtectionLevel = when (item.optString("privacyProtectionLevel")) {
                        BrowserSettings.PRIVACY_PROTECTION_NONE ->
                            BrowserSettings.PRIVACY_PROTECTION_NONE
                        BrowserSettings.PRIVACY_PROTECTION_STRICT ->
                            BrowserSettings.PRIVACY_PROTECTION_STRICT
                        else -> BrowserSettings.PRIVACY_PROTECTION_STANDARD
                    },
                    localePreference = BrowserSettings.normalizedLocalePreference(
                        item.optString("localePreference", BrowserSettings.LOCALE_DEFAULT)
                    ),
                    webDavSyncEnabled = item.optBoolean("webDavSyncEnabled", false),
                    webDavSyncUrl = item.optString("webDavSyncUrl"),
                    webDavSyncUsername = item.optString("webDavSyncUsername"),
                    webDavSyncPassword = item.optString("webDavSyncPassword"),
                    webDavSyncDeviceName = item.optString("webDavSyncDeviceName"),
                    webDavSyncDeviceId = item.optString("webDavSyncDeviceId")
                )
            }.getOrDefault(BrowserSettings())
        }

    }
}
