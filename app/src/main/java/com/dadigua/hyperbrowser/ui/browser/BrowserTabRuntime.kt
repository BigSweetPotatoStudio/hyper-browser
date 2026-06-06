package com.dadigua.hyperbrowser.ui.browser

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dadigua.hyperbrowser.HyperBrowserApp
import com.dadigua.hyperbrowser.extensions.ExtensionNewTabRequest
import com.dadigua.hyperbrowser.gecko.GeckoDownloadRequest
import com.dadigua.hyperbrowser.gecko.GeckoSessionController
import com.dadigua.hyperbrowser.gecko.HyperRoute
import org.json.JSONObject
import java.util.UUID

internal class BrowserTabRuntime private constructor(
    val id: String,
    val controller: GeckoSessionController,
    input: String,
    restoredTitle: String?,
    pendingLoadUrl: String?
) {
    var input by mutableStateOf(input)
    var thumbnail by mutableStateOf<Bitmap?>(null)
    var iconPath by mutableStateOf<String?>(null)
    var restoredTitle by mutableStateOf(restoredTitle)
    var loaded by mutableStateOf(pendingLoadUrl == null)
        private set
    var restoreUrl by mutableStateOf(pendingLoadUrl ?: input)
        private set
    private var pendingLoadUrl by mutableStateOf(pendingLoadUrl)

    fun loadIfNeeded(searchUrlTemplate: String? = null) {
        val url = pendingLoadUrl ?: return
        pendingLoadUrl = null
        loaded = true
        restoreUrl = url
        controller.load(url, searchUrlTemplate)
    }

    fun rememberCommittedLocation(url: String, title: String): Boolean {
        if (pendingLoadUrl != null) return false
        if (!isRecoverableBrowserTabUrl(url)) return false
        val nextTitle = title.takeIf { it.isNotBlank() }
        val changed = !loaded ||
            restoreUrl != url ||
            (nextTitle != null && restoredTitle != nextTitle)
        if (!changed) return false
        loaded = true
        restoreUrl = url
        if (nextTitle != null) {
            restoredTitle = nextTitle
        }
        return true
    }

    fun toSavedTab() =
        savedBrowserTabFromSnapshot(
            id = id,
            input = input,
            iconPath = iconPath,
            loaded = loaded,
            restoreUrl = restoreUrl,
            title = restoredTitle
        )

    fun clearIcon() {
        iconPath = null
    }

    companion object {
        fun create(
            app: HyperBrowserApp,
            url: String,
            id: String = UUID.randomUUID().toString(),
            initialInput: String = url,
            initialTitle: String? = null,
            initialIconPath: String? = null,
            loadImmediately: Boolean = true,
            onHyperRoute: (HyperRoute) -> Unit = {},
            onHyperBridgeMessage: (JSONObject) -> JSONObject = { JSONObject().put("ok", false) },
            onLinkContextMenu: (String, String?) -> Unit = { _, _ -> },
            onDownload: (GeckoDownloadRequest) -> Unit = {}
        ): BrowserTabRuntime =
            BrowserTabRuntime(
                id = id,
                controller = GeckoSessionController(
                    context = app,
                    initialUrl = url,
                    loadInitialUrl = loadImmediately,
                    onHyperRoute = onHyperRoute,
                    onHyperBridgeMessage = onHyperBridgeMessage,
                    onLinkContextMenu = onLinkContextMenu,
                    onDownload = onDownload,
                    mediaNotificationIntent = app.packageManager.getLaunchIntentForPackage(app.packageName)
                ),
                input = initialInput,
                restoredTitle = initialTitle,
                pendingLoadUrl = url.takeUnless { loadImmediately }
            ).also { tab ->
                tab.iconPath = initialIconPath
            }

        fun fromExtensionRequest(
            app: HyperBrowserApp,
            request: ExtensionNewTabRequest,
            onHyperRoute: (HyperRoute) -> Unit = {},
            onHyperBridgeMessage: (JSONObject) -> JSONObject = { JSONObject().put("ok", false) },
            onLinkContextMenu: (String, String?) -> Unit = { _, _ -> },
            onDownload: (GeckoDownloadRequest) -> Unit = {}
        ): BrowserTabRuntime =
            BrowserTabRuntime(
                id = UUID.randomUUID().toString(),
                controller = GeckoSessionController(
                    context = app,
                    initialUrl = request.url,
                    existingSession = request.session,
                    onHyperRoute = onHyperRoute,
                    onHyperBridgeMessage = onHyperBridgeMessage,
                    onLinkContextMenu = onLinkContextMenu,
                    onDownload = onDownload,
                    mediaNotificationIntent = app.packageManager.getLaunchIntentForPackage(app.packageName)
                ),
                input = request.url,
                restoredTitle = request.title,
                pendingLoadUrl = null
            )
    }
}
