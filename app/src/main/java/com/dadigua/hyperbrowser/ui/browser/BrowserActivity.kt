package com.dadigua.hyperbrowser.ui.browser

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Rational
import android.webkit.URLUtil
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AddToHomeScreen
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dadigua.hyperbrowser.HyperBrowserApp
import com.dadigua.hyperbrowser.browser.BrowserBookmark
import com.dadigua.hyperbrowser.browser.BrowserDownloadEntry
import com.dadigua.hyperbrowser.browser.BrowserHistoryEntry
import com.dadigua.hyperbrowser.browser.DownloadHandler
import com.dadigua.hyperbrowser.browser.DownloadStatus
import com.dadigua.hyperbrowser.browser.DownloadStore
import com.dadigua.hyperbrowser.browser.FaviconRepository
import com.dadigua.hyperbrowser.browser.BrowserProfileStore
import com.dadigua.hyperbrowser.browser.BrowserSettings
import com.dadigua.hyperbrowser.browser.BrowserMediaNotificationController
import com.dadigua.hyperbrowser.data.InstalledExtensionState
import com.dadigua.hyperbrowser.data.WebAppDefinition
import com.dadigua.hyperbrowser.extensions.AmoAddonListing
import com.dadigua.hyperbrowser.extensions.ExtensionMenuActionState
import com.dadigua.hyperbrowser.extensions.ExtensionNewTabRequest
import com.dadigua.hyperbrowser.extensions.ExtensionPopupState
import com.dadigua.hyperbrowser.gecko.GeckoBrowserView
import com.dadigua.hyperbrowser.gecko.GeckoDownloadRequest
import com.dadigua.hyperbrowser.gecko.GeckoPageState
import com.dadigua.hyperbrowser.gecko.GeckoSessionController
import com.dadigua.hyperbrowser.gecko.GeckoSessionView
import com.dadigua.hyperbrowser.gecko.HyperCommand
import com.dadigua.hyperbrowser.gecko.HyperRoute
import com.dadigua.hyperbrowser.ui.theme.HyperBrowserTheme
import com.dadigua.hyperbrowser.ui.webapp.WebAppActivity
import com.dadigua.hyperbrowser.webapp.PinnedShortcutRequestResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

private val ChromeActionBarHeight = 48.dp
private val ChromeActionButtonSize = 40.dp
private val ChromeAddressBarHeight = 44.dp
private val ChromeActionIconSize = 24.sp

class BrowserActivity : ComponentActivity() {
    private val externalIntents = MutableSharedFlow<ExternalBrowserIntent>(extraBufferCapacity = 1)
    private var inPictureInPicture by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inPictureInPicture = isInPictureInPictureMode
        val initialIntent = intent.toExternalBrowserIntent()
        val initialUrl = if (initialIntent?.download == false) {
            initialIntent.url
        } else {
            GeckoSessionController.HOME_URL
        }
        setContent {
            HyperBrowserTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BrowserScreen(
                        app = application as HyperBrowserApp,
                        initialUrl = initialUrl,
                        initialDownloadUrl = initialIntent?.url?.takeIf { initialIntent.download },
                        externalIntents = externalIntents,
                        inPictureInPicture = inPictureInPicture
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.toExternalBrowserIntent()?.let { externalIntents.tryEmit(it) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPictureInPictureIfMediaPlaying()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
    }

    private fun enterPictureInPictureIfMediaPlaying() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || isInPictureInPictureMode) return
        if (!BrowserMediaNotificationController.get(this).hasActiveVideoPlayback) return
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .build()
        runCatching { enterPictureInPictureMode(params) }
    }

    companion object {
        const val EXTRA_URL = "extra_url"

        fun intent(context: Context, url: String): Intent =
            Intent(context, BrowserActivity::class.java).putExtra(EXTRA_URL, url)
    }
}

private data class ExternalBrowserIntent(
    val url: String,
    val download: Boolean
)

private data class LinkContextMenuState(
    val url: String,
    val label: String?
)

private fun Intent.toExternalBrowserIntent(): ExternalBrowserIntent? {
    getStringExtra(BrowserActivity.EXTRA_URL)?.takeIf { it.isNotBlank() }?.let {
        return ExternalBrowserIntent(it, download = false)
    }
    if (action == Intent.ACTION_SEND && type?.startsWith("text/") == true) {
        val text = getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        extractFirstHttpUrl(text)?.let {
            return ExternalBrowserIntent(it, download = true)
        }
    }
    if (action == Intent.ACTION_VIEW) {
        dataString?.takeIf { URLUtil.isHttpUrl(it) || URLUtil.isHttpsUrl(it) }?.let {
            return ExternalBrowserIntent(it, download = false)
        }
    }
    return null
}

private fun extractFirstHttpUrl(text: String): String? =
    Regex("""https?://\S+""")
        .find(text)
        ?.value
        ?.trimEnd('.', ',', ';', ')', ']', '}', '"', '\'')

@Composable
private fun BrowserScreen(
    app: HyperBrowserApp,
    initialUrl: String,
    initialDownloadUrl: String?,
    externalIntents: MutableSharedFlow<ExternalBrowserIntent>,
    inPictureInPicture: Boolean
) {
    var pendingHyperRoute by remember { mutableStateOf<HyperRoute?>(null) }
    var pendingHyperCommand by remember { mutableStateOf<HyperCommand?>(null) }
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val profileStore = remember { BrowserProfileStore(app) }
    val faviconStore = remember { FaviconRepository(app) }
    val downloadStore = remember { DownloadStore(app) }
    val downloadHandler = remember { DownloadHandler(app, downloadStore) }
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    fun requestDownloadNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !downloadHandler.canPostNotifications()) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun enqueueUrlDownload(url: String) {
        scope.launch {
            message = "Downloading..."
            runCatching { downloadHandler.enqueueUrlDownload(url) }
                .onSuccess { message = "Download queued: ${it.name}" }
                .onFailure { message = it.message ?: "Download failed." }
        }
    }
    fun saveGeckoDownload(request: GeckoDownloadRequest) {
        requestDownloadNotificationsIfNeeded()
        scope.launch {
            message = "Saving ${request.fileName}..."
            runCatching { downloadHandler.saveResponse(request, downloadHandler.canPostNotifications()) }
                .onSuccess { message = "Downloaded: ${it.name}" }
                .onFailure { message = it.message ?: "Download failed." }
        }
    }
    fun handleHyperBridgeMessage(message: JSONObject): JSONObject {
        val payload = message.optJSONObject("payload") ?: JSONObject()
        return when (message.optString("type")) {
            "data.home" -> okItems(profileStore.observeHistory().value.toHistoryJsonString(faviconStore))
            "data.search" -> okItems(
                searchSuggestionsJsonString(
                    bookmarks = profileStore.observeBookmarks().value,
                    history = profileStore.observeHistory().value
                )
            )
            "data.bookmarks" -> okItems(profileStore.observeBookmarks().value.toBookmarksJsonString(faviconStore))
            "data.history" -> okItems(profileStore.observeHistory().value.toHistoryJsonString(faviconStore))
            "data.apps" -> okItems(app.webApps.observeAll().value.toWebAppsJsonString(app))
            "data.settings" -> okData(profileStore.observeSettings().value.toJson())
            "search.submit" -> {
                pendingHyperCommand = HyperCommand.Search.Submit(payload.optString("query"))
                ok()
            }
            "settings.searchEngine.update" -> {
                profileStore.updateSearchEngine(
                    searchEngineId = payload.optString("searchEngineId"),
                    customSearchUrl = payload.optString("customSearchUrl")
                )
                okData(profileStore.observeSettings().value.toJson())
            }
            "settings.toolbarPosition.update" -> {
                profileStore.updateToolbarPosition(payload.optString("toolbarPosition"))
                okData(profileStore.observeSettings().value.toJson())
            }
            "bookmarks.open" -> {
                pendingHyperCommand = HyperCommand.Bookmarks.Open(payload.optString("url"))
                ok()
            }
            "bookmarks.remove" -> {
                pendingHyperCommand = HyperCommand.Bookmarks.Remove(payload.optString("url"))
                ok()
            }
            "bookmarks.edit" -> {
                pendingHyperCommand = HyperCommand.Bookmarks.Edit(
                    oldUrl = payload.optString("oldUrl"),
                    title = payload.optString("title"),
                    url = payload.optString("url")
                )
                ok()
            }
            "history.open" -> {
                pendingHyperCommand = HyperCommand.History.Open(payload.optString("url"))
                ok()
            }
            "history.remove" -> {
                pendingHyperCommand = HyperCommand.History.Remove(payload.optString("url"))
                ok()
            }
            "history.clear" -> {
                pendingHyperCommand = HyperCommand.History.Clear
                ok()
            }
            "apps.open" -> {
                pendingHyperCommand = HyperCommand.Apps.Open(payload.optString("id"))
                ok()
            }
            "apps.pin" -> {
                pendingHyperCommand = HyperCommand.Apps.Pin(payload.optString("id"))
                ok()
            }
            "apps.edit" -> {
                pendingHyperCommand = HyperCommand.Apps.Edit(
                    id = payload.optString("id"),
                    name = payload.optString("name"),
                    startUrl = payload.optString("startUrl")
                )
                ok()
            }
            "apps.delete" -> {
                pendingHyperCommand = HyperCommand.Apps.Delete(payload.optString("id"))
                ok()
            }
            "panel.extensions" -> {
                pendingHyperCommand = HyperCommand.Panel.Extensions
                ok()
            }
            else -> JSONObject().put("ok", false).put("error", "Unknown bridge message.")
        }
    }
    var linkContextMenu by remember { mutableStateOf<LinkContextMenuState?>(null) }
    val tabs = remember {
        mutableStateListOf(
            BrowserTabRuntime.create(
                app = app,
                url = initialUrl,
                onHyperRoute = { pendingHyperRoute = it },
                onHyperBridgeMessage = ::handleHyperBridgeMessage,
                onLinkContextMenu = { url, label ->
                    linkContextMenu = LinkContextMenuState(url = url, label = label)
                },
                onDownload = ::saveGeckoDownload
            )
        )
    }
    var selectedTabId by remember { mutableStateOf(tabs.first().id) }
    var showTabs by remember { mutableStateOf(false) }
    val selectedIndex = tabs.indexOfFirst { it.id == selectedTabId }.takeIf { it >= 0 } ?: 0
    val tab = tabs.getOrNull(selectedIndex) ?: tabs.first()
    val controller = tab.controller
    val pageState by controller.state.collectAsState()
    val onHomePage = GeckoSessionController.isHomeUrl(pageState.url)
    val onSearchPage = GeckoSessionController.isSearchUrl(pageState.url)
    val history by profileStore.observeHistory().collectAsState()
    val bookmarks by profileStore.observeBookmarks().collectAsState()
    val downloads by downloadStore.observeDownloads().collectAsState()
    val settings by profileStore.observeSettings().collectAsState()
    val webApps by app.webApps.observeAll().collectAsState()
    val installedExtensions by app.extensions.observeInstalled().collectAsState()
    val extensionActions by app.extensions.observeMenuActions().collectAsState()
    val extensionPopup by app.extensions.observePopup().collectAsState()
    val extensionNewTabRequest by app.extensions.observeNewTabRequests().collectAsState()
    var showSearch by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showDownloads by remember { mutableStateOf(false) }
    var showExtensions by remember { mutableStateOf(false) }
    var editingAddress by remember { mutableStateOf(false) }
    var extensionQuery by remember { mutableStateOf("ublock") }
    var extensionResults by remember { mutableStateOf<List<AmoAddonListing>>(emptyList()) }
    var extensionMessage by remember { mutableStateOf<String?>(null) }
    var installingAddonGuid by remember { mutableStateOf<String?>(null) }
    var currentIconPath by remember { mutableStateOf<String?>(null) }

    fun createBrowserTab(url: String): BrowserTabRuntime =
        BrowserTabRuntime.create(
            app = app,
            url = url,
            onHyperRoute = { pendingHyperRoute = it },
            onHyperBridgeMessage = ::handleHyperBridgeMessage,
            onLinkContextMenu = { linkUrl, label ->
                linkContextMenu = LinkContextMenuState(url = linkUrl, label = label)
            },
            onDownload = ::saveGeckoDownload
        )

    fun openLinkInBackgroundTab(url: String) {
        tabs.add(createBrowserTab(url))
        linkContextMenu = null
        showTabs = false
        message = "已在后台标签页打开"
    }

    LaunchedEffect(initialDownloadUrl) {
        initialDownloadUrl?.let { enqueueUrlDownload(it) }
    }

    LaunchedEffect(externalIntents, selectedTabId, settings.searchUrlTemplate) {
        externalIntents.collect { command ->
            if (command.download) {
                enqueueUrlDownload(command.url)
            } else {
                tab.input = command.url
                controller.load(command.url, settings.searchUrlTemplate)
                showSearch = false
                showBookmarks = false
                showHistory = false
                showDownloads = false
                showExtensions = false
                showTabs = false
                editingAddress = false
                message = null
            }
        }
    }

    LaunchedEffect(showDownloads, downloads) {
        while (showDownloads || downloads.any {
                it.downloadManagerId != null && (it.status == DownloadStatus.Running || it.status == DownloadStatus.Queued)
            }
        ) {
            downloadHandler.refreshSystemDownloads()
            delay(1000)
        }
    }

    BackHandler {
        when {
            extensionPopup != null -> app.extensions.closePopup()
            editingAddress -> {
                editingAddress = false
                message = null
            }
            showSearch -> showSearch = false
            showBookmarks -> showBookmarks = false
            showHistory -> showHistory = false
            showDownloads -> showDownloads = false
            showExtensions -> showExtensions = false
            showTabs -> showTabs = false
            pageState.canGoBack -> controller.goBack()
            else -> controller.goBack()
        }
    }

    LaunchedEffect(pageState.url, pageState.title) {
        if (pageState.url.isNotBlank() && !GeckoSessionController.isInternalUrl(pageState.url)) {
            profileStore.recordVisit(pageState.url, pageState.title, currentIconPath)
        }
    }

    LaunchedEffect(pageState.url) {
        currentIconPath = null
        if (pageState.url.isNotBlank() && !GeckoSessionController.isInternalUrl(pageState.url)) {
            val iconPath = faviconStore.resolveIconPath(pageState.url)
            if (pageState.url == controller.state.value.url) {
                currentIconPath = iconPath
                if (iconPath != null) {
                    tab.iconPath = iconPath
                    profileStore.recordVisit(pageState.url, controller.state.value.title, iconPath)
                    profileStore.updateBookmarkIcon(pageState.url, iconPath)
                }
            }
        }
    }

    LaunchedEffect(selectedTabId, installedExtensions) {
        runCatching { app.extensions.refreshMenuActions(controller.session) }
    }

    LaunchedEffect(extensionNewTabRequest) {
        extensionNewTabRequest?.let { request ->
            val newTab = BrowserTabRuntime.fromExtensionRequest(app, request)
            tabs.add(newTab)
            selectedTabId = newTab.id
            showTabs = false
            showSearch = false
            showBookmarks = false
            showHistory = false
            showDownloads = false
            showExtensions = false
            message = "Opened ${request.title}."
            app.extensions.consumeNewTabRequest()
        }
    }

    LaunchedEffect(pendingHyperRoute) {
        when (pendingHyperRoute) {
            null -> return@LaunchedEffect
            HyperRoute.Home -> {
                tab.input = GeckoSessionController.HOME_URL
                controller.loadHome()
            }
            HyperRoute.Search -> {
                controller.loadSearch()
            }
            HyperRoute.Settings -> {
                tab.input = GeckoSessionController.SETTINGS_URL
                controller.loadSettings()
            }
            HyperRoute.Apps -> {
                tab.input = GeckoSessionController.APPS_URL
                controller.loadApps()
            }
            HyperRoute.Bookmarks -> {
                tab.input = GeckoSessionController.BOOKMARKS_URL
                controller.loadBookmarks()
            }
            HyperRoute.History -> {
                tab.input = GeckoSessionController.HISTORY_URL
                controller.loadHistory()
            }
        }
        pendingHyperRoute = null
    }

    LaunchedEffect(pendingHyperCommand) {
        when (val command = pendingHyperCommand) {
            null -> return@LaunchedEffect
            is HyperCommand.Search.Submit -> {
                tab.input = command.query
                controller.load(command.query, settings.searchUrlTemplate)
            }
            is HyperCommand.Bookmarks.Open -> {
                tab.input = command.url
                controller.load(command.url)
            }
            is HyperCommand.Bookmarks.Remove -> {
                profileStore.removeBookmark(command.url)
            }
            is HyperCommand.Bookmarks.Edit -> {
                profileStore.editBookmark(command.oldUrl, command.title, command.url)
            }
            is HyperCommand.History.Open -> {
                tab.input = command.url
                controller.load(command.url)
            }
            is HyperCommand.History.Remove -> {
                profileStore.removeHistoryEntry(command.url)
            }
            HyperCommand.History.Clear -> {
                profileStore.clearHistory()
            }
            is HyperCommand.Apps.Open -> {
                if (command.id.isNotBlank()) {
                    context.startActivity(WebAppActivity.intent(context, command.id, true))
                }
            }
            is HyperCommand.Apps.Pin -> {
                scope.launch {
                    runCatching { app.webApps.pinToHome(command.id) }
                        .onSuccess { message = shortcutRequestMessage(it) }
                        .onFailure { message = it.message ?: "Shortcut failed." }
                }
            }
            is HyperCommand.Apps.Edit -> {
                scope.launch {
                    runCatching { app.webApps.update(command.id, command.name, command.startUrl) }
                        .onSuccess { message = if (it != null) "Updated ${it.name}." else "WebApp not found." }
                        .onFailure { message = it.message ?: "Update failed." }
                }
            }
            is HyperCommand.Apps.Delete -> {
                scope.launch {
                    runCatching { app.webApps.delete(command.id) }
                        .onSuccess { message = if (it) "WebApp deleted." else "WebApp not found." }
                        .onFailure { message = it.message ?: "Delete failed." }
                }
            }
            HyperCommand.Panel.Extensions -> showExtensions = true
        }
        pendingHyperCommand = null
    }

    DisposableEffect(Unit) {
        onDispose { tabs.forEach { it.controller.close(closeActivePlayback = false) } }
    }

    LaunchedEffect(message) {
        if (message != null) {
            delay(2400)
            message = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (showSearch) {
                SearchPage(
                    initialInput = if (onHomePage) "" else tab.input,
                    history = history,
                    bookmarks = bookmarks,
                    onCancel = { showSearch = false },
                    onGo = { value ->
                        tab.input = value
                        controller.load(value, settings.searchUrlTemplate)
                        showSearch = false
                        message = null
                    }
                )
            } else if (showBookmarks) {
                BookmarksPage(
                    bookmarks = bookmarks,
                    onBack = { showBookmarks = false },
                    onOpen = { url ->
                        tab.input = url
                        controller.load(url)
                        showBookmarks = false
                        message = null
                    },
                    onRemove = profileStore::removeBookmark
                )
            } else if (showHistory) {
                HistoryPage(
                    history = history,
                    onBack = { showHistory = false },
                    onOpen = { url ->
                        tab.input = url
                        controller.load(url)
                        showHistory = false
                        message = null
                    },
                    onRemove = profileStore::removeHistoryEntry,
                    onClear = profileStore::clearHistory
                )
            } else if (showDownloads) {
                DownloadsPage(
                    downloads = downloads,
                    onBack = { showDownloads = false },
                    onOpen = { entry ->
                        val openIntent = downloadHandler.openIntent(entry)
                        if (openIntent == null) {
                            message = "File is not ready."
                        } else {
                            runCatching { context.startActivity(openIntent) }
                                .onFailure { message = it.message ?: "No app can open this file." }
                        }
                    },
                    onRemove = { entry, deleteFile ->
                        scope.launch {
                            runCatching { downloadHandler.delete(entry, deleteFile) }
                                .onSuccess { message = "Download removed." }
                                .onFailure { message = it.message ?: "Unable to remove download." }
                        }
                    }
                )
            } else if (showExtensions) {
                ExtensionsPage(
                    query = extensionQuery,
                    installed = installedExtensions,
                    results = extensionResults,
                    message = extensionMessage,
                    installingAddonGuid = installingAddonGuid,
                    onQueryChange = { extensionQuery = it },
                    onBack = { showExtensions = false },
                    onSearch = {
                        scope.launch {
                            extensionMessage = "Searching AMO..."
                            runCatching { app.extensions.searchAndroidAddons(extensionQuery) }
                                .onSuccess { results ->
                                    extensionResults = results
                                    val installedMatches = results.count { result ->
                                        installedExtensions.any { it.guid == result.guid }
                                    }
                                    val installableCount = results.size - installedMatches
                                    extensionMessage = when {
                                        results.isEmpty() -> "No Android add-ons found."
                                        installableCount == 0 -> "All AMO matches are already installed."
                                        installedMatches > 0 -> "$installableCount add-ons found · $installedMatches already installed"
                                        else -> null
                                    }
                                }
                                .onFailure { extensionMessage = it.message ?: "AMO search failed." }
                        }
                    },
                    onInstall = { addon ->
                        scope.launch {
                            installingAddonGuid = addon.guid
                            runCatching {
                                app.extensions.downloadAndInstall(addon) { stage ->
                                    extensionMessage = stage
                                }
                            }
                                .onSuccess { extensionMessage = "Installed ${addon.name}." }
                                .onFailure { extensionMessage = it.message ?: "Extension install failed." }
                            installingAddonGuid = null
                        }
                    },
                    onToggleEnabled = { extension ->
                        scope.launch {
                            runCatching { app.extensions.setEnabled(extension.guid, !extension.enabled) }
                                .onFailure { extensionMessage = it.message ?: "Unable to update extension." }
                        }
                    },
                    onUninstall = { extension ->
                        scope.launch {
                            runCatching { app.extensions.uninstall(extension.guid) }
                                .onFailure { extensionMessage = it.message ?: "Unable to uninstall extension." }
                        }
                    }
                )
            } else if (showTabs) {
                TabTray(
                    tabs = tabs,
                    faviconStore = faviconStore,
                    selectedTabId = selectedTabId,
                    onBack = { showTabs = false },
                    onSelect = {
                        selectedTabId = it
                        showTabs = false
                    },
                    onClose = { id ->
                        val closing = tabs.firstOrNull { it.id == id } ?: return@TabTray
                        val oldIndex = tabs.indexOf(closing)
                        closing.controller.close()
                        tabs.remove(closing)
                        if (tabs.isEmpty()) {
                            val replacement = createBrowserTab(GeckoSessionController.HOME_URL)
                            tabs.add(replacement)
                            selectedTabId = replacement.id
                        } else if (selectedTabId == id) {
                            selectedTabId = tabs[oldIndex.coerceAtMost(tabs.lastIndex)].id
                        }
                    },
                    onNewTab = {
                        val newTab = createBrowserTab(GeckoSessionController.HOME_URL)
                        tabs.add(newTab)
                        selectedTabId = newTab.id
                        showTabs = false
                    }
                )
            } else if (inPictureInPicture) {
                BrowserContent(
                    controller = controller,
                    tabId = tab.id,
                    extensionPopup = null,
                    onClosePopup = app.extensions::closePopup,
                    modifier = Modifier.weight(1f)
                )
            } else if (!onSearchPage) {
                val toolbar = @Composable {
                    val currentPageUrl = pageState.url.ifBlank { tab.input }
                    val installedWebApp = if (GeckoSessionController.isInternalUrl(currentPageUrl)) {
                        null
                    } else {
                        webApps.firstOrNull { it.startUrl == currentPageUrl }
                    }
                    BrowserToolbar(
                        input = tab.input,
                        pageState = pageState,
                        tabCount = tabs.size,
                        bookmarked = !GeckoSessionController.isInternalUrl(pageState.url) &&
                            profileStore.isBookmarked(currentPageUrl),
                        webAppInstalled = installedWebApp != null,
                        installedExtensions = installedExtensions,
                        extensionActions = extensionActions,
                        toolbarPosition = settings.toolbarPosition,
                        editingAddress = editingAddress,
                        onEditingAddressChange = { editingAddress = it },
                        bookmarks = bookmarks,
                        history = history,
                        downloads = downloads,
                        onSubmitAddress = { query ->
                            tab.input = query
                            controller.load(query, settings.searchUrlTemplate)
                        },
                        onBack = controller::goBack,
                        onForward = controller::goForward,
                        onReload = controller::reload,
                        onShowTabs = {
                            controller.capturePixels { bitmap ->
                                bitmap?.let { tab.thumbnail = it }
                                showTabs = true
                            }
                        },
                        onNewTab = {
                            val newTab = createBrowserTab(GeckoSessionController.HOME_URL)
                            tabs.add(newTab)
                            selectedTabId = newTab.id
                            showTabs = false
                            message = null
                        },
                        onHome = {
                            tab.input = GeckoSessionController.HOME_URL
                            controller.loadHome()
                            message = null
                        },
                        onToggleBookmark = {
                            val url = pageState.url.ifBlank { tab.input }
                            profileStore.toggleBookmark(url, pageState.title, currentIconPath)
                        },
                        onShowSettings = {
                            tab.input = GeckoSessionController.SETTINGS_URL
                            controller.loadSettings()
                        },
                        onShowDownloads = { showDownloads = true },
                        onShowExtensions = { showExtensions = true },
                        onExtensionClick = { extension ->
                            scope.launch {
                                runCatching { app.extensions.clickMenuAction(extension.guid) }
                                    .onFailure { message = it.message ?: "Extension popup unavailable." }
                            }
                        },
                        onInstall = install@{
                            installedWebApp?.let { webApp ->
                                scope.launch {
                                    runCatching { app.webApps.delete(webApp.id) }
                                        .onSuccess { message = if (it) "Uninstalled ${webApp.name}." else "WebApp not found." }
                                        .onFailure { message = it.message ?: "Uninstall failed." }
                                }
                                return@install
                            }
                            if (GeckoSessionController.isInternalUrl(pageState.url)) {
                                message = "Open a web page before installing it as a WebApp."
                                return@install
                            }
                            scope.launch {
                                val title = pageState.title.ifBlank { tab.input }
                                val url = pageState.url.ifBlank { tab.input }
                                runCatching { app.webApps.installFromPage(title, url, iconPath = currentIconPath) }
                                    .onSuccess {
                                        message = "Installed ${it.webApp.name}. ${shortcutRequestMessage(it.shortcutRequest)}"
                                    }
                                    .onFailure { message = it.message ?: "Install failed." }
                            }
                        }
                    )
                }
                if (settings.toolbarPosition == BrowserSettings.TOOLBAR_POSITION_BOTTOM) {
                    BrowserContent(
                        controller = controller,
                        tabId = tab.id,
                        extensionPopup = extensionPopup,
                        onClosePopup = app.extensions::closePopup,
                        modifier = Modifier.weight(1f)
                    )
                    toolbar()
                } else {
                    toolbar()
                    BrowserContent(
                        controller = controller,
                        tabId = tab.id,
                        extensionPopup = extensionPopup,
                        onClosePopup = app.extensions::closePopup,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                BrowserContent(controller = controller, tabId = tab.id, extensionPopup = extensionPopup, onClosePopup = app.extensions::closePopup)
            }
        }
        linkContextMenu?.let { menu ->
            AlertDialog(
                onDismissRequest = { linkContextMenu = null },
                title = {
                    Text(
                        text = menu.label?.takeIf { it.isNotBlank() } ?: "链接",
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                text = {
                    Text(
                        text = menu.url,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                confirmButton = {
                    TextButton(onClick = { openLinkInBackgroundTab(menu.url) }) {
                        Text("在新标签页打开")
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(menu.url))
                                linkContextMenu = null
                                message = "链接已复制"
                            }
                        ) {
                            Text("复制链接")
                        }
                        TextButton(onClick = { linkContextMenu = null }) {
                            Text("取消")
                        }
                    }
                }
            )
        }
        BrowserTip(
            message = message,
            toolbarPosition = settings.toolbarPosition,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

private fun shortcutRequestMessage(result: PinnedShortcutRequestResult): String =
    when (result) {
        PinnedShortcutRequestResult.Requested -> "Tap Create shortcut in the system prompt."
        PinnedShortcutRequestResult.Unsupported -> "Home screen shortcuts are not supported by this launcher."
        PinnedShortcutRequestResult.Failed -> "Shortcut request failed."
        PinnedShortcutRequestResult.WebAppNotFound -> "WebApp not found."
    }

@Composable
private fun BrowserContent(
    controller: GeckoSessionController,
    tabId: String,
    extensionPopup: ExtensionPopupState?,
    onClosePopup: () -> Unit,
    modifier: Modifier = Modifier
) {
    val contentPageState by controller.state.collectAsState()
    Box(modifier = modifier.fillMaxSize()) {
        key(tabId) {
            GeckoBrowserView(controller = controller, modifier = Modifier.fillMaxSize())
        }
        TopPageLoadingProgressBar(
            loading = contentPageState.isLoading,
            progress = contentPageState.loadProgress,
            modifier = Modifier.align(Alignment.TopCenter)
        )
        extensionPopup?.let { popup ->
            ExtensionPopupOverlay(
                popup = popup,
                onClose = onClosePopup
            )
        }
    }
}

@Composable
private fun TopPageLoadingProgressBar(
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

private class BrowserTabRuntime private constructor(
    val id: String,
    val controller: GeckoSessionController,
    input: String
) {
    var input by mutableStateOf(input)
    var thumbnail by mutableStateOf<Bitmap?>(null)
    var iconPath by mutableStateOf<String?>(null)

    companion object {
        fun create(
            app: HyperBrowserApp,
            url: String,
            onHyperRoute: (HyperRoute) -> Unit = {},
            onHyperBridgeMessage: (JSONObject) -> JSONObject = { JSONObject().put("ok", false) },
            onLinkContextMenu: (String, String?) -> Unit = { _, _ -> },
            onDownload: (GeckoDownloadRequest) -> Unit = {}
        ): BrowserTabRuntime =
            BrowserTabRuntime(
                id = UUID.randomUUID().toString(),
                controller = GeckoSessionController(
                    context = app,
                    initialUrl = url,
                    onHyperRoute = onHyperRoute,
                    onHyperBridgeMessage = onHyperBridgeMessage,
                    onLinkContextMenu = onLinkContextMenu,
                    onDownload = onDownload,
                    mediaNotificationIntent = app.packageManager.getLaunchIntentForPackage(app.packageName)
                ),
                input = url
            )

        fun fromExtensionRequest(app: HyperBrowserApp, request: ExtensionNewTabRequest): BrowserTabRuntime =
            BrowserTabRuntime(
                id = UUID.randomUUID().toString(),
                controller = GeckoSessionController(
                    context = app,
                    initialUrl = request.url,
                    existingSession = request.session,
                    mediaNotificationIntent = app.packageManager.getLaunchIntentForPackage(app.packageName)
                ),
                input = request.url
            )
    }
}

private fun browserAddressText(url: String, input: String): String {
    return url.ifBlank { input.ifBlank { "搜索或输入网址" } }
}

private fun ok(data: JSONObject? = null): JSONObject {
    val response = JSONObject().put("ok", true)
    if (data != null) response.put("data", data)
    return response
}

private fun okData(data: JSONObject): JSONObject =
    JSONObject().put("ok", true).put("data", data)

private fun okItems(itemsJson: String): JSONObject =
    JSONObject().put("ok", true).put("itemsJson", itemsJson)

private fun BrowserSettings.toJson(): JSONObject =
    JSONObject()
        .put("searchEngineId", searchEngineId)
        .put("searchEngineName", searchEngineName)
        .put("customSearchUrl", customSearchUrl)
        .put("toolbarPosition", toolbarPosition)

private fun List<BrowserBookmark>.toBookmarksJsonString(faviconStore: FaviconRepository): String {
    val array = JSONArray()
    forEach { bookmark ->
        array.put(
            JSONObject()
                .put("title", bookmark.title)
                .put("url", bookmark.url)
                .put("createdAt", bookmark.createdAt)
                .put("iconDataUrl", faviconStore.iconDataUrl(bookmark.iconPath, bookmark.url))
        )
    }
    return array.toString()
}

private fun List<BrowserHistoryEntry>.toHistoryJsonString(faviconStore: FaviconRepository): String {
    val array = JSONArray()
    filterNot { GeckoSessionController.isInternalUrl(it.url) }.forEach { entry ->
        array.put(
            JSONObject()
                .put("title", entry.title)
                .put("url", entry.url)
                .put("visitedAt", entry.visitedAt)
                .put("iconDataUrl", faviconStore.iconDataUrl(entry.iconPath, entry.url))
        )
    }
    return array.toString()
}

private fun List<WebAppDefinition>.toWebAppsJsonString(app: HyperBrowserApp): String {
    val array = JSONArray()
    forEach { webApp ->
        array.put(
            JSONObject()
                .put("id", webApp.id)
                .put("name", webApp.name)
                .put("startUrl", webApp.startUrl)
                .put("scopeUrl", webApp.scopeUrl)
                .put("iconPath", webApp.iconPath)
                .put("iconDataUrl", app.webApps.iconDataUrl(webApp))
                .put("themeColor", webApp.themeColor)
                .put("displayMode", webApp.displayMode)
                .put("createdAt", webApp.createdAt)
                .put("lastOpenedAt", webApp.lastOpenedAt)
        )
    }
    return array.toString()
}

private fun searchSuggestionsJsonString(
    bookmarks: List<BrowserBookmark>,
    history: List<BrowserHistoryEntry>
): String {
    val seen = mutableSetOf<String>()
    val array = JSONArray()
    bookmarks.forEach { bookmark ->
        if (seen.add(bookmark.url)) {
            array.put(
                JSONObject()
                    .put("title", bookmark.title)
                    .put("url", bookmark.url)
                    .put("source", "bookmark")
            )
        }
    }
    history.filterNot { GeckoSessionController.isInternalUrl(it.url) }.forEach { entry ->
        if (seen.add(entry.url)) {
            array.put(
                JSONObject()
                    .put("title", entry.title)
                    .put("url", entry.url)
                    .put("source", "history")
            )
        }
    }
    return array.toString()
}

private data class ToolbarSuggestion(
    val title: String,
    val url: String,
    val source: String
)

@Composable
private fun ToolbarCurrentPagePanel(
    title: String,
    url: String,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Public,
                contentDescription = null,
                tint = Color(0xFF5F6368),
                modifier = Modifier.size(28.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title.ifBlank { url },
                    color = Color(0xFF202124),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = url,
                    color = Color(0xFF3F51B5),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onShare, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.Share, contentDescription = "Share", tint = Color(0xFF202124))
            }
            IconButton(onClick = onCopy, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy URL", tint = Color(0xFF202124))
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.Edit, contentDescription = "Edit URL", tint = Color(0xFF202124))
            }
        }
    }
}

@Composable
private fun ToolbarSuggestionPanel(
    suggestions: List<ToolbarSuggestion>,
    onOpen: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column {
            suggestions.forEachIndexed { index, suggestion ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(suggestion.url) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = if (suggestion.source == "书签") "★" else "◷",
                        color = Color(0xFF5F6368),
                        fontSize = 18.sp,
                        modifier = Modifier.width(24.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = suggestion.title.ifBlank { suggestion.url },
                            color = Color(0xFF202124),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = suggestion.url,
                            color = Color(0xFF6F737B),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = suggestion.source,
                        color = Color(0xFF6F737B),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                if (index != suggestions.lastIndex) {
                    HorizontalDivider(color = Color(0xFFE8EAED))
                }
            }
        }
    }
}

@Composable
private fun ToolbarSuggestionItems(
    suggestions: List<ToolbarSuggestion>,
    onOpen: (String) -> Unit
) {
    suggestions.forEach { suggestion ->
        Row(
            modifier = Modifier
                .width(320.dp)
                .clickable { onOpen(suggestion.url) }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = if (suggestion.source == "书签") "★" else "◷",
                color = Color(0xFF5F6368),
                fontSize = 18.sp,
                modifier = Modifier.width(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = suggestion.title.ifBlank { suggestion.url },
                    color = Color(0xFF202124),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = suggestion.url,
                    color = Color(0xFF6F737B),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = suggestion.source,
                color = Color(0xFF6F737B),
                style = MaterialTheme.typography.labelMedium
            )
        }
        if (suggestion != suggestions.last()) {
            HorizontalDivider(color = Color(0xFFE8EAED))
        }
    }
}

@Composable
private fun BrowserToolbar(
    input: String,
    pageState: GeckoPageState,
    tabCount: Int,
    bookmarked: Boolean,
    webAppInstalled: Boolean,
    installedExtensions: List<InstalledExtensionState>,
    extensionActions: Map<String, ExtensionMenuActionState>,
    toolbarPosition: String,
    editingAddress: Boolean,
    onEditingAddressChange: (Boolean) -> Unit,
    bookmarks: List<BrowserBookmark>,
    history: List<BrowserHistoryEntry>,
    downloads: List<BrowserDownloadEntry>,
    onSubmitAddress: (String) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onHome: () -> Unit,
    onShowTabs: () -> Unit,
    onNewTab: () -> Unit,
    onToggleBookmark: () -> Unit,
    onShowSettings: () -> Unit,
    onShowDownloads: () -> Unit,
    onShowExtensions: () -> Unit,
    onExtensionClick: (InstalledExtensionState) -> Unit,
    onInstall: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var extensionsExpanded by remember { mutableStateOf(false) }
    var addressDraft by remember { mutableStateOf(TextFieldValue(browserAddressText(pageState.url, input))) }
    val currentAddress = browserAddressText(pageState.url, input)
    val addressDraftText = addressDraft.text
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val addressFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val enabledExtensions = installedExtensions.filter { it.enabled }
    val addressSuggestions = remember(addressDraftText, bookmarks, history, editingAddress) {
        if (!editingAddress) {
            emptyList()
        } else {
            val needle = addressDraftText.trim().lowercase()
            if (needle.isBlank()) {
                emptyList()
            } else {
                (bookmarks.map { ToolbarSuggestion(it.title, it.url, "书签") } +
                    history.filterNot { GeckoSessionController.isInternalUrl(it.url) }
                        .map { ToolbarSuggestion(it.title, it.url, "历史") })
                    .distinctBy { it.url }
                    .filter {
                        it.title.lowercase().contains(needle) ||
                            it.url.lowercase().contains(needle)
                    }
                    .take(5)
            }
        }
    }
    val toolbarPadding = if (toolbarPosition == BrowserSettings.TOOLBAR_POSITION_BOTTOM) {
        Modifier.navigationBarsPadding()
    } else {
        Modifier
    }
    val toolbarImeOffset = if (toolbarPosition == BrowserSettings.TOOLBAR_POSITION_BOTTOM && editingAddress) {
        Modifier.offset { IntOffset(0, -imeBottomPx) }
    } else {
        Modifier
    }

    fun submitAddress(value: String = addressDraftText) {
        val query = value.trim()
        if (query.isBlank()) return
        onEditingAddressChange(false)
        keyboardController?.hide()
        onSubmitAddress(query)
    }

    fun startAddressEdit() {
        onEditingAddressChange(true)
        addressDraft = TextFieldValue("")
    }

    fun editCurrentAddress() {
        onEditingAddressChange(true)
        addressDraft = TextFieldValue(currentAddress, selection = TextRange(currentAddress.length))
    }

    fun cancelAddressEdit() {
        onEditingAddressChange(false)
        addressDraft = TextFieldValue(currentAddress)
        keyboardController?.hide()
    }

    LaunchedEffect(currentAddress, editingAddress) {
        if (!editingAddress) {
            addressDraft = TextFieldValue(currentAddress)
        }
    }

    LaunchedEffect(editingAddress) {
        if (editingAddress) {
            addressFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    val suggestionPanel: @Composable () -> Unit = {
        if (editingAddress) {
            if (addressDraftText.isBlank()) {
                ToolbarCurrentPagePanel(
                    title = pageState.title,
                    url = currentAddress,
                    onShare = {
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, currentAddress)
                        }
                        context.startActivity(Intent.createChooser(sendIntent, currentAddress))
                    },
                    onCopy = {
                        clipboardManager.setText(AnnotatedString(currentAddress))
                    },
                    onEdit = {
                        editCurrentAddress()
                        addressFocusRequester.requestFocus()
                    }
                )
            } else if (addressSuggestions.isNotEmpty()) {
                ToolbarSuggestionPanel(
                    suggestions = addressSuggestions,
                    onOpen = { url ->
                        addressDraft = TextFieldValue(url, selection = TextRange(url.length))
                        submitAddress(url)
                    }
                )
            }
        }
    }

    val toolbarRow: @Composable () -> Unit = {
        Row(
            modifier = Modifier.height(ChromeActionBarHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(onClick = onHome, modifier = Modifier.size(ChromeActionButtonSize)) {
                Text("⌂", fontSize = ChromeActionIconSize, color = Color(0xFF202124))
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(ChromeAddressBarHeight)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0xFFE9EAF1))
                    .clickable {
                        startAddressEdit()
                    }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AddressSecurityIndicator(
                    insecure = pageState.insecureHttp && !editingAddress,
                    modifier = Modifier.width(26.dp)
                )
                BasicTextField(
                    value = addressDraft,
                    onValueChange = {
                        addressDraft = it
                        if (!editingAddress) onEditingAddressChange(true)
                    },
                    singleLine = true,
                    enabled = editingAddress,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submitAddress() }),
                    textStyle = MaterialTheme.typography.titleMedium.copy(color = Color(0xFF202124)),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(addressFocusRequester)
                        .onFocusChanged { focusState ->
                            if (editingAddress && !focusState.isFocused) {
                                cancelAddressEdit()
                            }
                    },
                    decorationBox = { inner ->
                        if (addressDraftText.isBlank()) {
                            Text("搜索或输入网址", color = Color(0xFF6F737B), style = MaterialTheme.typography.titleMedium)
                        }
                        inner()
                    }
                )
                if (editingAddress && addressDraftText.isNotBlank()) {
                    Text(
                        "×",
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { addressDraft = TextFieldValue("") }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        fontSize = 24.sp,
                        color = Color(0xFF5F6368)
                    )
                }
            }
            IconButton(onClick = onNewTab, modifier = Modifier.size(ChromeActionButtonSize)) {
                Text("+", fontSize = ChromeActionIconSize, color = Color(0xFF202124))
            }
            IconButton(
                onClick = onShowTabs,
                modifier = Modifier
                    .size(ChromeActionButtonSize)
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Color.Transparent)
                        .borderForChromeTabCounter(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(tabCount.toString(), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF202124))
                }
            }
            Box {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(ChromeActionButtonSize)) {
                    Text("⋮", fontSize = ChromeActionIconSize)
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        BrowserMenuPanel(
                            bookmarked = bookmarked,
                            webAppInstalled = webAppInstalled,
                            enabledExtensions = enabledExtensions,
                            downloads = downloads,
                        installedExtensionCount = installedExtensions.size,
                        extensionActions = extensionActions,
                        extensionsExpanded = extensionsExpanded,
                        onExtensionsExpandedChange = { extensionsExpanded = it },
                        onNewTab = { showMenu = false; onNewTab() },
                        onBack = { showMenu = false; onBack() },
                        onForward = { showMenu = false; onForward() },
                        onReload = { showMenu = false; onReload() },
                        onToggleBookmark = { showMenu = false; onToggleBookmark() },
                        onShowSettings = { showMenu = false; onShowSettings() },
                        onShowDownloads = { showMenu = false; onShowDownloads() },
                        onShowExtensions = { showMenu = false; onShowExtensions() },
                        onExtensionClick = { showMenu = false; onExtensionClick(it) },
                        onInstall = { showMenu = false; onInstall() }
                    )
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(toolbarPadding)
            .then(toolbarImeOffset)
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (toolbarPosition == BrowserSettings.TOOLBAR_POSITION_BOTTOM) {
            suggestionPanel()
            toolbarRow()
        } else {
            toolbarRow()
            suggestionPanel()
        }
    }
}

@Composable
private fun AddressSecurityIndicator(
    insecure: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        if (insecure) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, MaterialTheme.colorScheme.error, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "!",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            Icon(
                imageVector = Icons.Outlined.Public,
                contentDescription = null,
                tint = Color(0xFF5F6368),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun BrowserTip(
    message: String?,
    toolbarPosition: String,
    modifier: Modifier = Modifier
) {
    if (message == null) return
    val bottomPadding = if (toolbarPosition == BrowserSettings.TOOLBAR_POSITION_BOTTOM) 72.dp else 24.dp
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 18.dp, end = 18.dp, bottom = bottomPadding),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0xE6202124))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BrowserMenuPanel(
    bookmarked: Boolean,
    webAppInstalled: Boolean,
    enabledExtensions: List<InstalledExtensionState>,
    downloads: List<BrowserDownloadEntry>,
    installedExtensionCount: Int,
    extensionActions: Map<String, ExtensionMenuActionState>,
    extensionsExpanded: Boolean,
    onExtensionsExpandedChange: (Boolean) -> Unit,
    onNewTab: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onToggleBookmark: () -> Unit,
    onShowSettings: () -> Unit,
    onShowDownloads: () -> Unit,
    onShowExtensions: () -> Unit,
    onExtensionClick: (InstalledExtensionState) -> Unit,
    onInstall: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(304.dp)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MenuNavRow(
            onBack = onBack,
            onForward = onForward,
            onReload = onReload
        )
        MenuGroupBox {
            BrowserMenuRow(label = "New tab", leadingIconVector = Icons.Outlined.Add, onClick = onNewTab)
            BrowserMenuRow(
                label = if (bookmarked) "Remove bookmark" else "Bookmark this page",
                leadingIconVector = if (bookmarked) Icons.Outlined.BookmarkRemove else Icons.Outlined.BookmarkAdd,
                onClick = onToggleBookmark
            )
            BrowserMenuRow(
                label = if (webAppInstalled) "Uninstall WebApp" else "Install as WebApp",
                leadingIconVector = if (webAppInstalled) Icons.Outlined.Delete else Icons.AutoMirrored.Outlined.AddToHomeScreen,
                onClick = onInstall
            )
        }
        MenuGroupBox {
            ExtensionsMenuRow(
                enabledCount = enabledExtensions.size,
                installedCount = installedExtensionCount,
                expanded = extensionsExpanded,
                onClick = { onExtensionsExpandedChange(!extensionsExpanded) }
            )
            if (extensionsExpanded) {
                if (enabledExtensions.isEmpty()) {
                    BrowserMenuRow(
                        label = "Discover more extensions",
                        leadingIconVector = Icons.Outlined.Add,
                        description = "Search Android add-ons",
                        indent = 28.dp,
                        onClick = onShowExtensions
                    )
                } else {
                    enabledExtensions.forEach { extension ->
                        val action = extensionActions[extension.guid]
                        BrowserMenuRow(
                            label = action?.title ?: extension.name,
                            leadingIcon = action?.icon,
                            leadingIconVector = Icons.Outlined.Extension,
                            description = action?.badgeText?.takeIf { it.isNotBlank() }
                                ?: extension.version,
                            trailing = if (action?.enabled == false) "Disabled" else "Settings",
                            indent = 28.dp,
                            onClick = { onExtensionClick(extension) },
                            onTrailingClick = onShowExtensions
                        )
                    }
                    BrowserMenuRow(
                        label = "Manage extensions",
                        leadingIconVector = Icons.Outlined.Tune,
                        description = "$installedExtensionCount installed",
                        indent = 28.dp,
                        onClick = onShowExtensions
                    )
                }
            }
        }
        MenuGroupBox {
            val activeDownloads = downloads.count { it.status == DownloadStatus.Running || it.status == DownloadStatus.Queued }
            BrowserMenuRow(
                label = "Downloads",
                leadingIconVector = Icons.Outlined.Download,
                description = if (activeDownloads > 0) {
                    "$activeDownloads downloading"
                } else {
                    "${downloads.size} files"
                },
                onClick = onShowDownloads
            )
            BrowserMenuRow(label = "Settings", leadingIconVector = Icons.Outlined.Settings, onClick = onShowSettings)
        }
    }
}

@Composable
private fun MenuGroupBox(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.clip(RoundedCornerShape(18.dp)),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        content()
    }
}

@Composable
private fun MenuNavRow(
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFFF1F3F8))
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        MenuNavButton(label = "Back", icon = "‹", onClick = onBack, modifier = Modifier.weight(1f))
        MenuNavButton(label = "Forward", icon = "›", onClick = onForward, modifier = Modifier.weight(1f))
        MenuNavButton(label = "Reload", icon = "↻", onClick = onReload, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun MenuNavButton(
    label: String,
    icon: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(icon, fontSize = 24.sp, color = Color(0xFF202124))
        Text(label, fontSize = 11.sp, color = Color(0xFF5F6368), maxLines = 1)
    }
}

@Composable
private fun ExtensionsMenuRow(
    enabledCount: Int,
    installedCount: Int,
    expanded: Boolean,
    onClick: () -> Unit
) {
    BrowserMenuRow(
        label = "Extensions",
        leadingIconVector = Icons.Outlined.Extension,
        description = when {
            enabledCount > 0 -> "$enabledCount enabled"
            installedCount > 0 -> "$installedCount installed, none enabled"
            else -> "No extensions enabled"
        },
        trailing = if (expanded) "⌃" else "$enabledCount  ˅",
        onClick = onClick
    )
}

@Composable
private fun BrowserMenuRow(
    label: String,
    leadingIcon: android.graphics.Bitmap? = null,
    leadingIconVector: ImageVector? = null,
    description: String? = null,
    trailing: String? = null,
    indent: Dp = 0.dp,
    onTrailingClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indent)
            .background(Color(0xFFF1F3F8))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            if (leadingIcon != null) {
                Image(
                    bitmap = leadingIcon.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            } else if (leadingIconVector != null) {
                Icon(
                    imageVector = leadingIconVector,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp),
                    tint = Color(0xFF202124)
                )
            } else {
                Spacer(modifier = Modifier.size(19.dp))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Color(0xFF202124), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            description?.let {
                Text(it, color = Color(0xFF5F6368), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.let {
            Text(
                it,
                color = Color(0xFF5F6368),
                fontSize = 12.sp,
                maxLines = 1,
                modifier = if (onTrailingClick != null) {
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onTrailingClick)
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                } else {
                    Modifier
                }
            )
        }
    }
}

@Composable
private fun ExtensionPopupOverlay(
    popup: ExtensionPopupState,
    onClose: () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(18.dp)
    ) {
        val density = LocalDensity.current
        val availableWidth = maxWidth
        val availableHeight = maxHeight
        val popupWidth = if (availableWidth <= 360.dp) availableWidth else availableWidth * 0.92f
        val expandedHeight = if (availableHeight <= 560.dp) availableHeight else availableHeight * 0.88f
        var collapsed by remember(popup.guid) { mutableStateOf(false) }
        val popupHeight = if (collapsed) ChromeActionBarHeight else expandedHeight
        val maxOffsetX = with(density) { (availableWidth - popupWidth).toPx().coerceAtLeast(0f) }
        val maxOffsetY = with(density) { (availableHeight - popupHeight).toPx().coerceAtLeast(0f) }
        var offsetX by remember(popup.guid) { mutableStateOf(maxOffsetX) }
        var offsetY by remember(popup.guid) { mutableStateOf(0f) }

        LaunchedEffect(maxOffsetX, maxOffsetY) {
            offsetX = offsetX.coerceIn(0f, maxOffsetX)
            offsetY = offsetY.coerceIn(0f, maxOffsetY)
        }

        Card(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .width(popupWidth)
                .height(popupHeight),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ChromeActionBarHeight)
                        .pointerInput(maxOffsetX, maxOffsetY, collapsed) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                offsetX = (offsetX + dragAmount.x).coerceIn(0f, maxOffsetX)
                                offsetY = (offsetY + dragAmount.y).coerceIn(0f, maxOffsetY)
                            }
                        }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        popup.title,
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    IconButton(
                        onClick = {
                            val nextCollapsed = !collapsed
                            collapsed = nextCollapsed
                            val nextHeight = if (nextCollapsed) ChromeActionBarHeight else expandedHeight
                            val nextMaxOffsetY = with(density) { (availableHeight - nextHeight).toPx().coerceAtLeast(0f) }
                            offsetY = offsetY.coerceIn(0f, nextMaxOffsetY)
                        },
                        modifier = Modifier.size(ChromeActionButtonSize)
                    ) {
                        Text(
                            if (collapsed) "□" else "−",
                            color = Color(0xFF202124),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = onClose, modifier = Modifier.size(ChromeActionButtonSize)) {
                        Text(
                            "×",
                            color = Color(0xFF202124),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                if (!collapsed) {
                    HorizontalDivider(color = Color(0xFFE0E3EA))
                    GeckoSessionView(
                        session = popup.session,
                        modifier = Modifier.fillMaxSize(),
                        viewBackend = GeckoView.BACKEND_TEXTURE_VIEW
                    )
                }
            }
        }
    }
}

private fun Modifier.borderForChromeTabCounter(): Modifier =
    this.border(3.dp, Color(0xFF202124), RoundedCornerShape(9.dp))

@Composable
private fun SearchPage(
    initialInput: String,
    history: List<BrowserHistoryEntry>,
    bookmarks: List<BrowserBookmark>,
    onCancel: () -> Unit,
    onGo: (String) -> Unit
) {
    var query by remember { mutableStateOf(initialInput) }
    val quick = listOf(
        "Google" to "https://google.com",
        "Bilibili" to "https://m.bilibili.com",
        "GitHub" to "https://github.com",
        "AMO Android" to "https://addons.mozilla.org/android/",
        "NeverSSL" to "http://neverssl.com"
    )
    val matches = remember(query, history, bookmarks) {
        val needle = query.trim().lowercase()
        (bookmarks.map { it.title to it.url } + history.map { it.title to it.url })
            .distinctBy { it.second }
            .filter { needle.isBlank() || it.first.lowercase().contains(needle) || it.second.lowercase().contains(needle) }
            .take(12)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF7F8FC))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.height(ChromeActionBarHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = onCancel, modifier = Modifier.size(ChromeActionButtonSize)) {
                Text("‹", fontSize = ChromeActionIconSize, color = Color(0xFF202124))
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(ChromeActionButtonSize)
                    .clip(RoundedCornerShape(26.dp))
                    .background(Color(0xFFE7E9F1))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium.copy(color = Color(0xFF202124)),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp),
                    decorationBox = { inner ->
                        if (query.isBlank()) {
                            Text("搜索或输入网址", color = Color(0xFF6F737B), style = MaterialTheme.typography.titleMedium)
                        }
                        inner()
                    }
                )
                if (query.isNotBlank()) {
                    Text(
                        "×",
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { query = "" }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        fontSize = 24.sp,
                        color = Color(0xFF5F6368)
                    )
                }
            }
            TextButton(onClick = { onGo(query) }) { Text("Go", fontWeight = FontWeight.Bold) }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ChromeQuickChip("AI 模式") { onGo("https://google.com/search?q=AI") }
            ChromeQuickChip("无痕模式") { }
        }

        Text("快捷访问", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFF3C4043))
        quick.forEach { item ->
            SuggestionRow(title = item.first, url = item.second, leading = "↗", chrome = true) { onGo(item.second) }
        }

        Text("书签和历史记录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFF3C4043))
        if (matches.isEmpty()) {
            Text("没有匹配记录", color = Color(0xFF6F737B))
        } else {
            matches.forEach { item ->
                SuggestionRow(title = item.first, url = item.second, leading = "◷", chrome = true) { onGo(item.second) }
            }
        }
    }
}

@Composable
private fun ChromeQuickChip(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(54.dp)
            .clip(RoundedCornerShape(27.dp))
            .background(Color(0xFFE1E4EC))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(if (label.contains("无痕")) "⌂" else "✦", fontSize = 24.sp, color = Color(0xFF202124))
        Text(label, fontWeight = FontWeight.Bold, color = Color(0xFF202124))
    }
}

@Composable
private fun SuggestionRow(title: String, url: String, leading: String, chrome: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(if (chrome) 0.dp else 16.dp))
            .clickable(onClick = onClick)
            .background(if (chrome) Color.Transparent else Color.White)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color(0xFFE8EAED)),
            contentAlignment = Alignment.Center
        ) {
            Text(leading, fontSize = 20.sp, color = Color(0xFF5F6368))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color(0xFF202124))
            Text(url, color = Color(0xFF5F6368), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
        }
    }
}

@Composable
private fun BookmarksPage(
    bookmarks: List<BrowserBookmark>,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    BrowserLibraryPage(
        title = "书签",
        emptyTitle = "没有书签",
        emptyBody = "打开网页后，从菜单里点 Bookmark，就会保存到这里。",
        items = bookmarks,
        onBack = onBack,
        onOpen = { onOpen(it.url) },
        onRemove = { onRemove(it.url) },
        itemTitle = { it.title },
        itemUrl = { it.url },
        itemMeta = { "已保存" },
        leading = "★",
        action = null
    )
}

@Composable
private fun HistoryPage(
    history: List<BrowserHistoryEntry>,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit
) {
    BrowserLibraryPage(
        title = "历史记录",
        emptyTitle = "没有历史记录",
        emptyBody = "你访问过的页面会显示在这里，也会出现在地址栏建议里。",
        items = history,
        onBack = onBack,
        onOpen = { onOpen(it.url) },
        onRemove = { onRemove(it.url) },
        itemTitle = { it.title },
        itemUrl = { it.url },
        itemMeta = { formatVisitTime(it.visitedAt) },
        leading = "◷",
        action = if (history.isEmpty()) null else "清空" to onClear
    )
}

@Composable
private fun DownloadsPage(
    downloads: List<BrowserDownloadEntry>,
    onBack: () -> Unit,
    onOpen: (BrowserDownloadEntry) -> Unit,
    onRemove: (BrowserDownloadEntry, Boolean) -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var pendingDelete by remember { mutableStateOf<BrowserDownloadEntry?>(null) }
    var deleteFile by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF7F8FC))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(ChromeActionBarHeight)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(ChromeActionButtonSize)) {
                Text("‹", fontSize = ChromeActionIconSize, color = Color(0xFF202124))
            }
            Text(
                "下载内容",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF202124)
            )
        }
        HorizontalDivider(color = Color(0xFFDADCE3))

        if (downloads.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(74.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE1E4EC)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(34.dp), tint = Color(0xFF5F6368))
                    }
                    Text("还没有下载内容", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("下载过的文件会显示在这里。", color = Color(0xFF5F6368))
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(downloads, key = { it.id }) { entry ->
                    DownloadRow(
                        entry = entry,
                        onOpen = { onOpen(entry) },
                        onCopyUrl = {
                            clipboardManager.setText(AnnotatedString(entry.sourceUrl))
                            Toast.makeText(context, "已复制下载地址", Toast.LENGTH_SHORT).show()
                        },
                        onRemove = {
                            pendingDelete = entry
                            deleteFile = true
                        }
                    )
                    HorizontalDivider(color = Color(0xFFE8EAED))
                }
                item { Spacer(modifier = Modifier.height(36.dp)) }
            }
        }
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除下载") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("是否删除 ${entry.name}？")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { deleteFile = !deleteFile },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Checkbox(checked = deleteFile, onCheckedChange = { deleteFile = it })
                        Text("同时删除文件")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemove(entry, deleteFile)
                        pendingDelete = null
                    }
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadRow(
    entry: BrowserDownloadEntry,
    onOpen: () -> Unit,
    onCopyUrl: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpen,
                onLongClick = onCopyUrl
            )
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(Color(0xFFE8EAED)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(22.dp), tint = Color(0xFF5F6368))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entry.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color(0xFF202124))
            Text(entry.sourceUrl, color = Color(0xFF5F6368), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
            if (entry.status == DownloadStatus.Running || entry.status == DownloadStatus.Queued) {
                if (entry.totalBytes > 0L) {
                    LinearProgressIndicator(
                        progress = (entry.bytesDownloaded.toFloat() / entry.totalBytes.toFloat()).coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            Text(
                text = downloadMeta(entry),
                color = Color(0xFF6F737B),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Outlined.Delete, contentDescription = "Remove download")
        }
    }
}

@Composable
private fun <T> BrowserLibraryPage(
    title: String,
    emptyTitle: String,
    emptyBody: String,
    items: List<T>,
    onBack: () -> Unit,
    onOpen: (T) -> Unit,
    onRemove: (T) -> Unit,
    itemTitle: (T) -> String,
    itemUrl: (T) -> String,
    itemMeta: (T) -> String,
    leading: String,
    action: (Pair<String, () -> Unit>)?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF7F8FC))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(ChromeActionBarHeight)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(ChromeActionButtonSize)) {
                Text("‹", fontSize = ChromeActionIconSize, color = Color(0xFF202124))
            }
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF202124)
            )
            action?.let { (label, callback) ->
                TextButton(onClick = callback) { Text(label) }
            }
        }
        HorizontalDivider(color = Color(0xFFDADCE3))

        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(74.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE1E4EC)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(leading, fontSize = 36.sp, color = Color(0xFF5F6368))
                    }
                    Text(emptyTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(emptyBody, color = Color(0xFF5F6368))
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(items) { item ->
                    LibraryRow(
                        title = itemTitle(item),
                        url = itemUrl(item),
                        meta = itemMeta(item),
                        leading = leading,
                        onOpen = { onOpen(item) },
                        onRemove = { onRemove(item) }
                    )
                }
                item { Spacer(modifier = Modifier.height(36.dp)) }
            }
        }
    }
}

@Composable
private fun LibraryRow(
    title: String,
    url: String,
    meta: String,
    leading: String,
    onOpen: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(Color(0xFFE8EAED)),
            contentAlignment = Alignment.Center
        ) {
            Text(leading, color = Color(0xFF5F6368), fontSize = 20.sp)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title.ifBlank { url },
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Color(0xFF202124)
            )
            Text(url, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color(0xFF5F6368), fontSize = 13.sp)
            Text(meta, color = Color(0xFF80868B), fontSize = 12.sp)
        }
        TextButton(onClick = onRemove, shape = RectangleShape) {
            Text("×", fontSize = 30.sp, color = Color(0xFF5F6368))
        }
    }
}

private fun formatVisitTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return formatter.format(Date(timestamp))
}

private fun downloadMeta(entry: BrowserDownloadEntry): String {
    val status = when (entry.status) {
        DownloadStatus.Queued -> "Queued"
        DownloadStatus.Running -> "Downloading"
        DownloadStatus.Completed -> "Complete"
        DownloadStatus.Failed -> "Failed"
    }
    val size = when {
        entry.totalBytes > 0L -> "${formatBytes(entry.bytesDownloaded)} / ${formatBytes(entry.totalBytes)}"
        entry.bytesDownloaded > 0L -> formatBytes(entry.bytesDownloaded)
        else -> "Unknown size"
    }
    val time = formatVisitTime(entry.completedAt ?: entry.createdAt)
    val error = entry.error?.takeIf { it.isNotBlank() }
    return listOfNotNull(status, size, time, error).joinToString(" · ")
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 0L) return "Unknown"
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    return if (unitIndex == 0) {
        "${bytes} B"
    } else {
        String.format(Locale.getDefault(), "%.1f %s", value, units[unitIndex])
    }
}

@Composable
private fun ExtensionsPage(
    query: String,
    installed: List<InstalledExtensionState>,
    results: List<AmoAddonListing>,
    message: String?,
    installingAddonGuid: String?,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onInstall: (AmoAddonListing) -> Unit,
    onToggleEnabled: (InstalledExtensionState) -> Unit,
    onUninstall: (InstalledExtensionState) -> Unit
) {
    val installedGuids = installed.map { it.guid }.toSet()
    val installedAmoListings = results.associateBy { it.guid }
    val installableResults = results.filterNot { it.guid in installedGuids }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF7F8FC)),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ChromeActionBarHeight)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(ChromeActionButtonSize)) {
                    Text("‹", fontSize = ChromeActionIconSize, color = Color(0xFF202124))
                }
                Text(
                    "扩展",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF202124)
                )
            }
            HorizontalDivider(color = Color(0xFFDADCE3))
        }
        item {
            ExtensionSummaryCard(
                installedCount = installed.size,
                enabledCount = installed.count { it.enabled },
                onSearch = onSearch
            )
        }
        item {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Find more add-ons", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .clip(RoundedCornerShape(26.dp))
                            .background(Color(0xFFE7E9F1))
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicTextField(
                            value = query,
                            onValueChange = onQueryChange,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.titleMedium.copy(color = Color(0xFF202124)),
                            modifier = Modifier.weight(1f),
                            decorationBox = { inner ->
                                if (query.isBlank()) {
                                    Text("搜索扩展", color = Color(0xFF6F737B), style = MaterialTheme.typography.titleMedium)
                                }
                                inner()
                            }
                        )
                    }
                    Button(onClick = onSearch) { Text("Search") }
                }
                message?.let { Text(it, color = Color(0xFF126D6A)) }
            }
        }
        item {
            Text(
                "Installed add-ons",
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        if (installed.isEmpty()) {
            item {
                Text(
                    "还没有安装扩展。",
                    modifier = Modifier.padding(horizontal = 18.dp),
                    color = Color(0xFF5F6368)
                )
            }
        } else {
            items(installed, key = { "installed:${it.guid}" }) { extension ->
                InstalledExtensionRow(
                    extension = extension,
                    amoListing = installedAmoListings[extension.guid],
                    onToggle = { onToggleEnabled(extension) },
                    onUninstall = { onUninstall(extension) }
                )
            }
        }
        if (installableResults.isNotEmpty()) {
            item {
                Text(
                    "Recommended from AMO",
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            items(installableResults, key = { "result:${it.guid}" }) { addon ->
                AddonResultRow(
                    addon = addon,
                    installing = installingAddonGuid == addon.guid,
                    installed = installed.any { it.guid == addon.guid },
                    onInstall = { onInstall(addon) }
                )
            }
        }
        item {
            Text(
                "Search uses the Android AMO catalog. Installed add-ons appear in the browser menu like Iceraven's extensions section.",
                modifier = Modifier.padding(horizontal = 18.dp),
                color = Color(0xFF5F6368),
                style = MaterialTheme.typography.bodySmall
            )
        }
        item { Spacer(modifier = Modifier.height(36.dp)) }
    }
}

@Composable
private fun ExtensionSummaryCard(
    installedCount: Int,
    enabledCount: Int,
    onSearch: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE8EAED)),
                contentAlignment = Alignment.Center
            ) {
                Text("E", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Color(0xFF202124))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Extensions", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(
                    "$enabledCount enabled · $installedCount installed",
                    color = Color(0xFF5F6368),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            TextButton(onClick = onSearch) {
                Text("Refresh")
            }
        }
    }
}

@Composable
private fun InstalledExtensionRow(
    extension: InstalledExtensionState,
    amoListing: AmoAddonListing?,
    onToggle: () -> Unit,
    onUninstall: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE8EAED)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("🧩", fontSize = 20.sp)
                }
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(extension.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${extension.version} · ${if (extension.enabled) "Enabled" else "Disabled"}", color = Color(0xFF5F6368))
                }
            }
            amoListing?.let { listing ->
                val updateAvailable = listing.version != extension.version
                Text(
                    if (updateAvailable) {
                        "AMO update available: ${listing.version}"
                    } else {
                        "AMO latest: ${listing.version}"
                    },
                    color = if (updateAvailable) Color(0xFF126D6A) else Color(0xFF5F6368),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (updateAvailable) FontWeight.SemiBold else FontWeight.Normal
                )
            }
            if (extension.permissionsSnapshot.isNotBlank()) {
                Text(
                    extension.permissionsSnapshot.lines().take(3).joinToString(", "),
                    color = Color(0xFF5F6368),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onToggle) {
                    Text(if (extension.enabled) "Disable" else "Enable")
                }
                TextButton(onClick = onUninstall) { Text("Uninstall") }
            }
        }
    }
}

@Composable
private fun AddonResultRow(
    addon: AmoAddonListing,
    installing: Boolean,
    installed: Boolean,
    onInstall: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE8EAED)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("🧩", fontSize = 20.sp)
                }
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(addon.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Version ${addon.version} · ${addon.userCount} users", color = Color(0xFF5F6368))
                }
            }
            Text(
                if (addon.permissions.isEmpty()) "No declared permissions" else addon.permissions.take(5).joinToString(", "),
                color = Color(0xFF5F6368),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Button(
                onClick = onInstall,
                enabled = !installing && !installed
            ) {
                Text(
                    when {
                        installed -> "Installed"
                        installing -> "Installing..."
                        else -> "Add"
                    }
                )
            }
        }
    }
}

@Composable
private fun TabTray(
    tabs: List<BrowserTabRuntime>,
    faviconStore: FaviconRepository,
    selectedTabId: String,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNewTab: () -> Unit
) {
    var mode by remember { mutableStateOf(TabTrayMode.Card) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF4F5FA))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ChromeTabHeader(
                count = tabs.size,
                mode = mode,
                onBack = onBack,
                onModeChange = { mode = it },
                onNewTab = onNewTab
            )
            if (mode == TabTrayMode.List) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(tabs, key = { it.id }) { tab ->
                        val pageState by tab.controller.state.collectAsState()
                        ChromeTabListRow(
                            tab = tab,
                            pageState = pageState,
                            faviconStore = faviconStore,
                            selected = tab.id == selectedTabId,
                            onSelect = { onSelect(tab.id) },
                            onClose = { onClose(tab.id) }
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    items(tabs, key = { it.id }) { tab ->
                        val pageState by tab.controller.state.collectAsState()
                        ChromeTabCard(
                            tab = tab,
                            pageState = pageState,
                            faviconStore = faviconStore,
                            selected = tab.id == selectedTabId,
                            onSelect = { onSelect(tab.id) },
                            onClose = { onClose(tab.id) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(302.dp)
                        )
                    }
                }
            }
        }
    }
}

private enum class TabTrayMode {
    Card,
    List
}

@Composable
private fun ChromeTabHeader(
    count: Int,
    mode: TabTrayMode,
    onBack: () -> Unit,
    onModeChange: (TabTrayMode) -> Unit,
    onNewTab: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ChromeActionBarHeight)
                .padding(horizontal = 18.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(ChromeActionButtonSize)
            ) {
                Text("‹", fontSize = ChromeActionIconSize, color = Color(0xFF202124))
            }
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .height(ChromeActionBarHeight)
                    .clip(RoundedCornerShape(34.dp))
                    .background(Color(0xFFE6E8F0))
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(if (mode == TabTrayMode.Card) Color(0xFFF8F9FE) else Color.Transparent)
                        .clickable { onModeChange(TabTrayMode.Card) },
                    contentAlignment = Alignment.Center
                ) {
                    Text("▣", fontSize = ChromeActionIconSize, color = Color(0xFF202124))
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(if (mode == TabTrayMode.List) Color(0xFFF8F9FE) else Color.Transparent)
                        .clickable { onModeChange(TabTrayMode.List) },
                    contentAlignment = Alignment.Center
                ) {
                    Text("≡", fontSize = ChromeActionIconSize, color = Color(0xFF202124))
                }
            }
            IconButton(
                onClick = onNewTab,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(ChromeActionButtonSize)
            ) {
                Text("+", fontSize = ChromeActionIconSize, color = Color(0xFF202124))
            }
        }
        HorizontalDivider(color = Color(0xFFDADCE3))
    }
}

@Composable
private fun ChromeTabCard(
    tab: BrowserTabRuntime,
    pageState: GeckoPageState,
    faviconStore: FaviconRepository,
    selected: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFF5669A6) else Color(0xFFE0E2EA)),
        border = null,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TabFavicon(
                    iconPath = tab.iconPath,
                    pageUrl = pageState.url.ifBlank { tab.input },
                    fallbackLabel = pageState.title.ifBlank { pageState.url.ifBlank { tab.input } },
                    faviconStore = faviconStore,
                    selected = selected,
                    size = 30.dp
                )
                Text(
                    pageState.title.ifBlank { "打开新的标签页" },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .clickable { onSelect() },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    lineHeight = 16.sp,
                    color = if (selected) Color.White else Color(0xFF202124)
                )
                IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                    Text("×", fontSize = 28.sp, lineHeight = 28.sp, color = if (selected) Color.White else Color(0xFF202124))
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .clickable { onSelect() },
                contentAlignment = Alignment.TopStart
            ) {
                val thumbnail = tab.thumbnail
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail.asImageBitmap(),
                        contentDescription = null,
                        alignment = Alignment.TopCenter,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        horizontalAlignment = Alignment.Start,
                    ) {
                        Text(
                            pageState.title.ifBlank { "New tab" },
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            pageState.url.ifBlank { tab.input },
                            color = Color(0xFF6F737B),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChromeTabListRow(
    tab: BrowserTabRuntime,
    pageState: GeckoPageState,
    faviconStore: FaviconRepository,
    selected: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(86.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) Color(0xFF5669A6) else Color.White)
            .clickable(onClick = onSelect)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TabFavicon(
            iconPath = tab.iconPath,
            pageUrl = pageState.url.ifBlank { tab.input },
            fallbackLabel = pageState.title.ifBlank { pageState.url.ifBlank { tab.input } },
            faviconStore = faviconStore,
            selected = selected,
            size = 42.dp
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                pageState.title.ifBlank { "New tab" },
                color = if (selected) Color.White else Color(0xFF202124),
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                pageState.url.ifBlank { tab.input },
                color = if (selected) Color(0xFFE8EAED) else Color(0xFF6F737B),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onClose, modifier = Modifier.size(44.dp)) {
            Text("×", fontSize = 28.sp, lineHeight = 28.sp, color = if (selected) Color.White else Color(0xFF202124))
        }
    }
}

@Composable
private fun TabFavicon(
    iconPath: String?,
    pageUrl: String,
    fallbackLabel: String,
    faviconStore: FaviconRepository,
    selected: Boolean,
    size: Dp
) {
    val iconBitmap = remember(iconPath, pageUrl) {
        val path = iconPath ?: faviconStore.cachedIconPath(pageUrl)
        path?.let { android.graphics.BitmapFactory.decodeFile(it) }
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (selected) Color.White else Color(0xFFE8EAED)),
        contentAlignment = Alignment.Center
    ) {
        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size((size.value * 0.68f).dp)
                    .clip(RoundedCornerShape(4.dp))
            )
        } else {
            Text(
                fallbackLabel.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                fontSize = (size.value * 0.48f).sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF4285F4)
            )
        }
    }
}
