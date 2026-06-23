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
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

class WebDavSyncManager(
    context: Context,
    private val profileStore: BrowserProfileStore,
    private val webAppRepository: WebAppRepository,
    private val faviconStore: FaviconRepository
) {
    private val metadataStore = WebDavSyncMetadataStore(context)

    suspend fun sync(settings: BrowserSettings): WebDavSyncResult = withContext(Dispatchers.IO) {
        val config = WebDavSyncConfig.from(settings)
        val client = WebDavClient(config)
        val now = System.currentTimeMillis()
        val known = metadataStore.load()

        client.ensureCollections()
        var lastConflict: Throwable? = null
        repeat(MAX_SYNC_ATTEMPTS) { attempt ->
            val remoteBookmarks = client.getJson(BOOKMARKS_FILE)
            val remoteWebApps = client.getJson(WEBAPPS_FILE)
            val remoteBookmarkRecords = parseBookmarkRecords(remoteBookmarks?.body)
            val remoteWebAppRecords = parseWebAppRecords(remoteWebApps?.body)
            val localBookmarks = localBookmarkRecords(known.bookmarks, config.deviceId, now)
            val localWebApps = localWebAppRecords(known.webApps, config.deviceId, now)
            val mergedBookmarks = mergeByKey(
                remoteBookmarkRecords.associateBy { it.url },
                localBookmarks.associateBy { it.url }
            )
            val mergedWebApps = mergeByKey(
                remoteWebAppRecords.associateBy { it.id },
                localWebApps.associateBy { it.id }
            )
            val bookmarksChanged = !sameBookmarkRecords(remoteBookmarkRecords, mergedBookmarks.values)
            val webAppsChanged = !sameWebAppRecords(remoteWebAppRecords, mergedWebApps.values)

            try {
                if (bookmarksChanged) {
                    client.putJson(BOOKMARKS_FILE, bookmarksDocument(mergedBookmarks.values).toString(2), remoteBookmarks?.etag)
                }
                if (webAppsChanged) {
                    client.putJson(WEBAPPS_FILE, webAppsDocument(mergedWebApps.values).toString(2), remoteWebApps?.etag)
                }
                if (bookmarksChanged || webAppsChanged) {
                    client.putJson(MANIFEST_FILE, manifestDocument(config, now).toString(2), null)
                }
                client.putJson("devices/android-${safeSegment(config.deviceId)}.json", deviceDocument(config, now).toString(2), null)

                val appliedBookmarks = applyBookmarks(mergedBookmarks.values)
                val appliedWebApps = applyWebApps(mergedWebApps.values)
                metadataStore.save(
                    bookmarks = mergedBookmarks.values.toList(),
                    webApps = mergedWebApps.values.toList()
                )
                return@withContext WebDavSyncResult(
                    bookmarkCount = mergedBookmarks.values.count { it.deletedAt == null },
                    webAppCount = mergedWebApps.values.count { it.deletedAt == null },
                    deletedBookmarkCount = mergedBookmarks.values.count { it.deletedAt != null },
                    deletedWebAppCount = mergedWebApps.values.count { it.deletedAt != null },
                    importedBookmarkCount = appliedBookmarks.imported,
                    removedBookmarkCount = appliedBookmarks.removed,
                    importedWebAppCount = appliedWebApps.imported,
                    removedWebAppCount = appliedWebApps.removed,
                    syncedAt = now,
                    deviceId = config.deviceId,
                    attemptCount = attempt + 1
                )
            } catch (throwable: WebDavConflictException) {
                lastConflict = throwable
            }
        }
        throw lastConflict ?: IOException("WebDAV sync failed.")
    }

    suspend fun readManifest(settings: BrowserSettings): WebDavSyncManifest? = withContext(Dispatchers.IO) {
        val config = WebDavSyncConfig.from(settings)
        val client = WebDavClient(config)
        parseManifest(client.getJson(MANIFEST_FILE)?.body)
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
        var removed = 0
        val imports = mutableListOf<BrowserBookmark>()
        records.sortedByDescending { it.updatedAt }.forEach { record ->
            if (record.deletedAt != null) {
                if (record.url in currentByUrl) {
                    profileStore.removeBookmark(record.url)
                    removed += 1
                }
                return@forEach
            }
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
        var removed = 0
        val imports = mutableListOf<WebAppDefinition>()
        records.sortedByDescending { it.updatedAt }.forEach { record ->
            if (record.deletedAt != null) {
                currentById[record.id]?.let {
                    if (webAppRepository.delete(it.id)) removed += 1
                }
                return@forEach
            }
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

    private fun parseManifest(body: String?): WebDavSyncManifest? {
        if (body.isNullOrBlank()) return null
        return runCatching {
            val item = JSONObject(body)
            val updatedAt = item.optLong("updatedAt", 0L)
            if (updatedAt <= 0L) {
                null
            } else {
                WebDavSyncManifest(
                    updatedAt = updatedAt,
                    lastWriter = item.optString("lastWriter")
                )
            }
        }.getOrNull()
    }

    private companion object {
        const val MANIFEST_FILE = "manifest.json"
        const val BOOKMARKS_FILE = "bookmarks.json"
        const val WEBAPPS_FILE = "webapps.json"
        const val MAX_SYNC_ATTEMPTS = 3
    }
}

data class WebDavSyncManifest(
    val updatedAt: Long,
    val lastWriter: String
)

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
    val attemptCount: Int
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
            .put("attemptCount", attemptCount)
}

private data class WebDavSyncConfig(
    val rootUrl: String,
    val username: String,
    val password: String,
    val deviceName: String,
    val deviceId: String
) {
    companion object {
        fun from(settings: BrowserSettings): WebDavSyncConfig {
            val baseUrl = settings.webDavSyncUrl.trim()
            require(baseUrl.startsWith("https://") || baseUrl.startsWith("http://")) {
                "WebDAV URL must start with http:// or https://."
            }
            val root = baseUrl.trimEnd('/').let { clean ->
                if (clean.endsWith("/HyperBrowserSync", ignoreCase = true)) "$clean/" else "$clean/HyperBrowserSync/"
            }
            val deviceId = settings.webDavSyncDeviceId.ifBlank { UUID.randomUUID().toString() }
            return WebDavSyncConfig(
                rootUrl = root,
                username = settings.webDavSyncUsername,
                password = settings.webDavSyncPassword,
                deviceName = settings.webDavSyncDeviceName.ifBlank { "Hyper Browser Android" },
                deviceId = deviceId
            )
        }
    }
}

private class WebDavClient(private val config: WebDavSyncConfig) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun ensureCollections() {
        mkcol("")
        mkcol("devices/")
    }

    fun getJson(path: String): RemoteJson? {
        val request = authenticated(Request.Builder().url(urlFor(path))).get().build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("WebDAV GET failed: HTTP ${response.code}.")
            return RemoteJson(
                body = response.body?.string().orEmpty(),
                etag = response.header("ETag")
            )
        }
    }

    fun putJson(path: String, json: String, etag: String?) {
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val builder = authenticated(Request.Builder().url(urlFor(path))).put(body)
        if (!etag.isNullOrBlank()) builder.header("If-Match", etag)
        val request = builder.build()
        client.newCall(request).execute().use { response ->
            if (response.code == 409 || response.code == 412) {
                throw WebDavConflictException("WebDAV write conflict.")
            }
            if (!response.isSuccessful) {
                throw IOException("WebDAV PUT failed: HTTP ${response.code}.")
            }
        }
    }

    private fun mkcol(path: String) {
        val request = authenticated(Request.Builder().url(urlFor(path)).method("MKCOL", null)).build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful || response.code == 405) return
            if (response.code == 409 && path == "devices/") {
                mkcol("")
                return
            }
            throw IOException("WebDAV MKCOL failed: HTTP ${response.code}.")
        }
    }

    private fun authenticated(builder: Request.Builder): Request.Builder {
        if (config.username.isNotBlank() || config.password.isNotBlank()) {
            builder.header("Authorization", Credentials.basic(config.username, config.password))
        }
        return builder
    }

    private fun urlFor(path: String): String {
        if (path.isBlank()) return config.rootUrl
        return config.rootUrl + path.split("/")
            .filter { it.isNotBlank() }
            .joinToString("/") { segment ->
                URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")
            } + if (path.endsWith("/")) "/" else ""
    }
}

private data class RemoteJson(val body: String, val etag: String?)

private class WebDavConflictException(message: String) : IOException(message)

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
)

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

private fun <T : SyncRecord> mergeByKey(remote: Map<String, T>, local: Map<String, T>): Map<String, T> {
    val keys = remote.keys + local.keys
    return keys.mapNotNull { key ->
        val left = remote[key]
        val right = local[key]
        val merged = when {
            left == null -> right
            right == null -> left
            right.updatedAt > left.updatedAt -> right
            left.updatedAt > right.updatedAt -> left
            right.deletedAt != null && left.deletedAt == null -> right
            else -> left
        }
        merged?.let { key to it }
    }.toMap()
}

private fun sameBookmarkRecords(
    left: Collection<SyncBookmarkRecord>,
    right: Collection<SyncBookmarkRecord>
): Boolean =
    left.associateBy { it.url } == right.associateBy { it.url }

private fun sameWebAppRecords(
    left: Collection<SyncWebAppRecord>,
    right: Collection<SyncWebAppRecord>
): Boolean =
    left.associateBy { it.id } == right.associateBy { it.id }

private fun parseBookmarkRecords(raw: String?): List<SyncBookmarkRecord> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val root = JSONObject(raw)
        parseBookmarkRecords(root.optJSONArray("items") ?: root.optJSONArray("bookmarks") ?: JSONArray())
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

private fun bookmarksDocument(records: Collection<SyncBookmarkRecord>): JSONObject =
    JSONObject()
        .put("type", "hyper-browser-bookmarks")
        .put("schemaVersion", 1)
        .put("updatedAt", System.currentTimeMillis())
        .put("items", bookmarksArray(records.sortedWith(compareBy({ it.deletedAt != null }, { it.title.lowercase() }))))

private fun webAppsDocument(records: Collection<SyncWebAppRecord>): JSONObject =
    JSONObject()
        .put("type", "hyper-browser-webapps")
        .put("schemaVersion", 1)
        .put("updatedAt", System.currentTimeMillis())
        .put("items", webAppsArray(records.sortedWith(compareBy({ it.deletedAt != null }, { it.name.lowercase() }))))

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

private fun manifestDocument(config: WebDavSyncConfig, now: Long): JSONObject =
    JSONObject()
        .put("type", "hyper-browser-sync")
        .put("schemaVersion", 1)
        .put("updatedAt", now)
        .put("syncRoot", "HyperBrowserSync")
        .put("lastWriter", config.deviceId)
        .put("files", JSONArray().put("bookmarks.json").put("webapps.json").put("launcher.json").put("devices/"))

private fun deviceDocument(config: WebDavSyncConfig, now: Long): JSONObject =
    JSONObject()
        .put("schemaVersion", 1)
        .put("deviceId", config.deviceId)
        .put("deviceName", config.deviceName)
        .put("client", "hyper-browser-android")
        .put("lastSyncAt", now)

private fun JSONObject.optNullableLong(name: String): Long? {
    if (!has(name) || isNull(name)) return null
    val value = optLong(name, 0L)
    return value.takeIf { it > 0 }
}

private fun safeSegment(value: String): String =
    value.map { char ->
        when {
            char.isLetterOrDigit() || char == '-' || char == '_' || char == '.' -> char
            else -> '_'
        }
    }.joinToString("").ifBlank { UUID.randomUUID().toString() }
