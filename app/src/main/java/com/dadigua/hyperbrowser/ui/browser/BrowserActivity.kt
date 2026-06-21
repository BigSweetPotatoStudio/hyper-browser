package com.dadigua.hyperbrowser.ui.browser

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.security.KeyChain
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.dadigua.hyperbrowser.HyperBrowserApp
import com.dadigua.hyperbrowser.R
import com.dadigua.hyperbrowser.browser.BrowserDownloadEntry
import com.dadigua.hyperbrowser.browser.DownloadHandler
import com.dadigua.hyperbrowser.browser.DownloadStatus
import com.dadigua.hyperbrowser.browser.DownloadStore
import com.dadigua.hyperbrowser.browser.FaviconRepository
import com.dadigua.hyperbrowser.backup.BrowserBackupManager
import com.dadigua.hyperbrowser.browser.BrowserProfileStore
import com.dadigua.hyperbrowser.browser.BrowserSettings
import com.dadigua.hyperbrowser.browser.BrowserMediaNotificationController
import com.dadigua.hyperbrowser.browser.SavedBrowserTabs
import com.dadigua.hyperbrowser.data.WebAppDefinition
import com.dadigua.hyperbrowser.extensions.AmoAddonListing
import com.dadigua.hyperbrowser.gecko.GeckoAuthPromptRequest
import com.dadigua.hyperbrowser.gecko.GeckoCertificatePromptRequest
import com.dadigua.hyperbrowser.gecko.GeckoContextMenuTarget
import com.dadigua.hyperbrowser.gecko.GeckoDownloadRequest
import com.dadigua.hyperbrowser.gecko.GeckoFilePromptRequest
import com.dadigua.hyperbrowser.gecko.GeckoPageSecurity
import com.dadigua.hyperbrowser.gecko.GeckoPromptRequest
import com.dadigua.hyperbrowser.gecko.GeckoSharePromptRequest
import com.dadigua.hyperbrowser.gecko.GeckoRuntimeProvider
import com.dadigua.hyperbrowser.gecko.GeckoSessionController
import com.dadigua.hyperbrowser.gecko.HyperCommand
import com.dadigua.hyperbrowser.gecko.HyperRoute
import com.dadigua.hyperbrowser.ui.FullscreenSystemBarsEffect
import com.dadigua.hyperbrowser.ui.theme.HyperBrowserTheme
import com.dadigua.hyperbrowser.ui.withAppLocale
import com.dadigua.hyperbrowser.ui.webapp.WebAppActivity
import com.dadigua.hyperbrowser.update.AppUpdateManager
import com.dadigua.hyperbrowser.update.AvailableUpdate
import com.dadigua.hyperbrowser.update.UpdateDownloadState
import com.dadigua.hyperbrowser.update.UpdateSettingsStore
import com.dadigua.hyperbrowser.webapp.PinnedShortcutRequestResult
import com.dadigua.hyperbrowser.webapp.WebAppIconPresets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.mozilla.geckoview.GeckoSession
import java.util.UUID

class BrowserActivity : ComponentActivity() {
    private val externalIntents = MutableSharedFlow<ExternalBrowserIntent>(extraBufferCapacity = 1)

    override fun attachBaseContext(newBase: Context) {
        val localePreference = BrowserProfileStore.loadBrowserSettings(newBase).localePreference
        super.attachBaseContext(newBase.withAppLocale(localePreference))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialIntent = intent.toExternalBrowserIntent()
        val initialUrl = if (initialIntent?.download == false && initialIntent.url != null) {
            initialIntent.url
        } else {
            GeckoSessionController.HOME_URL
        }
        setContent {
            val app = application as HyperBrowserApp
            val profileStore = remember { BrowserProfileStore(app) }
            HyperBrowserTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BrowserScreen(
                        activity = this@BrowserActivity,
                        app = app,
                        profileStore = profileStore,
                        initialUrl = initialUrl,
                        initialDownloadUrl = initialIntent?.url?.takeIf { initialIntent.download },
                        initialShowDownloads = initialIntent?.showDownloads == true,
                        initialSelectTabId = initialIntent?.selectTabId,
                        externalIntents = externalIntents
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
    activity: BrowserActivity,
    app: HyperBrowserApp,
    profileStore: BrowserProfileStore,
    initialUrl: String,
    initialDownloadUrl: String?,
    initialShowDownloads: Boolean,
    initialSelectTabId: String?,
    externalIntents: MutableSharedFlow<ExternalBrowserIntent>
) {
    var pendingHyperRoute by remember { mutableStateOf<HyperRoute?>(null) }
    var pendingHyperCommand by remember { mutableStateOf<HyperCommand?>(null) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val clipboard = LocalClipboard.current
    val focusManager = LocalFocusManager.current
    val imageCopiedText = stringResource(R.string.browser_toast_image_url_copied)
    val linkCopiedText = stringResource(R.string.browser_toast_link_copied)
    val faviconStore = remember { FaviconRepository(app) }
    val backupManager = remember { BrowserBackupManager(profileStore, app.webApps, faviconStore) }
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
            message = context.getString(R.string.download_toast_downloading)
            runCatching { downloadHandler.enqueueUrlDownload(url) }
                .onSuccess { message = context.getString(R.string.download_toast_queued, it.name) }
                .onFailure { message = it.message ?: context.getString(R.string.download_toast_failed) }
        }
    }
    fun saveGeckoDownload(request: GeckoDownloadRequest) {
        requestDownloadNotificationsIfNeeded()
        scope.launch {
            message = context.getString(R.string.download_toast_saving, request.fileName)
            runCatching { downloadHandler.saveResponse(request, downloadHandler.canPostNotifications()) }
                .onSuccess { message = context.getString(R.string.download_toast_queued, it.name) }
                .onFailure { message = it.message ?: context.getString(R.string.download_toast_failed) }
        }
    }

    fun canRetryDownload(entry: BrowserDownloadEntry): Boolean =
        entry.id != AppUpdateManager.APP_UPDATE_DOWNLOAD_ID &&
            (entry.status == DownloadStatus.Failed || entry.status == DownloadStatus.Canceled) &&
            (entry.sourceUrl.startsWith("http://") || entry.sourceUrl.startsWith("https://"))

    fun canCancelDownload(entry: BrowserDownloadEntry): Boolean =
        entry.id != AppUpdateManager.APP_UPDATE_DOWNLOAD_ID &&
            (entry.status == DownloadStatus.Queued || entry.status == DownloadStatus.Running)

    fun canClearDownload(entry: BrowserDownloadEntry): Boolean =
        entry.id != AppUpdateManager.APP_UPDATE_DOWNLOAD_ID

    fun retryDownload(entry: BrowserDownloadEntry) {
        scope.launch {
            message = context.getString(R.string.download_retrying, entry.name)
            runCatching { downloadHandler.retry(entry) }
                .onSuccess { message = context.getString(R.string.download_toast_queued, it.name) }
                .onFailure { message = it.message ?: context.getString(R.string.download_retry_failed) }
        }
    }

    fun cancelDownload(entry: BrowserDownloadEntry) {
        scope.launch {
            runCatching { downloadHandler.cancel(entry) }
                .onSuccess { message = context.getString(R.string.download_canceled) }
                .onFailure { message = it.message ?: context.getString(R.string.download_cancel_failed) }
        }
    }

    fun clearFinishedDownloads() {
        scope.launch {
            runCatching { downloadHandler.clearFinishedRecords() }
                .onSuccess { count ->
                    message = if (count > 0) {
                        context.getString(R.string.library_downloads_cleared_count, count)
                    } else {
                        context.getString(R.string.library_downloads_none_to_clear)
                    }
                }
                .onFailure { message = it.message ?: context.getString(R.string.library_downloads_clear_failed) }
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
                message = context.getString(R.string.browser_install_unknown_apps_required)
            )
            message = updateDownloadState.message
            runCatching { activity.startActivity(updateManager.installPermissionIntent()) }
                .onFailure { message = it.message ?: context.getString(R.string.browser_open_install_permission_failed) }
            return updateDownloadState
        }

        updateInstallInFlight = true
        updateDownloadState = UpdateDownloadState(
            status = UpdateDownloadState.STATUS_PREPARING,
            versionCode = update.versionCode,
            versionName = update.versionName,
            totalBytes = update.asset.sizeBytes,
            message = context.getString(R.string.update_preparing)
        )
        requestDownloadNotificationsIfNeeded()
        message = context.getString(R.string.update_started_version, update.versionName)

        activity.lifecycleScope.launch {
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
                                    runCatching { activity.startActivity(intent) }
                                        .onFailure { message = it.message ?: context.getString(R.string.browser_open_installer_failed) }
                                }
                            }
                            .onFailure { message = it.message ?: context.getString(R.string.browser_open_installer_failed) }
                    }
                }
                .onFailure { throwable ->
                    val error = throwable.message ?: context.getString(R.string.update_download_failed)
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
                activity.startActivity(launchIntent)
            }.onSuccess {
                return true
            }
        }
        return false
    }

    fun defaultBackupFileName(): String =
        "hyper-browser-backup-${System.currentTimeMillis()}.json"

    val exportBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) {
            message = context.getString(R.string.backup_export_canceled)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            message = context.getString(R.string.backup_exporting)
            runCatching {
                val backupJson = withContext(Dispatchers.IO) { backupManager.exportJson() }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)
                        ?.bufferedWriter(Charsets.UTF_8)
                        ?.use { writer -> writer.write(backupJson) }
                        ?: error(context.getString(R.string.backup_write_failed))
                }
            }
                .onSuccess { message = context.getString(R.string.backup_export_success) }
                .onFailure { message = it.message ?: context.getString(R.string.backup_export_failed) }
        }
    }

    val importBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            message = context.getString(R.string.backup_import_canceled)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            message = context.getString(R.string.backup_importing)
            runCatching {
                val backupJson = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)
                        ?.use { reader -> reader.readText() }
                        ?: error(context.getString(R.string.backup_read_failed))
                }
                withContext(Dispatchers.IO) { backupManager.importJson(backupJson) }
            }
                .onSuccess { result ->
                    message = context.getString(R.string.backup_import_result, result.bookmarks, result.webApps)
                }
                .onFailure { message = it.message ?: context.getString(R.string.backup_import_failed) }
        }
    }

    fun handleHyperBridgeMessage(message: JSONObject): JSONObject {
        val payload = message.optJSONObject("payload") ?: JSONObject()
        return when (message.optString("type")) {
            "data.home" -> okItems(profileStore.observeHistory().value.toHistoryJsonString(faviconStore))
            "data.search" -> okItems(
                searchSuggestionsJsonString(
                    bookmarks = profileStore.observeBookmarks().value,
                    history = profileStore.observeHistory().value,
                    webApps = app.webApps.observeAll().value
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
            "settings.openNewTabsInCurrentTab.update" -> {
                profileStore.updateOpenNewTabsInCurrentTab(payload.optString("enabled") == "true")
                okData(profileStore.observeSettings().value.toJson())
            }
            "settings.locale.update" -> {
                val previousLocalePreference = profileStore.observeSettings().value.localePreference
                profileStore.updateLocalePreference(payload.optString("localePreference"))
                val nextSettings = profileStore.observeSettings().value
                if (nextSettings.localePreference != previousLocalePreference) {
                    activity.window.decorView.post { activity.recreate() }
                }
                okData(nextSettings.toJson())
            }
            "settings.privacy.update" -> {
                profileStore.updatePrivacySettings(
                    dohEnabled = payload.optString("dohEnabled") == "true",
                    dohProviderUrl = payload.optString("dohProviderUrl"),
                    httpsOnlyEnabled = payload.optString("httpsOnlyEnabled") == "true",
                    privacyProtectionLevel = payload.optString("privacyProtectionLevel")
                )
                val nextSettings = profileStore.observeSettings().value
                GeckoRuntimeProvider.applyBrowserSettings(app, nextSettings)
                okData(nextSettings.toJson())
            }
            "settings.batteryOptimizationState" -> okData(
                JSONObject().put("ignoringBatteryOptimizations", isIgnoringBatteryOptimizations())
            )
            "settings.openBatteryOptimization" -> okData(
                JSONObject()
                    .put("opened", openBatteryOptimizationSettings())
                    .put("ignoringBatteryOptimizations", isIgnoringBatteryOptimizations())
            )
            "backup.export" -> {
                scope.launch { exportBackupLauncher.launch(defaultBackupFileName()) }
                okData(JSONObject().put("message", context.getString(R.string.backup_choose_save_location)))
            }
            "backup.import" -> {
                scope.launch {
                    importBackupLauncher.launch(arrayOf("application/json", "text/json", "application/octet-stream", "*/*"))
                }
                okData(JSONObject().put("message", context.getString(R.string.backup_choose_file)))
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
                    JSONObject().put("ok", false).put("error", context.getString(R.string.update_check_first))
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
                pendingHyperCommand = HyperCommand.Apps.Edit(payload.optString("id"))
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
    var authPrompt by remember { mutableStateOf<GeckoAuthPromptRequest?>(null) }
    var geckoPrompt by remember { mutableStateOf<GeckoPromptRequest?>(null) }
    var pendingFilePrompt by remember { mutableStateOf<GeckoFilePromptRequest?>(null) }
    val singleFilePromptLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val request = pendingFilePrompt ?: return@rememberLauncherForActivityResult
        pendingFilePrompt = null
        if (uri == null) {
            request.dismiss()
        } else {
            request.confirm(listOf(uri))
        }
    }
    val multipleFilePromptLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val request = pendingFilePrompt ?: return@rememberLauncherForActivityResult
        pendingFilePrompt = null
        if (uris.isEmpty()) {
            request.dismiss()
        } else {
            request.confirm(uris)
        }
    }
    fun handleFilePrompt(request: GeckoFilePromptRequest) {
        pendingFilePrompt?.dismiss()
        pendingFilePrompt = request
        if (request.multiple) {
            multipleFilePromptLauncher.launch(request.pickerMimeTypes())
        } else {
            singleFilePromptLauncher.launch(request.pickerMimeTypes())
        }
    }
    fun handleCertificatePrompt(request: GeckoCertificatePromptRequest) {
        val activity = context as? BrowserActivity
        if (activity == null) {
            request.dismiss()
            return
        }
        KeyChain.choosePrivateKeyAlias(
            activity,
            { alias ->
                if (alias == null) {
                    request.dismiss()
                } else {
                    request.confirm(alias)
                }
            },
            null,
            request.issuers,
            request.host.takeIf { it.isNotBlank() },
            -1,
            null
        )
    }
    fun handleSharePrompt(request: GeckoSharePromptRequest) {
        val shareText = listOf(request.text, request.uri)
            .filter { it.isNotBlank() }
            .joinToString("\n")
        if (shareText.isBlank()) {
            request.dismiss()
            return
        }
        val sendIntent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, shareText)
        request.title.takeIf { it.isNotBlank() }?.let {
            sendIntent.putExtra(Intent.EXTRA_TITLE, it)
        }
        runCatching {
            activity.startActivity(
                Intent.createChooser(sendIntent, context.getString(R.string.prompt_share_title))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onSuccess {
            request.confirm(GeckoSession.PromptDelegate.SharePrompt.Result.SUCCESS)
        }.onFailure {
            request.confirm(GeckoSession.PromptDelegate.SharePrompt.Result.FAILURE)
        }
    }
    fun refreshTabThumbnail(
        tab: BrowserTabRuntime,
        shouldApply: () -> Boolean = { true },
        onFinished: () -> Unit = {}
    ) {
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

    var openNewSessionAsTab: ((String, String?) -> GeckoSession?)? = null
    var closeTabById: ((String) -> Unit)? = null
    var focusTabById: ((String) -> Unit)? = null

    fun createBrowserTab(
        url: String,
        id: String? = null,
        input: String = url,
        title: String? = null,
        iconPath: String? = null,
        loadImmediately: Boolean = true
    ): BrowserTabRuntime {
        val tabId = id ?: UUID.randomUUID().toString()
        val restoredSessionState = loadValidatedSessionState(id, url)
        val restoredThumbnail = id?.let { profileStore.loadTabThumbnail(it) }
        return BrowserTabRuntime.create(
            app = app,
            url = url,
            id = tabId,
            initialInput = input,
            initialTitle = title,
            initialIconPath = iconPath,
            loadImmediately = loadImmediately,
            restoredSessionState = restoredSessionState,
            onHyperRoute = { pendingHyperRoute = it },
            onHyperBridgeMessage = ::handleHyperBridgeMessage,
            onPageContextMenu = { pageContextMenu = it },
            onAuthPrompt = { authPrompt = it },
            onPrompt = { geckoPrompt = it },
            onFilePrompt = ::handleFilePrompt,
            onCertificatePrompt = ::handleCertificatePrompt,
            onSharePrompt = ::handleSharePrompt,
            onDownload = ::saveGeckoDownload,
            openNewTabsInCurrentTab = { profileStore.observeSettings().value.openNewTabsInCurrentTab },
            onNewSession = { uri -> openNewSessionAsTab?.invoke(uri, tabId) },
            onCloseRequest = { closeTabById?.invoke(tabId) },
            onFocusRequest = { focusTabById?.invoke(tabId) },
            onEngineSessionStateChange = { state ->
                state?.let { profileStore.saveTabSessionState(tabId, it) }
            },
            onPageStop = { success ->
                if (success) {
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
    val pageFullScreen by controller.fullScreen.collectAsState()
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
    var extensionQuery by remember { mutableStateOf("ublock") }
    var extensionResults by remember { mutableStateOf<List<AmoAddonListing>>(emptyList()) }
    var extensionMessage by remember { mutableStateOf<String?>(null) }
    var installingAddonGuid by remember { mutableStateOf<String?>(null) }
    var currentIconPath by remember { mutableStateOf<String?>(null) }
    var webAppDetailsDialog by remember { mutableStateOf<WebAppDetailsDialogState?>(null) }
    val webAppIconImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        val dialog = webAppDetailsDialog ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val key = dialog.webAppId ?: dialog.startUrl
            val iconPath = runCatching { faviconStore.saveCustomIconFromUri(key, uri) }.getOrNull()
            if (iconPath == null) {
                message = context.getString(R.string.webapp_icon_choose_failed)
            } else {
                webAppDetailsDialog = dialog.copy(selectedIcon = WebAppIconSelection.Image(iconPath))
            }
        }
    }

    fun handlePageContentTouchStarted() {
        focusManager.clearFocus(force = true)
    }

    fun showInstallWebAppDetailsDialog(name: String, startUrl: String, siteIconPath: String?) {
        val usableSiteIconPath = faviconStore.existingIconPath(siteIconPath)
        webAppDetailsDialog = WebAppDetailsDialogState(
            mode = WebAppDetailsDialogMode.Install,
            name = name,
            startUrl = startUrl,
            siteIconPath = usableSiteIconPath,
            selectedIcon = WebAppIconSelection.Site
        )
    }

    fun showEditWebAppDetailsDialog(webApp: WebAppDefinition) {
        val currentCustomIconPath = faviconStore.existingIconPath(webApp.iconPath)
            ?.takeIf { faviconStore.isCustomIconPath(it) }
        val siteIconPath = if (currentCustomIconPath == null) {
            faviconStore.existingIconPath(webApp.iconPath)
                ?: faviconStore.cachedIconPath(webApp.startUrl)
        } else {
            faviconStore.cachedIconPath(webApp.startUrl)
        }
        val selectedIcon = when {
            currentCustomIconPath != null -> WebAppIconSelection.Image(currentCustomIconPath)
            else -> WebAppIconSelection.Site
        }
        webAppDetailsDialog = WebAppDetailsDialogState(
            mode = WebAppDetailsDialogMode.Edit,
            webAppId = webApp.id,
            name = webApp.name,
            startUrl = webApp.startUrl,
            siteIconPath = siteIconPath,
            selectedIcon = selectedIcon
        )
    }

    fun refreshAppsPageIfVisible() {
        if (GeckoSessionController.isAppsUrl(pageState.url)) {
            tab.input = GeckoSessionController.APPS_URL
            controller.loadApps()
        }
    }

    fun normalizeWebAppUrl(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null
        val normalized = if (
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else if (trimmed.contains(".") && !trimmed.any { it.isWhitespace() }) {
            "https://$trimmed"
        } else {
            trimmed
        }
        return normalized.takeIf {
            it.startsWith("http://", ignoreCase = true) ||
                it.startsWith("https://", ignoreCase = true)
        }
    }

    fun cleanWebAppName(name: String, url: String): String =
        name.trim().ifBlank {
            Uri.parse(url).host.orEmpty().removePrefix("www.").ifBlank {
                context.getString(R.string.browser_search_source_webapp)
            }
        }

    suspend fun selectedIconPath(dialog: WebAppDetailsDialogState, key: String): String? =
        when (val selection = dialog.selectedIcon) {
            WebAppIconSelection.Site -> dialog.siteIconPath
            is WebAppIconSelection.Image -> selection.iconPath
            is WebAppIconSelection.Preset -> WebAppIconPresets.find(selection.id)
                ?.let { preset ->
                    withContext(Dispatchers.IO) {
                        faviconStore.saveCustomIconPreset(dialog.webAppId ?: key, preset)
                    }
                }
        }

    fun confirmWebAppDetailsDialog(dialog: WebAppDetailsDialogState) {
        webAppDetailsDialog = null
        scope.launch {
            when (dialog.mode) {
                WebAppDetailsDialogMode.Install -> {
                    runCatching {
                        val cleanUrl = normalizeWebAppUrl(dialog.startUrl)
                            ?: error(context.getString(R.string.webapp_update_failed))
                        val cleanName = cleanWebAppName(dialog.name, cleanUrl)
                        val iconPath = selectedIconPath(dialog, cleanUrl)
                        if (dialog.selectedIcon != WebAppIconSelection.Site && iconPath == null) {
                            error(context.getString(R.string.webapp_icon_update_failed))
                        }
                        app.webApps.installFromPage(
                            name = cleanName,
                            url = cleanUrl,
                            iconPath = iconPath
                        )
                    }
                        .onSuccess {
                            message = context.getString(
                                R.string.webapp_installed_with_shortcut,
                                it.webApp.name,
                                shortcutRequestMessage(context, it.shortcutRequest)
                            )
                        }
                        .onFailure { message = it.message ?: context.getString(R.string.webapp_install_failed) }
                }
                WebAppDetailsDialogMode.Edit -> {
                    val webAppId = dialog.webAppId
                    if (webAppId.isNullOrBlank()) {
                        message = context.getString(R.string.webapp_not_found)
                        return@launch
                    }
                    runCatching {
                        val cleanUrl = normalizeWebAppUrl(dialog.startUrl)
                            ?: error(context.getString(R.string.webapp_update_failed))
                        val cleanName = cleanWebAppName(dialog.name, cleanUrl)
                        val updatedDetails = app.webApps.update(webAppId, cleanName, cleanUrl)
                            ?: error(context.getString(R.string.webapp_not_found))
                        if (dialog.selectedIcon == WebAppIconSelection.Site) {
                            app.webApps.resetIconToSite(webAppId)
                        } else {
                            val iconPath = selectedIconPath(dialog, cleanUrl)
                                ?: error(context.getString(R.string.webapp_icon_update_failed))
                            app.webApps.updateIcon(webAppId, iconPath)
                        } ?: updatedDetails
                    }
                        .onSuccess { updated ->
                            refreshAppsPageIfVisible()
                            message = context.getString(R.string.webapp_updated, updated.name)
                        }
                        .onFailure { message = it.message ?: context.getString(R.string.webapp_update_failed) }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        runCatching { app.extensions.refreshInstalledFromRuntime() }
        runCatching { app.webApps.refreshMissingIcons() }
    }

    fun showPanel(panel: BrowserPanel) {
        activePanel = panel
    }

    fun closePanel() {
        activePanel = BrowserPanel.None
    }

    fun openLinkInBackgroundTab(url: String) {
        tabs.add(createBrowserTab(url))
        pageContextMenu = null
        activePanel = BrowserPanel.None
        message = context.getString(R.string.browser_opened_background_tab)
    }

    fun isActiveUpdateDownload(state: UpdateDownloadState): Boolean =
        state.status == UpdateDownloadState.STATUS_PREPARING ||
            state.status == UpdateDownloadState.STATUS_DOWNLOADING ||
            state.status == UpdateDownloadState.STATUS_VERIFYING

    fun persistBrowserTabs(selectedId: String = selectedTabId) {
        profileStore.saveTabs(
            SavedBrowserTabs(
                selectedTabId = selectedId,
                tabs = tabs.mapNotNull { it.toSavedTab() }.take(50)
            )
        )
        val keptTabIds = tabs.map { it.id }.toSet()
        profileStore.pruneTabSessionStates(keptTabIds)
        profileStore.pruneTabThumbnails(keptTabIds)
    }

    fun closeBrowserTabById(id: String) {
        val closing = tabs.firstOrNull { it.id == id } ?: return
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
            selectedTabId = closing.openerTabId
                ?.takeIf { openerId -> tabs.any { it.id == openerId } }
                ?: tabs[(oldIndex - 1).coerceIn(0, tabs.lastIndex)].id
        }
        activePanel = BrowserPanel.None
        message = null
        persistBrowserTabs()
    }

    closeTabById = ::closeBrowserTabById
    focusTabById = { id ->
        if (tabs.any { it.id == id }) {
            selectedTabId = id
            activePanel = BrowserPanel.None
        }
    }

    openNewSessionAsTab = { uri, openerTabId ->
        val newSession = GeckoSessionController.createSession()
        var createdTab: BrowserTabRuntime? = null
        val newTab = BrowserTabRuntime.fromExistingSession(
            app = app,
            url = uri,
            session = newSession,
            openerTabId = openerTabId,
            onHyperRoute = { pendingHyperRoute = it },
            onHyperBridgeMessage = ::handleHyperBridgeMessage,
            onPageContextMenu = { pageContextMenu = it },
            onAuthPrompt = { authPrompt = it },
            onPrompt = { geckoPrompt = it },
            onFilePrompt = ::handleFilePrompt,
            onCertificatePrompt = ::handleCertificatePrompt,
            onSharePrompt = ::handleSharePrompt,
            onDownload = ::saveGeckoDownload,
            openNewTabsInCurrentTab = { profileStore.observeSettings().value.openNewTabsInCurrentTab },
            onNewSession = { nextUri -> openNewSessionAsTab?.invoke(nextUri, createdTab?.id) },
            onCloseRequest = {
                createdTab?.id?.let { closeTabById(it) }
            },
            onFocusRequest = {
                createdTab?.id?.let { focusTabById(it) }
            },
            onEngineSessionStateChange = { state ->
                val tabId = createdTab?.id
                if (tabId != null && state != null) {
                    profileStore.saveTabSessionState(tabId, state)
                }
            },
            onPageStop = { success ->
                val tabId = createdTab?.id
                if (success && tabId != null) {
                    thumbnailRefreshRequests.tryEmit(tabId)
                }
            }
        )
        createdTab = newTab
        tabs.add(newTab)
        selectedTabId = newTab.id
        activePanel = BrowserPanel.None
        message = null
        persistBrowserTabs(newTab.id)
        newSession
    }
    val persistBrowserTabsForLifecycle by rememberUpdatedState(newValue = { persistBrowserTabs() })

    LaunchedEffect(initialDownloadUrl) {
        initialDownloadUrl?.let { enqueueUrlDownload(it) }
    }

    LaunchedEffect(selectedTabId, settings.searchUrlTemplate) {
        tab.loadIfNeeded(settings.searchUrlTemplate)
    }

    val pageCanOwnFocus = pageFullScreen ||
        (activePanel == BrowserPanel.None && extensionPopup == null && pageContextMenu == null)

    LaunchedEffect(selectedTabId, tabs.map { Triple(it.id, it.openerTabId, it.controller) }, pageCanOwnFocus) {
        val openPopupOpenerIds = tabs.mapNotNull { it.openerTabId }.toSet()
        tabs.forEach { browserTab ->
            val selected = browserTab.id == selectedTabId
            val ownsOpenPopup = browserTab.id in openPopupOpenerIds
            browserTab.controller?.setVisible(
                visible = selected || ownsOpenPopup,
                focused = selected && pageCanOwnFocus
            )
        }
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
                message = null
            } else if (command.showDownloads) {
                showPanel(BrowserPanel.Downloads)
                message = null
            } else if (command.openInNewTab && commandUrl != null) {
                val newTab = createBrowserTab(commandUrl)
                tabs.add(newTab)
                selectedTabId = newTab.id
                closePanel()
                message = null
            } else if (command.download && commandUrl != null) {
                enqueueUrlDownload(commandUrl)
            } else if (commandUrl != null) {
                tab.input = commandUrl
                controller.load(commandUrl, settings.searchUrlTemplate)
                closePanel()
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
            pageFullScreen -> controller.exitFullScreen()
            webAppDetailsDialog != null -> webAppDetailsDialog = null
            extensionPopup != null -> app.extensions.closePopup()
            activePanel != BrowserPanel.None -> closePanel()
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
        if (pageState.url.isBlank()) return@LaunchedEffect
        if (GeckoSessionController.isInternalUrl(pageState.url)) {
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
                runCatching { app.webApps.updateIconForUrl(pageState.url, iconPath) }
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
                onAuthPrompt = { authPrompt = it },
                onPrompt = { geckoPrompt = it },
                onFilePrompt = ::handleFilePrompt,
                onCertificatePrompt = ::handleCertificatePrompt,
                onSharePrompt = ::handleSharePrompt,
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
                    activity.startActivity(WebAppActivity.intent(activity, command.id, true))
                }
            }
            is HyperCommand.Apps.Pin -> {
                scope.launch {
                    runCatching { app.webApps.pinToHome(command.id) }
                        .onSuccess { message = shortcutRequestMessage(context, it) }
                        .onFailure { message = it.message ?: context.getString(R.string.shortcut_request_failed) }
                }
            }
            is HyperCommand.Apps.Edit -> {
                val webApp = webApps.firstOrNull { it.id == command.id } ?: app.webApps.get(command.id)
                if (webApp == null) {
                    message = context.getString(R.string.webapp_not_found)
                } else {
                    showEditWebAppDetailsDialog(webApp)
                }
            }
            is HyperCommand.Apps.Delete -> {
                scope.launch {
                    runCatching { app.webApps.delete(command.id) }
                        .onSuccess {
                            message = if (it) {
                                context.getString(R.string.webapp_deleted)
                            } else {
                                context.getString(R.string.webapp_not_found)
                            }
                        }
                        .onFailure { message = it.message ?: context.getString(R.string.webapp_delete_failed) }
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

    FullscreenSystemBarsEffect(pageFullScreen)

    Box(
        modifier = if (pageFullScreen) {
            Modifier.fillMaxSize()
        } else {
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (pageFullScreen) {
                BrowserContent(
                    controller = controller,
                    tabId = tab.id,
                    extensionPopup = null,
                    onClosePopup = app.extensions::closePopup,
                    modifier = Modifier.weight(1f),
                    imeAvoidanceEnabled = false
                )
            } else if (showSearch) {
                SearchPage(
                    initialInput = "",
                    currentTitle = pageState.title,
                    currentUrl = browserAddressText(pageState.url, tab.input),
                    history = history,
                    bookmarks = bookmarks,
                    webApps = webApps,
                    onCancel = ::closePanel,
                    onGo = { value ->
                        tab.input = value
                        controller.load(value, settings.searchUrlTemplate)
                        closePanel()
                        message = null
                    },
                    onOpenWebApp = { webAppId ->
                        activity.startActivity(WebAppActivity.intent(activity, webAppId, true))
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
                                        message = context.getString(R.string.update_asset_unavailable)
                                    } else {
                                        runCatching { activity.startActivity(installIntent) }
                                            .onFailure { message = it.message ?: context.getString(R.string.browser_open_installer_failed) }
                                    }
                                } else if (state.status == UpdateDownloadState.STATUS_ERROR) {
                                    message = state.message.ifBlank { context.getString(R.string.update_download_failed) }
                                } else {
                                    message = state.message.ifBlank { context.getString(R.string.update_downloading) }
                                }
                            }
                        } else {
                            val openIntent = downloadHandler.openIntent(entry)
                            if (openIntent == null) {
                                message = context.getString(R.string.download_file_not_ready)
                            } else {
                                runCatching { activity.startActivity(openIntent) }
                                    .onFailure { message = it.message ?: context.getString(R.string.download_file_open_failed) }
                            }
                        }
                    },
                    onRetry = ::retryDownload,
                    onCancel = ::cancelDownload,
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
                    onClearFinished = ::clearFinishedDownloads,
                    canRetry = ::canRetryDownload,
                    canCancel = ::canCancelDownload,
                    canClear = ::canClearDownload
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
                            extensionMessage = context.getString(R.string.extensions_searching_amo)
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
                                        results.isEmpty() -> context.getString(R.string.extensions_no_android_addons)
                                        installableCount == 0 -> context.getString(R.string.extensions_all_matches_installed)
                                        installedMatches > 0 -> context.getString(R.string.extensions_found_with_installed, installableCount, installedMatches)
                                        else -> null
                                    }
                                }
                                .onFailure { extensionMessage = it.message ?: context.getString(R.string.extensions_amo_search_failed) }
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
                                    extensionMessage = context.getString(R.string.extensions_installed_name, addon.name)
                                }
                                .onFailure { extensionMessage = it.message ?: context.getString(R.string.extensions_install_failed) }
                            installingAddonGuid = null
                        }
                    },
                    onToggleEnabled = { extension ->
                        scope.launch {
                            runCatching { app.extensions.setEnabled(extension.guid, !extension.enabled) }
                                .onFailure { extensionMessage = it.message ?: context.getString(R.string.extensions_update_failed) }
                        }
                    },
                    onUninstall = { extension ->
                        scope.launch {
                            runCatching { app.extensions.uninstall(extension.guid) }
                                .onFailure { extensionMessage = it.message ?: context.getString(R.string.extensions_uninstall_failed) }
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
                    onClose = ::closeBrowserTabById,
                    onNewTab = {
                        val newTab = createBrowserTab(GeckoSessionController.HOME_URL)
                        tabs.add(newTab)
                        selectedTabId = newTab.id
                        closePanel()
                    }
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
                        downloads = downloads,
                        addressSecurityLevel = addressSecurityLevel(pageState.securityLevel, settings),
                        onOpenSearchPage = {
                            showPanel(BrowserPanel.Search)
                            message = null
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
                            val newTab = createBrowserTab(GeckoSessionController.HOME_URL)
                            tabs.add(newTab)
                            selectedTabId = newTab.id
                            closePanel()
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
                        onShowBookmarks = {
                            tab.input = GeckoSessionController.BOOKMARKS_URL
                            controller.loadBookmarks()
                            message = null
                        },
                        onShowHistory = {
                            tab.input = GeckoSessionController.HISTORY_URL
                            controller.loadHistory()
                            message = null
                        },
                        onShowSettings = {
                            tab.input = GeckoSessionController.SETTINGS_URL
                            controller.loadSettings()
                        },
                        onShowDownloads = { showPanel(BrowserPanel.Downloads) },
                        onShowExtensions = { showPanel(BrowserPanel.Extensions) },
                        onExtensionClick = { extension ->
                            scope.launch {
                                runCatching { app.extensions.clickMenuAction(extension.guid) }
                                    .onFailure { message = it.message ?: context.getString(R.string.extensions_popup_unavailable) }
                            }
                        },
                        onInstall = install@{
                            installedWebApp?.let { webApp ->
                                scope.launch {
                                    runCatching { app.webApps.delete(webApp.id) }
                                        .onSuccess {
                                            message = if (it) {
                                                context.getString(R.string.webapp_uninstalled, webApp.name)
                                            } else {
                                                context.getString(R.string.webapp_not_found)
                                            }
                                        }
                                        .onFailure { message = it.message ?: context.getString(R.string.webapp_uninstall_failed) }
                                }
                                return@install
                            }
                            if (GeckoSessionController.isInternalUrl(pageState.url)) {
                                message = context.getString(R.string.webapp_open_page_before_install)
                                return@install
                            }
                            val title = pageState.title.ifBlank { tab.input }
                            val url = pageState.url.ifBlank { tab.input }
                            showInstallWebAppDetailsDialog(title, url, currentIconPath)
                        }
                    )
                }
                if (settings.toolbarPosition == BrowserSettings.TOOLBAR_POSITION_BOTTOM) {
                    BrowserContent(
                        controller = controller,
                        tabId = tab.id,
                        extensionPopup = extensionPopup,
                        onClosePopup = app.extensions::closePopup,
                        modifier = Modifier.weight(1f),
                        onContentTouchStarted = ::handlePageContentTouchStarted
                    )
                    toolbar()
                } else {
                    toolbar()
                    BrowserContent(
                        controller = controller,
                        tabId = tab.id,
                        extensionPopup = extensionPopup,
                        onClosePopup = app.extensions::closePopup,
                        modifier = Modifier.weight(1f),
                        onContentTouchStarted = ::handlePageContentTouchStarted
                    )
                }
            } else {
                BrowserContent(
                    controller = controller,
                    tabId = tab.id,
                    extensionPopup = extensionPopup,
                    onClosePopup = app.extensions::closePopup,
                    onContentTouchStarted = ::handlePageContentTouchStarted
                )
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
                onCopyImage = { url -> copyContextUrl("image", url, imageCopiedText) },
                onOpenLink = ::openLinkInBackgroundTab,
                onCopyLink = { url -> copyContextUrl("link", url, linkCopiedText) }
            )
        }
        authPrompt?.let { request ->
            AuthPromptDialog(
                request = request,
                onFinished = { authPrompt = null }
            )
        }
        geckoPrompt?.let { request ->
            GeckoPromptDialog(
                prompt = request,
                onFinished = { geckoPrompt = null }
            )
        }
        webAppDetailsDialog?.let { dialog ->
            WebAppDetailsDialog(
                state = dialog,
                onNameChange = { name -> webAppDetailsDialog = dialog.copy(name = name) },
                onStartUrlChange = { url -> webAppDetailsDialog = dialog.copy(startUrl = url) },
                onSelect = { selection -> webAppDetailsDialog = dialog.copy(selectedIcon = selection) },
                onChooseImage = { webAppIconImageLauncher.launch("image/*") },
                onConfirm = { confirmWebAppDetailsDialog(dialog) },
                onDismiss = { webAppDetailsDialog = null }
            )
        }
        if (!pageFullScreen) {
            BrowserTip(
                message = message,
                toolbarPosition = settings.toolbarPosition,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

private fun shortcutRequestMessage(context: Context, result: PinnedShortcutRequestResult): String =
    when (result) {
        PinnedShortcutRequestResult.Requested -> context.getString(R.string.shortcut_request_create)
        PinnedShortcutRequestResult.Unsupported -> context.getString(R.string.shortcut_request_unsupported)
        PinnedShortcutRequestResult.Failed -> context.getString(R.string.shortcut_request_failed)
        PinnedShortcutRequestResult.WebAppNotFound -> context.getString(R.string.webapp_not_found)
    }

private fun addressSecurityLevel(
    pageSecurity: GeckoPageSecurity,
    settings: BrowserSettings
): AddressSecurityLevel =
    when (pageSecurity) {
        GeckoPageSecurity.Insecure -> AddressSecurityLevel.Insecure
        GeckoPageSecurity.Secure,
        GeckoPageSecurity.Verified -> {
            if (settings.dohEnabled && settings.echEnabled) {
                AddressSecurityLevel.Enhanced
            } else {
                AddressSecurityLevel.Secure
            }
        }
        GeckoPageSecurity.Neutral -> AddressSecurityLevel.Neutral
    }
