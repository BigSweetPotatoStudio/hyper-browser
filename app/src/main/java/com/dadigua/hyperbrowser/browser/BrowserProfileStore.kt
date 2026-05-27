package com.dadigua.hyperbrowser.browser

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
    val toolbarPosition: String = TOOLBAR_POSITION_TOP
) {
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
    }
}

class BrowserProfileStore(context: Context) {
    private val historyFile = File(context.filesDir, "browser_history.json")
    private val bookmarksFile = File(context.filesDir, "browser_bookmarks.json")
    private val settingsFile = File(context.filesDir, "browser_settings.json")
    private val historyState = MutableStateFlow(loadHistory())
    private val bookmarksState = MutableStateFlow(loadBookmarks())
    private val settingsState = MutableStateFlow(loadSettings())

    fun observeHistory(): StateFlow<List<BrowserHistoryEntry>> = historyState
    fun observeBookmarks(): StateFlow<List<BrowserBookmark>> = bookmarksState
    fun observeSettings(): StateFlow<BrowserSettings> = settingsState

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
        if (!settingsFile.exists()) return BrowserSettings()
        return runCatching {
            val item = JSONObject(settingsFile.readText())
            BrowserSettings(
                searchEngineId = item.optString("searchEngineId", BrowserSettings.SEARCH_ENGINE_GOOGLE),
                customSearchUrl = item.optString("customSearchUrl"),
                toolbarPosition = item.optString("toolbarPosition", BrowserSettings.TOOLBAR_POSITION_TOP)
            )
        }.getOrDefault(BrowserSettings())
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
                .toString()
        )
    }
}
