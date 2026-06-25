package com.dadigua.hyperbrowser.ui.browser

import android.graphics.Bitmap
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dadigua.hyperbrowser.HyperBrowserApp
import com.dadigua.hyperbrowser.extensions.ExtensionNewTabRequest
import com.dadigua.hyperbrowser.browser.BrowserSettings
import com.dadigua.hyperbrowser.browser.BrowserMediaNotificationController
import com.dadigua.hyperbrowser.browser.BrowserMediaOwnerInfo
import com.dadigua.hyperbrowser.browser.BrowserMediaOwnerKind
import com.dadigua.hyperbrowser.browser.closeBrowserMediaPlaybackOwner
import com.dadigua.hyperbrowser.gecko.GeckoAuthPromptRequest
import com.dadigua.hyperbrowser.gecko.GeckoCertificatePromptRequest
import com.dadigua.hyperbrowser.gecko.GeckoContextMenuTarget
import com.dadigua.hyperbrowser.gecko.GeckoDownloadRequest
import com.dadigua.hyperbrowser.gecko.GeckoFilePromptRequest
import com.dadigua.hyperbrowser.gecko.GeckoPageState
import com.dadigua.hyperbrowser.gecko.GeckoPromptRequest
import com.dadigua.hyperbrowser.gecko.GeckoSharePromptRequest
import com.dadigua.hyperbrowser.gecko.GeckoSessionCloseResult
import com.dadigua.hyperbrowser.gecko.GeckoSessionController
import com.dadigua.hyperbrowser.gecko.HyperRoute
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.json.JSONObject
import java.util.UUID

internal class BrowserTabRuntime private constructor(
    val id: String,
    val openerTabId: String? = null,
    private val app: HyperBrowserApp,
    private val controllerFactory: (
        initialUrl: String,
        loadInitialUrl: Boolean,
        restoredSessionState: GeckoSession.SessionState?
    ) -> GeckoSessionController,
    private val mediaOwnerInfo: () -> BrowserMediaOwnerInfo,
    initialController: GeckoSessionController?,
    input: String,
    restoredTitle: String?,
    pendingLoadUrl: String?,
    private var pendingRestoredSessionState: GeckoSession.SessionState?,
    private val temporaryWebsiteDisplayModeState: MutableState<String?>,
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
    var temporaryWebsiteDisplayMode: String?
        get() = temporaryWebsiteDisplayModeState.value
        private set(value) {
            temporaryWebsiteDisplayModeState.value = value
        }
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
            insecureHttp = url.startsWith("http://"),
            securityLevel = GeckoSessionController.securityLevelForUrl(url)
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

    fun currentWebsiteDisplayMode(defaultMode: String): String =
        BrowserSettings.normalizedWebsiteDisplayMode(temporaryWebsiteDisplayMode ?: defaultMode)

    fun updateTemporaryWebsiteDisplayMode(mode: String) {
        val normalized = BrowserSettings.normalizedWebsiteDisplayMode(mode)
        temporaryWebsiteDisplayMode = normalized.takeUnless {
            it == BrowserSettings.WEBSITE_DISPLAY_DEFAULT
        }
    }

    fun close(closeActivePlayback: Boolean = true) {
        val result = controller?.close(closeActivePlayback) ?: GeckoSessionCloseResult.Closed
        if (closeActivePlayback) {
            closeDetachedPlaybackForOwner()
        }
        if (result == GeckoSessionCloseResult.Closed) {
            controller = null
        }
    }

    fun hasActivePlayback(): Boolean =
        BrowserMediaNotificationController.get(app).ownsActivePlayback(mediaOwnerInfo())

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
            openerTabId: String? = null,
            initialInput: String = url,
            initialTitle: String? = null,
            initialIconPath: String? = null,
            loadImmediately: Boolean = true,
            restoredSessionState: GeckoSession.SessionState? = null,
            onHyperRoute: (HyperRoute) -> Unit = {},
            onHyperBridgeMessage: (JSONObject) -> GeckoResult<Any> = { GeckoResult.fromValue(JSONObject().put("ok", false).toString()) },
            onPageContextMenu: (GeckoContextMenuTarget) -> Unit = {},
            onAuthPrompt: (GeckoAuthPromptRequest) -> Unit = { it.dismiss() },
            onPrompt: (GeckoPromptRequest) -> Unit = { it.dismiss() },
            onFilePrompt: (GeckoFilePromptRequest) -> Unit = { it.dismiss() },
            onCertificatePrompt: (GeckoCertificatePromptRequest) -> Unit = { it.dismiss() },
            onSharePrompt: (GeckoSharePromptRequest) -> Unit = {
                it.confirm(GeckoSession.PromptDelegate.SharePrompt.Result.FAILURE)
            },
            onDownload: (GeckoDownloadRequest) -> Unit = {},
            openNewTabsInCurrentTab: () -> Boolean = { false },
            onNewSession: (String) -> GeckoSession? = { null },
            onCloseRequest: () -> Unit = {},
            onFocusRequest: () -> Unit = {},
            onEngineSessionStateChange: (String?) -> Unit = {},
            defaultWebsiteDisplayMode: () -> String = { BrowserSettings.WEBSITE_DISPLAY_DEFAULT },
            onPageStop: (Boolean) -> Unit = {}
        ): BrowserTabRuntime {
            val engineStateAvailable = mutableStateOf(restoredSessionState != null)
            val temporaryWebsiteDisplayModeState = mutableStateOf<String?>(null)
            val controllerFactory = { initialUrl: String,
                shouldLoadInitialUrl: Boolean,
                state: GeckoSession.SessionState? ->
                val mediaLaunchIntent = BrowserActivity.selectTabIntent(app, id)
                GeckoSessionController(
                    context = app,
                    initialUrl = initialUrl,
                    loadInitialUrl = shouldLoadInitialUrl,
                    restoredSessionState = state,
                    onHyperRoute = onHyperRoute,
                    onHyperBridgeMessage = onHyperBridgeMessage,
                    onPageContextMenu = onPageContextMenu,
                    onAuthPrompt = onAuthPrompt,
                    onPrompt = onPrompt,
                    onFilePrompt = onFilePrompt,
                    onCertificatePrompt = onCertificatePrompt,
                    onSharePrompt = onSharePrompt,
                    onDownload = onDownload,
                    openNewTabsInCurrentTab = openNewTabsInCurrentTab,
                    onNewSession = onNewSession,
                    onCloseRequest = onCloseRequest,
                    onFocusRequest = onFocusRequest,
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
                    websiteDisplayModeForSession = {
                        BrowserSettings.normalizedWebsiteDisplayMode(
                            temporaryWebsiteDisplayModeState.value ?: defaultWebsiteDisplayMode()
                        )
                    },
                    mediaNotificationIntent = mediaLaunchIntent,
                    mediaOwnerInfo = {
                        BrowserMediaOwnerInfo(
                            id = id,
                            kind = BrowserMediaOwnerKind.BrowserTab,
                            launchIntent = BrowserActivity.selectTabIntent(app, id)
                        )
                    }
                )
            }
            return BrowserTabRuntime(
                id = id,
                openerTabId = openerTabId,
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
                temporaryWebsiteDisplayModeState = temporaryWebsiteDisplayModeState,
                app = app,
                mediaOwnerInfo = {
                    BrowserMediaOwnerInfo(
                        id = id,
                        kind = BrowserMediaOwnerKind.BrowserTab,
                        launchIntent = BrowserActivity.selectTabIntent(app, id)
                    )
                },
                engineStateAvailable = engineStateAvailable
            ).also { tab ->
                tab.iconPath = initialIconPath
            }
        }

        fun fromExtensionRequest(
            app: HyperBrowserApp,
            request: ExtensionNewTabRequest,
            onHyperRoute: (HyperRoute) -> Unit = {},
            onHyperBridgeMessage: (JSONObject) -> GeckoResult<Any> = { GeckoResult.fromValue(JSONObject().put("ok", false).toString()) },
            onPageContextMenu: (GeckoContextMenuTarget) -> Unit = {},
            onAuthPrompt: (GeckoAuthPromptRequest) -> Unit = { it.dismiss() },
            onPrompt: (GeckoPromptRequest) -> Unit = { it.dismiss() },
            onFilePrompt: (GeckoFilePromptRequest) -> Unit = { it.dismiss() },
            onCertificatePrompt: (GeckoCertificatePromptRequest) -> Unit = { it.dismiss() },
            onSharePrompt: (GeckoSharePromptRequest) -> Unit = {
                it.confirm(GeckoSession.PromptDelegate.SharePrompt.Result.FAILURE)
            },
            onDownload: (GeckoDownloadRequest) -> Unit = {}
        ): BrowserTabRuntime {
            val id = UUID.randomUUID().toString()
            return BrowserTabRuntime(
                id = id,
                openerTabId = null,
                app = app,
                controllerFactory = { _, _, _ ->
                    val mediaLaunchIntent = BrowserActivity.selectTabIntent(app, id)
                    GeckoSessionController(
                        context = app,
                        initialUrl = request.url,
                        existingSession = request.session,
                        onHyperRoute = onHyperRoute,
                        onHyperBridgeMessage = onHyperBridgeMessage,
                        onPageContextMenu = onPageContextMenu,
                        onAuthPrompt = onAuthPrompt,
                        onPrompt = onPrompt,
                        onFilePrompt = onFilePrompt,
                        onCertificatePrompt = onCertificatePrompt,
                        onSharePrompt = onSharePrompt,
                        onDownload = onDownload,
                        mediaNotificationIntent = mediaLaunchIntent,
                        mediaOwnerInfo = {
                            BrowserMediaOwnerInfo(
                                id = request.url,
                                kind = BrowserMediaOwnerKind.ExtensionTab,
                                displayName = request.title,
                                url = request.url,
                                launchIntent = BrowserActivity.selectTabIntent(app, id)
                            )
                        }
                    )
                },
                mediaOwnerInfo = {
                    BrowserMediaOwnerInfo(
                        id = request.url,
                        kind = BrowserMediaOwnerKind.ExtensionTab,
                        displayName = request.title,
                        url = request.url,
                        launchIntent = BrowserActivity.selectTabIntent(app, id)
                    )
                },
                initialController = GeckoSessionController(
                    context = app,
                    initialUrl = request.url,
                    existingSession = request.session,
                    onHyperRoute = onHyperRoute,
                    onHyperBridgeMessage = onHyperBridgeMessage,
                    onPageContextMenu = onPageContextMenu,
                    onAuthPrompt = onAuthPrompt,
                    onPrompt = onPrompt,
                    onFilePrompt = onFilePrompt,
                    onCertificatePrompt = onCertificatePrompt,
                    onSharePrompt = onSharePrompt,
                    onDownload = onDownload,
                    mediaNotificationIntent = BrowserActivity.selectTabIntent(app, id),
                    mediaOwnerInfo = {
                        BrowserMediaOwnerInfo(
                            id = request.url,
                            kind = BrowserMediaOwnerKind.ExtensionTab,
                            displayName = request.title,
                            url = request.url,
                            launchIntent = BrowserActivity.selectTabIntent(app, id)
                        )
                    }
                ),
                input = request.url,
                restoredTitle = request.title,
                pendingLoadUrl = null,
                pendingRestoredSessionState = null,
                temporaryWebsiteDisplayModeState = mutableStateOf<String?>(null),
                engineStateAvailable = mutableStateOf(false)
            )
        }

        fun fromExistingSession(
            app: HyperBrowserApp,
            url: String,
            session: GeckoSession,
            openerTabId: String? = null,
            onHyperRoute: (HyperRoute) -> Unit = {},
            onHyperBridgeMessage: (JSONObject) -> GeckoResult<Any> = { GeckoResult.fromValue(JSONObject().put("ok", false).toString()) },
            onPageContextMenu: (GeckoContextMenuTarget) -> Unit = {},
            onAuthPrompt: (GeckoAuthPromptRequest) -> Unit = { it.dismiss() },
            onPrompt: (GeckoPromptRequest) -> Unit = { it.dismiss() },
            onFilePrompt: (GeckoFilePromptRequest) -> Unit = { it.dismiss() },
            onCertificatePrompt: (GeckoCertificatePromptRequest) -> Unit = { it.dismiss() },
            onSharePrompt: (GeckoSharePromptRequest) -> Unit = {
                it.confirm(GeckoSession.PromptDelegate.SharePrompt.Result.FAILURE)
            },
            onDownload: (GeckoDownloadRequest) -> Unit = {},
            openNewTabsInCurrentTab: () -> Boolean = { false },
            onNewSession: (String) -> GeckoSession? = { null },
            onCloseRequest: () -> Unit = {},
            onFocusRequest: () -> Unit = {},
            onEngineSessionStateChange: (String?) -> Unit = {},
            defaultWebsiteDisplayMode: () -> String = { BrowserSettings.WEBSITE_DISPLAY_DEFAULT },
            onPageStop: (Boolean) -> Unit = {}
        ): BrowserTabRuntime {
            val id = UUID.randomUUID().toString()
            val initialUrl = url.ifBlank { GeckoSessionController.ABOUT_BLANK_URL }
            val engineStateAvailable = mutableStateOf(false)
            val temporaryWebsiteDisplayModeState = mutableStateOf<String?>(null)
            val mediaOwnerInfo = {
                BrowserMediaOwnerInfo(
                    id = id,
                    kind = BrowserMediaOwnerKind.BrowserTab,
                    launchIntent = BrowserActivity.selectTabIntent(app, id)
                )
            }
            val controllerFactory = { _: String, _: Boolean, _: GeckoSession.SessionState? ->
                val mediaLaunchIntent = BrowserActivity.selectTabIntent(app, id)
                GeckoSessionController(
                    context = app,
                    initialUrl = initialUrl,
                    existingSession = session,
                    onHyperRoute = onHyperRoute,
                    onHyperBridgeMessage = onHyperBridgeMessage,
                    onPageContextMenu = onPageContextMenu,
                    onAuthPrompt = onAuthPrompt,
                    onPrompt = onPrompt,
                    onFilePrompt = onFilePrompt,
                    onCertificatePrompt = onCertificatePrompt,
                    onSharePrompt = onSharePrompt,
                    onDownload = onDownload,
                    openNewTabsInCurrentTab = openNewTabsInCurrentTab,
                    onNewSession = onNewSession,
                    onCloseRequest = onCloseRequest,
                    onFocusRequest = onFocusRequest,
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
                    websiteDisplayModeForSession = {
                        BrowserSettings.normalizedWebsiteDisplayMode(
                            temporaryWebsiteDisplayModeState.value ?: defaultWebsiteDisplayMode()
                        )
                    },
                    mediaNotificationIntent = mediaLaunchIntent,
                    mediaOwnerInfo = mediaOwnerInfo
                )
            }
            return BrowserTabRuntime(
                id = id,
                openerTabId = openerTabId,
                app = app,
                controllerFactory = controllerFactory,
                mediaOwnerInfo = mediaOwnerInfo,
                initialController = controllerFactory(initialUrl, false, null),
                input = initialUrl,
                restoredTitle = null,
                pendingLoadUrl = null,
                pendingRestoredSessionState = null,
                temporaryWebsiteDisplayModeState = temporaryWebsiteDisplayModeState,
                engineStateAvailable = engineStateAvailable
            )
        }
    }

    private fun closeDetachedPlaybackForOwner() {
        closeBrowserMediaPlaybackOwner(app, mediaOwnerInfo())
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
