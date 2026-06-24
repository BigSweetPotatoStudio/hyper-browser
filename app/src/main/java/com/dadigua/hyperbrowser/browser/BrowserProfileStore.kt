package com.dadigua.hyperbrowser.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

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
    val iconPath: String? = null,
    val id: String = stableBookmarkIdForUrl(url),
    val kind: String = BOOKMARK_KIND_BOOKMARK,
    val parentId: String? = null,
    val index: Int? = null,
    val identityKey: String = if (kind == BOOKMARK_KIND_FOLDER) {
        folderBookmarkIdentityKey(parentId, title, index)
    } else {
        bookmarkIdentityKey(url)
    }
) {
    val isFolder: Boolean
        get() = kind == BOOKMARK_KIND_FOLDER
}

const val BOOKMARK_KIND_BOOKMARK = "bookmark"
const val BOOKMARK_KIND_FOLDER = "folder"

internal fun normalizeBookmarkUrl(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return ""
    return runCatching {
        val uri = java.net.URI(trimmed)
        val scheme = uri.scheme?.lowercase() ?: return@runCatching trimmed
        val authority = uri.rawAuthority ?: return@runCatching trimmed.substringBefore('#')
        val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        buildString {
            append(scheme).append("://").append(authority).append(path)
            uri.rawQuery?.let { append('?').append(it) }
        }
    }.getOrDefault(trimmed.substringBefore('#'))
}

internal fun bookmarkIdentityKey(url: String): String =
    normalizeBookmarkUrl(url)

internal fun folderBookmarkIdentityKey(parentId: String?, title: String, index: Int?): String =
    "folder:${parentId?.takeIf { it.isNotBlank() } ?: "root"}:${title.trim().lowercase()}"

internal fun stableBookmarkIdForUrl(url: String): String {
    val key = bookmarkIdentityKey(url)
    return if (key.isBlank()) {
        UUID.randomUUID().toString()
    } else {
        UUID.nameUUIDFromBytes("bookmark:$key".toByteArray(Charsets.UTF_8)).toString()
    }
}

internal fun stableFolderBookmarkId(parentId: String?, title: String, index: Int?): String =
    UUID.nameUUIDFromBytes("folder:${folderBookmarkIdentityKey(parentId, title, index)}".toByteArray(Charsets.UTF_8)).toString()

data class BrowserSettings(
    val searchEngineId: String = SEARCH_ENGINE_GOOGLE,
    val customSearchUrl: String = "",
    val toolbarPosition: String = TOOLBAR_POSITION_DYNAMIC_BOTTOM,
    val backgroundVideoEnhancementEnabled: Boolean = false,
    val openNewTabsInCurrentTab: Boolean = false,
    val dohEnabled: Boolean = false,
    val dohProviderUrl: String = DEFAULT_DOH_PROVIDER_URL,
    val httpsOnlyEnabled: Boolean = false,
    val privacyProtectionLevel: String = PRIVACY_PROTECTION_STANDARD,
    val localePreference: String = LOCALE_DEFAULT,
    val webDavSyncEnabled: Boolean = false,
    val webDavSyncUrl: String = "",
    val webDavSyncUsername: String = "",
    val webDavSyncPassword: String = "",
    val webDavSyncDeviceName: String = "",
    val webDavSyncDeviceId: String = ""
) {
    val echEnabled: Boolean
        get() = dohEnabled

    val strictPrivacyEnabled: Boolean
        get() = privacyProtectionLevel == PRIVACY_PROTECTION_STRICT

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
        const val TOOLBAR_POSITION_DYNAMIC_BOTTOM = "dynamic_bottom"
        const val DEFAULT_DOH_PROVIDER_URL = "https://mozilla.cloudflare-dns.com/dns-query"
        const val PRIVACY_PROTECTION_NONE = "none"
        const val PRIVACY_PROTECTION_STANDARD = "standard"
        const val PRIVACY_PROTECTION_STRICT = "strict"
        const val LOCALE_DEFAULT = "default"
        const val LOCALE_CHINESE = "zh"
        const val LOCALE_ENGLISH = "en"

        fun normalizedLocalePreference(value: String): String =
            when (value) {
                LOCALE_CHINESE -> LOCALE_CHINESE
                LOCALE_ENGLISH -> LOCALE_ENGLISH
                else -> LOCALE_DEFAULT
            }

        fun normalizedToolbarPosition(value: String): String =
            when (value) {
                TOOLBAR_POSITION_TOP -> TOOLBAR_POSITION_TOP
                TOOLBAR_POSITION_BOTTOM -> TOOLBAR_POSITION_BOTTOM
                TOOLBAR_POSITION_DYNAMIC_BOTTOM -> TOOLBAR_POSITION_DYNAMIC_BOTTOM
                else -> TOOLBAR_POSITION_DYNAMIC_BOTTOM
            }

        fun isBottomToolbarPosition(value: String): Boolean =
            value == TOOLBAR_POSITION_BOTTOM || value == TOOLBAR_POSITION_DYNAMIC_BOTTOM

        fun isDynamicBottomToolbarPosition(value: String): Boolean =
            value == TOOLBAR_POSITION_DYNAMIC_BOTTOM
    }
}

private fun JSONObject.optPositiveLong(name: String): Long? {
    if (!has(name) || isNull(name)) return null
    val value = optLong(name, 0L)
    return value.takeIf { it > 0L }
}

private fun JSONObject.optCleanString(name: String): String? {
    if (!has(name) || isNull(name)) return null
    val value = optString(name).trim()
    return value.takeIf { it.isNotBlank() && it != "null" && it != "undefined" }
}

private fun JSONObject.putCleanNullable(name: String, value: Any?): JSONObject =
    put(name, value ?: JSONObject.NULL)

data class SavedBrowserTab(
    val id: String,
    val title: String,
    val url: String,
    val input: String,
    val iconPath: String? = null,
    val loaded: Boolean = true
)

data class SavedBrowserTabs(
    val selectedTabId: String?,
    val tabs: List<SavedBrowserTab>
)

class BrowserProfileStore(context: Context) {
    private val historyFile = File(context.filesDir, "browser_history.json")
    private val bookmarksFile = File(context.filesDir, "browser_bookmarks.json")
    private val settingsFile = File(context.filesDir, SETTINGS_FILE_NAME)
    private val tabsFile = File(context.filesDir, "browser_tabs.json")
    private val launcherLayoutFile = File(context.filesDir, "launcher_layout.json")
    private val tabSessionStateDir = File(context.filesDir, "browser_tab_states")
    private val tabThumbnailDir = File(context.filesDir, "browser_tab_thumbnails")
    private val historyState = MutableStateFlow(loadHistory())
    private val bookmarksState = MutableStateFlow(loadBookmarks())
    private val settingsState = MutableStateFlow(loadSettings())

    fun observeHistory(): StateFlow<List<BrowserHistoryEntry>> = historyState
    fun observeBookmarks(): StateFlow<List<BrowserBookmark>> = bookmarksState
    fun observeSettings(): StateFlow<BrowserSettings> = settingsState

    fun mergeBookmarks(imported: List<BrowserBookmark>): Int {
        val accepted = mutableListOf<BrowserBookmark>()
        val seenIds = mutableSetOf<String>()
        val seenBookmarkIdentities = mutableSetOf<String>()
        val seenFolderIdentities = mutableSetOf<String>()
        val current = bookmarksState.value
        val existingById = current.associateBy { it.id }
        val existingByIdentity = current
            .filter { it.identityKey.isNotBlank() }
            .associateBy { it.identityKey }
        imported.forEachIndexed { importIndex, bookmark ->
            val normalized = normalizeBookmarkForStorage(
                bookmark = bookmark,
                existingById = existingById,
                existingByIdentity = existingByIdentity,
                defaultIndex = bookmark.index ?: importIndex
            ) ?: return@forEachIndexed
            if (!seenIds.add(normalized.id)) return@forEachIndexed
            if (!normalized.isFolder && normalized.identityKey.isNotBlank() && !seenBookmarkIdentities.add(normalized.identityKey)) {
                return@forEachIndexed
            }
            if (normalized.isFolder && normalized.identityKey.isNotBlank() && !seenFolderIdentities.add(normalized.identityKey)) {
                return@forEachIndexed
            }
            accepted.add(normalized)
        }
        if (accepted.isEmpty()) return 0
        val importedIds = accepted.map { it.id }.toSet()
        val importedBookmarkIdentities = accepted
            .filterNot { it.isFolder }
            .map { it.identityKey }
            .toSet()
        val importedFolderIdentities = accepted
            .filter { it.isFolder }
            .map { it.identityKey }
            .toSet()
        val next = dedupeBookmarkFolderIdentities(accepted + current.filterNot {
            it.id in importedIds ||
                (!it.isFolder && it.identityKey in importedBookmarkIdentities) ||
                (it.isFolder && it.identityKey in importedFolderIdentities)
        })
        bookmarksState.value = next
        saveBookmarks(next)
        return accepted.size
    }

    fun loadSavedTabs(): SavedBrowserTabs {
        if (!tabsFile.exists()) return SavedBrowserTabs(selectedTabId = null, tabs = emptyList())
        return BrowserTabPersistenceCodec.decode(tabsFile.readText())
    }

    fun saveTabs(state: SavedBrowserTabs) {
        tabsFile.writeText(BrowserTabPersistenceCodec.encode(state))
    }

    fun loadLauncherLayout(): JSONObject? {
        if (!launcherLayoutFile.exists()) return null
        return runCatching { JSONObject(launcherLayoutFile.readText()) }.getOrNull()
    }

    fun saveLauncherLayout(layout: JSONObject) {
        val current = loadLauncherLayout()
        val currentUpdatedAt = current?.optPositiveLong("updatedAt") ?: 0L
        val nextUpdatedAt = layout.optPositiveLong("updatedAt") ?: 0L
        if (currentUpdatedAt > 0L && currentUpdatedAt > nextUpdatedAt) return
        launcherLayoutFile.writeText(layout.toString())
    }

    fun loadTabSessionState(tabId: String): String? {
        val file = tabSessionStateFile(tabId) ?: return null
        if (!file.exists()) return null
        return runCatching { file.readText().takeIf { it.isNotBlank() } }.getOrNull()
    }

    fun saveTabSessionState(tabId: String, state: String) {
        val file = tabSessionStateFile(tabId) ?: return
        if (state.isBlank()) {
            runCatching { file.delete() }
            return
        }
        tabSessionStateDir.mkdirs()
        file.writeText(state)
    }

    fun deleteTabSessionState(tabId: String) {
        val file = tabSessionStateFile(tabId) ?: return
        runCatching { file.delete() }
    }

    fun pruneTabSessionStates(keptTabIds: Set<String>) {
        if (!tabSessionStateDir.exists()) return
        val keptFileNames = keptTabIds.mapNotNull(::tabSessionStateFileName).toSet()
        tabSessionStateDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name !in keptFileNames) {
                runCatching { file.delete() }
            }
        }
    }

    fun loadTabThumbnail(tabId: String): Bitmap? {
        val file = tabThumbnailFile(tabId) ?: return null
        if (!file.exists() || file.length() <= 0) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    fun saveTabThumbnail(tabId: String, bitmap: Bitmap) {
        if (bitmap.width <= 0 || bitmap.height <= 0) return
        val file = tabThumbnailFile(tabId) ?: return
        tabThumbnailDir.mkdirs()
        val thumbnail = scaledTabThumbnail(bitmap)
        runCatching {
            val saved = file.outputStream().use { out ->
                thumbnail.compress(Bitmap.CompressFormat.JPEG, 82, out)
            }
            if (!saved) file.delete()
        }.onFailure {
            runCatching { file.delete() }
        }
        if (thumbnail !== bitmap) {
            thumbnail.recycle()
        }
    }

    fun deleteTabThumbnail(tabId: String) {
        val file = tabThumbnailFile(tabId) ?: return
        runCatching { file.delete() }
    }

    fun pruneTabThumbnails(keptTabIds: Set<String>) {
        if (!tabThumbnailDir.exists()) return
        val keptFileNames = keptTabIds.mapNotNull(::tabThumbnailFileName).toSet()
        tabThumbnailDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name !in keptFileNames) {
                runCatching { file.delete() }
            }
        }
    }

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
        val cleanUrl = normalizeBookmarkUrl(url)
        if (cleanUrl.isBlank()) return
        val identityKey = bookmarkIdentityKey(cleanUrl)
        val current = bookmarksState.value
        val existing = current.firstOrNull { !it.isFolder && it.identityKey == identityKey }
        val next = if (existing != null) {
            current.filterNot { it.id == existing.id }
        } else {
            listOf(
                BrowserBookmark(
                    url = cleanUrl,
                    title = title.ifBlank { cleanUrl },
                    createdAt = System.currentTimeMillis(),
                    iconPath = iconPath,
                    id = UUID.randomUUID().toString(),
                    identityKey = identityKey
                )
            ) + current
        }
        bookmarksState.value = next
        saveBookmarks(next)
    }

    fun isBookmarked(url: String): Boolean {
        val identityKey = bookmarkIdentityKey(url)
        return identityKey.isNotBlank() && bookmarksState.value.any { !it.isFolder && it.identityKey == identityKey }
    }

    fun updateBookmarkIcon(url: String, iconPath: String) {
        if (url.isBlank() || iconPath.isBlank()) return
        val identityKey = bookmarkIdentityKey(url)
        val next = bookmarksState.value.map { bookmark ->
            if (!bookmark.isFolder && bookmark.identityKey == identityKey && bookmark.iconPath != iconPath) {
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
        removeBookmarkByIdOrUrl(id = null, url = url)
    }

    fun removeBookmarkByIdOrUrl(id: String?, url: String?) {
        val cleanId = id?.trim().orEmpty()
        val identityKey = bookmarkIdentityKey(url.orEmpty())
        if (cleanId.isBlank() && identityKey.isBlank()) return
        val current = bookmarksState.value
        val rootIds = current
            .filter { bookmark ->
                if (cleanId.isNotBlank()) {
                    bookmark.id == cleanId
                } else {
                    !bookmark.isFolder && bookmark.identityKey == identityKey
                }
            }
            .map { it.id }
            .toSet()
        if (rootIds.isEmpty()) return
        val removedIds = bookmarkIdsWithDescendants(current, rootIds)
        val next = current.filterNot { it.id in removedIds }
        bookmarksState.value = next
        saveBookmarks(next)
    }

    fun editBookmark(oldUrl: String, title: String, url: String) {
        saveBookmark(id = null, oldUrl = oldUrl, title = title, url = url)
    }

    fun saveBookmark(
        id: String?,
        oldUrl: String?,
        title: String,
        url: String,
        kind: String = BOOKMARK_KIND_BOOKMARK,
        parentId: String? = null,
        index: Int? = null,
        iconPath: String? = null
    ): List<BrowserBookmark> {
        val cleanKind = normalizedBookmarkKind(kind)
        val cleanParentId = parentId?.trim()?.takeIf { it.isNotBlank() }
        val cleanIndex = index?.takeIf { it >= 0 }
        val cleanUrl = if (cleanKind == BOOKMARK_KIND_FOLDER) "" else normalizeBookmarkUrl(url)
        if (cleanKind == BOOKMARK_KIND_BOOKMARK && cleanUrl.isBlank()) return bookmarksState.value
        val current = bookmarksState.value
        val oldIdentity = bookmarkIdentityKey(oldUrl.orEmpty())
        val nextIdentity = if (cleanKind == BOOKMARK_KIND_FOLDER) {
            folderBookmarkIdentityKey(cleanParentId, title, cleanIndex)
        } else {
            bookmarkIdentityKey(cleanUrl)
        }
        val cleanId = id?.trim().orEmpty()
        val existing = current.firstOrNull { it.id == cleanId } ?: current.firstOrNull {
            cleanKind == BOOKMARK_KIND_BOOKMARK && oldIdentity.isNotBlank() && !it.isFolder && it.identityKey == oldIdentity
        } ?: current.firstOrNull {
            cleanKind == BOOKMARK_KIND_BOOKMARK && !it.isFolder && it.identityKey == nextIdentity
        }
        val resolvedTitle = title.trim().ifBlank {
            existing?.title ?: if (cleanKind == BOOKMARK_KIND_FOLDER) "Folder" else cleanUrl
        }
        val resolvedId = cleanId.ifBlank {
            existing?.id ?: UUID.randomUUID().toString()
        }
        val saved = BrowserBookmark(
            url = cleanUrl,
            title = resolvedTitle,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            iconPath = iconPath ?: existing?.iconPath,
            id = resolvedId,
            kind = cleanKind,
            parentId = cleanParentId,
            index = cleanIndex,
            identityKey = nextIdentity
        )
        val duplicateIds = current
            .filter {
                it.id == saved.id || (
                    !saved.isFolder &&
                        !it.isFolder &&
                        saved.identityKey.isNotBlank() &&
                        it.identityKey == saved.identityKey
                    ) || (
                    saved.isFolder &&
                        it.isFolder &&
                        saved.identityKey.isNotBlank() &&
                        it.identityKey == saved.identityKey
                    )
            }
            .map { it.id }
            .toSet()
        val insertAt = current.indexOfFirst { it.id in duplicateIds }.takeIf { it >= 0 } ?: 0
        val next = current.filterNot { it.id in duplicateIds }.toMutableList()
        next.add(insertAt.coerceIn(0, next.size), saved)
        val deduped = dedupeBookmarkFolderIdentities(next)
        bookmarksState.value = deduped
        saveBookmarks(deduped)
        return deduped
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
            toolbarPosition = BrowserSettings.normalizedToolbarPosition(toolbarPosition)
        )
        settingsState.value = next
        saveSettings(next)
    }

    fun updateBackgroundVideoEnhancement(enabled: Boolean) {
        val next = settingsState.value.copy(backgroundVideoEnhancementEnabled = enabled)
        settingsState.value = next
        saveSettings(next)
    }

    fun updateOpenNewTabsInCurrentTab(enabled: Boolean) {
        val next = settingsState.value.copy(openNewTabsInCurrentTab = enabled)
        settingsState.value = next
        saveSettings(next)
    }

    fun updateLocalePreference(localePreference: String) {
        val next = settingsState.value.copy(
            localePreference = BrowserSettings.normalizedLocalePreference(localePreference)
        )
        settingsState.value = next
        saveSettings(next)
    }

    fun updatePrivacySettings(
        dohEnabled: Boolean,
        dohProviderUrl: String,
        httpsOnlyEnabled: Boolean,
        privacyProtectionLevel: String
    ) {
        val next = settingsState.value.copy(
            dohEnabled = dohEnabled,
            dohProviderUrl = dohProviderUrl.trim().takeIf { it.startsWith("https://") }
                ?: BrowserSettings.DEFAULT_DOH_PROVIDER_URL,
            httpsOnlyEnabled = httpsOnlyEnabled,
            privacyProtectionLevel = when (privacyProtectionLevel) {
                BrowserSettings.PRIVACY_PROTECTION_NONE -> BrowserSettings.PRIVACY_PROTECTION_NONE
                BrowserSettings.PRIVACY_PROTECTION_STRICT -> BrowserSettings.PRIVACY_PROTECTION_STRICT
                else -> BrowserSettings.PRIVACY_PROTECTION_STANDARD
            }
        )
        settingsState.value = next
        saveSettings(next)
    }

    fun updateWebDavSyncSettings(
        enabled: Boolean,
        url: String,
        username: String,
        password: String,
        deviceName: String
    ): BrowserSettings {
        val current = settingsState.value
        val next = current.copy(
            webDavSyncEnabled = enabled,
            webDavSyncUrl = url.trim(),
            webDavSyncUsername = username.trim(),
            webDavSyncPassword = password,
            webDavSyncDeviceName = deviceName.trim(),
            webDavSyncDeviceId = current.webDavSyncDeviceId.ifBlank { UUID.randomUUID().toString() }
        )
        settingsState.value = next
        saveSettings(next)
        return next
    }

    private fun normalizeBookmarkForStorage(
        bookmark: BrowserBookmark,
        existingById: Map<String, BrowserBookmark>,
        existingByIdentity: Map<String, BrowserBookmark>,
        defaultIndex: Int
    ): BrowserBookmark? {
        val cleanKind = normalizedBookmarkKind(bookmark.kind)
        val cleanParentId = bookmark.parentId?.trim()?.takeIf { it.isNotBlank() }
        val cleanIndex = bookmark.index?.takeIf { it >= 0 } ?: defaultIndex.takeIf { it >= 0 }
        val cleanUrl = if (cleanKind == BOOKMARK_KIND_FOLDER) "" else normalizeBookmarkUrl(bookmark.url)
        if (cleanKind == BOOKMARK_KIND_BOOKMARK && cleanUrl.isBlank()) return null
        val identityKey = if (cleanKind == BOOKMARK_KIND_FOLDER) {
            folderBookmarkIdentityKey(cleanParentId, bookmark.title, cleanIndex)
        } else {
            bookmarkIdentityKey(bookmark.identityKey.ifBlank { cleanUrl })
        }
        val existing = existingById[bookmark.id] ?: existingByIdentity[identityKey]
        val title = bookmark.title.trim().ifBlank {
            existing?.title ?: if (cleanKind == BOOKMARK_KIND_FOLDER) "Folder" else cleanUrl
        }
        val resolvedId = bookmark.id.trim().ifBlank {
            existing?.id ?: if (cleanKind == BOOKMARK_KIND_FOLDER) {
                stableFolderBookmarkId(cleanParentId, title, cleanIndex)
            } else {
                stableBookmarkIdForUrl(cleanUrl)
            }
        }
        return BrowserBookmark(
            url = cleanUrl,
            title = title,
            createdAt = bookmark.createdAt.takeIf { it > 0 }
                ?: existing?.createdAt
                ?: System.currentTimeMillis(),
            iconPath = bookmark.iconPath ?: existing?.iconPath,
            id = resolvedId,
            kind = cleanKind,
            parentId = cleanParentId,
            index = cleanIndex,
            identityKey = identityKey
        )
    }

    private fun bookmarkIdsWithDescendants(items: List<BrowserBookmark>, rootIds: Set<String>): Set<String> {
        val removed = rootIds.toMutableSet()
        var changed: Boolean
        do {
            changed = false
            items.forEach { bookmark ->
                val parentId = bookmark.parentId
                if (parentId != null && parentId in removed && removed.add(bookmark.id)) {
                    changed = true
                }
            }
        } while (changed)
        return removed
    }

    private fun normalizedBookmarkKind(kind: String): String =
        if (kind == BOOKMARK_KIND_FOLDER) BOOKMARK_KIND_FOLDER else BOOKMARK_KIND_BOOKMARK

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
            val loaded = buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val kind = normalizedBookmarkKind(item.optString("kind", BOOKMARK_KIND_BOOKMARK))
                    val parentId = item.optCleanString("parentId")
                    val bookmarkIndex = if (item.has("index") && !item.isNull("index")) item.optInt("index") else index
                    val url = if (kind == BOOKMARK_KIND_FOLDER) "" else normalizeBookmarkUrl(item.optString("url"))
                    if (kind == BOOKMARK_KIND_BOOKMARK && url.isBlank()) continue
                    val title = item.optString("title").trim().ifBlank {
                        if (kind == BOOKMARK_KIND_FOLDER) "Folder" else url
                    }
                    val id = item.optString("id").trim().ifBlank {
                        if (kind == BOOKMARK_KIND_FOLDER) {
                            stableFolderBookmarkId(parentId, title, bookmarkIndex)
                        } else {
                            stableBookmarkIdForUrl(url)
                        }
                    }
                    val identityKey = if (kind == BOOKMARK_KIND_FOLDER) {
                        folderBookmarkIdentityKey(parentId, title, bookmarkIndex)
                    } else {
                        item.optCleanString("identityKey") ?: bookmarkIdentityKey(url)
                    }
                    add(
                        BrowserBookmark(
                            url = url,
                            title = title,
                            createdAt = item.optLong("createdAt"),
                            iconPath = item.optCleanString("iconPath"),
                            id = id,
                            kind = kind,
                            parentId = parentId,
                            index = bookmarkIndex,
                            identityKey = identityKey
                        )
                    )
                }
            }
            val deduped = dedupeBookmarkFolderIdentities(loaded)
            if (deduped != loaded) {
                saveBookmarks(deduped)
            }
            deduped
        }.getOrDefault(emptyList())
    }

    private fun dedupeBookmarkFolderIdentities(items: List<BrowserBookmark>): List<BrowserBookmark> {
        var current = items.map { bookmark ->
            if (!bookmark.isFolder) {
                bookmark
            } else {
                bookmark.copy(identityKey = folderBookmarkIdentityKey(bookmark.parentId, bookmark.title, bookmark.index))
            }
        }
        var changed: Boolean
        do {
            changed = false
            val selected = mutableMapOf<String, BrowserBookmark>()
            current.filter { it.isFolder }.forEach { folder ->
                val identityKey = folderBookmarkIdentityKey(folder.parentId, folder.title, folder.index)
                val normalized = if (folder.identityKey == identityKey) folder else folder.copy(identityKey = identityKey)
                val existing = selected[identityKey]
                if (existing == null || compareFolderDedupeCandidate(current, normalized, existing) > 0) {
                    selected[identityKey] = normalized
                }
            }
            val duplicateToKeep = current
                .filter { it.isFolder }
                .mapNotNull { folder ->
                    val keep = selected[folderBookmarkIdentityKey(folder.parentId, folder.title, folder.index)]
                    if (keep != null && keep.id != folder.id) folder.id to keep.id else null
                }
                .toMap()
            if (duplicateToKeep.isEmpty()) continue
            changed = true
            current = current.mapNotNull { bookmark ->
                if (bookmark.isFolder && duplicateToKeep.containsKey(bookmark.id)) {
                    null
                } else {
                    val nextParentId = duplicateToKeep[bookmark.parentId] ?: bookmark.parentId
                    if (nextParentId == bookmark.parentId) {
                        bookmark
                    } else if (bookmark.isFolder) {
                        bookmark.copy(
                            parentId = nextParentId,
                            identityKey = folderBookmarkIdentityKey(nextParentId, bookmark.title, bookmark.index)
                        )
                    } else {
                        bookmark.copy(parentId = nextParentId)
                    }
                }
            }
        } while (changed)
        return current
    }

    private fun compareFolderDedupeCandidate(
        items: List<BrowserBookmark>,
        left: BrowserBookmark,
        right: BrowserBookmark
    ): Int {
        val childDelta = bookmarkDescendantCount(items, left.id) - bookmarkDescendantCount(items, right.id)
        if (childDelta != 0) return childDelta
        val leftIndex = left.index ?: Int.MAX_VALUE
        val rightIndex = right.index ?: Int.MAX_VALUE
        if (leftIndex != rightIndex) return rightIndex - leftIndex
        return left.id.compareTo(right.id)
    }

    private fun bookmarkDescendantCount(items: List<BrowserBookmark>, folderId: String): Int {
        var count = 0
        fun visit(parentId: String) {
            items.filter { it.parentId == parentId }.forEach { bookmark ->
                count += 1
                if (bookmark.isFolder) {
                    visit(bookmark.id)
                }
            }
        }
        visit(folderId)
        return count
    }

    private fun loadSettings(): BrowserSettings {
        return loadBrowserSettings(settingsFile)
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
                    .put("id", it.id)
                    .put("kind", it.kind)
                    .put("identityKey", it.identityKey)
                    .putCleanNullable("parentId", it.parentId)
                    .putCleanNullable("index", it.index)
                    .put("url", it.url)
                    .put("title", it.title)
                    .put("createdAt", it.createdAt)
                    .putCleanNullable("iconPath", it.iconPath)
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
                .put("backgroundVideoEnhancementEnabled", settings.backgroundVideoEnhancementEnabled)
                .put("openNewTabsInCurrentTab", settings.openNewTabsInCurrentTab)
                .put("dohEnabled", settings.dohEnabled)
                .put("dohProviderUrl", settings.dohProviderUrl)
                .put("httpsOnlyEnabled", settings.httpsOnlyEnabled)
                .put("privacyProtectionLevel", settings.privacyProtectionLevel)
                .put("localePreference", settings.localePreference)
                .put("webDavSyncEnabled", settings.webDavSyncEnabled)
                .put("webDavSyncUrl", settings.webDavSyncUrl)
                .put("webDavSyncUsername", settings.webDavSyncUsername)
                .put("webDavSyncPassword", settings.webDavSyncPassword)
                .put("webDavSyncDeviceName", settings.webDavSyncDeviceName)
                .put("webDavSyncDeviceId", settings.webDavSyncDeviceId)
                .put("privacySettingsVersion", CURRENT_PRIVACY_SETTINGS_VERSION)
                .toString()
        )
    }

    private fun tabSessionStateFile(tabId: String): File? {
        val fileName = tabSessionStateFileName(tabId) ?: return null
        return File(tabSessionStateDir, fileName)
    }

    private fun tabThumbnailFile(tabId: String): File? {
        val fileName = tabThumbnailFileName(tabId) ?: return null
        return File(tabThumbnailDir, fileName)
    }

    private fun tabSessionStateFileName(tabId: String): String? =
        tabFileName(tabId, extension = "session")

    private fun tabThumbnailFileName(tabId: String): String? =
        tabFileName(tabId, extension = "jpg")

    private fun tabFileName(tabId: String, extension: String): String? {
        if (tabId.isBlank()) return null
        val safeId = tabId.map { char ->
            when {
                char.isLetterOrDigit() || char == '-' || char == '_' || char == '.' -> char
                else -> '_'
            }
        }.joinToString("")
        return "$safeId.$extension"
    }

    private fun scaledTabThumbnail(bitmap: Bitmap): Bitmap {
        val scale = minOf(
            TAB_THUMBNAIL_MAX_WIDTH.toFloat() / bitmap.width,
            TAB_THUMBNAIL_MAX_HEIGHT.toFloat() / bitmap.height,
            1f
        )
        if (scale >= 1f) return bitmap
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true
        )
    }

    companion object {
        private const val SETTINGS_FILE_NAME = "browser_settings.json"
        private const val CURRENT_PRIVACY_SETTINGS_VERSION = 1
        private const val TAB_THUMBNAIL_MAX_WIDTH = 480
        private const val TAB_THUMBNAIL_MAX_HEIGHT = 720

        fun loadBrowserSettings(context: Context): BrowserSettings =
            loadBrowserSettings(File(context.filesDir, SETTINGS_FILE_NAME))

        private fun loadBrowserSettings(file: File): BrowserSettings {
            if (!file.exists()) return BrowserSettings()
            return runCatching {
                val item = JSONObject(file.readText())
                val privacySettingsVersion = item.optInt("privacySettingsVersion", 0)
                BrowserSettings(
                    searchEngineId = item.optString("searchEngineId", BrowserSettings.SEARCH_ENGINE_GOOGLE),
                    customSearchUrl = item.optString("customSearchUrl"),
                    toolbarPosition = BrowserSettings.normalizedToolbarPosition(
                        item.optString("toolbarPosition", BrowserSettings.TOOLBAR_POSITION_DYNAMIC_BOTTOM)
                    ),
                    backgroundVideoEnhancementEnabled = item.optBoolean("backgroundVideoEnhancementEnabled", false),
                    openNewTabsInCurrentTab = item.optBoolean("openNewTabsInCurrentTab", false),
                    dohEnabled = item.optBoolean("dohEnabled", false),
                    dohProviderUrl = item.optString(
                        "dohProviderUrl",
                        BrowserSettings.DEFAULT_DOH_PROVIDER_URL
                    ).takeIf { it.startsWith("https://") } ?: BrowserSettings.DEFAULT_DOH_PROVIDER_URL,
                    httpsOnlyEnabled = if (privacySettingsVersion > 0) {
                        item.optBoolean("httpsOnlyEnabled", false)
                    } else {
                        false
                    },
                    privacyProtectionLevel = when (item.optString("privacyProtectionLevel")) {
                        BrowserSettings.PRIVACY_PROTECTION_NONE ->
                            BrowserSettings.PRIVACY_PROTECTION_NONE
                        BrowserSettings.PRIVACY_PROTECTION_STRICT ->
                            BrowserSettings.PRIVACY_PROTECTION_STRICT
                        else -> BrowserSettings.PRIVACY_PROTECTION_STANDARD
                    },
                    localePreference = BrowserSettings.normalizedLocalePreference(
                        item.optString("localePreference", BrowserSettings.LOCALE_DEFAULT)
                    ),
                    webDavSyncEnabled = item.optBoolean("webDavSyncEnabled", false),
                    webDavSyncUrl = item.optString("webDavSyncUrl"),
                    webDavSyncUsername = item.optString("webDavSyncUsername"),
                    webDavSyncPassword = item.optString("webDavSyncPassword"),
                    webDavSyncDeviceName = item.optString("webDavSyncDeviceName"),
                    webDavSyncDeviceId = item.optString("webDavSyncDeviceId")
                )
            }.getOrDefault(BrowserSettings())
        }
    }
}
