package com.dadigua.hyperbrowser.webapp

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import com.dadigua.hyperbrowser.R
import com.dadigua.hyperbrowser.data.WebAppDefinition
import com.dadigua.hyperbrowser.ui.webapp.WebAppActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class WebAppRepository(
    private val context: Context
) {
    private val storeFile = File(context.filesDir, "web_apps.json")
    private val state = MutableStateFlow(load())

    fun observeAll(): StateFlow<List<WebAppDefinition>> = state

    suspend fun get(id: String): WebAppDefinition? = state.value.firstOrNull { it.id == id }

    suspend fun installFromPage(name: String, url: String, themeColor: Int = Color.rgb(18, 109, 106)): WebAppDefinition {
        val now = System.currentTimeMillis()
        val webApp = WebAppDefinition(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { Uri.parse(url).host ?: "WebApp" },
            startUrl = url,
            scopeUrl = scopeFor(url),
            iconPath = null,
            themeColor = themeColor,
            displayMode = "standalone",
            createdAt = now,
            lastOpenedAt = now
        )
        upsert(webApp)
        requestPinnedShortcut(webApp)
        return webApp
    }

    suspend fun markOpened(id: String) {
        val now = System.currentTimeMillis()
        val updated = state.value.map { if (it.id == id) it.copy(lastOpenedAt = now) else it }
        save(updated)
    }

    fun applyTaskDescription(activity: Activity, webApp: WebAppDefinition) {
        @Suppress("DEPRECATION")
        val icon = BitmapFactory.decodeResource(activity.resources, R.mipmap.ic_launcher)
        @Suppress("DEPRECATION")
        activity.setTaskDescription(ActivityManager.TaskDescription(webApp.name, icon, webApp.themeColor))
    }

    private fun requestPinnedShortcut(webApp: WebAppDefinition) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val shortcutManager = context.getSystemService(ShortcutManager::class.java)
        if (!shortcutManager.isRequestPinShortcutSupported) return
        val intent = WebAppActivity.intent(context, webApp.id, true).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("hyperbrowser://webapp/${webApp.id}")
        }
        val shortcut = ShortcutInfo.Builder(context, "webapp-${webApp.id}")
            .setShortLabel(webApp.name.take(20))
            .setLongLabel(webApp.name)
            .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(intent)
            .build()
        shortcutManager.requestPinShortcut(shortcut, null)
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
