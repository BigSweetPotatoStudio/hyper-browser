package com.dadigua.hyperbrowser.sync

import android.content.Context
import android.graphics.Color
import com.dadigua.hyperbrowser.browser.BrowserBookmark
import com.dadigua.hyperbrowser.browser.BrowserProfileStore
import com.dadigua.hyperbrowser.browser.BrowserSettings
import com.dadigua.hyperbrowser.browser.FaviconRepository
import com.dadigua.hyperbrowser.data.WebAppDefinition
import com.dadigua.hyperbrowser.webapp.WebAppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class WebDavLocalSyncAdapter(
    context: Context,
    private val profileStore: BrowserProfileStore,
    private val webAppRepository: WebAppRepository,
    private val faviconStore: FaviconRepository
) {
    private val metadataStore = WebDavSyncMetadataStore(context)

    fun localData(settings: BrowserSettings): JSONObject {
        val config = WebDavSyncConfig.from(settings)
        val now = System.currentTimeMillis()
        val known = metadataStore.load()
        return JSONObject()
            .put("deviceId", config.deviceId)
            .put("deviceName", config.deviceName)
            .put("metadata", known.toJson())
            .put("bookmarks", bookmarksArray(localBookmarkRecords(known.bookmarks, config.deviceId, now)))
            .put("webApps", webAppsArray(localWebAppRecords(known.webApps, config.deviceId, now)))
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
        metadataStore.save(
            bookmarks = bookmarkRecords,
            webApps = webAppRecords
        )
        WebDavSyncResult(
            bookmarkCount = bookmarkRecords.count { it.deletedAt == null },
            webAppCount = webAppRecords.count { it.deletedAt == null },
            deletedBookmarkCount = bookmarkRecords.count { it.deletedAt != null },
            deletedWebAppCount = webAppRecords.count { it.deletedAt != null },
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

    private fun localBookmarkRecords(
        knownRecords: Map<String, SyncBookmarkRecord>,
        deviceId: String,
        now: Long
    ): List<SyncBookmarkRecord> {
        val localByUrl = profileStore.observeBookmarks().value.associateBy { it.url.trim() }
        val records = mutableMapOf<String, SyncBookmarkRecord>()

        knownRecords.values
            .filter { it.deletedAt != null }
            .forEach { known ->
                records[known.url] = known
            }

        knownRecords.values
            .filter { it.deletedAt == null && it.url !in localByUrl }
            .forEach { known ->
                records[known.url] = known.copy(
                    updatedAt = now,
                    deletedAt = now,
                    sourceDeviceId = deviceId
                )
            }

        localByUrl.values.forEach { bookmark ->
            val url = bookmark.url.trim()
            if (url.isBlank()) return@forEach
            val known = knownRecords[url]
            val iconDataUrl = faviconStore.iconDataUrl(bookmark.iconPath, url)
            val changed = known == null ||
                known.deletedAt != null ||
                known.title != bookmark.title ||
                known.iconDataUrl != iconDataUrl
            records[url] = SyncBookmarkRecord(
                url = url,
                title = bookmark.title.ifBlank { url },
                createdAt = bookmark.createdAt.takeIf { it > 0 } ?: now,
                updatedAt = if (changed) now else known.updatedAt,
                deletedAt = null,
                sourceDeviceId = if (changed) deviceId else known.sourceDeviceId,
                iconDataUrl = iconDataUrl
            )
        }

        return records.values.toList()
    }

    private fun localWebAppRecords(
        knownRecords: Map<String, SyncWebAppRecord>,
        deviceId: String,
        now: Long
    ): List<SyncWebAppRecord> {
        val localById = webAppRepository.observeAll().value.associateBy { it.id.trim() }
        val records = mutableMapOf<String, SyncWebAppRecord>()

        knownRecords.values
            .filter { it.deletedAt != null }
            .forEach { known ->
                records[known.id] = known
            }

        knownRecords.values
            .filter { it.deletedAt == null && it.id !in localById }
            .forEach { known ->
                records[known.id] = known.copy(
                    updatedAt = now,
                    deletedAt = now,
                    sourceDeviceId = deviceId
                )
            }

        localById.values.forEach { webApp ->
            val id = webApp.id.trim()
            val startUrl = webApp.startUrl.trim()
            if (id.isBlank() || startUrl.isBlank()) return@forEach
            val known = knownRecords[id]
            val iconDataUrl = webAppRepository.iconDataUrl(webApp)
            val iconSource = webAppRepository.iconSource(webApp)
            val changed = known == null ||
                known.deletedAt != null ||
                known.name != webApp.name ||
                known.scopeUrl != webApp.scopeUrl ||
                known.themeColor != webApp.themeColor ||
                known.displayMode != webApp.displayMode ||
                known.iconDataUrl != iconDataUrl ||
                known.iconSource != iconSource
            records[id] = SyncWebAppRecord(
                id = id,
                name = webApp.name.ifBlank { startUrl },
                startUrl = startUrl,
                scopeUrl = webApp.scopeUrl,
                themeColor = webApp.themeColor,
                displayMode = webApp.displayMode.ifBlank { "standalone" },
                createdAt = webApp.createdAt.takeIf { it > 0 } ?: now,
                lastOpenedAt = webApp.lastOpenedAt.takeIf { it > 0 } ?: now,
                updatedAt = if (changed) now else known.updatedAt,
                deletedAt = null,
                sourceDeviceId = if (changed) deviceId else known.sourceDeviceId,
                iconDataUrl = iconDataUrl,
                iconSource = iconSource
            )
        }

        return records.values.toList()
    }

    private fun applyBookmarks(records: Collection<SyncBookmarkRecord>): ApplyCounts {
        val currentByUrl = profileStore.observeBookmarks().value.associateBy { it.url }
        val activeRecords = records
            .filter { it.deletedAt == null }
            .associateBy { it.url.trim() }
        var removed = 0
        val imports = mutableListOf<BrowserBookmark>()
        currentByUrl.values.forEach { current ->
            if (current.url !in activeRecords) {
                profileStore.removeBookmark(current.url)
                removed += 1
            }
        }
        activeRecords.values.sortedByDescending { it.updatedAt }.forEach { record ->
            val current = currentByUrl[record.url]
            if (current == null || current.title != record.title) {
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
        val activeRecords = webAppRecordsById(records.filter { it.deletedAt == null })
        var removed = 0
        val imports = mutableListOf<WebAppDefinition>()
        currentById.values.forEach { current ->
            if (current.id !in activeRecords) {
                if (webAppRepository.delete(current.id)) removed += 1
            }
        }
        activeRecords.values.sortedByDescending { it.updatedAt }.forEach { record ->
            val current = currentById[record.id]
            if (current == null ||
                current.name != record.name ||
                current.scopeUrl != record.scopeUrl ||
                current.themeColor != record.themeColor ||
                current.displayMode != record.displayMode ||
                webAppRepository.iconDataUrl(current) != record.iconDataUrl ||
                webAppRepository.iconSource(current) != record.normalizedIconSource()
            ) {
                imports += WebAppDefinition(
                    id = record.id.ifBlank { UUID.randomUUID().toString() },
                    name = record.name.ifBlank { record.startUrl },
                    startUrl = record.startUrl,
                    scopeUrl = record.scopeUrl,
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

private class WebDavSyncMetadataStore(context: Context) {
    private val file = File(context.filesDir, "webdav_sync_metadata.json")

    fun load(): SyncMetadata {
        if (!file.exists()) return SyncMetadata(emptyMap(), emptyMap())
        return runCatching {
            val root = JSONObject(file.readText())
            SyncMetadata(
                bookmarks = parseBookmarkRecords(root.optJSONArray("bookmarks") ?: JSONArray()).associateBy { it.url },
                webApps = parseWebAppRecords(root.optJSONArray("webApps") ?: JSONArray()).associateBy { it.id }
            )
        }.getOrDefault(SyncMetadata(emptyMap(), emptyMap()))
    }

    fun save(bookmarks: List<SyncBookmarkRecord>, webApps: List<SyncWebAppRecord>) {
        file.writeText(
            JSONObject()
                .put("schemaVersion", 1)
                .put("updatedAt", System.currentTimeMillis())
                .put("bookmarks", bookmarksArray(bookmarks))
                .put("webApps", webAppsArray(webApps))
                .toString()
        )
    }
}

private data class SyncMetadata(
    val bookmarks: Map<String, SyncBookmarkRecord>,
    val webApps: Map<String, SyncWebAppRecord>
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("bookmarks", bookmarksArray(bookmarks.values))
            .put("webApps", webAppsArray(webApps.values))
}

private data class SyncBookmarkRecord(
    val url: String,
    val title: String,
    val createdAt: Long,
    override val updatedAt: Long,
    override val deletedAt: Long?,
    val sourceDeviceId: String,
    val iconDataUrl: String?
) : SyncRecord

private data class SyncWebAppRecord(
    val id: String,
    val name: String,
    val startUrl: String,
    val scopeUrl: String,
    val themeColor: Int,
    val displayMode: String,
    val createdAt: Long,
    val lastOpenedAt: Long,
    override val updatedAt: Long,
    override val deletedAt: Long?,
    val sourceDeviceId: String,
    val iconDataUrl: String?,
    val iconSource: String?
) : SyncRecord {
    fun normalizedIconSource(): String =
        when (iconSource) {
            "custom", "site", "title" -> iconSource
            else -> if (iconDataUrl.isNullOrBlank()) "title" else "custom"
        }
}

private interface SyncRecord {
    val updatedAt: Long
    val deletedAt: Long?
}

private data class ApplyCounts(val imported: Int, val removed: Int)

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
        right.updatedAt > left.updatedAt -> right
        left.updatedAt > right.updatedAt -> left
        right.deletedAt != null && left.deletedAt == null -> right
        else -> left
    }

private fun parseBookmarkRecords(raw: String?): List<SyncBookmarkRecord> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val root = JSONObject(raw)
        parseBookmarkRecords(root.optJSONArray("items") ?: root.optJSONArray("bookmarks") ?: JSONArray())
    }.getOrDefault(emptyList())
}

private fun parseBookmarkRecordsPayload(raw: String?): List<SyncBookmarkRecord> {
    if (raw.isNullOrBlank()) return emptyList()
    val clean = raw.trim()
    return runCatching {
        if (clean.startsWith("[")) {
            parseBookmarkRecords(JSONArray(clean))
        } else {
            parseBookmarkRecords(clean)
        }
    }.getOrDefault(emptyList())
}

private fun parseBookmarkRecords(array: JSONArray): List<SyncBookmarkRecord> =
    buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val url = item.optString("url").trim()
            if (url.isBlank()) continue
            val updatedAt = item.optLong("updatedAt").takeIf { it > 0 }
                ?: item.optLong("createdAt").takeIf { it > 0 }
                ?: System.currentTimeMillis()
            add(
                SyncBookmarkRecord(
                    url = url,
                    title = item.optString("title").ifBlank { url },
                    createdAt = item.optLong("createdAt", updatedAt),
                    updatedAt = updatedAt,
                    deletedAt = item.optNullableLong("deletedAt"),
                    sourceDeviceId = item.optString("sourceDeviceId"),
                    iconDataUrl = item.optString("iconDataUrl").ifBlank { null }
                )
            )
        }
    }

private fun parseWebAppRecords(raw: String?): List<SyncWebAppRecord> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val root = JSONObject(raw)
        parseWebAppRecords(root.optJSONArray("items") ?: root.optJSONArray("webApps") ?: JSONArray())
    }.getOrDefault(emptyList())
}

private fun parseWebAppRecordsPayload(raw: String?): List<SyncWebAppRecord> {
    if (raw.isNullOrBlank()) return emptyList()
    val clean = raw.trim()
    return runCatching {
        if (clean.startsWith("[")) {
            parseWebAppRecords(JSONArray(clean))
        } else {
            parseWebAppRecords(clean)
        }
    }.getOrDefault(emptyList())
}

private fun parseWebAppRecords(array: JSONArray): List<SyncWebAppRecord> =
    buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val startUrl = item.optString("startUrl").trim()
            if (startUrl.isBlank()) continue
            val updatedAt = item.optLong("updatedAt").takeIf { it > 0 }
                ?: item.optLong("createdAt").takeIf { it > 0 }
                ?: System.currentTimeMillis()
            add(
                SyncWebAppRecord(
                    id = item.optString("id").ifBlank { UUID.nameUUIDFromBytes(startUrl.toByteArray(Charsets.UTF_8)).toString() },
                    name = item.optString("name").ifBlank { startUrl },
                    startUrl = startUrl,
                    scopeUrl = item.optString("scopeUrl"),
                    themeColor = item.optInt("themeColor", Color.rgb(18, 109, 106)),
                    displayMode = item.optString("displayMode", "standalone"),
                    createdAt = item.optLong("createdAt", updatedAt),
                    lastOpenedAt = item.optLong("lastOpenedAt", updatedAt),
                    updatedAt = updatedAt,
                    deletedAt = item.optNullableLong("deletedAt"),
                    sourceDeviceId = item.optString("sourceDeviceId"),
                    iconDataUrl = item.optString("iconDataUrl").ifBlank { null },
                    iconSource = item.optString("iconSource").ifBlank { null }
                )
            )
        }
    }

private fun bookmarksArray(records: Collection<SyncBookmarkRecord>): JSONArray =
    JSONArray().apply {
        records.forEach { record ->
            put(
                JSONObject()
                    .put("url", record.url)
                    .put("title", record.title)
                    .put("createdAt", record.createdAt)
                    .put("updatedAt", record.updatedAt)
                    .put("deletedAt", record.deletedAt)
                    .put("sourceDeviceId", record.sourceDeviceId)
                    .put("iconDataUrl", record.iconDataUrl)
            )
        }
    }

private fun webAppsArray(records: Collection<SyncWebAppRecord>): JSONArray =
    JSONArray().apply {
        records.forEach { record ->
            put(
                JSONObject()
                    .put("id", record.id)
                    .put("name", record.name)
                    .put("startUrl", record.startUrl)
                    .put("scopeUrl", record.scopeUrl)
                    .put("themeColor", record.themeColor)
                    .put("displayMode", record.displayMode)
                    .put("createdAt", record.createdAt)
                    .put("lastOpenedAt", record.lastOpenedAt)
                    .put("updatedAt", record.updatedAt)
                    .put("deletedAt", record.deletedAt)
                    .put("sourceDeviceId", record.sourceDeviceId)
                    .put("iconDataUrl", record.iconDataUrl)
                    .put("iconSource", record.iconSource)
            )
        }
    }

private fun JSONObject.optNullableLong(name: String): Long? {
    if (!has(name) || isNull(name)) return null
    val value = optLong(name, 0L)
    return value.takeIf { it > 0 }
}
