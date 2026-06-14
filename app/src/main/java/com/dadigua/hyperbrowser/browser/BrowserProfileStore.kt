package com.dadigua.hyperbrowser.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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

data class BrowserSettings(
    val searchEngineId: String = SEARCH_ENGINE_GOOGLE,
    val customSearchUrl: String = "",
    val toolbarPosition: String = TOOLBAR_POSITION_TOP,
    val backgroundVideoEnhancementEnabled: Boolean = false,
    val dohEnabled: Boolean = false,
    val dohProviderUrl: String = DEFAULT_DOH_PROVIDER_URL,
    val httpsOnlyEnabled: Boolean = false,
    val privacyProtectionLevel: String = PRIVACY_PROTECTION_STANDARD
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
        const val DEFAULT_DOH_PROVIDER_URL = "https://mozilla.cloudflare-dns.com/dns-query"
        const val PRIVACY_PROTECTION_NONE = "none"
        const val PRIVACY_PROTECTION_STANDARD = "standard"
        const val PRIVACY_PROTECTION_STRICT = "strict"
    }
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
    private val bookmarksFile = File(context.filesDir, "browser_bookmarks.json")
    private val settingsFile = File(context.filesDir, SETTINGS_FILE_NAME)
    private val tabsFile = File(context.filesDir, "browser_tabs.json")
    private val tabSessionStateDir = File(context.filesDir, "browser_tab_states")
    private val tabThumbnailDir = File(context.filesDir, "browser_tab_thumbnails")
    private val historyState = MutableStateFlow(loadHistory())
    private val bookmarksState = MutableStateFlow(loadBookmarks())
    private val settingsState = MutableStateFlow(loadSettings())

    fun observeHistory(): StateFlow<List<BrowserHistoryEntry>> = historyState
    fun observeBookmarks(): StateFlow<List<BrowserBookmark>> = bookmarksState
    fun observeSettings(): StateFlow<BrowserSettings> = settingsState

    fun mergeBookmarks(imported: List<BrowserBookmark>): Int {
        val accepted = mutableListOf<BrowserBookmark>()
        val seenUrls = mutableSetOf<String>()
        val existingByUrl = bookmarksState.value.associateBy { it.url }
        imported.forEach { bookmark ->
            val url = bookmark.url.trim()
            if (url.isBlank() || !seenUrls.add(url)) return@forEach
            val existing = existingByUrl[url]
            accepted.add(
                BrowserBookmark(
                    url = url,
                    title = bookmark.title.trim().ifBlank { existing?.title ?: url },
                    createdAt = bookmark.createdAt.takeIf { it > 0 }
                        ?: existing?.createdAt
                        ?: System.currentTimeMillis(),
                    iconPath = bookmark.iconPath ?: existing?.iconPath
                )
            )
        }
        if (accepted.isEmpty()) return 0
        val importedUrls = accepted.map { it.url }.toSet()
        val next = accepted + bookmarksState.value.filterNot { it.url in importedUrls }
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
        if (url.isBlank()) return
        val current = bookmarksState.value
        val next = if (current.any { it.url == url }) {
            current.filterNot { it.url == url }
        } else {
            listOf(
                BrowserBookmark(
                    url = url,
                    title = title.ifBlank { url },
                    createdAt = System.currentTimeMillis(),
                    iconPath = iconPath
                )
            ) + current
        }
        bookmarksState.value = next
        saveBookmarks(next)
    }

    fun isBookmarked(url: String): Boolean = bookmarksState.value.any { it.url == url }

    fun updateBookmarkIcon(url: String, iconPath: String) {
        if (url.isBlank() || iconPath.isBlank()) return
        val next = bookmarksState.value.map { bookmark ->
            if (bookmark.url == url && bookmark.iconPath != iconPath) {
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
        if (url.isBlank()) return
        val next = bookmarksState.value.filterNot { it.url == url }
        bookmarksState.value = next
        saveBookmarks(next)
    }

    fun editBookmark(oldUrl: String, title: String, url: String) {
        if (oldUrl.isBlank() || url.isBlank()) return
        val next = bookmarksState.value.map { bookmark ->
            if (bookmark.url == oldUrl) {
                bookmark.copy(url = url, title = title.ifBlank { url })
            } else {
                bookmark
            }
        }
        bookmarksState.value = next
        saveBookmarks(next)
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
            toolbarPosition = when (toolbarPosition) {
                BrowserSettings.TOOLBAR_POSITION_BOTTOM -> BrowserSettings.TOOLBAR_POSITION_BOTTOM
                else -> BrowserSettings.TOOLBAR_POSITION_TOP
            }
        )
        settingsState.value = next
        saveSettings(next)
    }

    fun updateBackgroundVideoEnhancement(enabled: Boolean) {
        val next = settingsState.value.copy(backgroundVideoEnhancementEnabled = enabled)
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
        if (!bookmarksFile.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(bookmarksFile.readText())
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        BrowserBookmark(
                            url = item.getString("url"),
                            title = item.optString("title"),
                            createdAt = item.optLong("createdAt"),
                            iconPath = item.optString("iconPath").ifBlank { null }
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

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
        val array = JSONArray()
        items.forEach {
            array.put(
                JSONObject()
                    .put("url", it.url)
                    .put("title", it.title)
                    .put("createdAt", it.createdAt)
                    .put("iconPath", it.iconPath)
            )
        }
        bookmarksFile.writeText(array.toString())
    }

    private fun saveSettings(settings: BrowserSettings) {
        settingsFile.writeText(
            JSONObject()
                .put("searchEngineId", settings.searchEngineId)
                .put("customSearchUrl", settings.customSearchUrl)
                .put("toolbarPosition", settings.toolbarPosition)
                .put("backgroundVideoEnhancementEnabled", settings.backgroundVideoEnhancementEnabled)
                .put("dohEnabled", settings.dohEnabled)
                .put("dohProviderUrl", settings.dohProviderUrl)
                .put("httpsOnlyEnabled", settings.httpsOnlyEnabled)
                .put("privacyProtectionLevel", settings.privacyProtectionLevel)
                .put("privacySettingsVersion", CURRENT_PRIVACY_SETTINGS_VERSION)
                .toString()
        )
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
                    toolbarPosition = item.optString("toolbarPosition", BrowserSettings.TOOLBAR_POSITION_TOP),
                    backgroundVideoEnhancementEnabled = item.optBoolean("backgroundVideoEnhancementEnabled", false),
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
                    }
                )
            }.getOrDefault(BrowserSettings())
        }
    }
}
