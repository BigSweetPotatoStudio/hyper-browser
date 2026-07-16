package com.dadigua.hyperbrowser.gecko

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.MediaSession
import org.mozilla.geckoview.WebResponse
import java.security.Principal
import java.net.URLEncoder
import java.io.InputStream
import com.dadigua.hyperbrowser.R
import com.dadigua.hyperbrowser.browser.BrowserSettings
import com.dadigua.hyperbrowser.browser.DownloadHandler
import com.dadigua.hyperbrowser.browser.BrowserMediaNotificationController
import com.dadigua.hyperbrowser.browser.BrowserMediaOwnerInfo
import com.dadigua.hyperbrowser.browser.BrowserMediaOwnerKind
import com.dadigua.hyperbrowser.browser.BrowserProfileStore
import com.dadigua.hyperbrowser.ui.localeTagForPreference

data class GeckoPageState(
    val title: String = "",
    val url: String = "",
    val insecureHttp: Boolean = false,
    val securityLevel: GeckoPageSecurity = GeckoPageSecurity.Neutral,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isLoading: Boolean = false,
    val loadProgress: Int = 0
)

enum class GeckoPageSecurity {
    Neutral,
    Insecure,
    Secure,
    Verified
}

data class GeckoDownloadRequest(
    val url: String,
    val fileName: String,
    val contentType: String?,
    val contentLength: Long,
    val body: InputStream
)

data class GeckoContextMenuTarget(
    val linkUrl: String?,
    val imageUrl: String?,
    val label: String?
)

class GeckoAuthPromptRequest(
    val title: String,
    val message: String,
    val uri: String,
    val username: String,
    val password: String,
    private val onConfirmResponse: (String, String) -> Unit,
    private val onDismissResponse: () -> Unit
) {
    private var completed = false

    fun confirm(username: String, password: String) {
        if (completed) return
        completed = true
        onConfirmResponse(username, password)
    }

    fun dismiss() {
        if (completed) return
        completed = true
        onDismissResponse()
    }
}

class GeckoAlertPromptRequest(
    val title: String,
    val message: String,
    private val onDismissResponse: () -> Unit
) {
    private var completed = false

    fun dismiss() {
        if (completed) return
        completed = true
        onDismissResponse()
    }
}

class GeckoButtonPromptRequest(
    val title: String,
    val message: String,
    private val onConfirmResponse: (Int) -> Unit,
    private val onDismissResponse: () -> Unit
) {
    private var completed = false

    fun confirm(button: Int) {
        if (completed) return
        completed = true
        onConfirmResponse(button)
    }

    fun dismiss() {
        if (completed) return
        completed = true
        onDismissResponse()
    }
}

class GeckoTextPromptRequest(
    val title: String,
    val message: String,
    val defaultValue: String,
    private val onConfirmResponse: (String) -> Unit,
    private val onDismissResponse: () -> Unit
) {
    private var completed = false

    fun confirm(value: String) {
        if (completed) return
        completed = true
        onConfirmResponse(value)
    }

    fun dismiss() {
        if (completed) return
        completed = true
        onDismissResponse()
    }
}

data class GeckoChoicePromptItem(
    val id: String,
    val label: String,
    val disabled: Boolean,
    val selected: Boolean,
    val level: Int
)

class GeckoChoicePromptRequest(
    val title: String,
    val message: String,
    val choices: List<GeckoChoicePromptItem>,
    val multiple: Boolean,
    private val onConfirmResponse: (List<String>) -> Unit,
    private val onDismissResponse: () -> Unit
) {
    private var completed = false

    fun confirm(ids: List<String>) {
        if (completed) return
        completed = true
        onConfirmResponse(ids)
    }

    fun dismiss() {
        if (completed) return
        completed = true
        onDismissResponse()
    }
}

class GeckoConfirmPromptRequest(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val dismissLabel: String,
    private val onConfirmResponse: () -> Unit,
    private val onDismissResponse: () -> Unit
) {
    private var completed = false

    fun confirm() {
        if (completed) return
        completed = true
        onConfirmResponse()
    }

    fun dismiss() {
        if (completed) return
        completed = true
        onDismissResponse()
    }
}

class GeckoFilePromptRequest(
    val title: String,
    val mimeTypes: Array<String>,
    val multiple: Boolean,
    private val onConfirmResponse: (Array<Uri>) -> Unit,
    private val onDismissResponse: () -> Unit
) {
    private var completed = false

    fun confirm(uris: List<Uri>) {
        if (completed) return
        completed = true
        onConfirmResponse(uris.toTypedArray())
    }

    fun dismiss() {
        if (completed) return
        completed = true
        onDismissResponse()
    }

    fun pickerMimeTypes(): Array<String> =
        mimeTypes.takeIf { it.isNotEmpty() } ?: arrayOf("*/*")
}

class GeckoCertificatePromptRequest(
    val title: String,
    val host: String,
    val issuers: Array<Principal>,
    private val onConfirmResponse: (String) -> Unit,
    private val onDismissResponse: () -> Unit
) {
    private var completed = false

    fun confirm(alias: String) {
        if (completed) return
        completed = true
        onConfirmResponse(alias)
    }

    fun dismiss() {
        if (completed) return
        completed = true
        onDismissResponse()
    }
}

class GeckoColorPromptRequest(
    val title: String,
    val defaultValue: String,
    val predefinedValues: Array<String>,
    private val onConfirmResponse: (String) -> Unit,
    private val onDismissResponse: () -> Unit
) {
    private var completed = false

    fun confirm(value: String) {
        if (completed) return
        completed = true
        onConfirmResponse(value)
    }

    fun dismiss() {
        if (completed) return
        completed = true
        onDismissResponse()
    }
}

class GeckoDateTimePromptRequest(
    val title: String,
    val defaultValue: String,
    val minValue: String,
    val maxValue: String,
    val stepValue: String,
    private val onConfirmResponse: (String) -> Unit,
    private val onDismissResponse: () -> Unit
) {
    private var completed = false

    fun confirm(value: String) {
        if (completed) return
        completed = true
        onConfirmResponse(value)
    }

    fun dismiss() {
        if (completed) return
        completed = true
        onDismissResponse()
    }
}

class GeckoSharePromptRequest(
    val title: String,
    val text: String,
    val uri: String,
    private val onConfirmResponse: (Int) -> Unit,
    private val onDismissResponse: () -> Unit
) {
    private var completed = false

    fun confirm(result: Int) {
        if (completed) return
        completed = true
        onConfirmResponse(result)
    }

    fun dismiss() {
        if (completed) return
        completed = true
        onDismissResponse()
    }
}

sealed class GeckoPromptRequest {
    data class Alert(val request: GeckoAlertPromptRequest) : GeckoPromptRequest()
    data class Button(val request: GeckoButtonPromptRequest) : GeckoPromptRequest()
    data class Text(val request: GeckoTextPromptRequest) : GeckoPromptRequest()
    data class Choice(val request: GeckoChoicePromptRequest) : GeckoPromptRequest()
    data class Confirm(val request: GeckoConfirmPromptRequest) : GeckoPromptRequest()
    data class Color(val request: GeckoColorPromptRequest) : GeckoPromptRequest()
    data class DateTime(val request: GeckoDateTimePromptRequest) : GeckoPromptRequest()

    fun dismiss() {
        when (this) {
            is Alert -> request.dismiss()
            is Button -> request.dismiss()
            is Text -> request.dismiss()
            is Choice -> request.dismiss()
            is Confirm -> request.dismiss()
            is Color -> request.dismiss()
            is DateTime -> request.dismiss()
        }
    }
}

enum class GeckoSessionCloseResult {
    Closed,
    DetachedForPlayback
}

data class GeckoFindInPageResult(
    val found: Boolean,
    val current: Int,
    val total: Int
)

class GeckoSessionController(
    context: Context,
    initialUrl: String,
    existingSession: GeckoSession? = null,
    loadInitialUrl: Boolean = true,
    restoredSessionState: GeckoSession.SessionState? = null,
    private val onHyperRoute: (HyperRoute) -> Unit = {},
    private val onHyperBridgeMessage: ((org.json.JSONObject) -> GeckoResult<Any>)? = null,
    private val onPageContextMenu: (GeckoContextMenuTarget) -> Unit = {},
    private val onAuthPrompt: (GeckoAuthPromptRequest) -> Unit = { it.dismiss() },
    private val onPrompt: (GeckoPromptRequest) -> Unit = { it.dismiss() },
    private val onFilePrompt: (GeckoFilePromptRequest) -> Unit = { it.dismiss() },
    private val onCertificatePrompt: (GeckoCertificatePromptRequest) -> Unit = { it.dismiss() },
    private val onSharePrompt: (GeckoSharePromptRequest) -> Unit = {
        it.confirm(GeckoSession.PromptDelegate.SharePrompt.Result.FAILURE)
    },
    private val onDownload: (GeckoDownloadRequest) -> Unit = {},
    private val openNewTabsInCurrentTab: () -> Boolean = { false },
    private val onNewSession: (String) -> GeckoSession? = { null },
    private val onCloseRequest: () -> Unit = {},
    private val onFocusRequest: () -> Unit = {},
    private val onSessionStateChange: (GeckoSession.SessionState) -> Unit = {},
    private val onPageStop: (Boolean) -> Unit = {},
    private val websiteDisplayModeForSession: () -> String = { BrowserSettings.WEBSITE_DISPLAY_MOBILE },
    private val mediaNotificationIntent: Intent? = null,
    private val mediaOwnerInfo: () -> BrowserMediaOwnerInfo? = { null }
) {
    var session: GeckoSession = existingSession ?: createBrowserSession()
        private set

    private val appContext = context.applicationContext
    private val runtime = GeckoRuntimeProvider.get(context)
    private val _state = MutableStateFlow(pageStateForUrl(initialUrl))
    private val _fullScreen = MutableStateFlow(false)
    private val _sessionChangeVersion = MutableStateFlow(0)
    private var currentRawUrl: String = initialUrl
    private var lastLoadTarget: String = initialUrl
    private var waitingForInitialLocation = existingSession == null && initialUrl != ABOUT_BLANK_URL
    private var view: GeckoView? = null
    private var sessionCrashed = false
    private var automaticRecoveryTarget: String? = null
    private var closeRequestScheduled = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var contentTouchCanScrollUp = false
    private var lastContentTouchStateAt = 0L
    private var pullRefreshGestureStartedAt = 0L
    val state: StateFlow<GeckoPageState> = _state
    val fullScreen: StateFlow<Boolean> = _fullScreen
    val sessionChangeVersion: StateFlow<Int> = _sessionChangeVersion

    init {
        configureSession(session)
        registerBridgeHandler(session)
        applyWebsiteDisplayModeForUrl(initialUrl)
        if (existingSession == null) {
            session.open(runtime)
            HyperBridge.ensureInstalled(appContext) {
                val restored = restoredSessionState?.takeIf { loadInitialUrl }?.let { state ->
                    runCatching {
                        session.restoreState(GeckoSession.SessionState(state))
                        true
                    }.getOrDefault(false)
                } ?: false
                if (!restored && loadInitialUrl) {
                    load(initialUrl)
                }
            }
        }
    }

    private fun configureSession(targetSession: GeckoSession) {
        targetSession.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                _state.value = _state.value.copy(title = title.orEmpty())
            }

            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                _fullScreen.value = fullScreen
            }

            override fun onFocusRequest(session: GeckoSession) {
                onFocusRequest()
            }

            override fun onCloseRequest(session: GeckoSession) {
                if (closeRequestScheduled) return
                closeRequestScheduled = true
                mainHandler.postDelayed({
                    closeRequestScheduled = false
                    onCloseRequest()
                }, POPUP_CLOSE_DELAY_MS)
            }

            override fun onContextMenu(
                session: GeckoSession,
                screenX: Int,
                screenY: Int,
                element: GeckoSession.ContentDelegate.ContextElement
            ) {
                val linkUrl = element.linkUri.takeIfNotBlank()
                val imageUrl = element.srcUri
                    .takeIfNotBlank()
                    ?.takeIf { element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE }
                if (linkUrl == null && imageUrl == null) return
                onPageContextMenu(
                    GeckoContextMenuTarget(
                        linkUrl = linkUrl,
                        imageUrl = imageUrl,
                        label = element.altText.takeIfNotBlank()
                            ?: element.linkText.takeIfNotBlank()
                            ?: element.title.takeIfNotBlank()
                    )
                )
            }

            override fun onCrash(session: GeckoSession) {
                recoverAfterContentLoss()
            }

            override fun onKill(session: GeckoSession) {
                recoverAfterContentLoss()
            }

            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                val body = response.body ?: return
                val contentDisposition = response.headerValue("Content-Disposition")
                val contentType = response.headerValue("Content-Type")
                val contentLength = response.headerValue("Content-Length")?.toLongOrNull() ?: -1L
                onDownload(
                    GeckoDownloadRequest(
                        url = response.uri,
                        fileName = DownloadHandler.fileNameFor(response.uri, contentDisposition, contentType),
                        contentType = contentType,
                        contentLength = contentLength,
                        body = body
                    )
                )
            }
        }
        targetSession.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(
                session: GeckoSession,
                perm: GeckoSession.PermissionDelegate.ContentPermission
            ): GeckoResult<Int> {
                val value = when (perm.permission) {
                    GeckoSession.PermissionDelegate.PERMISSION_STORAGE_ACCESS,
                    GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE,
                    GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE,
                    GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE,
                    GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> {
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                    }
                    else -> GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                }
                return GeckoResult.fromValue(value)
            }
        }
        targetSession.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onAlertPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AlertPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                onPrompt(
                    GeckoPromptRequest.Alert(
                        GeckoAlertPromptRequest(
                            title = prompt.title.orEmpty(),
                            message = prompt.message.orEmpty(),
                            onDismissResponse = { result.complete(prompt.dismiss()) }
                        )
                    )
                )
                return result
            }

            override fun onButtonPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.ButtonPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                onPrompt(
                    GeckoPromptRequest.Button(
                        GeckoButtonPromptRequest(
                            title = prompt.title.orEmpty(),
                            message = prompt.message.orEmpty(),
                            onConfirmResponse = { button -> result.complete(prompt.confirm(button)) },
                            onDismissResponse = { result.complete(prompt.dismiss()) }
                        )
                    )
                )
                return result
            }

            override fun onTextPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.TextPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                onPrompt(
                    GeckoPromptRequest.Text(
                        GeckoTextPromptRequest(
                            title = prompt.title.orEmpty(),
                            message = prompt.message.orEmpty(),
                            defaultValue = prompt.defaultValue.orEmpty(),
                            onConfirmResponse = { value -> result.complete(prompt.confirm(value)) },
                            onDismissResponse = { result.complete(prompt.dismiss()) }
                        )
                    )
                )
                return result
            }

            override fun onAuthPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AuthPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                onAuthPrompt(
                    GeckoAuthPromptRequest(
                        title = prompt.title.orEmpty(),
                        message = prompt.message.orEmpty(),
                        uri = prompt.authOptions.uri.orEmpty(),
                        username = prompt.authOptions.username.orEmpty(),
                        password = prompt.authOptions.password.orEmpty(),
                        onConfirmResponse = { username, password ->
                            result.complete(prompt.confirm(username, password))
                        },
                        onDismissResponse = {
                            result.complete(prompt.dismiss())
                        }
                    )
                )
                return result
            }

            override fun onChoicePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.ChoicePrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                onPrompt(
                    GeckoPromptRequest.Choice(
                        GeckoChoicePromptRequest(
                            title = prompt.title.orEmpty(),
                            message = prompt.message.orEmpty(),
                            choices = prompt.choices.toPromptItems(),
                            multiple = prompt.type == GeckoSession.PromptDelegate.ChoicePrompt.Type.MULTIPLE,
                            onConfirmResponse = { ids ->
                                if (prompt.type == GeckoSession.PromptDelegate.ChoicePrompt.Type.MULTIPLE) {
                                    result.complete(prompt.confirm(ids.toTypedArray()))
                                } else {
                                    result.complete(prompt.confirm(ids.firstOrNull().orEmpty()))
                                }
                            },
                            onDismissResponse = { result.complete(prompt.dismiss()) }
                        )
                    )
                )
                return result
            }

            override fun onBeforeUnloadPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.BeforeUnloadPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                onPrompt(
                    GeckoPromptRequest.Confirm(
                        GeckoConfirmPromptRequest(
                            title = prompt.title.orEmpty().ifBlank {
                                appContext.getString(R.string.prompt_title_leave_site)
                            },
                            message = appContext.getString(R.string.prompt_message_leave_site),
                            confirmLabel = appContext.getString(R.string.prompt_action_leave),
                            dismissLabel = appContext.getString(R.string.prompt_action_stay),
                            onConfirmResponse = { result.complete(prompt.confirm(AllowOrDeny.ALLOW)) },
                            onDismissResponse = { result.complete(prompt.confirm(AllowOrDeny.DENY)) }
                        )
                    )
                )
                return result
            }

            override fun onRepostConfirmPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.RepostConfirmPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                onPrompt(
                    GeckoPromptRequest.Confirm(
                        GeckoConfirmPromptRequest(
                            title = prompt.title.orEmpty().ifBlank {
                                appContext.getString(R.string.prompt_title_resend_form)
                            },
                            message = appContext.getString(R.string.prompt_message_resend_form),
                            confirmLabel = appContext.getString(R.string.prompt_action_resend),
                            dismissLabel = appContext.getString(R.string.prompt_action_cancel),
                            onConfirmResponse = { result.complete(prompt.confirm(AllowOrDeny.ALLOW)) },
                            onDismissResponse = { result.complete(prompt.confirm(AllowOrDeny.DENY)) }
                        )
                    )
                )
                return result
            }

            override fun onColorPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.ColorPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                onPrompt(
                    GeckoPromptRequest.Color(
                        GeckoColorPromptRequest(
                            title = prompt.title.orEmpty(),
                            defaultValue = prompt.defaultValue.orEmpty(),
                            predefinedValues = prompt.predefinedValues ?: emptyArray(),
                            onConfirmResponse = { value -> result.complete(prompt.confirm(value)) },
                            onDismissResponse = { result.complete(prompt.dismiss()) }
                        )
                    )
                )
                return result
            }

            override fun onDateTimePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.DateTimePrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                onPrompt(
                    GeckoPromptRequest.DateTime(
                        GeckoDateTimePromptRequest(
                            title = prompt.title.orEmpty(),
                            defaultValue = prompt.defaultValue.orEmpty(),
                            minValue = prompt.minValue.orEmpty(),
                            maxValue = prompt.maxValue.orEmpty(),
                            stepValue = prompt.stepValue.orEmpty(),
                            onConfirmResponse = { value -> result.complete(prompt.confirm(value)) },
                            onDismissResponse = { result.complete(prompt.dismiss()) }
                        )
                    )
                )
                return result
            }

            override fun onFilePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.FilePrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                onFilePrompt(
                    GeckoFilePromptRequest(
                        title = prompt.title.orEmpty(),
                        mimeTypes = prompt.mimeTypes ?: emptyArray(),
                        multiple = prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE,
                        onConfirmResponse = { uris ->
                            if (prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE) {
                                result.complete(prompt.confirm(appContext, uris))
                            } else {
                                val uri = uris.firstOrNull()
                                if (uri == null) {
                                    result.complete(prompt.dismiss())
                                } else {
                                    result.complete(prompt.confirm(appContext, uri))
                                }
                            }
                        },
                        onDismissResponse = { result.complete(prompt.dismiss()) }
                    )
                )
                return result
            }

            override fun onFolderUploadPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.FolderUploadPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                onPrompt(
                    GeckoPromptRequest.Confirm(
                        GeckoConfirmPromptRequest(
                            title = prompt.title.orEmpty().ifBlank {
                                appContext.getString(R.string.prompt_title_upload_folder)
                            },
                            message = prompt.directoryName.takeIfNotBlank()
                                ?.let { appContext.getString(R.string.prompt_message_upload_folder_named, it) }
                                ?: appContext.getString(R.string.prompt_message_upload_folder),
                            confirmLabel = appContext.getString(R.string.prompt_action_allow),
                            dismissLabel = appContext.getString(R.string.prompt_action_cancel),
                            onConfirmResponse = { result.complete(prompt.confirm(AllowOrDeny.ALLOW)) },
                            onDismissResponse = { result.complete(prompt.confirm(AllowOrDeny.DENY)) }
                        )
                    )
                )
                return result
            }

            override fun onPopupPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.PopupPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                onPrompt(
                    GeckoPromptRequest.Confirm(
                        GeckoConfirmPromptRequest(
                            title = prompt.title.orEmpty().ifBlank {
                                appContext.getString(R.string.prompt_title_open_popup)
                            },
                            message = prompt.targetUri.takeIfNotBlank()
                                ?.let { appContext.getString(R.string.prompt_message_open_popup_url, it) }
                                ?: appContext.getString(R.string.prompt_message_open_popup),
                            confirmLabel = appContext.getString(R.string.prompt_action_open),
                            dismissLabel = appContext.getString(R.string.prompt_action_block),
                            onConfirmResponse = { result.complete(prompt.confirm(AllowOrDeny.ALLOW)) },
                            onDismissResponse = { result.complete(prompt.confirm(AllowOrDeny.DENY)) }
                        )
                    )
                )
                return result
            }

            override fun onRedirectPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.RedirectPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                onPrompt(
                    GeckoPromptRequest.Confirm(
                        GeckoConfirmPromptRequest(
                            title = prompt.title.orEmpty().ifBlank {
                                appContext.getString(R.string.prompt_title_redirect)
                            },
                            message = prompt.targetUri.takeIfNotBlank()
                                ?.let { appContext.getString(R.string.prompt_message_redirect_url, it) }
                                ?: appContext.getString(R.string.prompt_message_redirect),
                            confirmLabel = appContext.getString(R.string.prompt_action_continue),
                            dismissLabel = appContext.getString(R.string.prompt_action_cancel),
                            onConfirmResponse = { result.complete(prompt.confirm(AllowOrDeny.ALLOW)) },
                            onDismissResponse = { result.complete(prompt.confirm(AllowOrDeny.DENY)) }
                        )
                    )
                )
                return result
            }

            override fun onRequestCertificate(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.CertificateRequest
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                onCertificatePrompt(
                    GeckoCertificatePromptRequest(
                        title = prompt.title.orEmpty(),
                        host = prompt.host.orEmpty(),
                        issuers = prompt.issuers ?: emptyArray(),
                        onConfirmResponse = { alias -> result.complete(prompt.confirm(alias)) },
                        onDismissResponse = { result.complete(prompt.dismiss()) }
                    )
                )
                return result
            }

            override fun onSharePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.SharePrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                onSharePrompt(
                    GeckoSharePromptRequest(
                        title = prompt.title.orEmpty(),
                        text = prompt.text.orEmpty(),
                        uri = prompt.uri.orEmpty(),
                        onConfirmResponse = { value -> result.complete(prompt.confirm(value)) },
                        onDismissResponse = { result.complete(prompt.dismiss()) }
                    )
                )
                return result
            }
        }
        targetSession.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                applyWebsiteDisplayModeForUrl(semanticUrlForRawUrl(url))
                resetContentTouchState()
                _state.value = _state.value.copy(isLoading = true, loadProgress = 0)
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                _state.value = _state.value.copy(loadProgress = progress.coerceIn(0, 100))
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                _state.value = _state.value.copy(isLoading = false, loadProgress = 100)
                if (success) {
                    sessionCrashed = false
                    automaticRecoveryTarget = null
                }
                onPageStop(success)
            }

            override fun onSessionStateChange(
                session: GeckoSession,
                state: GeckoSession.SessionState
            ) {
                onSessionStateChange(GeckoSession.SessionState(state))
            }

            override fun onSecurityChange(
                session: GeckoSession,
                securityInfo: GeckoSession.ProgressDelegate.SecurityInformation
            ) {
                val currentUrl = _state.value.url.ifBlank { currentRawUrl }
                val nextSecurity = securityLevelFor(currentUrl, securityInfo)
                _state.value = _state.value.copy(
                    insecureHttp = nextSecurity == GeckoPageSecurity.Insecure,
                    securityLevel = nextSecurity
                )
            }
        }
        targetSession.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                val rawUrl = url.orEmpty()
                currentRawUrl = rawUrl
                if (shouldIgnoreInitialAboutBlank(rawUrl, _state.value.url, waitingForInitialLocation)) {
                    return
                }
                if (rawUrl != ABOUT_BLANK_URL) {
                    waitingForInitialLocation = false
                }
                val target = semanticUrlForRawUrl(rawUrl)
                if (target.isBlank()) return
                applyWebsiteDisplayModeForUrl(target)
                _state.value = _state.value.copy(
                    title = when {
                        isHomeUrl(target) -> "Hyper Browser"
                        isSettingsUrl(target) -> appContext.getString(R.string.internal_title_settings)
                        isBookmarksUrl(target) -> "Bookmarks"
                        isHistoryUrl(target) -> "History"
                        else -> _state.value.title
                    },
                    url = target,
                    insecureHttp = target.startsWith("http://"),
                    securityLevel = securityLevelForUrl(target)
                )
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                _state.value = _state.value.copy(canGoBack = canGoBack)
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                _state.value = _state.value.copy(canGoForward = canGoForward)
            }

            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                routeFromUri(request.uri)?.let { route ->
                    onHyperRoute(route)
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                applyWebsiteDisplayModeForUrl(semanticUrlForRawUrl(request.uri))
                return null
            }

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> {
                if (openNewTabsInCurrentTab() && uri.isNotBlank()) {
                    load(uri)
                    return GeckoResult.fromValue(null)
                }
                val newSession = onNewSession(uri)
                if (newSession == null && uri.isNotBlank()) {
                    load(uri)
                }
                return GeckoResult.fromValue(newSession)
            }
        }
        targetSession.setMediaSessionDelegate(object : MediaSession.Delegate {
            private val mediaNotifications: BrowserMediaNotificationController
                get() = BrowserMediaNotificationController.get(appContext)

            override fun onActivated(session: GeckoSession, mediaSession: MediaSession) {
                logMediaDelegateEvent("activated", session, mediaSession)
                mediaNotifications.onActivated(session, mediaSession, currentMediaOwnerInfo(session))
            }

            override fun onDeactivated(session: GeckoSession, mediaSession: MediaSession) {
                logMediaDelegateEvent("deactivated", session, mediaSession)
                mediaNotifications.onDeactivated(session, mediaSession)
            }

            override fun onMetadata(
                session: GeckoSession,
                mediaSession: MediaSession,
                meta: MediaSession.Metadata
            ) {
                logMediaDelegateEvent(
                    event = "metadata",
                    session = session,
                    mediaSession = mediaSession,
                    detail = "title=${meta.title.orEmpty()} artist=${meta.artist.orEmpty()}"
                )
                mediaNotifications.onMetadata(session, mediaSession, meta)
            }

            override fun onFeatures(session: GeckoSession, mediaSession: MediaSession, features: Long) {
                logMediaDelegateEvent("features", session, mediaSession, "features=$features")
                mediaNotifications.onFeatures(session, mediaSession, features)
            }

            override fun onPlay(session: GeckoSession, mediaSession: MediaSession) {
                logMediaDelegateEvent("play", session, mediaSession)
                mediaNotifications.onPlay(session, mediaSession, currentMediaOwnerInfo(session))
            }

            override fun onPause(session: GeckoSession, mediaSession: MediaSession) {
                logMediaDelegateEvent("pause", session, mediaSession)
                mediaNotifications.onPause(session, mediaSession)
            }

            override fun onStop(session: GeckoSession, mediaSession: MediaSession) {
                logMediaDelegateEvent("stop", session, mediaSession)
                mediaNotifications.onStop(session, mediaSession)
            }

            override fun onPositionState(
                session: GeckoSession,
                mediaSession: MediaSession,
                state: MediaSession.PositionState
            ) {
                logMediaDelegateEvent(
                    event = "position",
                    session = session,
                    mediaSession = mediaSession,
                    detail = "position=${state.position} duration=${state.duration} rate=${state.playbackRate}"
                )
                mediaNotifications.onPositionState(session, mediaSession, state)
            }

            override fun onFullscreen(
                session: GeckoSession,
                mediaSession: MediaSession,
                enabled: Boolean,
                meta: MediaSession.ElementMetadata?
            ) {
                logMediaDelegateEvent(
                    event = "fullscreen",
                    session = session,
                    mediaSession = mediaSession,
                    detail = "enabled=$enabled videoTracks=${meta?.videoTrackCount ?: 0}"
                )
            }
        })
    }

    fun load(input: String, searchUrlTemplate: String? = null) {
        val target = normalizeUrl(input, searchUrlTemplate)
        lastLoadTarget = target
        sessionCrashed = false
        automaticRecoveryTarget = null
        if (isHomeUrl(target)) {
            loadHome()
            return
        }
        if (isSettingsUrl(target)) {
            onHyperRoute(HyperRoute.Settings)
            return
        }
        if (isBookmarksUrl(target)) {
            onHyperRoute(HyperRoute.Bookmarks)
            return
        }
        if (isHistoryUrl(target)) {
            onHyperRoute(HyperRoute.History)
            return
        }
        _state.value = _state.value.copy(
            url = target,
            insecureHttp = target.startsWith("http://"),
            securityLevel = securityLevelForUrl(target)
        )
        applyWebsiteDisplayModeForUrl(target)
        session.loadUri(target)
    }

    fun loadHome(historyJson: String? = null) {
        lastLoadTarget = HOME_URL
        applyWebsiteDisplayMode(BrowserSettings.WEBSITE_DISPLAY_MOBILE)
        loadInternalPage(HOME_URL, "Hyper Browser", "home.html", historyJson)
    }

    fun goBack() {
        runCatching { session.goBack() }
    }

    fun goForward() {
        runCatching { session.goForward() }
    }

    fun exitFullScreen() {
        runCatching { session.exitFullScreen() }
        _fullScreen.value = false
    }

    fun reload() {
        if (sessionCrashed || !session.isOpen) {
            recoverSession(force = true)
            return
        }
        applyWebsiteDisplayModeForUrl(currentRecoveryTarget())
        runCatching { session.reload(GeckoSession.LOAD_FLAGS_BYPASS_CACHE) }
            .onFailure { recoverSession(force = true) }
    }

    fun findInPage(
        searchString: String?,
        backwards: Boolean = false,
        onResult: (GeckoFindInPageResult) -> Unit,
        onFailure: () -> Unit = {}
    ) {
        runCatching {
            val finder = session.finder
            if (searchString != null) {
                finder.clear()
            }
            finder.setDisplayFlags(GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL)
            finder.find(
                searchString,
                if (backwards) GeckoSession.FINDER_FIND_BACKWARDS else GeckoSession.FINDER_FIND_FORWARD
            ).accept(
                { result ->
                    if (result == null) {
                        onFailure()
                    } else {
                        onResult(
                            GeckoFindInPageResult(
                                found = result.found,
                                current = result.current,
                                total = result.total
                            )
                        )
                    }
                },
                { onFailure() }
            )
        }.onFailure {
            onFailure()
        }
    }

    fun clearFindInPage() {
        runCatching { session.finder.clear() }
    }

    fun flushSessionState() {
        runCatching { session.flushSessionState() }
    }

    fun restore(state: GeckoSession.SessionState, visibleUrl: String): Boolean {
        lastLoadTarget = visibleUrl
        waitingForInitialLocation = visibleUrl != ABOUT_BLANK_URL
        sessionCrashed = false
        automaticRecoveryTarget = null
        applyWebsiteDisplayModeForUrl(visibleUrl)
        _state.value = _state.value.copy(
            url = visibleUrl,
            insecureHttp = visibleUrl.startsWith("http://"),
            securityLevel = securityLevelForUrl(visibleUrl)
        )
        return runCatching {
            session.restoreState(GeckoSession.SessionState(state))
            true
        }.getOrDefault(false)
    }

    fun attachView(view: GeckoView?) {
        this.view = view
    }

    fun setVisible(visible: Boolean, focused: Boolean = visible) {
        session.setActive(visible)
        session.setFocused(focused)
        session.setPriorityHint(
            if (visible) GeckoSession.PRIORITY_HIGH else GeckoSession.PRIORITY_DEFAULT
        )
        if (focused) {
            focusForUserInteraction()
        }
    }

    fun focusForUserInteraction() {
        session.setActive(true)
        session.setFocused(true)
        session.setPriorityHint(GeckoSession.PRIORITY_HIGH)
        view?.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocusFromTouch()
            requestFocus()
            post {
                this@GeckoSessionController.session.setActive(true)
                this@GeckoSessionController.session.setFocused(true)
            }
        }
    }

    fun markPullRefreshGestureStarted() {
        pullRefreshGestureStartedAt = SystemClock.uptimeMillis()
        contentTouchCanScrollUp = false
        lastContentTouchStateAt = 0L
    }

    fun canStartPullRefreshFromContent(): Boolean {
        if (pullRefreshGestureStartedAt == 0L || lastContentTouchStateAt < pullRefreshGestureStartedAt) {
            return false
        }
        val stateAgeMs = SystemClock.uptimeMillis() - lastContentTouchStateAt
        return stateAgeMs <= CONTENT_TOUCH_STATE_TTL_MS && !contentTouchCanScrollUp
    }

    fun capturePixels(onCaptured: (Bitmap?) -> Unit) {
        val currentView = view
        if (currentView == null) {
            onCaptured(null)
            return
        }
        currentView.capturePixels().accept({ bitmap ->
            onCaptured(bitmap)
        }, {
            onCaptured(null)
        })
    }

    fun applyWebsiteDisplayMode(mode: String) {
        val sessionSettings = session.settings
        sessionSettings.setUserAgentOverride(null)
        when (BrowserSettings.normalizedWebsiteDisplayMode(mode)) {
            BrowserSettings.WEBSITE_DISPLAY_DESKTOP -> {
                sessionSettings.setUserAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
                sessionSettings.setViewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP)
            }
            BrowserSettings.WEBSITE_DISPLAY_TABLET -> {
                sessionSettings.setUserAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                sessionSettings.setViewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP)
                sessionSettings.setUserAgentOverride(tabletUserAgent())
            }
            else -> {
                sessionSettings.setUserAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                sessionSettings.setViewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            }
        }
    }

    fun applyWebsiteDisplayModeForUrl(url: String) {
        if (isInternalUrl(url) || url.startsWith("moz-extension://")) {
            applyWebsiteDisplayMode(BrowserSettings.WEBSITE_DISPLAY_MOBILE)
            return
        }
        applyWebsiteDisplayMode(websiteDisplayModeForSession())
    }

    fun close(closeActivePlayback: Boolean = true): GeckoSessionCloseResult {
        val mediaNotifications = BrowserMediaNotificationController.get(appContext)
        val ownerInfo = currentMediaOwnerInfo()
        if (!closeActivePlayback && mediaNotifications.ownsActivePlayback(ownerInfo)) {
            if (mediaNotifications.ownsActivePlayback(session)) {
                attachView(null)
                return GeckoSessionCloseResult.DetachedForPlayback
            }
            HyperBridge.unregister(session)
            runCatching { session.close() }
            return GeckoSessionCloseResult.Closed
        }
        val sessionsToClose = (mediaNotifications.sessionsForOwner(ownerInfo) + session).distinct()
        sessionsToClose.forEach { ownerSession ->
            HyperBridge.unregister(ownerSession)
            mediaNotifications.clearIfOwner(ownerSession)
            runCatching { ownerSession.close() }
        }
        return GeckoSessionCloseResult.Closed
    }

    private fun tabletUserAgent(): String {
        val androidVersion = Build.VERSION.RELEASE.takeIf { it.isNotBlank() } ?: "10"
        return "Mozilla/5.0 (Android $androidVersion; Tablet; rv:151.0) Gecko/151.0 Firefox/151.0"
    }

    private fun registerBridgeHandler(targetSession: GeckoSession) {
        HyperBridge.register(
            targetSession,
            { message -> handleBridgeMessage(targetSession, message) },
            useAsFallback = onHyperBridgeMessage != null
        )
    }

    private fun handleBridgeMessage(ownerSession: GeckoSession, message: org.json.JSONObject): GeckoResult<Any> {
        val payload = message.optJSONObject("payload") ?: org.json.JSONObject()
        when (message.optString("type")) {
            "pullRefresh.touch" -> {
                contentTouchCanScrollUp = payload.optBoolean("canScrollUp", false)
                lastContentTouchStateAt = SystemClock.uptimeMillis()
                return bridgeResult(org.json.JSONObject().put("ok", true))
            }
            "media.keepAlive.start" -> {
                if (!isMediaKeepAliveForSession(ownerSession, payload)) {
                    logIgnoredKeepAlive("start", ownerSession, payload)
                    return bridgeResult(org.json.JSONObject().put("ok", true).put("ignored", true))
                }
                BrowserMediaNotificationController.get(appContext).startPageKeepAlive(
                    owner = ownerSession,
                    ownerInfo = currentMediaOwnerInfo(ownerSession),
                    title = payload.optString("title"),
                    url = payload.optString("url"),
                    mediaKind = payload.optString("mediaKind")
                )
                return bridgeResult(org.json.JSONObject().put("ok", true))
            }
            "media.keepAlive.pause" -> {
                if (!isMediaKeepAliveForSession(ownerSession, payload)) {
                    logIgnoredKeepAlive("pause", ownerSession, payload)
                    return bridgeResult(org.json.JSONObject().put("ok", true).put("ignored", true))
                }
                BrowserMediaNotificationController.get(appContext).pausePageKeepAlive(
                    owner = ownerSession,
                    ownerInfo = currentMediaOwnerInfo(ownerSession),
                    title = payload.optString("title"),
                    url = payload.optString("url"),
                    mediaKind = payload.optString("mediaKind")
                )
                return bridgeResult(org.json.JSONObject().put("ok", true))
            }
            "media.keepAlive.stop" -> {
                if (!isMediaKeepAliveForSession(ownerSession, payload)) {
                    logIgnoredKeepAlive("stop", ownerSession, payload)
                    return bridgeResult(org.json.JSONObject().put("ok", true).put("ignored", true))
                }
                BrowserMediaNotificationController.get(appContext).stopPageKeepAlive(ownerSession)
                return bridgeResult(org.json.JSONObject().put("ok", true))
            }
            "settings.backgroundVideoEnhancement.enabled" -> {
                val enabled = BrowserProfileStore
                    .loadBrowserSettings(appContext)
                    .backgroundVideoEnhancementEnabled
                return bridgeResult(
                    org.json.JSONObject()
                        .put("ok", true)
                        .put("data", org.json.JSONObject().put("enabled", enabled))
                )
            }
        }
        return onHyperBridgeMessage?.invoke(message)
            ?: bridgeResult(org.json.JSONObject().put("ok", false).put("error", "No bridge handler for session."))
    }

    private fun bridgeResult(response: org.json.JSONObject): GeckoResult<Any> =
        GeckoResult.fromValue(response.toString())

    private fun currentMediaOwnerInfo(ownerSession: GeckoSession = session): BrowserMediaOwnerInfo {
        val pageState = _state.value
        val provided = mediaOwnerInfo()
        val visibleUrl = pageState.url
            .ifBlank { lastLoadTarget }
            .ifBlank { currentRawUrl }
        return BrowserMediaOwnerInfo(
            id = provided?.id ?: "session-${System.identityHashCode(ownerSession)}",
            kind = provided?.kind ?: BrowserMediaOwnerKind.BrowserTab,
            displayName = provided?.displayName
                ?: pageState.title.takeIf { it.isNotBlank() },
            url = provided?.url
                ?: visibleUrl.takeIf { it.isNotBlank() },
            iconPath = provided?.iconPath,
            launchIntent = provided?.launchIntent ?: mediaNotificationIntent
        )
    }

    private fun isMediaKeepAliveForSession(ownerSession: GeckoSession, payload: org.json.JSONObject): Boolean {
        if (ownerSession !== session) return true
        val sourceUrl = payload.optString("sourceUrl")
            .ifBlank { payload.optString("url") }
            .ifBlank { return true }
        val source = semanticUrlForRawUrl(sourceUrl).withoutFragment()
        val candidates = listOf(_state.value.url, lastLoadTarget, currentRawUrl)
            .filter { it.isNotBlank() && it != ABOUT_BLANK_URL }
            .map { semanticUrlForRawUrl(it).withoutFragment() }
        return candidates.any { it == source }
    }

    private fun logIgnoredKeepAlive(event: String, ownerSession: GeckoSession, payload: org.json.JSONObject) {
        Log.d(
            MEDIA_DEBUG_TAG,
            buildString {
                append("keepAlive.ignored event=").append(event)
                append(" ownerSession=").append(System.identityHashCode(ownerSession))
                append(" sourceUrl=").append(payload.optString("sourceUrl"))
                append(" payloadUrl=").append(payload.optString("url"))
                append(" session=").append(System.identityHashCode(session))
                append(" current=").append(_state.value.url)
                append(" raw=").append(currentRawUrl)
            }
        )
    }

    private fun logMediaDelegateEvent(
        event: String,
        session: GeckoSession,
        mediaSession: MediaSession,
        detail: String = ""
    ) {
        val info = currentMediaOwnerInfo(session)
        Log.d(
            MEDIA_DEBUG_TAG,
            buildString {
                append("gecko event=").append(event)
                append(" session=").append(System.identityHashCode(session))
                append(" media=").append(System.identityHashCode(mediaSession))
                append(" ownerId=").append(info.id)
                append(" kind=").append(info.kind)
                append(" title=").append(info.displayName.orEmpty())
                append(" url=").append(info.url.orEmpty())
                if (detail.isNotBlank()) append(" ").append(detail)
            }
        )
    }

    private fun resetContentTouchState() {
        contentTouchCanScrollUp = false
        lastContentTouchStateAt = 0L
        pullRefreshGestureStartedAt = 0L
    }

    private fun loadInternalPage(semanticUrl: String, title: String, page: String, bootstrapJson: String?) {
        lastLoadTarget = semanticUrl
        sessionCrashed = false
        applyWebsiteDisplayMode(BrowserSettings.WEBSITE_DISPLAY_MOBILE)
        _state.value = pageStateForUrl(semanticUrl).copy(title = title)
        val loadPage = {
            HyperBridge.pageUrl(page)?.let { url ->
                currentRawUrl = url
                session.loadUri(assetUrlWithData(url, bootstrapJson))
            }
        }
        if (HyperBridge.pageUrl(page) == null) {
            HyperBridge.ensureInstalled(appContext) { loadPage() }
        } else {
            loadPage()
        }
    }

    private fun recoverAfterContentLoss() {
        val target = currentRecoveryTarget()
        sessionCrashed = true
        _state.value = _state.value.copy(isLoading = false)
        if (automaticRecoveryTarget == target) return
        automaticRecoveryTarget = target
        mainHandler.post { recoverSession(force = false) }
    }

    private fun createBrowserSession(): GeckoSession =
        GeckoSessionController.createSession()

    private fun recoverSession(force: Boolean) {
        val target = currentRecoveryTarget()
        if (force) {
            automaticRecoveryTarget = null
        }
        val mediaNotifications = BrowserMediaNotificationController.get(appContext)
        val preservePlayback = mediaNotifications.ownsActivePlayback(session)
        if (!preservePlayback) {
            HyperBridge.unregister(session)
            mediaNotifications.clearIfOwner(session)
            runCatching { session.close() }
        }
        session = createBrowserSession()
        waitingForInitialLocation = true
        configureSession(session)
        registerBridgeHandler(session)
        session.open(runtime)
        view?.setSession(session)
        _sessionChangeVersion.value = _sessionChangeVersion.value + 1
        sessionCrashed = false
        load(target)
        if (!force) {
            automaticRecoveryTarget = target
        }
    }

    private fun currentRecoveryTarget(): String {
        val visibleUrl = _state.value.url
        return when {
            visibleUrl.isNotBlank() -> visibleUrl
            currentRawUrl.isNotBlank() && currentRawUrl != ABOUT_BLANK_URL -> semanticUrlForRawUrl(currentRawUrl)
            else -> lastLoadTarget
        }
    }

    private fun assetUrlWithData(assetUrl: String, json: String?): String {
        val params = mutableMapOf("locale" to currentLocaleTag())
        if (json != null) params["data"] = json
        return urlWithHashParams(assetUrl, params)
    }

    private fun urlWithHashParams(assetUrl: String, params: Map<String, String>): String {
        val encoded = params
            .filterValues { it.isNotBlank() }
            .map { (key, value) ->
                "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8").replace("+", "%20")}"
            }
            .joinToString("&")
        if (encoded.isBlank()) return assetUrl
        return "$assetUrl#$encoded"
    }

    private fun currentLocaleTag(): String =
        localeTagForPreference(BrowserProfileStore.loadBrowserSettings(appContext).localePreference)

    companion object {
        internal const val ABOUT_BLANK_URL = "about:blank"
        const val HOME_URL = "hyper://home"
        const val SETTINGS_URL = "hyper://settings"
        const val BOOKMARKS_URL = "hyper://bookmarks"
        const val HISTORY_URL = "hyper://history"
        private const val HOME_ASSET_URL = "resource://android/assets/home.html"
        private const val CONTENT_TOUCH_STATE_TTL_MS = 700L
        private const val POPUP_CLOSE_DELAY_MS = 150L
        private const val MEDIA_DEBUG_TAG = "HyperMediaDebug"

        fun createSession(): GeckoSession =
            GeckoSession(
                GeckoSessionSettings.Builder()
                    .suspendMediaWhenInactive(false)
                    .build()
            )

        fun isHomeUrl(url: String): Boolean = url == HOME_URL
        fun isSettingsUrl(url: String): Boolean = url == SETTINGS_URL
        fun isBookmarksUrl(url: String): Boolean = url == BOOKMARKS_URL
        fun isHistoryUrl(url: String): Boolean = url == HISTORY_URL
        fun isHomeDocumentUrl(url: String): Boolean =
            url.startsWith(HOME_ASSET_URL) || HyperBridge.isPageUrl(url, "home.html")
        fun isInternalUrl(url: String): Boolean =
            isHomeUrl(url) || isSettingsUrl(url) ||
                isBookmarksUrl(url) || isHistoryUrl(url) ||
                isHomeDocumentUrl(url)
        fun isBrowserLoadableUrl(url: String): Boolean =
            url.startsWith("http://") || url.startsWith("https://") || url.startsWith("moz-extension://")

        internal fun shouldIgnoreInitialAboutBlank(
            rawUrl: String,
            visibleUrl: String,
            waitingForInitialLocation: Boolean
        ): Boolean =
            waitingForInitialLocation &&
                rawUrl == ABOUT_BLANK_URL &&
                visibleUrl.isNotBlank() &&
                visibleUrl != ABOUT_BLANK_URL

        fun normalizeUrl(input: String, searchUrlTemplate: String? = null): String {
            val value = input.trim()
            if (value.isBlank()) return HOME_URL
            if (isHomeUrl(value)) return HOME_URL
            if (isSettingsUrl(value)) return SETTINGS_URL
            if (isBookmarksUrl(value)) return BOOKMARKS_URL
            if (isHistoryUrl(value)) return HISTORY_URL
            if (isBrowserLoadableUrl(value)) return value
            if (value.contains(".") && !value.contains(" ")) return "https://$value"
            val encodedQuery = java.net.URLEncoder.encode(value, "UTF-8")
            val template = searchUrlTemplate
                ?.takeIf { it.contains("%s") }
                ?: "https://www.google.com/search?q=%s"
            return template.replace("%s", encodedQuery)
        }

        fun securityLevelForUrl(url: String): GeckoPageSecurity =
            when {
                url.startsWith("http://") -> GeckoPageSecurity.Insecure
                url.startsWith("https://") -> GeckoPageSecurity.Secure
                else -> GeckoPageSecurity.Neutral
            }

        private fun pageStateForUrl(url: String): GeckoPageState {
            val security = securityLevelForUrl(url)
            return GeckoPageState(
                url = url,
                insecureHttp = security == GeckoPageSecurity.Insecure,
                securityLevel = security
            )
        }

        private fun securityLevelFor(
            url: String,
            securityInfo: GeckoSession.ProgressDelegate.SecurityInformation
        ): GeckoPageSecurity =
            when {
                url.startsWith("http://") -> GeckoPageSecurity.Insecure
                securityInfo.isSecure &&
                    securityInfo.securityMode ==
                    GeckoSession.ProgressDelegate.SecurityInformation.SECURITY_MODE_VERIFIED ->
                    GeckoPageSecurity.Verified
                securityInfo.isSecure || url.startsWith("https://") -> GeckoPageSecurity.Secure
                else -> GeckoPageSecurity.Neutral
            }

        private fun semanticUrlForRawUrl(url: String): String =
            when {
                isHomeDocumentUrl(url) -> HOME_URL
                else -> url
            }

        private fun routeFromUri(uri: String): HyperRoute? {
            if (!uri.startsWith("hyper://")) return null
            return when {
                uri == HOME_URL -> HyperRoute.Home
                uri == SETTINGS_URL -> HyperRoute.Settings
                uri == BOOKMARKS_URL -> HyperRoute.Bookmarks
                uri == HISTORY_URL -> HyperRoute.History
                else -> null
            }
        }

    }
}

private fun String.withoutFragment(): String =
    substringBefore('#')

private fun String?.takeIfNotBlank(): String? =
    this?.takeIf { it.isNotBlank() }

private fun Array<GeckoSession.PromptDelegate.ChoicePrompt.Choice>.toPromptItems(
    level: Int = 0
): List<GeckoChoicePromptItem> =
    flatMap { choice ->
        val childItems = choice.items?.toPromptItems(level + 1).orEmpty()
        if (choice.separator) {
            childItems
        } else {
            listOf(
                GeckoChoicePromptItem(
                    id = choice.id.orEmpty(),
                    label = choice.label.orEmpty(),
                    disabled = choice.disabled,
                    selected = choice.selected,
                    level = level
                )
            ) + childItems
        }
    }

private fun WebResponse.headerValue(name: String): String? {
    val wanted = name.lowercase()
    return headers.entries.firstOrNull { it.key.lowercase() == wanted }?.value
}

sealed interface HyperRoute {
    data object Home : HyperRoute
    data object Settings : HyperRoute
    data object Bookmarks : HyperRoute
    data object History : HyperRoute
}

sealed interface HyperCommand {
    sealed interface Bookmarks : HyperCommand {
        data class Open(val url: String) : Bookmarks
    }

    sealed interface History : HyperCommand {
        data class Open(val url: String) : History
        data class Remove(val url: String) : History
        data object Clear : History
    }

    sealed interface Apps : HyperCommand {
        data class Open(val id: String) : Apps
        data class OpenStandalone(val id: String) : Apps
        data class Pin(val id: String) : Apps
    }

    sealed interface Panel : HyperCommand {
        data object Extensions : Panel
    }
}
