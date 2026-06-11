package com.dadigua.hyperbrowser.ui.browser

import android.Manifest
import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Rational
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboard
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.dadigua.hyperbrowser.HyperBrowserApp
import com.dadigua.hyperbrowser.browser.BrowserDownloadEntry
import com.dadigua.hyperbrowser.browser.DownloadHandler
import com.dadigua.hyperbrowser.browser.DownloadStatus
import com.dadigua.hyperbrowser.browser.DownloadStore
import com.dadigua.hyperbrowser.browser.FaviconRepository
import com.dadigua.hyperbrowser.browser.BrowserProfileStore
import com.dadigua.hyperbrowser.browser.BrowserSettings
import com.dadigua.hyperbrowser.browser.BrowserMediaNotificationController
import com.dadigua.hyperbrowser.browser.SavedBrowserTabs
import com.dadigua.hyperbrowser.extensions.AmoAddonListing
import com.dadigua.hyperbrowser.gecko.GeckoContextMenuTarget
import com.dadigua.hyperbrowser.gecko.GeckoDownloadRequest
import com.dadigua.hyperbrowser.gecko.GeckoRuntimeProvider
import com.dadigua.hyperbrowser.gecko.GeckoSessionController
import com.dadigua.hyperbrowser.gecko.HyperCommand
import com.dadigua.hyperbrowser.gecko.HyperRoute
import com.dadigua.hyperbrowser.ui.theme.HyperBrowserTheme
import com.dadigua.hyperbrowser.ui.webapp.WebAppActivity
import com.dadigua.hyperbrowser.update.AppUpdateManager
import com.dadigua.hyperbrowser.update.AvailableUpdate
import com.dadigua.hyperbrowser.update.UpdateDownloadState
import com.dadigua.hyperbrowser.update.UpdateSettingsStore
import com.dadigua.hyperbrowser.webapp.PinnedShortcutRequestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.StorageController
import java.util.UUID

class BrowserActivity : ComponentActivity() {
    private val externalIntents = MutableSharedFlow<ExternalBrowserIntent>(extraBufferCapacity = 1)
    private var inPictureInPicture by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inPictureInPicture = isInPictureInPictureMode
        val initialIntent = intent.toExternalBrowserIntent()
        val initialUrl = if (initialIntent?.download == false && initialIntent.url != null) {
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
                        initialShowDownloads = initialIntent?.showDownloads == true,
                        initialSelectTabId = initialIntent?.selectTabId,
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

    override fun onResume() {
        super.onResume()
        BrowserMediaNotificationController.get(this).cancelBackgroundPlaybackResume("foreground")
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        BrowserMediaNotificationController.get(this).allowBackgroundPlaybackResume()
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
        const val EXTRA_SHOW_DOWNLOADS = "extra_show_downloads"
        const val EXTRA_SELECT_TAB_ID = "extra_select_tab_id"
        const val EXTRA_OPEN_IN_NEW_TAB = "extra_open_in_new_tab"

        fun intent(context: Context, url: String): Intent =
            Intent(context, BrowserActivity::class.java).putExtra(EXTRA_URL, url)

        fun newTabIntent(context: Context, url: String): Intent =
            Intent(context, BrowserActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_OPEN_IN_NEW_TAB, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        fun selectTabIntent(context: Context, tabId: String): Intent =
            Intent(context, BrowserActivity::class.java)
                .putExtra(EXTRA_SELECT_TAB_ID, tabId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        fun downloadsIntent(context: Context): Intent =
            Intent(context, BrowserActivity::class.java)
                .putExtra(EXTRA_SHOW_DOWNLOADS, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

private enum class BrowserPanel {
    None,
    Search,
    Bookmarks,
    History,
    Downloads,
    Extensions,
    Tabs
}

private const val TAB_THUMBNAIL_PAGE_STOP_REFRESH_DELAY_MS = 700L

@Composable
private fun BrowserScreen(
    app: HyperBrowserApp,
    initialUrl: String,
    initialDownloadUrl: String?,
    initialShowDownloads: Boolean,
    initialSelectTabId: String?,
    externalIntents: MutableSharedFlow<ExternalBrowserIntent>,
    inPictureInPicture: Boolean
) {
    var pendingHyperRoute by remember { mutableStateOf<HyperRoute?>(null) }
    var pendingHyperCommand by remember { mutableStateOf<HyperCommand?>(null) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val clipboard = LocalClipboard.current
    val profileStore = remember { BrowserProfileStore(app) }
    val faviconStore = remember { FaviconRepository(app) }
    val downloadStore = remember { DownloadStore(app) }
    val downloadHandler = remember { DownloadHandler(app, downloadStore) }
    val updateManager = remember { AppUpdateManager(app, UpdateSettingsStore(app)) }
    val scope = rememberCoroutineScope()
    val thumbnailRefreshRequests = remember { MutableSharedFlow<String>(extraBufferCapacity = 16) }
    var message by remember { mutableStateOf<String?>(null) }
    var checkedUpdate by remember { mutableStateOf<AvailableUpdate?>(null) }
    var updateDownloadState by remember { mutableStateOf(UpdateDownloadState.idle()) }
    var updateDownloadEntry by remember { mutableStateOf<BrowserDownloadEntry?>(null) }
    var updateInstallInFlight by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    fun requestDownloadNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !downloadHandler.canPostNotifications()) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun enqueueUrlDownload(url: String) {
        requestDownloadNotificationsIfNeeded()
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
                .onSuccess { message = "Download queued: ${it.name}" }
                .onFailure { message = it.message ?: "Download failed." }
        }
    }

    fun beginUpdateInstall(update: AvailableUpdate): UpdateDownloadState {
        if (updateInstallInFlight && updateDownloadState.versionCode == update.versionCode) {
            return updateDownloadState
        }

        if (!updateManager.canInstallPackages()) {
            updateDownloadState = UpdateDownloadState(
                status = UpdateDownloadState.STATUS_PERMISSION_REQUIRED,
                versionCode = update.versionCode,
                versionName = update.versionName,
                totalBytes = update.asset.sizeBytes,
                message = "请先允许 Hyper Browser 安装未知应用。"
            )
            message = updateDownloadState.message
            runCatching { context.startActivity(updateManager.installPermissionIntent()) }
                .onFailure { message = it.message ?: "无法打开安装权限设置。" }
            return updateDownloadState
        }

        updateInstallInFlight = true
        updateDownloadState = UpdateDownloadState(
            status = UpdateDownloadState.STATUS_PREPARING,
            versionCode = update.versionCode,
            versionName = update.versionName,
            totalBytes = update.asset.sizeBytes,
            message = "正在准备更新..."
        )
        requestDownloadNotificationsIfNeeded()
        message = "已开始下载 ${update.versionName}。"

        (context as BrowserActivity).lifecycleScope.launch {
            runCatching {
                updateManager.startOrResumeDownload(update)
            }
                .onSuccess { state ->
                    updateDownloadState = state
                    if (state.status == UpdateDownloadState.STATUS_READY) {
                        message = state.message
                        runCatching { updateManager.createInstallIntentIfReady(update) }
                            .onSuccess { intent ->
                                if (intent != null) {
                                    runCatching { context.startActivity(intent) }
                                        .onFailure { message = it.message ?: "无法打开安装器。" }
                                }
                            }
                            .onFailure { message = it.message ?: "无法打开安装器。" }
                    }
                }
                .onFailure { throwable ->
                    val error = throwable.message ?: "更新下载失败。"
                    updateDownloadState = UpdateDownloadState(
                        status = UpdateDownloadState.STATUS_ERROR,
                        versionCode = update.versionCode,
                        versionName = update.versionName,
                        totalBytes = update.asset.sizeBytes,
                        message = error
                    )
                    updateManager.notifyUpdateError(update, error)
                    message = error
                }
            updateInstallInFlight = false
        }

        return updateDownloadState
    }

    fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun openBatteryOptimizationSettings(): Boolean {
        val packageUri = Uri.parse("package:${context.packageName}")
        val intents = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isIgnoringBatteryOptimizations()) {
                add(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(packageUri)
                )
            }
            add(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(packageUri)
            )
        }
        for (intent in intents) {
            val launchIntent = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launchIntent.resolveActivity(context.packageManager) == null) continue
            runCatching {
                context.startActivity(launchIntent)
            }.onSuccess {
                return true
            }
        }
        return false
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
            "settings.backgroundVideoEnhancement.update" -> {
                profileStore.updateBackgroundVideoEnhancement(payload.optString("enabled") == "true")
                okData(profileStore.observeSettings().value.toJson())
            }
            "settings.batteryOptimizationState" -> okData(
                JSONObject().put("ignoringBatteryOptimizations", isIgnoringBatteryOptimizations())
            )
            "settings.openBatteryOptimization" -> okData(
                JSONObject()
                    .put("opened", openBatteryOptimizationSettings())
                    .put("ignoringBatteryOptimizations", isIgnoringBatteryOptimizations())
            )
            "browsingData.clear" -> {
                runCatching {
                    clearGeckoBrowsingData(context)
                    val historyCount = profileStore.clearHistoryForBrowsingData()
                    val faviconCount = runBlocking { faviconStore.clearCache() }
                    okData(
                        JSONObject()
                            .put("historyCount", historyCount)
                            .put("faviconCount", faviconCount)
                            .put("siteDataCleared", true)
                            .put("message", clearBrowsingDataMessage(historyCount, faviconCount))
                    )
                }.getOrElse { throwable ->
                    JSONObject()
                        .put("ok", false)
                        .put("error", throwable.message ?: "清除浏览数据失败。")
                }
            }
            "update.check" -> {
                val result = runBlocking {
                    updateManager.check(ignoreSkipped = payload.optString("ignoreSkipped") == "true")
                }
                checkedUpdate = result.update
                okData(result.toJson())
            }
            "update.skip" -> {
                updateManager.skip(payload.optString("versionCode").toLongOrNull() ?: 0L)
                ok()
            }
            "update.clearSkip" -> {
                updateManager.clearSkip()
                ok()
            }
            "update.downloadState" -> {
                val state = runBlocking { updateManager.refreshDownloadState() }
                updateDownloadState = state
                okData(state.toJson())
            }
            "update.install" -> {
                val versionCode = payload.optString("versionCode").toLongOrNull() ?: 0L
                val update = checkedUpdate
                if (update == null || update.versionCode != versionCode) {
                    JSONObject().put("ok", false).put("error", "请先检查更新。")
                } else {
                    okData(beginUpdateInstall(update).toJson())
                }
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
    var pageContextMenu by remember { mutableStateOf<GeckoContextMenuTarget?>(null) }
    fun refreshTabThumbnail(
        tab: BrowserTabRuntime,
        shouldApply: () -> Boolean = { true },
        onFinished: () -> Unit = {}
    ) {
        if (tab.privateMode) {
            onFinished()
            return
        }
        tab.capturePixels { bitmap ->
            if (bitmap != null && shouldApply()) {
                tab.thumbnail = bitmap
                scope.launch(Dispatchers.IO) {
                    profileStore.saveTabThumbnail(tab.id, bitmap)
                }
            }
            onFinished()
        }
    }

    fun loadValidatedSessionState(tabId: String?, url: String): GeckoSession.SessionState? {
        if (tabId == null || !shouldPersistEngineSessionStateUri(url)) return null
        val rawState = profileStore.loadTabSessionState(tabId) ?: return null
        val state = runCatching { GeckoSession.SessionState.fromString(rawState) }
            .getOrNull()
            ?: run {
                profileStore.deleteTabSessionState(tabId)
                return null
            }
        if (currentUriForEngineSessionState(state) != url) {
            profileStore.deleteTabSessionState(tabId)
            return null
        }
        return state
    }

    fun createBrowserTab(
        url: String,
        id: String? = null,
        input: String = url,
        title: String? = null,
        iconPath: String? = null,
        loadImmediately: Boolean = true,
        privateMode: Boolean = false
    ): BrowserTabRuntime {
        val tabId = id ?: UUID.randomUUID().toString()
        val restoredSessionState = if (privateMode) null else loadValidatedSessionState(id, url)
        val restoredThumbnail = if (privateMode) null else id?.let { profileStore.loadTabThumbnail(it) }
        return BrowserTabRuntime.create(
            app = app,
            url = url,
            id = tabId,
            initialInput = input,
            initialTitle = title,
            initialIconPath = iconPath,
            loadImmediately = loadImmediately,
            privateMode = privateMode,
            restoredSessionState = restoredSessionState,
            onHyperRoute = { pendingHyperRoute = it },
            onHyperBridgeMessage = ::handleHyperBridgeMessage,
            onPageContextMenu = { pageContextMenu = it },
            onDownload = ::saveGeckoDownload,
            onEngineSessionStateChange = { state ->
                if (!privateMode) {
                    state?.let { profileStore.saveTabSessionState(tabId, it) }
                }
            },
            onPageStop = { success ->
                if (success && !privateMode) {
                    thumbnailRefreshRequests.tryEmit(tabId)
                }
            }
        ).also { tab ->
            restoredThumbnail?.let { tab.thumbnail = it }
        }
    }

    val launchUrl = remember(initialUrl, initialDownloadUrl, initialShowDownloads) {
        initialUrl.takeIf {
            it != GeckoSessionController.HOME_URL && initialDownloadUrl == null && !initialShowDownloads
        }
    }
    val restorePlan = remember(launchUrl) {
        planBrowserTabRestore(
            savedTabs = profileStore.loadSavedTabs(),
            launchUrl = launchUrl,
            fallbackUrl = GeckoSessionController.HOME_URL
        )
    }
    val tabs = remember {
        val initialTabs = restorePlan.tabs.map { tab ->
            createBrowserTab(
                url = tab.url,
                id = tab.id,
                input = tab.input,
                title = tab.title,
                iconPath = tab.iconPath,
                loadImmediately = tab.loadImmediately
            )
        }
        mutableStateListOf(*initialTabs.toTypedArray())
    }
    var selectedTabId by remember {
        mutableStateOf(
            when {
                initialSelectTabId != null && tabs.any { it.id == initialSelectTabId } -> initialSelectTabId
                restorePlan.selectLastTab -> tabs.last().id
                restorePlan.selectedSavedTabId != null -> restorePlan.selectedSavedTabId
                else -> tabs.first().id
            }
        )
    }
    var activePanel by remember {
        mutableStateOf(if (initialShowDownloads) BrowserPanel.Downloads else BrowserPanel.None)
    }
    val showSearch = activePanel == BrowserPanel.Search
    val showBookmarks = activePanel == BrowserPanel.Bookmarks
    val showHistory = activePanel == BrowserPanel.History
    val showDownloads = activePanel == BrowserPanel.Downloads
    val showExtensions = activePanel == BrowserPanel.Extensions
    val showTabs = activePanel == BrowserPanel.Tabs
    val selectedIndex = tabs.indexOfFirst { it.id == selectedTabId }.takeIf { it >= 0 } ?: 0
    val tab = tabs.getOrNull(selectedIndex) ?: tabs.first()
    val currentSelectedTabId = rememberUpdatedState(selectedTabId)
    val controller = tab.ensureController()
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
    var editingAddress by remember { mutableStateOf(false) }
    var extensionQuery by remember { mutableStateOf("ublock") }
    var extensionResults by remember { mutableStateOf<List<AmoAddonListing>>(emptyList()) }
    var extensionMessage by remember { mutableStateOf<String?>(null) }
    var installingAddonGuid by remember { mutableStateOf<String?>(null) }
    var currentIconPath by remember { mutableStateOf<String?>(null) }
    var findInPageVisible by remember { mutableStateOf(false) }
    var findInPageQuery by remember { mutableStateOf("") }
    var findInPageResult by remember { mutableStateOf<GeckoSession.FinderResult?>(null) }

    LaunchedEffect(Unit) {
        runCatching { app.extensions.refreshInstalledFromRuntime() }
    }

    fun showPanel(panel: BrowserPanel) {
        activePanel = panel
    }

    fun closePanel() {
        activePanel = BrowserPanel.None
    }

    fun closeFindInPage() {
        findInPageVisible = false
        findInPageQuery = ""
        findInPageResult = null
        controller.clearFindInPage()
    }

    fun runFindInPage(query: String = findInPageQuery, backwards: Boolean = false) {
        findInPageQuery = query
        controller.findInPage(query, backwards) { result ->
            findInPageResult = result
        }
    }

    fun openLinkInBackgroundTab(url: String) {
        tabs.add(createBrowserTab(url, privateMode = tab.privateMode))
        pageContextMenu = null
        activePanel = BrowserPanel.None
        message = "已在后台标签页打开"
    }

    fun openLinkInPrivateTab(url: String) {
        val newTab = createBrowserTab(url, privateMode = true)
        tabs.add(newTab)
        selectedTabId = newTab.id
        pageContextMenu = null
        activePanel = BrowserPanel.None
        message = "Private tab opened."
    }

    fun openNewTab(privateMode: Boolean = false) {
        val newTab = createBrowserTab(GeckoSessionController.HOME_URL, privateMode = privateMode)
        tabs.add(newTab)
        selectedTabId = newTab.id
        closePanel()
        message = if (privateMode) "Private tab opened." else null
    }

    fun isActiveUpdateDownload(state: UpdateDownloadState): Boolean =
        state.status == UpdateDownloadState.STATUS_PREPARING ||
            state.status == UpdateDownloadState.STATUS_DOWNLOADING ||
            state.status == UpdateDownloadState.STATUS_VERIFYING

    fun isFinishedDownload(entry: BrowserDownloadEntry): Boolean =
        entry.status == DownloadStatus.Completed || entry.status == DownloadStatus.Failed || entry.status == DownloadStatus.Canceled

    fun persistBrowserTabs(selectedId: String = selectedTabId) {
        val persistedTabs = tabs.mapNotNull { it.toSavedTab() }.take(50)
        val persistedSelectedId = selectedId
            .takeIf { id -> persistedTabs.any { it.id == id } }
            ?: persistedTabs.lastOrNull()?.id
        profileStore.saveTabs(
            SavedBrowserTabs(
                selectedTabId = persistedSelectedId,
                tabs = persistedTabs
            )
        )
        val keptTabIds = persistedTabs.map { it.id }.toSet()
        profileStore.pruneTabSessionStates(keptTabIds)
        profileStore.pruneTabThumbnails(keptTabIds)
    }
    val persistBrowserTabsForLifecycle by rememberUpdatedState(newValue = { persistBrowserTabs() })

    LaunchedEffect(initialDownloadUrl) {
        initialDownloadUrl?.let { enqueueUrlDownload(it) }
    }

    LaunchedEffect(selectedTabId, settings.searchUrlTemplate) {
        tab.loadIfNeeded(settings.searchUrlTemplate)
    }

    LaunchedEffect(thumbnailRefreshRequests) {
        thumbnailRefreshRequests.collectLatest { tabId ->
            delay(TAB_THUMBNAIL_PAGE_STOP_REFRESH_DELAY_MS)
            if (currentSelectedTabId.value != tabId) return@collectLatest
            val targetTab = tabs.firstOrNull { it.id == tabId } ?: return@collectLatest
            if (!targetTab.hasController) return@collectLatest
            refreshTabThumbnail(
                tab = targetTab,
                shouldApply = { currentSelectedTabId.value == targetTab.id }
            )
        }
    }

    LaunchedEffect(tabs.map { it.id to it.controller }) {
        val watchedTabs = tabs.mapNotNull { watchedTab ->
            watchedTab.controller?.let { watchedTab to it }
        }
        coroutineScope {
            watchedTabs.forEach { (watchedTab, watchedController) ->
                launch {
                    watchedController.state
                        .map { it.url to it.title }
                        .distinctUntilChanged()
                        .collect { (url, title) ->
                            if (watchedTab.rememberCommittedLocation(url, title)) {
                                persistBrowserTabs()
                            }
                        }
                }
            }
        }
    }

    LaunchedEffect(selectedTabId, tabs.size, tab.input, tab.iconPath, tab.loaded, tab.restoreUrl, tab.restoredTitle) {
        persistBrowserTabs()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                tabs.forEach { tab -> tab.flushSessionState() }
                persistBrowserTabsForLifecycle()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(externalIntents, selectedTabId, settings.searchUrlTemplate) {
        externalIntents.collect { command ->
            val commandUrl = command.url
            val targetTabId = command.selectTabId?.takeIf { id -> tabs.any { it.id == id } }
            if (targetTabId != null) {
                selectedTabId = targetTabId
                closePanel()
                editingAddress = false
                message = null
            } else if (command.showDownloads) {
                showPanel(BrowserPanel.Downloads)
                editingAddress = false
                message = null
            } else if (command.openInNewTab && commandUrl != null) {
                val newTab = createBrowserTab(commandUrl)
                tabs.add(newTab)
                selectedTabId = newTab.id
                closePanel()
                editingAddress = false
                message = null
            } else if (command.download && commandUrl != null) {
                enqueueUrlDownload(commandUrl)
            } else if (commandUrl != null) {
                tab.input = commandUrl
                controller.load(commandUrl, settings.searchUrlTemplate)
                closePanel()
                editingAddress = false
                message = null
            }
        }
    }

    LaunchedEffect(showDownloads, downloads) {
        while (showDownloads || downloads.any {
                it.status == DownloadStatus.Running || it.status == DownloadStatus.Queued
            }
        ) {
            downloadHandler.refreshSystemDownloads()
            delay(1000)
        }
    }

    LaunchedEffect(showDownloads, updateDownloadState.status) {
        while (showDownloads || isActiveUpdateDownload(updateDownloadState)) {
            updateDownloadState = updateManager.refreshDownloadState()
            updateDownloadEntry = updateManager.currentDownloadEntry()
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
            findInPageVisible -> closeFindInPage()
            activePanel != BrowserPanel.None -> closePanel()
            pageState.canGoBack -> controller.goBack()
            else -> controller.goBack()
        }
    }

    LaunchedEffect(selectedTabId, pageState.url) {
        if (findInPageVisible) {
            closeFindInPage()
        }
    }

    LaunchedEffect(pageState.url, pageState.title) {
        if (!tab.privateMode && pageState.url.isNotBlank() && !GeckoSessionController.isInternalUrl(pageState.url)) {
            profileStore.recordVisit(pageState.url, pageState.title, currentIconPath)
        }
    }

    LaunchedEffect(pageState.url) {
        currentIconPath = null
        if (pageState.url.isBlank()) return@LaunchedEffect
        if (tab.privateMode || GeckoSessionController.isInternalUrl(pageState.url)) {
            tab.clearIcon()
            return@LaunchedEffect
        }
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

    LaunchedEffect(selectedTabId, installedExtensions) {
        runCatching { app.extensions.refreshMenuActions(controller.session) }
    }

    LaunchedEffect(extensionNewTabRequest) {
        extensionNewTabRequest?.let { request ->
            val newTab = BrowserTabRuntime.fromExtensionRequest(
                app = app,
                request = request,
                onHyperRoute = { pendingHyperRoute = it },
                onHyperBridgeMessage = ::handleHyperBridgeMessage,
                onPageContextMenu = { pageContextMenu = it },
                onDownload = ::saveGeckoDownload
            )
            tabs.add(newTab)
            selectedTabId = newTab.id
            closePanel()
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
            HyperCommand.Panel.Extensions -> showPanel(BrowserPanel.Extensions)
        }
        pendingHyperCommand = null
    }

    DisposableEffect(Unit) {
        onDispose {
            persistBrowserTabs()
            tabs.forEach { it.close(closeActivePlayback = false) }
        }
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
                    onCancel = ::closePanel,
                    onNewPrivateTab = {
                        openNewTab(privateMode = true)
                    },
                    onGo = { value ->
                        tab.input = value
                        controller.load(value, settings.searchUrlTemplate)
                        closePanel()
                        message = null
                    }
                )
            } else if (showBookmarks) {
                BookmarksPage(
                    bookmarks = bookmarks,
                    onBack = ::closePanel,
                    onOpen = { url ->
                        tab.input = url
                        controller.load(url)
                        closePanel()
                        message = null
                    },
                    onRemove = profileStore::removeBookmark
                )
            } else if (showHistory) {
                HistoryPage(
                    history = history,
                    onBack = ::closePanel,
                    onOpen = { url ->
                        tab.input = url
                        controller.load(url)
                        closePanel()
                        message = null
                    },
                    onRemove = profileStore::removeHistoryEntry,
                    onClear = profileStore::clearHistory
                )
            } else if (showDownloads) {
                DownloadsPage(
                    downloads = listOfNotNull(updateDownloadEntry) + downloads,
                    onBack = ::closePanel,
                    onOpen = { entry ->
                        if (entry.id == AppUpdateManager.APP_UPDATE_DOWNLOAD_ID) {
                            scope.launch {
                                val state = updateManager.refreshDownloadState()
                                updateDownloadState = state
                                updateDownloadEntry = updateManager.currentDownloadEntry()
                                if (state.status == UpdateDownloadState.STATUS_READY) {
                                    val installIntent = updateManager.createInstallIntentForReadyDownload()
                                    if (installIntent == null) {
                                        message = "安装包不可用。"
                                    } else {
                                        runCatching { context.startActivity(installIntent) }
                                            .onFailure { message = it.message ?: "无法打开安装器。" }
                                    }
                                } else if (state.status == UpdateDownloadState.STATUS_ERROR) {
                                    message = state.message.ifBlank { "更新下载失败。" }
                                } else {
                                    message = state.message.ifBlank { "更新正在下载。" }
                                }
                            }
                        } else {
                            val openIntent = downloadHandler.openIntent(entry)
                            if (openIntent == null) {
                                message = "File is not ready."
                            } else {
                                runCatching { context.startActivity(openIntent) }
                                    .onFailure { message = it.message ?: "No app can open this file." }
                            }
                        }
                    },
                    onRetry = { entry ->
                        scope.launch {
                            if (entry.id == AppUpdateManager.APP_UPDATE_DOWNLOAD_ID) {
                                message = "请在设置中重新下载更新。"
                            } else {
                                runCatching { downloadHandler.retry(entry) }
                                    .onSuccess { message = "Download queued: ${it.name}" }
                                .onFailure { message = it.message ?: "Unable to retry download." }
                            }
                        }
                    },
                    onCancel = { entry ->
                        scope.launch {
                            if (entry.id == AppUpdateManager.APP_UPDATE_DOWNLOAD_ID) {
                                message = "更新下载请在设置中管理。"
                            } else {
                                runCatching { downloadHandler.cancel(entry) }
                                    .onSuccess { message = "Download canceled." }
                                    .onFailure { message = it.message ?: "Unable to cancel download." }
                            }
                        }
                    },
                    onRemove = { entry, deleteFile ->
                        scope.launch {
                            if (entry.id == AppUpdateManager.APP_UPDATE_DOWNLOAD_ID) {
                                runCatching { updateManager.clearDownload(deleteFile) }
                                    .onSuccess {
                                        updateDownloadState = UpdateDownloadState.idle()
                                        updateDownloadEntry = null
                                        message = "Download removed."
                                    }
                                    .onFailure { message = it.message ?: "Unable to remove download." }
                            } else {
                                runCatching { downloadHandler.delete(entry, deleteFile) }
                                    .onSuccess { message = "Download removed." }
                                    .onFailure { message = it.message ?: "Unable to remove download." }
                            }
                        }
                    },
                    onClearFinished = {
                        scope.launch {
                            var clearedUpdate = 0
                            val updateClearFailure = updateDownloadEntry?.takeIf(::isFinishedDownload)?.let {
                                runCatching { updateManager.clearDownload(deleteFile = false) }
                                    .onSuccess {
                                        updateDownloadState = UpdateDownloadState.idle()
                                        updateDownloadEntry = null
                                        clearedUpdate = 1
                                    }
                                    .exceptionOrNull()
                            }
                            if (updateClearFailure != null) {
                                message = updateClearFailure.message ?: "Unable to clear update download record."
                                return@launch
                            }
                            runCatching { downloadHandler.clearFinishedRecords() }
                                .onSuccess { count ->
                                    val total = count + clearedUpdate
                                    message = if (total > 0) {
                                        "Cleared $total download records."
                                    } else {
                                        "No finished downloads to clear."
                                    }
                                }
                                .onFailure { message = it.message ?: "Unable to clear download records." }
                        }
                    },
                    canRetry = { entry ->
                        entry.id != AppUpdateManager.APP_UPDATE_DOWNLOAD_ID &&
                            (entry.status == DownloadStatus.Failed || entry.status == DownloadStatus.Canceled) &&
                            (entry.sourceUrl.startsWith("http://") || entry.sourceUrl.startsWith("https://"))
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
                    onBack = ::closePanel,
                    onSearch = {
                        scope.launch {
                            extensionMessage = "Searching AMO..."
                            val syncedInstalled = runCatching { app.extensions.refreshInstalledFromRuntime() }
                                .getOrDefault(installedExtensions)
                            runCatching { app.extensions.searchAndroidAddons(extensionQuery) }
                                .onSuccess { results ->
                                    extensionResults = results
                                    val installedMatches = results.count { result ->
                                        syncedInstalled.any { it.guid == result.guid }
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
                                .onSuccess {
                                    runCatching { app.extensions.refreshInstalledFromRuntime() }
                                    extensionMessage = "Installed ${addon.name}."
                                }
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
                    onBack = ::closePanel,
                    onSelect = {
                        selectedTabId = it
                        closePanel()
                    },
                    onClose = { id ->
                        val closing = tabs.firstOrNull { it.id == id } ?: return@TabTray
                        val oldIndex = tabs.indexOf(closing)
                        closing.close()
                        profileStore.deleteTabSessionState(closing.id)
                        profileStore.deleteTabThumbnail(closing.id)
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
                        openNewTab()
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
                    val installedWebApp = if (tab.privateMode || GeckoSessionController.isInternalUrl(currentPageUrl)) {
                        null
                    } else {
                        webApps.firstOrNull { it.startUrl == currentPageUrl }
                    }
                    BrowserToolbar(
                        input = tab.input,
                        pageState = pageState,
                        tabCount = tabs.size,
                        bookmarked = !tab.privateMode &&
                            !GeckoSessionController.isInternalUrl(pageState.url) &&
                            profileStore.isBookmarked(currentPageUrl),
                        webAppInstalled = installedWebApp != null,
                        privateMode = tab.privateMode,
                        installedExtensions = installedExtensions,
                        extensionActions = extensionActions,
                        toolbarPosition = settings.toolbarPosition,
                        editingAddress = editingAddress,
                        onEditingAddressChange = { editingAddress = it },
                        onStartSearch = {
                            editingAddress = false
                            showPanel(BrowserPanel.Search)
                            message = null
                        },
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
                            refreshTabThumbnail(tab) {
                                showPanel(BrowserPanel.Tabs)
                            }
                        },
                        onNewTab = {
                            openNewTab()
                        },
                        onNewPrivateTab = {
                            openNewTab(privateMode = true)
                            message = "Private tab opened."
                        },
                        onHome = {
                            tab.input = GeckoSessionController.HOME_URL
                            controller.loadHome()
                            message = null
                        },
                        onToggleBookmark = bookmark@{
                            if (tab.privateMode) {
                                message = "Private tabs cannot be bookmarked."
                                return@bookmark
                            }
                            val url = pageState.url.ifBlank { tab.input }
                            profileStore.toggleBookmark(url, pageState.title, currentIconPath)
                        },
                        onShowSettings = {
                            tab.input = GeckoSessionController.SETTINGS_URL
                            controller.loadSettings()
                        },
                        onShowDownloads = { showPanel(BrowserPanel.Downloads) },
                        onShowExtensions = { showPanel(BrowserPanel.Extensions) },
                        onFindInPage = {
                            findInPageVisible = true
                            activePanel = BrowserPanel.None
                            editingAddress = false
                            message = null
                        },
                        onExtensionClick = { extension ->
                            scope.launch {
                                runCatching { app.extensions.clickMenuAction(extension.guid) }
                                    .onFailure { message = it.message ?: "Extension popup unavailable." }
                            }
                        },
                        onInstall = install@{
                            if (tab.privateMode) {
                                message = "Private tabs cannot be installed as WebApps."
                                return@install
                            }
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
                val findBar = @Composable {
                    if (findInPageVisible) {
                        BrowserFindBar(
                            query = findInPageQuery,
                            result = findInPageResult,
                            onQueryChange = { query -> runFindInPage(query) },
                            onFindNext = { runFindInPage(backwards = false) },
                            onFindPrevious = { runFindInPage(backwards = true) },
                            onClose = ::closeFindInPage
                        )
                    }
                }
                if (settings.toolbarPosition == BrowserSettings.TOOLBAR_POSITION_BOTTOM) {
                    findBar()
                    BrowserContent(
                        controller = controller,
                        tabId = tab.id,
                        extensionPopup = extensionPopup,
                        onClosePopup = app.extensions::closePopup,
                        modifier = Modifier.weight(1f),
                        imeAvoidanceEnabled = !editingAddress
                    )
                    toolbar()
                } else {
                    toolbar()
                    findBar()
                    BrowserContent(
                        controller = controller,
                        tabId = tab.id,
                        extensionPopup = extensionPopup,
                        onClosePopup = app.extensions::closePopup,
                        modifier = Modifier.weight(1f),
                        imeAvoidanceEnabled = !editingAddress
                    )
                }
            } else {
                BrowserContent(controller = controller, tabId = tab.id, extensionPopup = extensionPopup, onClosePopup = app.extensions::closePopup)
            }
        }
        pageContextMenu?.let { menu ->
            fun copyContextUrl(clipLabel: String, url: String, toast: String) {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(clipLabel, url)))
                }
                pageContextMenu = null
                message = toast
            }
            PageContextMenuDialog(
                target = menu,
                onDismissRequest = { pageContextMenu = null },
                onDownloadImage = { url ->
                    pageContextMenu = null
                    enqueueUrlDownload(url)
                },
                onOpenImage = ::openLinkInBackgroundTab,
                onOpenImagePrivate = ::openLinkInPrivateTab,
                onCopyImage = { url -> copyContextUrl("image", url, "图片地址已复制") },
                onOpenLink = ::openLinkInBackgroundTab,
                onOpenLinkPrivate = ::openLinkInPrivateTab,
                onCopyLink = { url -> copyContextUrl("link", url, "链接已复制") }
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

private fun clearGeckoBrowsingData(context: Context) {
    val flags = StorageController.ClearFlags.COOKIES or
        StorageController.ClearFlags.NETWORK_CACHE or
        StorageController.ClearFlags.IMAGE_CACHE or
        StorageController.ClearFlags.DOM_STORAGES or
        StorageController.ClearFlags.AUTH_SESSIONS or
        StorageController.ClearFlags.PERMISSIONS or
        StorageController.ClearFlags.SITE_SETTINGS
    GeckoRuntimeProvider.get(context)
        .storageController
        .clearData(flags)
        .accept({
            Log.d(BROWSING_DATA_TAG, "Gecko browsing data cleared")
        }, { throwable ->
            Log.w(BROWSING_DATA_TAG, "Failed to clear Gecko browsing data", throwable)
        })
}

private fun clearBrowsingDataMessage(historyCount: Int, faviconCount: Int): String =
    buildString {
        append("已开始清除网站数据、缓存和站点权限")
        if (historyCount > 0) append("，历史记录 $historyCount 条")
        if (faviconCount > 0) append("，favicon 缓存 $faviconCount 个")
        append("。")
    }

private const val BROWSING_DATA_TAG = "BrowsingData"
