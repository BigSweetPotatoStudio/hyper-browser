package com.dadigua.hyperbrowser.browser

import org.json.JSONArray
import org.json.JSONObject

object BrowserTabPersistenceCodec {
    fun decode(raw: String): SavedBrowserTabs =
        runCatching {
            val item = JSONObject(raw)
            val array = item.optJSONArray("tabs") ?: JSONArray()
            val tabs = buildList {
                for (index in 0 until array.length()) {
                    val tab = array.optJSONObject(index) ?: continue
                    val id = tab.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val url = tab.optString("url").takeIf { it.isNotBlank() } ?: continue
                    add(
                        SavedBrowserTab(
                            id = id,
                            title = tab.optString("title"),
                            url = url,
                            input = tab.optString("input").ifBlank { url },
                            iconPath = tab.optString("iconPath").ifBlank { null },
                            loaded = tab.optBoolean("loaded", true)
                        )
                    )
                }
            }
            SavedBrowserTabs(
                selectedTabId = item.optString("selectedTabId").ifBlank { null },
                tabs = tabs
            )
        }.getOrDefault(SavedBrowserTabs(selectedTabId = null, tabs = emptyList()))

    fun encode(state: SavedBrowserTabs): String {
        val array = JSONArray()
        state.tabs.forEach {
            if (it.id.isBlank() || it.url.isBlank()) return@forEach
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("title", it.title)
                    .put("url", it.url)
                    .put("input", it.input.ifBlank { it.url })
                    .put("iconPath", it.iconPath)
                    .put("loaded", it.loaded)
            )
        }
        return JSONObject()
            .put("selectedTabId", state.selectedTabId)
            .put("tabs", array)
            .toString()
    }
}
