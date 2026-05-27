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
    val visitedAt: Long
)

data class BrowserBookmark(
    val url: String,
    val title: String,
    val createdAt: Long
)

class BrowserProfileStore(context: Context) {
    private val historyFile = File(context.filesDir, "browser_history.json")
    private val bookmarksFile = File(context.filesDir, "browser_bookmarks.json")
    private val historyState = MutableStateFlow(loadHistory())
    private val bookmarksState = MutableStateFlow(loadBookmarks())

    fun observeHistory(): StateFlow<List<BrowserHistoryEntry>> = historyState
    fun observeBookmarks(): StateFlow<List<BrowserBookmark>> = bookmarksState

    fun recordVisit(url: String, title: String) {
        if (url.isBlank()) return
        val entry = BrowserHistoryEntry(url = url, title = title.ifBlank { url }, visitedAt = System.currentTimeMillis())
        val next = (listOf(entry) + historyState.value.filterNot { it.url == url }).take(100)
        historyState.value = next
        saveHistory(next)
    }

    fun toggleBookmark(url: String, title: String) {
        if (url.isBlank()) return
        val current = bookmarksState.value
        val next = if (current.any { it.url == url }) {
            current.filterNot { it.url == url }
        } else {
            listOf(BrowserBookmark(url = url, title = title.ifBlank { url }, createdAt = System.currentTimeMillis())) + current
        }
        bookmarksState.value = next
        saveBookmarks(next)
    }

    fun isBookmarked(url: String): Boolean = bookmarksState.value.any { it.url == url }

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
                            visitedAt = item.optLong("visitedAt")
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
                            createdAt = item.optLong("createdAt")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveHistory(items: List<BrowserHistoryEntry>) {
        val array = JSONArray()
        items.forEach {
            array.put(JSONObject().put("url", it.url).put("title", it.title).put("visitedAt", it.visitedAt))
        }
        historyFile.writeText(array.toString())
    }

    private fun saveBookmarks(items: List<BrowserBookmark>) {
        val array = JSONArray()
        items.forEach {
            array.put(JSONObject().put("url", it.url).put("title", it.title).put("createdAt", it.createdAt))
        }
        bookmarksFile.writeText(array.toString())
    }
}
