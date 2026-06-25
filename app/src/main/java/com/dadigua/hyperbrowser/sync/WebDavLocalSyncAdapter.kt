package com.dadigua.hyperbrowser.sync

import com.dadigua.hyperbrowser.browser.BrowserProfileStore
import com.dadigua.hyperbrowser.webapp.WebAppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class WebDavLocalSyncAdapter(
    private val profileStore: BrowserProfileStore,
    private val webAppRepository: WebAppRepository
) {
    fun readSyncFile(path: String): JSONObject? =
        when (path.trim()) {
            BOOKMARKS_FILE -> profileStore.bookmarksSyncJson()
            WEBAPPS_FILE -> webAppRepository.syncJson()
            LAUNCHER_FILE -> profileStore.launcherSyncJson()
            else -> throw IllegalArgumentException("Unsupported sync file: $path")
        }

    suspend fun saveSyncFile(path: String, content: String): Unit = withContext(Dispatchers.IO) {
        val json = JSONObject(content)
        when (path.trim()) {
            BOOKMARKS_FILE -> profileStore.saveBookmarksSyncJson(json)
            WEBAPPS_FILE -> webAppRepository.saveSyncJson(json)
            LAUNCHER_FILE -> profileStore.saveLauncherSyncJson(json)
            else -> throw IllegalArgumentException("Unsupported sync file: $path")
        }
    }
}

private const val BOOKMARKS_FILE = "bookmarks.json"
private const val WEBAPPS_FILE = "webapps.json"
private const val LAUNCHER_FILE = "launcher.json"
