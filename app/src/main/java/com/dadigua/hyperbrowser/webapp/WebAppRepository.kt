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

    fun iconDataUrl(webApp: WebAppDefinition): String? {
        val icon = webAppIconBitmap(webApp, 192) ?: return null
        val output = ByteArrayOutputStream()
        icon.compress(Bitmap.CompressFormat.PNG, 100, output)
        return "data:image/png;base64,${Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)}"
    }

    suspend fun installFromPage(
        name: String,
        url: String,
        themeColor: Int = Color.rgb(18, 109, 106),
        iconPath: String? = null
    ): WebAppInstallResult {
        val now = System.currentTimeMillis()
        val resolvedIconPath = iconPath ?: faviconStore.cachedIconPath(url) ?: faviconStore.resolveIconPath(url)
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
        val iconPath = faviconStore.cachedIconPath(cleanUrl) ?: faviconStore.resolveIconPath(cleanUrl)
        val updated = current.copy(
            name = name.trim().ifBlank { Uri.parse(cleanUrl).host ?: current.name },
            startUrl = cleanUrl,
            scopeUrl = scopeFor(cleanUrl),
            iconPath = iconPath ?: current.iconPath
        )
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
                val iconPath = it.iconPath ?: faviconStore.cachedIconPath(it.startUrl)
                it.copy(lastOpenedAt = now, iconPath = iconPath)
            } else {
                it
            }
        }
        save(updated)
        updated.firstOrNull { it.id == id }?.let { updateShortcut(it) }
    }

    fun applyTaskDescription(activity: Activity, webApp: WebAppDefinition) {
        @Suppress("DEPRECATION")
        val icon = webAppIconBitmap(webApp, 96)
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
        webAppIconBitmap(webApp, 192)
            ?.let { Icon.createWithBitmap(it) }
            ?: Icon.createWithResource(context, R.mipmap.ic_launcher)

    private fun iconPathFor(webApp: WebAppDefinition): String? =
        webApp.iconPath ?: faviconStore.cachedIconPath(webApp.startUrl)

    private fun shortcutId(id: String): String = "webapp-$id"

    private fun webAppIconBitmap(webApp: WebAppDefinition, size: Int): Bitmap? {
        return BrowserIconComposer.badgedSiteIcon(
            context,
            iconPathFor(webApp),
            webApp.startUrl,
            size
        )
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
                            iconPath = item.optString("iconPath").ifBlank { null },
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
