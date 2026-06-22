package com.dadigua.hyperbrowser.webapp

import android.app.Activity
import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.util.Base64
import com.dadigua.hyperbrowser.R
import com.dadigua.hyperbrowser.browser.BrowserIconComposer
import com.dadigua.hyperbrowser.browser.FaviconRepository
import com.dadigua.hyperbrowser.data.WebAppDefinition
import com.dadigua.hyperbrowser.ui.webapp.WebAppActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.UUID

class WebAppRepository(
    private val context: Context
) {
    private val storeFile = File(context.filesDir, "web_apps.json")
    private val faviconStore = FaviconRepository(context)
    private val state = MutableStateFlow(load())

    fun observeAll(): StateFlow<List<WebAppDefinition>> = state

    suspend fun get(id: String): WebAppDefinition? = state.value.firstOrNull { it.id == id }

    fun mergeImported(items: List<WebAppDefinition>): Int {
        val current = state.value
        val currentByStartUrl = current.associateBy { it.startUrl }
        val usedIds = current.map { it.id }.toMutableSet()
        val importedStartUrls = mutableSetOf<String>()
        val accepted = mutableListOf<WebAppDefinition>()
        val shortcutUpdates = mutableListOf<WebAppDefinition>()
        items.forEach { item ->
            val startUrl = item.startUrl.trim()
            if (startUrl.isBlank() || !importedStartUrls.add(startUrl)) return@forEach
            val existing = currentByStartUrl[startUrl]
            val id = existing?.id
                ?: item.id.takeIf { it.isNotBlank() && usedIds.add(it) }
                ?: UUID.randomUUID().toString().also { usedIds.add(it) }
            val iconPath = faviconStore.existingIconPath(siteModeIconPath(item.iconPath))
                ?: faviconStore.cachedIconPath(startUrl)
                ?: siteModeIconPath(existing?.iconPath)
            val webApp = WebAppDefinition(
                id = id,
                name = item.name.trim().ifBlank { existing?.name ?: Uri.parse(startUrl).host ?: "WebApp" },
                startUrl = startUrl,
                scopeUrl = item.scopeUrl.trim().ifBlank { scopeFor(startUrl) },
                iconPath = iconPath,
                themeColor = item.themeColor,
                displayMode = item.displayMode.ifBlank { existing?.displayMode ?: "standalone" },
                createdAt = item.createdAt.takeIf { it > 0 }
                    ?: existing?.createdAt
                    ?: System.currentTimeMillis(),
                lastOpenedAt = item.lastOpenedAt.takeIf { it > 0 }
                    ?: existing?.lastOpenedAt
                    ?: System.currentTimeMillis()
            )
            accepted.add(webApp)
            if (existing != null) shortcutUpdates.add(webApp)
        }
        if (accepted.isEmpty()) return 0
        val importedUrls = accepted.map { it.startUrl }.toSet()
        save((accepted + current.filterNot { it.startUrl in importedUrls }).sortedByDescending { it.lastOpenedAt })
        shortcutUpdates.forEach { updateShortcut(it) }
        return accepted.size
    }

    fun iconDataUrl(webApp: WebAppDefinition): String? {
        val icon = rawWebAppIconBitmap(webApp, 192) ?: return null
        return bitmapDataUrl(icon)
    }

    fun siteIconDataUrl(webApp: WebAppDefinition): String? {
        val currentIconPath = faviconStore.existingIconPath(siteModeIconPath(webApp.iconPath))
        val siteIconPath = if (faviconStore.isCustomIconPath(currentIconPath)) {
            faviconStore.cachedIconPath(webApp.startUrl)
        } else {
            currentIconPath ?: faviconStore.cachedIconPath(webApp.startUrl)
        }
        return faviconStore.iconDataUrl(siteIconPath)
    }

    fun iconSource(webApp: WebAppDefinition): String {
        val currentIconPath = faviconStore.existingIconPath(siteModeIconPath(webApp.iconPath))
        return when {
            faviconStore.isCustomIconPath(currentIconPath) -> "custom"
            currentIconPath != null || faviconStore.cachedIconPath(webApp.startUrl) != null -> "site"
            else -> "title"
        }
    }

    suspend fun installFromPage(
        name: String,
        url: String,
        themeColor: Int = Color.rgb(18, 109, 106),
        iconPath: String? = null
    ): WebAppInstallResult {
        val now = System.currentTimeMillis()
        val resolvedIconPath = resolveWebAppIconPath(url, iconPath)
        val webApp = WebAppDefinition(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { Uri.parse(url).host ?: "WebApp" },
            startUrl = url,
            scopeUrl = scopeFor(url),
            iconPath = resolvedIconPath,
            themeColor = themeColor,
            displayMode = "standalone",
            createdAt = now,
            lastOpenedAt = now
        )
        upsert(webApp)
        return WebAppInstallResult(
            webApp = webApp,
            shortcutRequest = requestPinnedShortcut(webApp)
        )
    }

    suspend fun pinToHome(id: String): PinnedShortcutRequestResult {
        val webApp = state.value.firstOrNull { it.id == id } ?: return PinnedShortcutRequestResult.WebAppNotFound
        return requestPinnedShortcut(webApp)
    }

    suspend fun update(id: String, name: String, startUrl: String): WebAppDefinition? {
        val current = state.value.firstOrNull { it.id == id } ?: return null
        val cleanUrl = startUrl.trim()
        if (cleanUrl.isBlank()) return null
        val currentIconPath = siteModeIconPath(current.iconPath)
        val iconPath = resolveWebAppIconPath(cleanUrl, currentIconPath)
        val updated = current.copy(
            name = name.trim().ifBlank { Uri.parse(cleanUrl).host ?: current.name },
            startUrl = cleanUrl,
            scopeUrl = scopeFor(cleanUrl),
            iconPath = iconPath ?: currentIconPath
        )
        upsert(updated)
        updateShortcut(updated)
        return updated
    }

    suspend fun updateIcon(id: String, iconPath: String): WebAppDefinition? {
        val current = state.value.firstOrNull { it.id == id } ?: return null
        val updated = if (isLegacyNoIconPath(iconPath)) {
            current.copy(iconPath = null)
        } else {
            val validIconPath = faviconStore.existingIconPath(iconPath) ?: return null
            current.copy(iconPath = validIconPath)
        }
        upsert(updated)
        updateShortcut(updated)
        return updated
    }

    suspend fun resetIconToSite(id: String): WebAppDefinition? {
        val current = state.value.firstOrNull { it.id == id } ?: return null
        val resolvedIconPath = resolveWebAppIconPath(current.startUrl, null)
        val updated = current.copy(iconPath = resolvedIconPath)
        upsert(updated)
        updateShortcut(updated)
        return updated
    }

    suspend fun delete(id: String): Boolean {
        val current = state.value.firstOrNull { it.id == id } ?: return false
        save(state.value.filterNot { it.id == id })
        disableShortcut(current)
        return true
    }

    suspend fun markOpened(id: String) {
        val now = System.currentTimeMillis()
        val updated = state.value.map {
            if (it.id == id) {
                val iconPath = resolveWebAppIconPath(it.startUrl, it.iconPath)
                it.copy(lastOpenedAt = now, iconPath = iconPath)
            } else {
                it
            }
        }
        save(updated)
        updated.firstOrNull { it.id == id }?.let { updateShortcut(it) }
    }

    suspend fun refreshMissingIcons() {
        val shortcutUpdates = mutableListOf<WebAppDefinition>()
        val updated = state.value.map { webApp ->
            if (!isLegacyNoIconPath(webApp.iconPath) && faviconStore.existingIconPath(webApp.iconPath) != null) {
                webApp
            } else {
                val iconPath = resolveWebAppIconPath(webApp.startUrl, webApp.iconPath)
                if (iconPath != webApp.iconPath) {
                    webApp.copy(iconPath = iconPath).also { shortcutUpdates.add(it) }
                } else {
                    webApp
                }
            }
        }
        if (shortcutUpdates.isEmpty()) return
        save(updated)
        shortcutUpdates.forEach { updateShortcut(it) }
    }

    suspend fun updateIconForUrl(url: String, iconPath: String): Boolean {
        val validIconPath = faviconStore.existingIconPath(iconPath) ?: return false
        val shortcutUpdates = mutableListOf<WebAppDefinition>()
        val updated = state.value.map { webApp ->
            if (
                sameOrigin(webApp.startUrl, url) &&
                !faviconStore.isCustomIconPath(webApp.iconPath) &&
                webApp.iconPath != validIconPath
            ) {
                webApp.copy(iconPath = validIconPath).also { shortcutUpdates.add(it) }
            } else {
                webApp
            }
        }
        if (shortcutUpdates.isEmpty()) return false
        save(updated)
        shortcutUpdates.forEach { updateShortcut(it) }
        return true
    }

    fun applyTaskDescription(activity: Activity, webApp: WebAppDefinition) {
        @Suppress("DEPRECATION")
        val icon = badgedWebAppIconBitmap(webApp, 96)
            ?: BitmapFactory.decodeResource(activity.resources, R.mipmap.ic_launcher)
        @Suppress("DEPRECATION")
        activity.setTaskDescription(ActivityManager.TaskDescription(webApp.name, icon, webApp.themeColor))
    }

    private fun requestPinnedShortcut(webApp: WebAppDefinition): PinnedShortcutRequestResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return PinnedShortcutRequestResult.Unsupported
        val shortcutManager = context.getSystemService(ShortcutManager::class.java)
        if (!shortcutManager.isRequestPinShortcutSupported) return PinnedShortcutRequestResult.Unsupported
        val shortcut = shortcutInfo(webApp)
        val callback = PendingIntent.getBroadcast(
            context,
            shortcutId(webApp.id).hashCode(),
            ShortcutPinnedReceiver.intent(context, webApp.name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return if (shortcutManager.requestPinShortcut(shortcut, callback.intentSender)) {
            PinnedShortcutRequestResult.Requested
        } else {
            PinnedShortcutRequestResult.Failed
        }
    }

    private fun updateShortcut(webApp: WebAppDefinition) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val shortcutManager = context.getSystemService(ShortcutManager::class.java)
        runCatching { shortcutManager.updateShortcuts(listOf(shortcutInfo(webApp))) }
    }

    private fun disableShortcut(webApp: WebAppDefinition) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        val shortcutManager = context.getSystemService(ShortcutManager::class.java)
        runCatching { shortcutManager.disableShortcuts(listOf(shortcutId(webApp.id)), "WebApp removed") }
    }

    private fun shortcutInfo(webApp: WebAppDefinition): ShortcutInfo {
        val intent = WebAppActivity.intent(context, webApp.id, true).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("hyperbrowser://webapp/${webApp.id}")
        }
        return ShortcutInfo.Builder(context, shortcutId(webApp.id))
            .setShortLabel(webApp.name.take(20))
            .setLongLabel(webApp.name)
            .setIcon(shortcutIcon(webApp))
            .setIntent(intent)
            .build()
    }

    private fun shortcutIcon(webApp: WebAppDefinition): Icon =
        badgedWebAppIconBitmap(webApp, 192)
            ?.let { Icon.createWithBitmap(it) }
            ?: Icon.createWithResource(context, R.mipmap.ic_launcher)

    private fun iconPathFor(webApp: WebAppDefinition): String? =
        faviconStore.existingIconPath(siteModeIconPath(webApp.iconPath))
            ?: faviconStore.cachedIconPath(webApp.startUrl)

    private suspend fun resolveWebAppIconPath(startUrl: String, currentIconPath: String?): String? {
        val existing = faviconStore.existingIconPath(siteModeIconPath(currentIconPath))
        if (faviconStore.isCustomIconPath(existing)) return existing
        val cached = faviconStore.cachedIconPath(startUrl)
        val resolved = runCatching { faviconStore.resolveIconPath(startUrl) }.getOrNull()
        return resolved ?: cached ?: existing
    }

    private fun sameOrigin(left: String, right: String): Boolean {
        val leftUri = runCatching { Uri.parse(left) }.getOrNull() ?: return false
        val rightUri = runCatching { Uri.parse(right) }.getOrNull() ?: return false
        val leftPort = if (leftUri.port > 0) leftUri.port else defaultPort(leftUri.scheme)
        val rightPort = if (rightUri.port > 0) rightUri.port else defaultPort(rightUri.scheme)
        return leftUri.scheme.equals(rightUri.scheme, ignoreCase = true) &&
            leftUri.host.equals(rightUri.host, ignoreCase = true) &&
            leftPort == rightPort
    }

    private fun defaultPort(scheme: String?): Int =
        when (scheme?.lowercase()) {
            "http" -> 80
            "https" -> 443
            else -> -1
        }

    private fun shortcutId(id: String): String = "webapp-$id"

    private fun rawWebAppIconBitmap(webApp: WebAppDefinition, size: Int): Bitmap? {
        val iconPath = iconPathFor(webApp)
        val bitmap = iconPath?.let { BitmapFactory.decodeFile(it) }
        if (bitmap != null && !bitmap.isRecycled) return normalizeBitmap(bitmap, size)
        return BrowserIconComposer.defaultWebAppIcon(webApp.name, webApp.startUrl, size)
    }

    private fun badgedWebAppIconBitmap(webApp: WebAppDefinition, size: Int): Bitmap? {
        val rawIcon = rawWebAppIconBitmap(webApp, size) ?: return null
        return BrowserIconComposer.badgedSiteIcon(context, rawIcon, size)
    }

    private fun normalizeBitmap(source: Bitmap, size: Int): Bitmap? {
        if (source.isRecycled || size <= 0) return null
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(output)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, null, android.graphics.RectF(0f, 0f, size.toFloat(), size.toFloat()), paint)
        return output
    }

    private fun bitmapDataUrl(bitmap: Bitmap): String {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        return "data:image/png;base64,${Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)}"
    }

    private fun scopeFor(url: String): String {
        val uri = Uri.parse(url)
        return uri.buildUpon().encodedPath("/").encodedQuery(null).fragment(null).build().toString()
    }

    private fun upsert(webApp: WebAppDefinition) {
        val updated = state.value.filterNot { it.id == webApp.id } + webApp
        save(updated.sortedByDescending { it.lastOpenedAt })
    }

    private fun save(items: List<WebAppDefinition>) {
        state.value = items
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("startUrl", item.startUrl)
                    .put("scopeUrl", item.scopeUrl)
                    .put("iconPath", item.iconPath)
                    .put("themeColor", item.themeColor)
                    .put("displayMode", item.displayMode)
                    .put("createdAt", item.createdAt)
                    .put("lastOpenedAt", item.lastOpenedAt)
            )
        }
        storeFile.writeText(array.toString())
    }

    private fun load(): List<WebAppDefinition> {
        if (!storeFile.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(storeFile.readText())
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        WebAppDefinition(
                            id = item.getString("id"),
                            name = item.getString("name"),
                            startUrl = item.getString("startUrl"),
                            scopeUrl = item.getString("scopeUrl"),
                            iconPath = siteModeIconPath(item.optString("iconPath").ifBlank { null }),
                            themeColor = item.optInt("themeColor", Color.rgb(18, 109, 106)),
                            displayMode = item.optString("displayMode", "standalone"),
                            createdAt = item.optLong("createdAt"),
                            lastOpenedAt = item.optLong("lastOpenedAt")
                        )
                    )
                }
            }.sortedByDescending { it.lastOpenedAt }
        }.getOrDefault(emptyList())
    }

    private fun siteModeIconPath(iconPath: String?): String? =
        iconPath?.takeUnless { isLegacyNoIconPath(it) }

    companion object {
        private const val LEGACY_NO_ICON_PATH = "hyperbrowser://webapp-icon/none"

        fun isLegacyNoIconPath(iconPath: String?): Boolean = iconPath == LEGACY_NO_ICON_PATH
    }
}

data class WebAppInstallResult(
    val webApp: WebAppDefinition,
    val shortcutRequest: PinnedShortcutRequestResult
)

enum class PinnedShortcutRequestResult {
    Requested,
    Unsupported,
    Failed,
    WebAppNotFound
}
