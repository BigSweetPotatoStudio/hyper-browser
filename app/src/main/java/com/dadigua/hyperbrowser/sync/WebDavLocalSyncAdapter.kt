package com.dadigua.hyperbrowser.sync

import android.content.Context
import android.graphics.Color
import com.dadigua.hyperbrowser.browser.BrowserBookmark
import com.dadigua.hyperbrowser.browser.BrowserProfileStore
import com.dadigua.hyperbrowser.browser.BrowserSettings
import com.dadigua.hyperbrowser.browser.FaviconRepository
import com.dadigua.hyperbrowser.browser.normalizeBookmarkUrl
import com.dadigua.hyperbrowser.data.WebAppDefinition
import com.dadigua.hyperbrowser.webapp.WebAppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class WebDavLocalSyncAdapter(
    context: Context,
    private val profileStore: BrowserProfileStore,
    private val webAppRepository: WebAppRepository,
    private val faviconStore: FaviconRepository
) {
    fun localData(settings: BrowserSettings): JSONObject {
        val config = WebDavSyncConfig.from(settings)
        val bookmarks = profileStore.bookmarksSyncJson()
        val webApps = webAppRepository.syncJson()
        return JSONObject()
            .put("deviceId", config.deviceId)
            .put("deviceName", config.deviceName)
            .put("bookmarks", bookmarks.optJSONObject("bookmarks") ?: JSONObject())
            .put("bookmarkTombstones", bookmarks.optJSONObject("bookmarkTombstones") ?: JSONObject())
            .put("webApps", webApps.optJSONObject("apps") ?: JSONObject())
            .put("appTombstones", webApps.optJSONObject("appTombstones") ?: JSONObject())
    }

    suspend fun applyRecords(
        settings: BrowserSettings,
        bookmarksJson: String,
        webAppsJson: String
    ): WebDavSyncResult = withContext(Dispatchers.IO) {
        val config = WebDavSyncConfig.from(settings)
        val now = System.currentTimeMillis()
        val bookmarkRecords = parseBookmarkRecordsPayload(bookmarksJson)
        val webAppRecords = webAppRecordsById(parseWebAppRecordsPayload(webAppsJson)).values.toList()
        val appliedBookmarks = applyBookmarks(bookmarkRecords)
        val appliedWebApps = applyWebApps(webAppRecords)
        WebDavSyncResult(
            bookmarkCount = bookmarkRecords.size,
            webAppCount = webAppRecords.size,
            deletedBookmarkCount = countTombstones(bookmarksJson, "bookmarkTombstones"),
            deletedWebAppCount = countTombstones(webAppsJson, "appTombstones"),
            importedBookmarkCount = appliedBookmarks.imported,
            removedBookmarkCount = appliedBookmarks.removed,
            importedWebAppCount = appliedWebApps.imported,
            removedWebAppCount = appliedWebApps.removed,
            syncedAt = now,
            deviceId = config.deviceId,
            uploadedOperationCount = 0,
            remoteOperationCount = 0,
            pendingOperationCount = 0
        )
    }

    private fun applyBookmarks(records: Collection<SyncBookmarkRecord>): ApplyCounts {
        val currentItems = profileStore.observeBookmarks().value
        val currentByKey = currentItems
            .mapNotNull { bookmark ->
                val key = bookmarkUrlKey(bookmark.url)
                if (key.isBlank()) null else key to bookmark
            }
            .toMap()
        val activeRecords = bookmarkRecordsById(records)
        var removed = 0
        val imports = mutableListOf<BrowserBookmark>()
        currentItems.forEach { current ->
            val key = bookmarkUrlKey(current.url)
            if (key !in activeRecords) {
                profileStore.removeBookmark(current.url)
                removed += 1
            }
        }
        activeRecords.values.sortedWith(compareBy<SyncBookmarkRecord> { it.title }.thenBy { it.url })
            .forEach { record ->
            val current = currentByKey[bookmarkRecordKey(record)]
            if (current == null ||
                current.url != record.url ||
                current.title != record.title ||
                faviconStore.iconDataUrl(current.iconPath, current.url) != record.iconDataUrl
            ) {
                imports += BrowserBookmark(
                    url = record.url,
                    title = record.title.ifBlank { record.url },
                    createdAt = record.createdAt.takeIf { it > 0 } ?: record.updatedAt,
                    iconPath = faviconStore.saveIconDataUrl(record.url, record.iconDataUrl)
                )
            }
        }
        val imported = if (imports.isEmpty()) 0 else profileStore.mergeBookmarks(imports)
        return ApplyCounts(imported = imported, removed = removed)
    }

    private suspend fun applyWebApps(records: Collection<SyncWebAppRecord>): ApplyCounts {
        val currentById = webAppRepository.observeAll().value.associateBy { it.id }
        val activeRecords = webAppRecordsById(records)
        var removed = 0
        val imports = mutableListOf<WebAppDefinition>()
        currentById.values.forEach { current ->
            if (current.id !in activeRecords) {
                if (webAppRepository.delete(current.id)) removed += 1
            }
        }
        activeRecords.values.forEach { record ->
            val current = currentById[record.id]
            if (current == null ||
                current.name != record.name ||
                current.startUrl != record.startUrl ||
                current.themeColor != record.themeColor ||
                current.displayMode != record.displayMode ||
                webAppRepository.iconDataUrl(current) != record.iconDataUrl ||
                webAppRepository.iconSource(current) != record.normalizedIconSource()
            ) {
                imports += WebAppDefinition(
                    id = record.id.ifBlank { UUID.randomUUID().toString() },
                    name = record.name.ifBlank { record.startUrl },
                    startUrl = record.startUrl,
                    iconPath = saveSyncedWebAppIcon(record),
                    themeColor = record.themeColor,
                    displayMode = record.displayMode.ifBlank { "standalone" },
                    createdAt = record.createdAt.takeIf { it > 0 } ?: record.updatedAt,
                    lastOpenedAt = record.lastOpenedAt.takeIf { it > 0 } ?: record.updatedAt
                )
            }
        }
        val imported = if (imports.isEmpty()) 0 else webAppRepository.mergeImported(imports)
        return ApplyCounts(imported = imported, removed = removed)
    }

    private fun saveSyncedWebAppIcon(record: SyncWebAppRecord): String? =
        when (record.normalizedIconSource()) {
            "custom" -> faviconStore.saveCustomIconDataUrl(record.id.ifBlank { record.startUrl }, record.iconDataUrl)
            "site" -> faviconStore.saveIconDataUrl(record.startUrl, record.iconDataUrl)
            else -> null
        }
}

data class WebDavSyncResult(
    val bookmarkCount: Int,
    val webAppCount: Int,
    val deletedBookmarkCount: Int,
    val deletedWebAppCount: Int,
    val importedBookmarkCount: Int,
    val removedBookmarkCount: Int,
    val importedWebAppCount: Int,
    val removedWebAppCount: Int,
    val syncedAt: Long,
    val deviceId: String,
    val uploadedOperationCount: Int,
    val remoteOperationCount: Int,
    val pendingOperationCount: Int
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("bookmarkCount", bookmarkCount)
            .put("webAppCount", webAppCount)
            .put("deletedBookmarkCount", deletedBookmarkCount)
            .put("deletedWebAppCount", deletedWebAppCount)
            .put("importedBookmarkCount", importedBookmarkCount)
            .put("removedBookmarkCount", removedBookmarkCount)
            .put("importedWebAppCount", importedWebAppCount)
            .put("removedWebAppCount", removedWebAppCount)
            .put("syncedAt", syncedAt)
            .put("deviceId", deviceId)
            .put("uploadedOperationCount", uploadedOperationCount)
            .put("remoteOperationCount", remoteOperationCount)
            .put("pendingOperationCount", pendingOperationCount)
}

private data class WebDavSyncConfig(
    val deviceName: String,
    val deviceId: String
) {
    companion object {
        fun from(settings: BrowserSettings): WebDavSyncConfig {
            val deviceId = settings.webDavSyncDeviceId.ifBlank { UUID.randomUUID().toString() }
            return WebDavSyncConfig(
                deviceName = settings.webDavSyncDeviceName.ifBlank { "Hyper Browser Android" },
                deviceId = deviceId
            )
        }
    }
}

private data class SyncBookmarkRecord(
    val url: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val iconDataUrl: String?,
    val rev: SyncRevision = SyncRevision()
)

private data class SyncWebAppRecord(
    val id: String,
    val name: String,
    val startUrl: String,
    val themeColor: Int,
    val displayMode: String,
    val createdAt: Long,
    val lastOpenedAt: Long,
    val updatedAt: Long,
    val iconDataUrl: String?,
    val iconSource: String?,
    val rev: SyncRevision = SyncRevision()
) {
    fun normalizedIconSource(): String =
        when (iconSource) {
            "custom", "site", "title" -> iconSource
            else -> if (iconDataUrl.isNullOrBlank()) "title" else "custom"
        }
}

private data class ApplyCounts(val imported: Int, val removed: Int)

private fun bookmarkRecordsById(records: Collection<SyncBookmarkRecord>): Map<String, SyncBookmarkRecord> {
    val result = linkedMapOf<String, SyncBookmarkRecord>()
    records.forEach { record ->
        val key = bookmarkRecordKey(record)
        if (key.isBlank()) return@forEach
        val flat = record.copy(url = key)
        val current = result[key]
        result[key] = if (current == null) flat else chooseLatestBookmark(current, flat)
    }
    return result
}

private data class SyncRevision(
    val counter: Long = 0L,
    val deviceId: String = ""
)

private fun bookmarkRecordKey(record: SyncBookmarkRecord): String =
    bookmarkUrlKey(record.url)

private fun bookmarkUrlKey(value: String): String {
    val url = normalizeBookmarkUrl(value)
    return if (isBookmarkSyncUrl(url)) url else ""
}

private fun isBookmarkSyncUrl(value: String): Boolean =
    Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE).containsMatchIn(value.trim())

private fun chooseLatestBookmark(
    left: SyncBookmarkRecord,
    right: SyncBookmarkRecord
): SyncBookmarkRecord =
    when {
        right.rev.counter > left.rev.counter -> right
        left.rev.counter > right.rev.counter -> left
        else -> left
    }

private fun webAppRecordsById(records: Collection<SyncWebAppRecord>): Map<String, SyncWebAppRecord> {
    val result = linkedMapOf<String, SyncWebAppRecord>()
    records.forEach { record ->
        val id = record.id.trim()
        if (id.isBlank()) return@forEach
        val current = result[id]
        result[id] = if (current == null) record else chooseLatestWebApp(current, record)
    }
    return result
}

private fun chooseLatestWebApp(
    left: SyncWebAppRecord,
    right: SyncWebAppRecord
): SyncWebAppRecord =
    when {
        right.rev.counter > left.rev.counter -> right
        left.rev.counter > right.rev.counter -> left
        else -> left
    }

private fun parseBookmarkRecords(raw: String?): List<SyncBookmarkRecord> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val root = JSONObject(raw)
        parseBookmarkRecordsMap(root.optJSONObject("bookmarks") ?: root)
    }.getOrDefault(emptyList())
}

private fun parseBookmarkRecordsPayload(raw: String?): List<SyncBookmarkRecord> {
    if (raw.isNullOrBlank()) return emptyList()
    return parseBookmarkRecords(raw)
}

private fun countTombstones(raw: String?, key: String): Int {
    if (raw.isNullOrBlank()) return 0
    return runCatching {
        val tombstones = JSONObject(raw).optJSONObject(key) ?: return@runCatching 0
        tombstones.length()
    }.getOrDefault(0)
}

private fun parseBookmarkRecordsMap(map: JSONObject): List<SyncBookmarkRecord> =
    buildList {
        val keys = map.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val item = map.optJSONObject(key) ?: continue
            parseBookmarkRecord(item, fallbackUrl = key)?.let(::add)
        }
    }

private fun parseBookmarkRecord(item: JSONObject, fallbackUrl: String?): SyncBookmarkRecord? {
    val url = bookmarkUrlKey(item.optString("url").ifBlank { fallbackUrl.orEmpty() })
    if (url.isBlank()) return null
    val updatedAt = item.optLong("updatedAt").takeIf { it > 0 }
        ?: item.optLong("createdAt").takeIf { it > 0 }
        ?: System.currentTimeMillis()
    val title = item.optString("title").ifBlank { url }
    return SyncBookmarkRecord(
        url = url,
        title = title,
        createdAt = item.optLong("createdAt", updatedAt),
        updatedAt = updatedAt,
        iconDataUrl = item.optCleanString("iconDataUrl"),
        rev = item.optRevision()
    )
}

private fun parseWebAppRecords(raw: String?): List<SyncWebAppRecord> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val root = JSONObject(raw)
        parseWebAppRecordsMap(root.optJSONObject("apps") ?: root)
    }.getOrDefault(emptyList())
}

private fun parseWebAppRecordsPayload(raw: String?): List<SyncWebAppRecord> {
    if (raw.isNullOrBlank()) return emptyList()
    return parseWebAppRecords(raw)
}

private fun parseWebAppRecordsMap(map: JSONObject): List<SyncWebAppRecord> =
    buildList {
        val keys = map.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val item = map.optJSONObject(key) ?: continue
            parseWebAppRecord(item, fallbackId = key)?.let(::add)
        }
    }

private fun parseWebAppRecord(item: JSONObject, fallbackId: String?): SyncWebAppRecord? {
    val startUrl = item.optString("startUrl").trim()
    if (startUrl.isBlank()) return null
    val updatedAt = item.optLong("updatedAt").takeIf { it > 0 }
        ?: item.optLong("createdAt").takeIf { it > 0 }
        ?: System.currentTimeMillis()
    return SyncWebAppRecord(
        id = item.optString("id").ifBlank {
            fallbackId?.trim().orEmpty().ifBlank {
                UUID.nameUUIDFromBytes(startUrl.toByteArray(Charsets.UTF_8)).toString()
            }
        },
        name = item.optString("name").ifBlank { startUrl },
        startUrl = startUrl,
        themeColor = item.optInt("themeColor", Color.rgb(18, 109, 106)),
        displayMode = item.optString("displayMode", "standalone"),
        createdAt = item.optLong("createdAt", updatedAt),
        lastOpenedAt = item.optLong("lastOpenedAt", updatedAt),
        updatedAt = updatedAt,
        iconDataUrl = item.optString("iconDataUrl").ifBlank { null },
        iconSource = item.optString("iconSource").ifBlank { null },
        rev = item.optRevision()
    )
}

private fun JSONObject.optRevision(): SyncRevision {
    val rev = optJSONObject("rev") ?: return SyncRevision()
    return SyncRevision(
        counter = rev.optLong("counter", 0L).coerceAtLeast(0L),
        deviceId = rev.optString("deviceId").trim()
    )
}

private fun JSONObject.optCleanString(name: String): String? {
    if (!has(name) || isNull(name)) return null
    val value = optString(name).trim()
    return value.takeIf { it.isNotBlank() && it != "null" && it != "undefined" }
}
