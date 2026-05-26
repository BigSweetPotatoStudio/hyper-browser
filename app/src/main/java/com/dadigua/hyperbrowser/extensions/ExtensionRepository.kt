package com.dadigua.hyperbrowser.extensions

import android.content.Context
import com.dadigua.hyperbrowser.data.InstalledExtensionState
import com.dadigua.hyperbrowser.gecko.GeckoRuntimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ExtensionRepository(
    private val context: Context
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()
    private val storeFile = File(context.filesDir, "installed_extensions.json")
    private val installedState = MutableStateFlow(loadInstalled())

    fun observeInstalled(): StateFlow<List<InstalledExtensionState>> = installedState

    suspend fun searchAndroidAddons(query: String): List<AmoAddonListing> = withContext(Dispatchers.IO) {
        val url = "https://addons.mozilla.org/api/v5/addons/search/?" +
            "app=android&page_size=20&lang=zh-CN&q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val body = http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) error("AMO search failed: HTTP ${response.code}")
            response.body?.string() ?: error("AMO returned an empty response.")
        }
        parseSearch(JSONObject(body).getJSONArray("results"))
    }

    suspend fun downloadAndInstall(addon: AmoAddonListing, onStage: (String) -> Unit = {}) {
        onStage("Downloading ${addon.name}...")
        val target = withContext(Dispatchers.IO) {
            val extensionDir = File(context.filesDir, "extensions").apply { mkdirs() }
            val target = File(extensionDir, "${addon.slug}-${addon.version}.xpi")
            http.newCall(Request.Builder().url(addon.xpiUrl).build()).execute().use { response ->
                if (!response.isSuccessful) error("XPI download failed: HTTP ${response.code}")
                target.outputStream().use { output ->
                    response.body?.byteStream()?.copyTo(output) ?: error("XPI download was empty.")
                }
            }
            target
        }
        onStage("Installing ${addon.name}...")
        installXpi(target, addon)
        upsertInstalled(
            InstalledExtensionState(
                guid = addon.guid,
                name = addon.name,
                version = addon.version,
                enabled = true,
                source = "AMO Android",
                permissionsSnapshot = addon.permissions.joinToString("\n"),
                xpiPath = target.absolutePath,
                installedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun setEnabled(guid: String, enabled: Boolean) {
        val installed = installedState.value.firstOrNull { it.guid == guid } ?: error("Extension is not installed.")
        runCatching {
            val extension = findRuntimeExtension(guid)
            if (extension != null) {
                invokeWebExtensionMethod(if (enabled) "enable" else "disable", extension)
            } else if (enabled && installed.xpiPath != null) {
                installRaw(File(installed.xpiPath))
            }
        }
        saveInstalled(installedState.value.map { if (it.guid == guid) it.copy(enabled = enabled) else it })
    }

    suspend fun uninstall(guid: String) {
        runCatching {
            val extension = findRuntimeExtension(guid)
            if (extension != null) {
                invokeWebExtensionMethod("uninstall", extension)
            }
        }
        saveInstalled(installedState.value.filterNot { it.guid == guid })
    }

    private suspend fun installXpi(file: File, addon: AmoAddonListing) {
        try {
            installRaw(file)
        } catch (error: Throwable) {
            throw IllegalStateException(
                "GeckoView rejected ${addon.name}. It may be incompatible with this GeckoView build. ${error.message}",
                error
            )
        }
    }

    private suspend fun installRaw(file: File) {
        withContext(Dispatchers.Main.immediate) {
            val uri = file.toURI().toString()
            val result = GeckoRuntimeProvider.get(context).webExtensionController.install(uri)
            result.await()
        }
    }

    private suspend fun findRuntimeExtension(guid: String): Any? {
        return withContext(Dispatchers.Main.immediate) {
            val controller = GeckoRuntimeProvider.get(context).webExtensionController
            val listMethod = controller.javaClass.methods.firstOrNull { it.name == "list" && it.parameterTypes.isEmpty() }
                ?: return@withContext null
            val result = listMethod.invoke(controller) as? GeckoResult<*> ?: return@withContext null
            val extensions = result.await() as? List<*> ?: return@withContext null
            extensions.firstOrNull { extensionGuid(it) == guid }
        }
    }

    private suspend fun invokeWebExtensionMethod(methodName: String, extension: Any) {
        withContext(Dispatchers.Main.immediate) {
            val controller = GeckoRuntimeProvider.get(context).webExtensionController
            val method = controller.javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterTypes.isNotEmpty()
            } ?: return@withContext
            val result = method.invoke(controller, extension)
            if (result is GeckoResult<*>) {
                result.await()
            }
        }
    }

    private fun extensionGuid(extension: Any?): String? {
        if (extension == null) return null
        val meta = extension.javaClass.methods.firstOrNull { it.name == "getMetaData" }?.invoke(extension)
            ?: return null
        return meta.javaClass.methods.firstOrNull { it.name == "getId" }?.invoke(meta) as? String
    }

    private fun parseSearch(results: JSONArray): List<AmoAddonListing> =
        buildList {
            for (index in 0 until results.length()) {
                val item = results.getJSONObject(index)
                val version = item.getJSONObject("current_version")
                val file = version.getJSONObject("file")
                val compatibility = version.optJSONObject("compatibility")?.optJSONObject("android")
                add(
                    AmoAddonListing(
                        name = localized(item.getJSONObject("name")),
                        slug = item.getString("slug"),
                        guid = item.getString("guid"),
                        version = version.getString("version"),
                        userCount = item.optInt("average_daily_users", 0),
                        xpiUrl = file.getString("url"),
                        permissions = file.optJSONArray("permissions").toStringList(),
                        minAndroidVersion = compatibility?.optString("min"),
                        maxAndroidVersion = compatibility?.optString("max")
                    )
                )
            }
        }

    private fun localized(value: JSONObject): String =
        value.optString("zh-CN")
            .ifBlank { value.optString("en-US") }
            .ifBlank { value.optString("_default") }
            .ifBlank { value.keys().asSequence().firstOrNull()?.let(value::optString).orEmpty() }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { optString(it) }.filter { it.isNotBlank() }
    }

    private fun upsertInstalled(extension: InstalledExtensionState) {
        saveInstalled((installedState.value.filterNot { it.guid == extension.guid } + extension).sortedByDescending { it.installedAt })
    }

    private fun saveInstalled(items: List<InstalledExtensionState>) {
        installedState.value = items
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("guid", item.guid)
                    .put("name", item.name)
                    .put("version", item.version)
                    .put("enabled", item.enabled)
                    .put("source", item.source)
                    .put("permissionsSnapshot", item.permissionsSnapshot)
                    .put("xpiPath", item.xpiPath)
                    .put("installedAt", item.installedAt)
            )
        }
        storeFile.writeText(array.toString())
    }

    private fun loadInstalled(): List<InstalledExtensionState> {
        if (!storeFile.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(storeFile.readText())
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        InstalledExtensionState(
                            guid = item.getString("guid"),
                            name = item.getString("name"),
                            version = item.getString("version"),
                            enabled = item.optBoolean("enabled", true),
                            source = item.optString("source", "AMO Android"),
                            permissionsSnapshot = item.optString("permissionsSnapshot"),
                            xpiPath = item.optString("xpiPath").ifBlank { null },
                            installedAt = item.optLong("installedAt")
                        )
                    )
                }
            }.sortedByDescending { it.installedAt }
        }.getOrDefault(emptyList())
    }
}

private suspend fun GeckoResult<*>.await(): Any? =
    suspendCancellableCoroutine { continuation ->
        accept(
            { value -> continuation.resume(value) },
            { error -> continuation.resumeWithException(error ?: IllegalStateException("GeckoView operation failed.")) }
        )
    }
