package com.dadigua.hyperbrowser.ui.webapp

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import com.dadigua.hyperbrowser.HyperBrowserApp
import com.dadigua.hyperbrowser.browser.DownloadHandler
import com.dadigua.hyperbrowser.browser.DownloadStore
import com.dadigua.hyperbrowser.browser.BrowserMediaNotificationController
import com.dadigua.hyperbrowser.browser.BrowserMediaOwnerInfo
import com.dadigua.hyperbrowser.browser.BrowserMediaOwnerKind
import com.dadigua.hyperbrowser.browser.closeBrowserMediaPlaybackOwner
import com.dadigua.hyperbrowser.data.WebAppDefinition
import com.dadigua.hyperbrowser.gecko.GeckoAuthPromptRequest
import com.dadigua.hyperbrowser.gecko.GeckoContextMenuTarget
import com.dadigua.hyperbrowser.gecko.GeckoBrowserView
import com.dadigua.hyperbrowser.gecko.GeckoSessionController
import com.dadigua.hyperbrowser.ui.FullscreenSystemBarsEffect
import com.dadigua.hyperbrowser.ui.browser.AuthPromptDialog
import com.dadigua.hyperbrowser.ui.browser.BrowserActivity
import com.dadigua.hyperbrowser.ui.browser.PageContextMenuDialog
import com.dadigua.hyperbrowser.ui.theme.HyperBrowserTheme
import kotlinx.coroutines.launch

class WebAppActivity : ComponentActivity() {
    private var activeWebAppId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val webAppId = intent.getStringExtra(EXTRA_WEB_APP_ID)
            ?: intent.data?.lastPathSegment
            ?: return finish()
        activeWebAppId = webAppId
        setContent {
            HyperBrowserTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WebAppScreen(
                        activity = this,
                        app = application as HyperBrowserApp,
                        webAppId = webAppId
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            activeWebAppId?.let { webAppId ->
                closeBrowserMediaPlaybackOwner(
                    application as HyperBrowserApp,
                    BrowserMediaOwnerInfo(
                        id = ownerId(webAppId),
                        kind = BrowserMediaOwnerKind.WebApp
                    )
                )
            }
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        BrowserMediaNotificationController.get(this).cancelBackgroundPlaybackResume("foreground")
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        BrowserMediaNotificationController.get(this).allowBackgroundPlaybackResume()
    }

    companion object {
        private const val EXTRA_WEB_APP_ID = "extra_web_app_id"

        fun ownerId(webAppId: String): String = "webapp-$webAppId"

        fun intent(context: Context, webAppId: String, asDocument: Boolean): Intent {
            val flags = if (asDocument) {
                Intent.FLAG_ACTIVITY_NEW_DOCUMENT
            } else {
                0
            }
            return Intent(context, WebAppActivity::class.java)
                .putExtra(EXTRA_WEB_APP_ID, webAppId)
                .setData(Uri.parse("hyperbrowser://webapp/$webAppId"))
                .addFlags(flags)
        }
    }
}

@Composable
private fun WebAppScreen(activity: WebAppActivity, app: HyperBrowserApp, webAppId: String) {
    var webApp by remember { mutableStateOf<WebAppDefinition?>(null) }
    var pageContextMenu by remember { mutableStateOf<GeckoContextMenuTarget?>(null) }
    var authPrompt by remember { mutableStateOf<GeckoAuthPromptRequest?>(null) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val downloadStore = remember { DownloadStore(app) }
    val downloadHandler = remember { DownloadHandler(app, downloadStore) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    fun showToast(text: String) {
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
    }

    fun requestDownloadNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !downloadHandler.canPostNotifications()) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun enqueueUrlDownload(url: String) {
        requestDownloadNotificationsIfNeeded()
        scope.launch {
            showToast("Downloading...")
            runCatching { downloadHandler.enqueueUrlDownload(url) }
                .onSuccess { showToast("Download queued: ${it.name}") }
                .onFailure { showToast(it.message ?: "Download failed.") }
        }
    }

    fun copyContextUrl(clipLabel: String, url: String, toast: String) {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(clipLabel, url)))
        }
        pageContextMenu = null
        showToast(toast)
    }

    fun openInBrowserNewTab(url: String) {
        pageContextMenu = null
        runCatching { activity.startActivity(BrowserActivity.newTabIntent(activity, url)) }
            .onFailure { showToast(it.message ?: "Unable to open link.") }
    }

    LaunchedEffect(webAppId) {
        webApp = app.webApps.get(webAppId)
        webApp?.let {
            app.webApps.markOpened(it.id)
            val updated = app.webApps.get(it.id) ?: it
            webApp = updated
            app.webApps.applyTaskDescription(activity, updated)
        }
    }

    val current = webApp
    if (current == null) {
        Text("WebApp not found", modifier = Modifier.padding(16.dp))
        return
    }

    val controller = remember(current.id) {
        GeckoSessionController(
            context = app,
            initialUrl = current.startUrl,
            onPageContextMenu = { pageContextMenu = it },
            onAuthPrompt = { authPrompt = it },
            mediaNotificationIntent = WebAppActivity.intent(app, current.id, true),
            mediaOwnerInfo = {
                BrowserMediaOwnerInfo(
                    id = WebAppActivity.ownerId(current.id),
                    kind = BrowserMediaOwnerKind.WebApp,
                    displayName = current.name,
                    url = current.startUrl,
                    iconPath = current.iconPath,
                    launchIntent = WebAppActivity.intent(app, current.id, true)
                )
            }
        )
    }
    val pageState by controller.state.collectAsState()
    val pageFullScreen by controller.fullScreen.collectAsState()

    FullscreenSystemBarsEffect(pageFullScreen)

    LaunchedEffect(pageFullScreen, pageContextMenu) {
        controller.setVisible(
            visible = true,
            focused = pageFullScreen || pageContextMenu == null
        )
    }

    DisposableEffect(current.id) {
        onDispose { controller.close(closeActivePlayback = false) }
    }

    BackHandler {
        when {
            pageFullScreen -> controller.exitFullScreen()
            pageState.canGoBack -> controller.goBack()
            else -> controller.goBack()
        }
    }

    Column(
        modifier = if (pageFullScreen) {
            Modifier.fillMaxSize()
        } else {
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            GeckoBrowserView(controller = controller, modifier = Modifier.fillMaxSize())
            WebAppLoadingProgressBar(
                loading = pageState.isLoading,
                progress = pageState.loadProgress,
                modifier = Modifier.align(Alignment.TopCenter)
            )
            pageContextMenu?.let { menu ->
                PageContextMenuDialog(
                    target = menu,
                    onDismissRequest = { pageContextMenu = null },
                    onDownloadImage = { url ->
                        pageContextMenu = null
                        enqueueUrlDownload(url)
                    },
                    onOpenImage = ::openInBrowserNewTab,
                    onCopyImage = { url -> copyContextUrl("image", url, "图片地址已复制") },
                    onOpenLink = ::openInBrowserNewTab,
                    onCopyLink = { url -> copyContextUrl("link", url, "链接已复制") },
                    openImageLabel = "在浏览器中打开图片",
                    openLinkLabel = "在浏览器中打开链接"
                )
            }
            authPrompt?.let { request ->
                AuthPromptDialog(
                    request = request,
                    onFinished = { authPrompt = null }
                )
            }
        }
    }
}

@Composable
private fun WebAppLoadingProgressBar(
    loading: Boolean,
    progress: Int,
    modifier: Modifier = Modifier
) {
    if (!loading) return
    val normalizedProgress = (progress.coerceIn(0, 100) / 100f).coerceAtLeast(0.08f)
    LinearProgressIndicator(
        progress = { normalizedProgress },
        modifier = modifier
            .fillMaxWidth()
            .height(2.dp),
        color = MaterialTheme.colorScheme.primary,
        trackColor = Color.Transparent
    )
}
