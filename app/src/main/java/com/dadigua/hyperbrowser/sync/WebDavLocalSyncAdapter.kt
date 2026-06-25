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
            .put("bookmarks", bookmarksObject(localBookmarkRecords(known.bookmarks, config.deviceId, now)))
            .put("webApps", webAppsObject(localWebAppRecords(known.webApps, config.deviceId, now)))
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
        val localByKey = profileStore.observeBookmarks().value
            .mapNotNull { bookmark ->
                val url = normalizeBookmarkUrl(bookmark.url)
                val key = bookmarkUrlKey(url)
                if (key.isBlank()) null else key to bookmark
            }
            .toMap()
        val records = mutableMapOf<String, SyncBookmarkRecord>()

        knownRecords.values
            .filter { it.deletedAt != null }
            .forEach { known ->
                val key = bookmarkRecordKey(known)
                if (key.isBlank()) return@forEach
                records[key] = known.copy(url = key)
            }

        knownRecords.values
            .filter { it.deletedAt == null && bookmarkRecordKey(it) !in localByKey }
            .forEach { known ->
                val key = bookmarkRecordKey(known)
                if (key.isBlank()) return@forEach
                records[key] = known.copy(
                    url = key,
                    updatedAt = now,
                    deletedAt = now
                )
            }

        localByKey.values.forEach { bookmark ->
            val url = normalizeBookmarkUrl(bookmark.url)
            val key = bookmarkUrlKey(url)
            if (key.isBlank()) return@forEach
            val known = knownRecords[key]
            val iconDataUrl = faviconStore.iconDataUrl(bookmark.iconPath, url)
            val changed = known == null ||
                known.deletedAt != null ||
                known.url != url ||
                known.title != bookmark.title ||
                known.iconDataUrl != iconDataUrl
            records[key] = SyncBookmarkRecord(
                url = url,
                title = bookmark.title.ifBlank { url },
                createdAt = bookmark.createdAt.takeIf { it > 0 } ?: now,
                updatedAt = if (changed) now else known.updatedAt,
                deletedAt = null,
                iconDataUrl = iconDataUrl,
                rev = known?.rev ?: SyncRevision()
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
                    deletedAt = now
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
                known.startUrl != startUrl ||
                known.themeColor != webApp.themeColor ||
                known.displayMode != webApp.displayMode ||
                known.iconDataUrl != iconDataUrl ||
                known.iconSource != iconSource
            records[id] = SyncWebAppRecord(
                id = id,
                name = webApp.name.ifBlank { startUrl },
                startUrl = startUrl,
                themeColor = webApp.themeColor,
                displayMode = webApp.displayMode.ifBlank { "standalone" },
                createdAt = webApp.createdAt.takeIf { it > 0 } ?: now,
                lastOpenedAt = webApp.lastOpenedAt.takeIf { it > 0 } ?: now,
                updatedAt = if (changed) now else known.updatedAt,
                deletedAt = null,
                iconDataUrl = iconDataUrl,
                iconSource = iconSource,
                rev = known?.rev ?: SyncRevision()
            )
        }

        return records.values.toList()
    }

    private fun applyBookmarks(records: Collection<SyncBookmarkRecord>): ApplyCounts {
        val currentItems = profileStore.observeBookmarks().value
        val currentByKey = currentItems
            .mapNotNull { bookmark ->
                val key = bookmarkUrlKey(bookmark.url)
                if (key.isBlank()) null else key to bookmark
            }
            .toMap()
        val activeRecords = bookmarkRecordsById(records.filter { it.deletedAt == null })
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
        val activeRecords = webAppRecordsById(records.filter { it.deletedAt == null })
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

private class WebDavSyncMetadataStore(context: Context) {
    private val file = File(context.filesDir, "webdav_sync_metadata.json")

    fun load(): SyncMetadata {
        if (!file.exists()) return SyncMetadata(emptyMap(), emptyMap())
        return runCatching {
            val root = JSONObject(file.readText())
            SyncMetadata(
                bookmarks = bookmarkRecordsById(parseBookmarkMetadataRecords(root.opt("bookmarks"))),
                webApps = parseWebAppMetadataRecords(root.opt("webApps")).associateBy { it.id }
            )
        }.getOrDefault(SyncMetadata(emptyMap(), emptyMap()))
    }

    fun save(bookmarks: List<SyncBookmarkRecord>, webApps: List<SyncWebAppRecord>) {
        file.writeText(
            JSONObject()
                .put("schemaVersion", 1)
                .put("updatedAt", System.currentTimeMillis())
                .put("bookmarks", bookmarksObject(bookmarks))
                .put("webApps", webAppsObject(webApps))
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
            .put("bookmarks", bookmarksObject(bookmarks.values))
            .put("webApps", webAppsObject(webApps.values))
}

private data class SyncBookmarkRecord(
    val url: String,
    val title: String,
    val createdAt: Long,
    override val updatedAt: Long,
    override val deletedAt: Long?,
    val iconDataUrl: String?,
    val rev: SyncRevision = SyncRevision()
) : SyncRecord

private data class SyncWebAppRecord(
    val id: String,
    val name: String,
    val startUrl: String,
    val themeColor: Int,
    val displayMode: String,
    val createdAt: Long,
    val lastOpenedAt: Long,
    override val updatedAt: Long,
    override val deletedAt: Long?,
    val iconDataUrl: String?,
    val iconSource: String?,
    val rev: SyncRevision = SyncRevision()
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
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("counter", counter)
            .put("deviceId", deviceId)
}

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
        right.deletedAt != null && left.deletedAt == null -> right
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
        right.deletedAt != null && left.deletedAt == null -> right
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

private fun parseBookmarkRecordsMap(map: JSONObject): List<SyncBookmarkRecord> =
    buildList {
        val keys = map.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val item = map.optJSONObject(key) ?: continue
            parseBookmarkRecord(item, fallbackUrl = key)?.let(::add)
        }
    }

private fun parseBookmarkMetadataRecords(value: Any?): List<SyncBookmarkRecord> =
    when (value) {
        is JSONObject -> parseBookmarkRecordsMap(value)
        is JSONArray -> buildList {
            for (index in 0 until value.length()) {
                val item = value.optJSONObject(index) ?: continue
                parseBookmarkRecord(item, fallbackUrl = null)?.let(::add)
            }
        }
        else -> emptyList()
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
        deletedAt = item.optNullableLong("deletedAt"),
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

private fun parseWebAppMetadataRecords(value: Any?): List<SyncWebAppRecord> =
    when (value) {
        is JSONObject -> parseWebAppRecordsMap(value)
        is JSONArray -> buildList {
            for (index in 0 until value.length()) {
                val item = value.optJSONObject(index) ?: continue
                parseWebAppRecord(item, fallbackId = null)?.let(::add)
            }
        }
        else -> emptyList()
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
        deletedAt = item.optNullableLong("deletedAt"),
        iconDataUrl = item.optString("iconDataUrl").ifBlank { null },
        iconSource = item.optString("iconSource").ifBlank { null },
        rev = item.optRevision()
    )
}

private fun bookmarksObject(records: Collection<SyncBookmarkRecord>): JSONObject =
    JSONObject().apply {
        records.forEach { record ->
            val key = bookmarkRecordKey(record)
            if (key.isNotBlank()) put(key, bookmarkJson(record))
        }
    }

private fun bookmarkJson(record: SyncBookmarkRecord): JSONObject =
    JSONObject()
        .put("url", record.url)
        .put("title", record.title)
        .put("createdAt", record.createdAt)
        .put("updatedAt", record.updatedAt)
        .putJsonNullable("deletedAt", record.deletedAt)
        .putJsonNullable("iconDataUrl", record.iconDataUrl)
        .putRevision(record.rev)

private fun webAppsObject(records: Collection<SyncWebAppRecord>): JSONObject =
    JSONObject().apply {
        records.forEach { record ->
            val id = record.id.trim()
            if (id.isNotBlank()) put(id, webAppJson(record))
        }
    }

private fun webAppJson(record: SyncWebAppRecord): JSONObject =
    JSONObject()
        .put("id", record.id)
        .put("name", record.name)
        .put("startUrl", record.startUrl)
        .put("themeColor", record.themeColor)
        .put("displayMode", record.displayMode)
        .put("createdAt", record.createdAt)
        .put("lastOpenedAt", record.lastOpenedAt)
        .put("updatedAt", record.updatedAt)
        .put("deletedAt", record.deletedAt)
        .put("iconDataUrl", record.iconDataUrl)
        .put("iconSource", record.iconSource)
        .putRevision(record.rev)

private fun JSONObject.optRevision(): SyncRevision {
    val rev = optJSONObject("rev") ?: return SyncRevision()
    return SyncRevision(
        counter = rev.optLong("counter", 0L).coerceAtLeast(0L),
        deviceId = rev.optString("deviceId").trim()
    )
}

private fun JSONObject.putRevision(rev: SyncRevision): JSONObject =
    put("rev", rev.toJson())

private fun JSONObject.optNullableLong(name: String): Long? {
    if (!has(name) || isNull(name)) return null
    val value = optLong(name, 0L)
    return value.takeIf { it > 0 }
}

private fun JSONObject.optCleanString(name: String): String? {
    if (!has(name) || isNull(name)) return null
    val value = optString(name).trim()
    return value.takeIf { it.isNotBlank() && it != "null" && it != "undefined" }
}

private fun JSONObject.putJsonNullable(name: String, value: Any?): JSONObject =
    put(name, value ?: JSONObject.NULL)
