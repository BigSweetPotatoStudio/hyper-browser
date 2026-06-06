package com.dadigua.hyperbrowser.ui.browser

import android.graphics.Bitmap
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dadigua.hyperbrowser.HyperBrowserApp
import com.dadigua.hyperbrowser.extensions.ExtensionNewTabRequest
import com.dadigua.hyperbrowser.gecko.GeckoDownloadRequest
import com.dadigua.hyperbrowser.gecko.GeckoPageState
import com.dadigua.hyperbrowser.gecko.GeckoSessionController
import com.dadigua.hyperbrowser.gecko.HyperRoute
import org.mozilla.geckoview.GeckoSession
import org.json.JSONObject
import java.util.UUID

internal class BrowserTabRuntime private constructor(
    val id: String,
    private val controllerFactory: (
        initialUrl: String,
        loadInitialUrl: Boolean,
        restoredSessionState: GeckoSession.SessionState?
    ) -> GeckoSessionController,
    initialController: GeckoSessionController?,
    input: String,
    restoredTitle: String?,
    pendingLoadUrl: String?,
    private var pendingRestoredSessionState: GeckoSession.SessionState?,
    private val engineStateAvailable: MutableState<Boolean>
) {
    var controller by mutableStateOf(initialController)
        private set
    var input by mutableStateOf(input)
    var thumbnail by mutableStateOf<Bitmap?>(null)
    var iconPath by mutableStateOf<String?>(null)
    var restoredTitle by mutableStateOf(restoredTitle)
    var loaded by mutableStateOf(pendingLoadUrl == null)
        private set
    var restoreUrl by mutableStateOf(pendingLoadUrl ?: input)
        private set
    val hasController: Boolean
        get() = controller != null
    val hasEngineState: Boolean
        get() = engineStateAvailable.value
    private var pendingLoadUrl by mutableStateOf(pendingLoadUrl)

    fun ensureController(): GeckoSessionController {
        controller?.let { return it }
        val url = pendingLoadUrl ?: restoreUrl.ifBlank { input }
        val state = pendingRestoredSessionState
        pendingLoadUrl = null
        pendingRestoredSessionState = null
        loaded = true
        restoreUrl = url
        return controllerFactory(url, true, state).also { controller = it }
    }

    fun loadIfNeeded(searchUrlTemplate: String? = null) {
        val activeController = controller ?: run {
            ensureController()
            return
        }
        val url = pendingLoadUrl ?: return
        pendingLoadUrl = null
        loaded = true
        restoreUrl = url
        activeController.load(url, searchUrlTemplate)
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

    fun snapshotPageState(): GeckoPageState {
        val url = restoreUrl.ifBlank { input }
        return GeckoPageState(
            title = restoredTitle.orEmpty(),
            url = url,
            insecureHttp = url.startsWith("http://")
        )
    }

    fun capturePixels(onCaptured: (Bitmap?) -> Unit) {
        val activeController = controller
        if (activeController == null) {
            onCaptured(null)
            return
        }
        activeController.capturePixels(onCaptured)
    }

    fun flushSessionState() {
        controller?.flushSessionState()
    }

    fun close(closeActivePlayback: Boolean = true) {
        controller?.close(closeActivePlayback)
        controller = null
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
            restoredSessionState: GeckoSession.SessionState? = null,
            onHyperRoute: (HyperRoute) -> Unit = {},
            onHyperBridgeMessage: (JSONObject) -> JSONObject = { JSONObject().put("ok", false) },
            onLinkContextMenu: (String, String?) -> Unit = { _, _ -> },
            onDownload: (GeckoDownloadRequest) -> Unit = {},
            onEngineSessionStateChange: (String?) -> Unit = {},
            onPageStop: (Boolean) -> Unit = {}
        ): BrowserTabRuntime {
            val engineStateAvailable = mutableStateOf(restoredSessionState != null)
            val controllerFactory = { initialUrl: String,
                shouldLoadInitialUrl: Boolean,
                state: GeckoSession.SessionState? ->
                GeckoSessionController(
                    context = app,
                    initialUrl = initialUrl,
                    loadInitialUrl = shouldLoadInitialUrl,
                    restoredSessionState = state,
                    onHyperRoute = onHyperRoute,
                    onHyperBridgeMessage = onHyperBridgeMessage,
                    onLinkContextMenu = onLinkContextMenu,
                    onDownload = onDownload,
                    onSessionStateChange = { sessionState ->
                        val currentUri = currentUriForEngineSessionState(sessionState)
                        if (shouldPersistEngineSessionStateUri(currentUri)) {
                            engineStateAvailable.value = true
                            onEngineSessionStateChange(sessionState.toString())
                        } else {
                            onEngineSessionStateChange(null)
                        }
                    },
                    onPageStop = onPageStop,
                    mediaNotificationIntent = app.packageManager.getLaunchIntentForPackage(app.packageName)
                )
            }
            return BrowserTabRuntime(
                id = id,
                controllerFactory = controllerFactory,
                initialController = if (loadImmediately) {
                    controllerFactory(url, true, restoredSessionState)
                } else {
                    null
                },
                input = initialInput,
                restoredTitle = initialTitle,
                pendingLoadUrl = url.takeUnless { loadImmediately },
                pendingRestoredSessionState = restoredSessionState.takeUnless { loadImmediately },
                engineStateAvailable = engineStateAvailable
            ).also { tab ->
                tab.iconPath = initialIconPath
            }
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
                controllerFactory = { _, _, _ ->
                    GeckoSessionController(
                        context = app,
                        initialUrl = request.url,
                        existingSession = request.session,
                        onHyperRoute = onHyperRoute,
                        onHyperBridgeMessage = onHyperBridgeMessage,
                        onLinkContextMenu = onLinkContextMenu,
                        onDownload = onDownload,
                        mediaNotificationIntent = app.packageManager.getLaunchIntentForPackage(app.packageName)
                    )
                },
                initialController = GeckoSessionController(
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
                pendingLoadUrl = null,
                pendingRestoredSessionState = null,
                engineStateAvailable = mutableStateOf(false)
            )
    }
}

internal fun shouldPersistEngineSessionStateUri(uri: String?): Boolean =
    uri?.startsWith("http://") == true || uri?.startsWith("https://") == true

internal fun shouldShowRestorableLabel(hasController: Boolean, hasEngineState: Boolean): Boolean =
    !hasController && hasEngineState

internal fun currentUriForEngineSessionState(state: GeckoSession.SessionState): String? {
    return state.currentUri()
}

private fun GeckoSession.SessionState.currentUri(): String? {
    val index = currentIndex
    if (index < 0 || index >= size) return null
    return get(index).uri
}
