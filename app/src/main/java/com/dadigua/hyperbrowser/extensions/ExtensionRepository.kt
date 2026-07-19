package com.dadigua.hyperbrowser.extensions

import android.content.Context
import android.graphics.Bitmap
import com.dadigua.hyperbrowser.data.AtomicFileWriter
import com.dadigua.hyperbrowser.data.InstalledExtensionState
import com.dadigua.hyperbrowser.gecko.GeckoRuntimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ExtensionMenuActionState(
    val guid: String,
    val title: String,
    val enabled: Boolean,
    val badgeText: String?,
    val icon: Bitmap? = null
)

data class ExtensionPopupState(
    val guid: String,
    val title: String,
    val session: GeckoSession
)

data class ExtensionNewTabRequest(
    val guid: String,
    val title: String,
    val url: String,
    val session: GeckoSession
)

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
    private val menuActionState = MutableStateFlow<Map<String, ExtensionMenuActionState>>(emptyMap())
    private val popupState = MutableStateFlow<ExtensionPopupState?>(null)
    private val newTabRequestState = MutableStateFlow<ExtensionNewTabRequest?>(null)
    private val menuActions = mutableMapOf<String, WebExtension.Action>()

    fun observeInstalled(): StateFlow<List<InstalledExtensionState>> = installedState

    fun observeMenuActions(): StateFlow<Map<String, ExtensionMenuActionState>> = menuActionState

    fun observePopup(): StateFlow<ExtensionPopupState?> = popupState

    fun observeNewTabRequests(): StateFlow<ExtensionNewTabRequest?> = newTabRequestState

    fun consumeNewTabRequest() {
        newTabRequestState.value = null
    }

    suspend fun refreshInstalledFromRuntime(): List<InstalledExtensionState> {
        val runtimeExtensions = withContext(Dispatchers.Main.immediate) {
            listRuntimeExtensions().filterNot { it.isBuiltIn || it.id == INTERNAL_EXTENSION_ID }
        }
        if (runtimeExtensions.isEmpty()) return installedState.value

        val existingByGuid = installedState.value.associateBy { it.guid }
        val runtimeGuids = runtimeExtensions.map { it.id }.toSet()
        val merged = (
            installedState.value.filterNot { it.guid in runtimeGuids } +
                runtimeExtensions.map { extension ->
                    val existing = existingByGuid[extension.id]
                    val desiredEnabled = existing?.enabled ?: extension.metaData.enabled
                    if (existing != null && extension.metaData.enabled != desiredEnabled) {
                        invokeWebExtensionMethod(if (desiredEnabled) "enable" else "disable", extension, ENABLE_SOURCE_APP)
                    }
                    extension.toInstalledState(existing, desiredEnabled)
                }
            ).sortedByDescending { it.installedAt }

        saveInstalled(merged)
        return merged
    }

    fun closePopup() {
        popupState.value?.session?.close()
        popupState.value = null
    }

    private fun openExtensionTab(extension: WebExtension, url: String): GeckoSession {
        val newSession = GeckoSession()
        newSession.open(GeckoRuntimeProvider.get(context))
        newSession.loadUri(url)
        closePopup()
        newTabRequestState.value = ExtensionNewTabRequest(
            guid = extension.id,
            title = extension.metaData.name ?: extension.id,
            url = url,
            session = newSession
        )
        return newSession
    }

    private fun configurePopupSession(popupSession: GeckoSession, extension: WebExtension) {
        popupSession.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> =
                GeckoResult.fromValue(openExtensionTab(extension, uri))

            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                val optionsPageUrl = extension.metaData.optionsPageUrl ?: return null
                val uriWithoutFragment = request.uri.substringBefore("#")
                val optionsWithoutFragment = optionsPageUrl.substringBefore("#")
                if (uriWithoutFragment == optionsWithoutFragment) {
                    openExtensionTab(extension, request.uri)
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                return null
            }
        }
    }

    suspend fun refreshMenuActions(activeSession: GeckoSession) {
        withContext(Dispatchers.Main.immediate) {
            listRuntimeExtensions().forEach { extension ->
                extension.setTabDelegate(
                    object : WebExtension.TabDelegate {
                        override fun onNewTab(
                            extension: WebExtension,
                            createDetails: WebExtension.CreateTabDetails
                        ): GeckoResult<GeckoSession> {
                            val url = createDetails.url?.takeIf { it.isNotBlank() }
                                ?: extension.metaData.optionsPageUrl
                                ?: extension.metaData.baseUrl
                            return GeckoResult.fromValue(openExtensionTab(extension, url))
                        }

                        override fun onOpenOptionsPage(extension: WebExtension) {
                            val url = extension.metaData.optionsPageUrl ?: return
                            openExtensionTab(extension, url)
                        }
                    }
                )
                extension.setActionDelegate(
                    object : WebExtension.ActionDelegate {
                        override fun onBrowserAction(
                            extension: WebExtension,
                            session: GeckoSession?,
                            action: WebExtension.Action
                        ) {
                            updateMenuAction(extension, action)
                        }

                        override fun onPageAction(
                            extension: WebExtension,
                            session: GeckoSession?,
                            action: WebExtension.Action
                        ) {
                            if (action.enabled == true) {
                                updateMenuAction(extension, action)
                            }
                        }

                        override fun onOpenPopup(
                            extension: WebExtension,
                            action: WebExtension.Action
                        ): GeckoResult<GeckoSession> {
                            val popupSession = GeckoSession()
                            configurePopupSession(popupSession, extension)
                            popupSession.open(GeckoRuntimeProvider.get(context))
                            popupState.value = ExtensionPopupState(
                                guid = extension.id,
                                title = action.title?.takeIf { it.isNotBlank() }
                                    ?: extension.metaData.name
                                    ?: extension.id,
                                session = popupSession
                            )
                            return GeckoResult.fromValue(popupSession)
                        }

                        override fun onTogglePopup(
                            extension: WebExtension,
                            action: WebExtension.Action
                        ): GeckoResult<GeckoSession> {
                            closePopup()
                            return onOpenPopup(extension, action)
                        }
                    }
                )
            }
            GeckoRuntimeProvider.get(context).webExtensionController.setTabActive(activeSession, true)
        }
    }

    suspend fun clickMenuAction(guid: String) {
        withContext(Dispatchers.Main.immediate) {
            val action = menuActions[guid] ?: error("Extension action is not ready.")
            if (action.enabled == false) error("Extension action is disabled on this page.")
            action.click()
        }
    }

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
        val extension = findRuntimeExtension(guid)
        if (extension != null) {
            invokeWebExtensionMethod(if (enabled) "enable" else "disable", extension, ENABLE_SOURCE_USER)
        } else if (enabled && installed.xpiPath != null) {
            installRaw(File(installed.xpiPath))
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
        val installError = runCatching {
            installRaw(file)
        }.exceptionOrNull()
        if (installError == null) return

        val installedRuntimeExtension = findRuntimeExtension(addon.guid)
        if (installedRuntimeExtension?.metaData?.version == addon.version) return

        runCatching { refreshInstalledFromRuntime() }
            .getOrDefault(installedState.value)
            .firstOrNull { it.guid == addon.guid && it.version == addon.version }
            ?.let { return }

        throw IllegalStateException(
            "GeckoView rejected ${addon.name}. It may be incompatible with this GeckoView build. ${installError.diagnosticMessage()}",
            installError
        )
    }

    private fun WebExtension.toInstalledState(
        existing: InstalledExtensionState?,
        enabledOverride: Boolean? = null
    ): InstalledExtensionState =
        InstalledExtensionState(
            guid = id,
            name = metaData.name?.takeIf { it.isNotBlank() } ?: existing?.name ?: id,
            version = metaData.version.takeIf { it.isNotBlank() } ?: existing?.version ?: "unknown",
            enabled = enabledOverride ?: metaData.enabled,
            source = existing?.source ?: "Gecko Runtime",
            permissionsSnapshot = existing?.permissionsSnapshot?.takeIf { it.isNotBlank() }
                ?: extensionPermissionsSnapshot(),
            xpiPath = existing?.xpiPath,
            installedAt = existing?.installedAt ?: System.currentTimeMillis()
        )

    private fun WebExtension.extensionPermissionsSnapshot(): String =
        (metaData.requiredPermissions.orEmpty().toList() + metaData.requiredOrigins.orEmpty().toList())
            .filter { it.isNotBlank() }
            .joinToString("\n")

    private fun Throwable.diagnosticMessage(): String =
        generateSequence(this) { it.cause }
            .mapNotNull { it.message?.takeIf { message -> message.isNotBlank() } }
            .firstOrNull()
            ?: this::class.java.simpleName

    private suspend fun installRaw(file: File) {
        withContext(Dispatchers.Main.immediate) {
            val uri = file.toURI().toString()
            val result = GeckoRuntimeProvider.get(context).webExtensionController.install(uri)
            withTimeout(30_000) {
                result.await()
            }
        }
    }

    private suspend fun findRuntimeExtension(guid: String): WebExtension? {
        return withContext(Dispatchers.Main.immediate) {
            val extensions = listRuntimeExtensions()
            extensions.firstOrNull { it.id == guid }
        }
    }

    private suspend fun listRuntimeExtensions(): List<WebExtension> {
        val controller = GeckoRuntimeProvider.get(context).webExtensionController
        val result = controller.list().await()
        return (result as? List<*>)
            ?.filterIsInstance<WebExtension>()
            ?: emptyList()
    }

    private suspend fun invokeWebExtensionMethod(
        methodName: String,
        extension: Any,
        enableSource: Int = ENABLE_SOURCE_APP
    ) {
        withContext(Dispatchers.Main.immediate) {
            val controller = GeckoRuntimeProvider.get(context).webExtensionController
            val method = controller.javaClass.methods.firstOrNull {
                it.name == methodName &&
                    it.parameterTypes.firstOrNull()?.isInstance(extension) == true &&
                    (it.parameterTypes.size == 1 || it.parameterTypes.size == 2 && it.parameterTypes[1] == Int::class.javaPrimitiveType)
            } ?: error("GeckoView WebExtensionController.$methodName is unavailable.")
            val args = if (method.parameterTypes.size == 2) {
                arrayOf(extension, enableSource)
            } else {
                arrayOf(extension)
            }
            val result = runCatching { method.invoke(controller, *args) }
                .getOrElse { error ->
                    throw (error.cause ?: error)
                }
            if (result is GeckoResult<*>) {
                result.await()
            }
        }
    }

    private fun updateMenuAction(extension: WebExtension, action: WebExtension.Action) {
        val title = action.title?.takeIf { it.isNotBlank() }
            ?: extension.metaData.name
            ?: extension.id
        menuActions[extension.id] = action
        val previous = menuActionState.value[extension.id]
        menuActionState.value = menuActionState.value + (
            extension.id to ExtensionMenuActionState(
                guid = extension.id,
                title = title,
                enabled = action.enabled != false,
                badgeText = action.badgeText,
                icon = previous?.icon
            )
        )
        loadMenuIcon(extension.id, action)
    }

    private fun loadMenuIcon(guid: String, action: WebExtension.Action) {
        val icon = action.icon ?: return
        icon.getBitmap(48).accept(
            { bitmap ->
                val current = menuActionState.value[guid] ?: return@accept
                menuActionState.value = menuActionState.value + (guid to current.copy(icon = bitmap))
            },
            { }
        )
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
        value.nonNullString("zh-CN")
            .ifBlank { value.nonNullString("en-US") }
            .ifBlank { value.nonNullString("_default") }
            .ifBlank { value.keys().asSequence().firstOrNull()?.let { key -> value.nonNullString(key) }.orEmpty() }

    private fun JSONObject.nonNullString(key: String): String =
        optString(key).takeUnless { it == "null" }.orEmpty()

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
        AtomicFileWriter.writeText(storeFile, array.toString())
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

    private companion object {
        const val INTERNAL_EXTENSION_ID = "hyper-browser-internal@dadigua.com"
        const val ENABLE_SOURCE_USER = 1
        const val ENABLE_SOURCE_APP = 2
    }
}

private suspend fun GeckoResult<*>.await(): Any? =
    suspendCancellableCoroutine { continuation ->
        accept(
            { value -> continuation.resume(value) },
            { error -> continuation.resumeWithException(error ?: IllegalStateException("GeckoView operation failed.")) }
        )
    }
