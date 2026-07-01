package com.dadigua.hyperbrowser.backup

import com.dadigua.hyperbrowser.browser.BrowserProfileStore
import com.dadigua.hyperbrowser.sync.WebDavLocalSyncAdapter
import com.dadigua.hyperbrowser.webapp.WebAppRepository
import org.json.JSONObject

private const val BACKUP_TYPE = "hyper-browser-backup"
private const val BACKUP_VERSION = 2
private const val SYNC_SCHEMA_VERSION = 2
private const val BOOKMARKS_FILE = "bookmarks.json"
private const val WEBAPPS_FILE = "webapps.json"
private const val LAUNCHER_FILE = "launcher.json"

class BrowserBackupManager(
    private val profileStore: BrowserProfileStore,
    private val webAppRepository: WebAppRepository
) {
    private val syncFileAdapter = WebDavLocalSyncAdapter(profileStore, webAppRepository)

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

    suspend fun importJson(raw: String): BrowserBackupImportResult {
        val files = parseBrowserBackupFiles(raw)
        val now = System.currentTimeMillis()
        val deviceId = profileStore.observeSettings().value.webDavSyncDeviceId.ifBlank { "local-backup-import" }
        val bookmarks = bumpBookmarksJson(files.bookmarks, now, deviceId)
        val webApps = bumpWebAppsJson(files.webApps, now, deviceId)
        val launcher = bumpLauncherJson(files.launcher, now, deviceId)

        syncFileAdapter.saveSyncFile(BOOKMARKS_FILE, bookmarks.toString())
        syncFileAdapter.saveSyncFile(WEBAPPS_FILE, webApps.toString())
        syncFileAdapter.saveSyncFile(LAUNCHER_FILE, launcher.toString())

        return BrowserBackupImportResult(
            bookmarks = bookmarks.optJSONObject("bookmarks")?.length() ?: 0,
            webApps = webApps.optJSONObject("apps")?.length() ?: 0
        )
    }

    private fun bumpBookmarksJson(json: JSONObject, updatedAt: Long, deviceId: String): JSONObject {
        val next = json.deepCopy()
        bumpRecordMap(next.requireJSONObject("bookmarks"), updatedAt, deviceId)
        bumpRecordMap(next.requireJSONObject("bookmarkTombstones"), updatedAt, deviceId)
        return next
    }

    private fun bumpWebAppsJson(json: JSONObject, updatedAt: Long, deviceId: String): JSONObject {
        val next = json.deepCopy()
        bumpRecordMap(next.requireJSONObject("apps"), updatedAt, deviceId)
        bumpRecordMap(next.requireJSONObject("appTombstones"), updatedAt, deviceId)
        return next
    }

    private fun bumpLauncherJson(json: JSONObject, updatedAt: Long, deviceId: String): JSONObject {
        val next = json.deepCopy()
        next.put("rev", syncRevisionJson(updatedAt, deviceId))
        return next
    }

    private fun bumpRecordMap(records: JSONObject, updatedAt: Long, deviceId: String) {
        val keys = records.keys().asSequence().toList()
        keys.forEach { key ->
            records.optJSONObject(key)?.put("rev", syncRevisionJson(updatedAt, deviceId))
        }
    }

    private fun JSONObject.requireJSONObject(name: String): JSONObject =
        optJSONObject(name) ?: error("Backup is missing $name.")

    private fun JSONObject.deepCopy(): JSONObject =
        JSONObject(toString())

    private fun emptyLauncherJson(): JSONObject =
        JSONObject().put("rev", syncRevisionJson(0L, ""))

    private fun syncRevisionJson(updatedAt: Long, deviceId: String): JSONObject =
        JSONObject()
            .put("updatedAt", updatedAt.coerceAtLeast(0L))
            .put("deviceId", deviceId.trim())

}

data class BrowserBackupImportResult(
    val bookmarks: Int,
    val webApps: Int
)

data class BrowserBackupImportPreview(
    val bookmarks: Int,
    val webApps: Int
)

internal fun previewBrowserBackupImport(raw: String): BrowserBackupImportPreview {
    val files = parseBrowserBackupFiles(raw)
    return BrowserBackupImportPreview(
        bookmarks = files.bookmarks.optJSONObject("bookmarks")?.length() ?: 0,
        webApps = files.webApps.optJSONObject("apps")?.length() ?: 0
    )
}

private data class BrowserBackupFiles(
    val bookmarks: JSONObject,
    val webApps: JSONObject,
    val launcher: JSONObject
)

private fun parseBrowserBackupFiles(raw: String): BrowserBackupFiles {
    val root = JSONObject(raw)
    if (root.optString("type") != BACKUP_TYPE || root.optInt("version") != BACKUP_VERSION) {
        error("Unsupported backup format.")
    }
    val files = root.optJSONObject("files") ?: error("Backup is missing files.")
    val bookmarks = files.requireJSONObject(BOOKMARKS_FILE)
    val webApps = files.requireJSONObject(WEBAPPS_FILE)
    val launcher = files.requireJSONObject(LAUNCHER_FILE)
    requireSchema(bookmarks)
    requireSchema(webApps)
    return BrowserBackupFiles(bookmarks, webApps, launcher)
}

private fun requireSchema(json: JSONObject) {
    if (json.optInt("schemaVersion") != SYNC_SCHEMA_VERSION) {
        error("Unsupported backup file schema.")
    }
}

private fun JSONObject.requireJSONObject(name: String): JSONObject =
    optJSONObject(name) ?: error("Backup is missing $name.")
