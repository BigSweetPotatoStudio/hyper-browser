package com.dadigua.hyperbrowser.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.dadigua.hyperbrowser.data.AtomicFileWriter
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
    val floatingDotXRatio: Float = FLOATING_DOT_POSITION_UNSET,
    val floatingDotYRatio: Float = FLOATING_DOT_POSITION_UNSET,
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
        const val TOOLBAR_POSITION_FLOATING_DOT = "floating_dot"
        const val FLOATING_DOT_POSITION_UNSET = -1f
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
                TOOLBAR_POSITION_FLOATING_DOT -> TOOLBAR_POSITION_FLOATING_DOT
                else -> TOOLBAR_POSITION_DYNAMIC_BOTTOM
            }

        fun isBottomToolbarPosition(value: String): Boolean =
            value == TOOLBAR_POSITION_BOTTOM || value == TOOLBAR_POSITION_DYNAMIC_BOTTOM

        fun isDynamicBottomToolbarPosition(value: String): Boolean =
            value == TOOLBAR_POSITION_DYNAMIC_BOTTOM

        fun isFloatingDotToolbarPosition(value: String): Boolean =
            value == TOOLBAR_POSITION_FLOATING_DOT

        fun normalizedFloatingDotRatio(value: Float): Float =
            if (value.isFinite() && value in 0f..1f) value else FLOATING_DOT_POSITION_UNSET

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
        return emptyBookmarksJson()
    }

    fun saveBookmarksSyncJson(json: JSONObject) {
        AtomicFileWriter.writeText(bookmarksFile, json.deepCopy().toString())
        bookmarksState.value = loadBookmarksFromSyncFile(bookmarksFile).distinctBy { it.url }
    }

    fun loadSavedTabs(): SavedBrowserTabs {
        if (!tabsFile.exists()) return SavedBrowserTabs(selectedTabId = null, tabs = emptyList())
        return BrowserTabPersistenceCodec.decode(tabsFile.readText())
    }

    fun saveTabs(state: SavedBrowserTabs) {
        AtomicFileWriter.writeText(tabsFile, BrowserTabPersistenceCodec.encode(state))
    }

    fun loadLauncherLayout(): JSONObject? {
        val layout = readJSONObject(launcherLayoutFile)
        return layout?.let { normalizeLauncherJson(it) }
    }

    fun launcherSyncJson(): JSONObject? =
        readJSONObject(launcherLayoutFile)?.let { normalizeLauncherJson(it) }

    fun saveLauncherSyncJson(json: JSONObject) {
        AtomicFileWriter.writeText(launcherLayoutFile, json.deepCopy().toString())
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
        AtomicFileWriter.writeText(file, state)
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

    fun isBookmarked(url: String): Boolean {
        val cleanUrl = normalizeBookmarkUrl(url)
        return cleanUrl.isNotBlank() && bookmarksState.value.any { normalizeBookmarkUrl(it.url) == cleanUrl }
    }

    fun updateBookmarkIcon(url: String, iconPath: String) {
        if (url.isBlank() || iconPath.isBlank()) return
        val cleanUrl = normalizeBookmarkUrl(url)
        if (cleanUrl.isBlank()) return
        val validIconPath = faviconStore.existingIconPath(iconPath) ?: return
        val current = bookmarksState.value
        val next = current.map { bookmark ->
            if (normalizeBookmarkUrl(bookmark.url) == cleanUrl && bookmark.iconPath != validIconPath) {
                bookmark.copy(iconPath = validIconPath)
            } else {
                bookmark
            }
        }
        if (next != current) {
            // Icon paths are local display cache only; bookmarks.json owns synced bookmark data.
            bookmarksState.value = next
        }
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

    fun updateFloatingDotPosition(xRatio: Float, yRatio: Float) {
        val next = settingsState.value.copy(
            floatingDotXRatio = BrowserSettings.normalizedFloatingDotRatio(xRatio),
            floatingDotYRatio = BrowserSettings.normalizedFloatingDotRatio(yRatio)
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
        return orderBookmarksByAddedAt(loaded)
    }

    private fun orderBookmarksByAddedAt(items: List<BrowserBookmark>): List<BrowserBookmark> =
        items.distinctBy { it.url }
            .sortedWith(
                compareByDescending<BrowserBookmark> { it.createdAt }
                    .thenBy { it.title }
                    .thenBy { it.url }
            )

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
                    add(
                        BrowserBookmark(
                            url = url,
                            title = item.optString("title").trim().ifBlank { url },
                            createdAt = item.optLong("createdAt").takeIf { it > 0 }
                                ?: item.optLong("updatedAt").takeIf { it > 0 }
                                ?: System.currentTimeMillis(),
                            iconPath = null
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
        AtomicFileWriter.writeText(historyFile, array.toString())
    }

    private fun saveSettings(settings: BrowserSettings) {
        AtomicFileWriter.writeText(
            settingsFile,
            JSONObject()
                .put("searchEngineId", settings.searchEngineId)
                .put("customSearchUrl", settings.customSearchUrl)
                .put("toolbarPosition", settings.toolbarPosition)
                .put("floatingDotXRatio", settings.floatingDotXRatio)
                .put("floatingDotYRatio", settings.floatingDotYRatio)
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
                .toString(),
        )
    }

    private fun readJSONObject(file: File): JSONObject? {
        if (!file.exists()) return null
        return runCatching { JSONObject(file.readText()) }.getOrNull()
    }

    private fun JSONObject.deepCopy(): JSONObject =
        JSONObject(toString())

    private fun emptyBookmarksJson(): JSONObject =
        JSONObject()
            .put("schemaVersion", 2)
            .put("bookmarks", JSONObject())
            .put("bookmarkTombstones", JSONObject())

    private fun syncRevisionJson(updatedAt: Long, deviceId: String): JSONObject =
        JSONObject()
            .put("updatedAt", updatedAt.coerceAtLeast(0L))
            .put("deviceId", deviceId.trim())

    private fun normalizeLauncherJson(layout: JSONObject): JSONObject {
        val pages = JSONArray()
        val sourcePages = layout.optJSONArray("pages") ?: JSONArray()
        val firstPage = sourcePages.optJSONObject(0)
        val firstPageCells = sortLauncherCells(firstPage?.optJSONArray("cells"))
        if (firstPageCells.length() > 0) pages.put(JSONObject().put("cells", firstPageCells))

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
                    floatingDotXRatio = BrowserSettings.normalizedFloatingDotRatio(
                        item.optDouble("floatingDotXRatio", BrowserSettings.FLOATING_DOT_POSITION_UNSET.toDouble()).toFloat()
                    ),
                    floatingDotYRatio = BrowserSettings.normalizedFloatingDotRatio(
                        item.optDouble("floatingDotYRatio", BrowserSettings.FLOATING_DOT_POSITION_UNSET.toDouble()).toFloat()
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
