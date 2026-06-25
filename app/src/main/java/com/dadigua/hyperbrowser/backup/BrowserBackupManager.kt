package com.dadigua.hyperbrowser.backup

import com.dadigua.hyperbrowser.browser.BrowserProfileStore
import com.dadigua.hyperbrowser.webapp.WebAppRepository
import org.json.JSONObject

class BrowserBackupManager(
    private val profileStore: BrowserProfileStore,
    private val webAppRepository: WebAppRepository
) {
    fun exportJson(): String =
        JSONObject()
            .put("type", BACKUP_TYPE)
            .put("version", BACKUP_VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put(
                "files",
                JSONObject()
                    .put(BOOKMARKS_FILE, profileStore.bookmarksSyncJson())
                    .put(WEBAPPS_FILE, webAppRepository.syncJson())
                    .put(LAUNCHER_FILE, profileStore.launcherSyncJson() ?: emptyLauncherJson())
            )
            .toString(2)

    fun importJson(raw: String): BrowserBackupImportResult {
        val root = JSONObject(raw)
        if (root.optString("type") != BACKUP_TYPE || root.optInt("version") != BACKUP_VERSION) {
            error("Unsupported backup format.")
        }
        val files = root.optJSONObject("files") ?: error("Backup is missing files.")
        val now = System.currentTimeMillis()
        val deviceId = profileStore.observeSettings().value.webDavSyncDeviceId.ifBlank { "local-backup-import" }
        val bookmarks = bumpBookmarksJson(files.requireJSONObject(BOOKMARKS_FILE), now, deviceId)
        val webApps = bumpWebAppsJson(files.requireJSONObject(WEBAPPS_FILE), now, deviceId)
        val launcher = bumpLauncherJson(files.requireJSONObject(LAUNCHER_FILE), now, deviceId)

        profileStore.saveBookmarksSyncJson(bookmarks)
        webAppRepository.saveSyncJson(webApps)
        profileStore.saveLauncherSyncJson(launcher)

        return BrowserBackupImportResult(
            bookmarks = bookmarks.optJSONObject("bookmarks")?.length() ?: 0,
            webApps = webApps.optJSONObject("apps")?.length() ?: 0
        )
    }

    private fun bumpBookmarksJson(json: JSONObject, counter: Long, deviceId: String): JSONObject {
        val next = json.deepCopy()
        requireSchema(next)
        bumpRecordMap(next.requireJSONObject("bookmarks"), counter, deviceId)
        bumpRecordMap(next.requireJSONObject("bookmarkTombstones"), counter, deviceId)
        return next
    }

    private fun bumpWebAppsJson(json: JSONObject, counter: Long, deviceId: String): JSONObject {
        val next = json.deepCopy()
        requireSchema(next)
        bumpRecordMap(next.requireJSONObject("apps"), counter, deviceId)
        bumpRecordMap(next.requireJSONObject("appTombstones"), counter, deviceId)
        return next
    }

    private fun bumpLauncherJson(json: JSONObject, counter: Long, deviceId: String): JSONObject {
        val next = json.deepCopy()
        next.put("rev", syncRevisionJson(counter, deviceId))
        return next
    }

    private fun bumpRecordMap(records: JSONObject, counter: Long, deviceId: String) {
        val keys = records.keys().asSequence().toList()
        keys.forEach { key ->
            records.optJSONObject(key)?.put("rev", syncRevisionJson(counter, deviceId))
        }
    }

    private fun requireSchema(json: JSONObject) {
        if (json.optInt("schemaVersion") != SYNC_SCHEMA_VERSION) {
            error("Unsupported backup file schema.")
        }
    }

    private fun JSONObject.requireJSONObject(name: String): JSONObject =
        optJSONObject(name) ?: error("Backup is missing $name.")

    private fun JSONObject.deepCopy(): JSONObject =
        JSONObject(toString())

    private fun emptyLauncherJson(): JSONObject =
        JSONObject().put("rev", syncRevisionJson(0L, ""))

    private fun syncRevisionJson(counter: Long, deviceId: String): JSONObject =
        JSONObject()
            .put("counter", counter.coerceAtLeast(0L))
            .put("deviceId", deviceId.trim())

    private companion object {
        const val BACKUP_TYPE = "hyper-browser-backup"
        const val BACKUP_VERSION = 2
        const val SYNC_SCHEMA_VERSION = 2
        const val BOOKMARKS_FILE = "bookmarks.json"
        const val WEBAPPS_FILE = "webapps.json"
        const val LAUNCHER_FILE = "launcher.json"
    }
}

data class BrowserBackupImportResult(
    val bookmarks: Int,
    val webApps: Int
)
