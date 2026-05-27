package com.dadigua.hyperbrowser.ui.browser

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dadigua.hyperbrowser.HyperBrowserApp
import com.dadigua.hyperbrowser.browser.BrowserBookmark
import com.dadigua.hyperbrowser.browser.BrowserHistoryEntry
import com.dadigua.hyperbrowser.browser.BrowserProfileStore
import com.dadigua.hyperbrowser.data.InstalledExtensionState
import com.dadigua.hyperbrowser.extensions.AmoAddonListing
import com.dadigua.hyperbrowser.extensions.ExtensionMenuActionState
import com.dadigua.hyperbrowser.extensions.ExtensionNewTabRequest
import com.dadigua.hyperbrowser.extensions.ExtensionPopupState
import com.dadigua.hyperbrowser.gecko.GeckoBrowserView
import com.dadigua.hyperbrowser.gecko.GeckoPageState
import com.dadigua.hyperbrowser.gecko.GeckoSessionController
import com.dadigua.hyperbrowser.gecko.GeckoSessionView
import com.dadigua.hyperbrowser.ui.theme.HyperBrowserTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private val ChromeActionBarHeight = 48.dp
private val ChromeActionButtonSize = 44.dp
private val ChromeActionIconSize = 28.sp

class BrowserActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialUrl = intent.getStringExtra(EXTRA_URL) ?: GeckoSessionController.HOME_URL
        setContent {
            HyperBrowserTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BrowserScreen(
                        app = application as HyperBrowserApp,
                        initialUrl = initialUrl
                    )
                }
            }
        }
    }

    companion object {
        private const val EXTRA_URL = "extra_url"

        fun intent(context: Context, url: String): Intent =
            Intent(context, BrowserActivity::class.java).putExtra(EXTRA_URL, url)
    }
}

@Composable
private fun BrowserScreen(app: HyperBrowserApp, initialUrl: String) {
    val tabs = remember {
        mutableStateListOf(BrowserTabRuntime.create(app, initialUrl))
    }
    var selectedTabId by remember { mutableStateOf(tabs.first().id) }
    var showTabs by remember { mutableStateOf(false) }
    val selectedIndex = tabs.indexOfFirst { it.id == selectedTabId }.takeIf { it >= 0 } ?: 0
    val tab = tabs.getOrNull(selectedIndex) ?: tabs.first()
    val controller = tab.controller
    val pageState by controller.state.collectAsState()
    val onHomePage = GeckoSessionController.isHomeUrl(pageState.url)
    val profileStore = remember { BrowserProfileStore(app) }
    val history by profileStore.observeHistory().collectAsState()
    val bookmarks by profileStore.observeBookmarks().collectAsState()
    val installedExtensions by app.extensions.observeInstalled().collectAsState()
    val extensionActions by app.extensions.observeMenuActions().collectAsState()
    val extensionPopup by app.extensions.observePopup().collectAsState()
    val extensionNewTabRequest by app.extensions.observeNewTabRequests().collectAsState()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showExtensions by remember { mutableStateOf(false) }
    var extensionQuery by remember { mutableStateOf("ublock") }
    var extensionResults by remember { mutableStateOf<List<AmoAddonListing>>(emptyList()) }
    var extensionMessage by remember { mutableStateOf<String?>(null) }
    var installingAddonGuid by remember { mutableStateOf<String?>(null) }

    BackHandler {
        when {
            extensionPopup != null -> app.extensions.closePopup()
            showSearch -> showSearch = false
            showBookmarks -> showBookmarks = false
            showHistory -> showHistory = false
            showExtensions -> showExtensions = false
            showTabs -> showTabs = false
            pageState.canGoBack -> controller.goBack()
            !onHomePage && tab.hasHomeBackEntry -> {
                tab.input = GeckoSessionController.HOME_URL
                controller.load(GeckoSessionController.HOME_URL)
            }
            else -> controller.goBack()
        }
    }

    LaunchedEffect(pageState.url, pageState.title) {
        if (pageState.url.isNotBlank() && !GeckoSessionController.isInternalUrl(pageState.url)) {
            profileStore.recordVisit(pageState.url, pageState.title)
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
            showExtensions = false
            message = "Opened ${request.title}."
            app.extensions.consumeNewTabRequest()
        }
    }

    DisposableEffect(Unit) {
        onDispose { tabs.forEach { it.controller.close() } }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (showSearch) {
            SearchPage(
                initialInput = if (onHomePage) "" else tab.input,
                history = history,
                bookmarks = bookmarks,
                onCancel = { showSearch = false },
                onGo = { value ->
                    if (onHomePage) tab.hasHomeBackEntry = true
                    tab.input = value
                    val target = GeckoSessionController.normalizeUrl(value)
                    controller.load(target)
                    showSearch = false
                    message = null
                }
            )
        } else if (showBookmarks) {
            BookmarksPage(
                bookmarks = bookmarks,
                onBack = { showBookmarks = false },
                onOpen = { url ->
                    if (onHomePage) tab.hasHomeBackEntry = true
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
                    if (onHomePage) tab.hasHomeBackEntry = true
                    tab.input = url
                    controller.load(url)
                    showHistory = false
                    message = null
                },
                onRemove = profileStore::removeHistoryEntry,
                onClear = profileStore::clearHistory
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
                            .onSuccess {
                                extensionResults = it
                                extensionMessage = if (it.isEmpty()) "No Android add-ons found." else null
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
                selectedTabId = selectedTabId,
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
                        val replacement = BrowserTabRuntime.create(app, GeckoSessionController.HOME_URL)
                        tabs.add(replacement)
                        selectedTabId = replacement.id
                    } else if (selectedTabId == id) {
                        selectedTabId = tabs[oldIndex.coerceAtMost(tabs.lastIndex)].id
                    }
                },
                onNewTab = {
                    val newTab = BrowserTabRuntime.create(app, GeckoSessionController.HOME_URL)
                    tabs.add(newTab)
                    selectedTabId = newTab.id
                    showTabs = false
                }
            )
        } else {
            BrowserToolbar(
                input = tab.input,
                pageState = pageState,
                message = message,
                tabCount = tabs.size,
                bookmarked = !GeckoSessionController.isInternalUrl(pageState.url) &&
                    profileStore.isBookmarked(pageState.url.ifBlank { tab.input }),
                installedExtensions = installedExtensions,
                extensionActions = extensionActions,
                onAddressClick = { showSearch = true },
                onLoad = {
                    if (onHomePage) tab.hasHomeBackEntry = true
                    val target = GeckoSessionController.normalizeUrl(tab.input)
                    controller.load(target)
                    if (!GeckoSessionController.isInternalUrl(target)) {
                        profileStore.recordVisit(target, pageState.title)
                    }
                },
                onBack = controller::goBack,
                onForward = controller::goForward,
                onReload = controller::reload,
                onShowTabs = { showTabs = true },
                onNewTab = {
                    val newTab = BrowserTabRuntime.create(app, GeckoSessionController.HOME_URL)
                    tabs.add(newTab)
                    selectedTabId = newTab.id
                    showTabs = false
                    message = null
                },
                onHome = {
                    tab.hasHomeBackEntry = true
                    tab.input = GeckoSessionController.HOME_URL
                    controller.load(GeckoSessionController.HOME_URL)
                    message = null
                },
                onToggleBookmark = {
                    val url = pageState.url.ifBlank { tab.input }
                    profileStore.toggleBookmark(url, pageState.title)
                },
                onShowBookmarks = { showBookmarks = true },
                onShowHistory = { showHistory = true },
                onShowExtensions = { showExtensions = true },
                onExtensionClick = { extension ->
                    scope.launch {
                        runCatching { app.extensions.clickMenuAction(extension.guid) }
                            .onFailure { message = it.message ?: "Extension popup unavailable." }
                    }
                },
                onInstall = install@{
                    if (GeckoSessionController.isInternalUrl(pageState.url)) {
                        message = "Open a web page before installing it as a WebApp."
                        return@install
                    }
                    scope.launch {
                        val title = pageState.title.ifBlank { tab.input }
                        val url = pageState.url.ifBlank { tab.input }
                        runCatching { app.webApps.installFromPage(title, url) }
                            .onSuccess { message = "Installed ${it.name} as WebApp." }
                            .onFailure { message = it.message ?: "Install failed." }
                    }
                }
            )
            Box(modifier = Modifier.fillMaxSize()) {
                if (onHomePage) {
                    BrowserHomePage(
                        history = history,
                        bookmarks = bookmarks,
                        onSearch = { showSearch = true },
                        onOpen = { url ->
                            tab.hasHomeBackEntry = true
                            tab.input = url
                            controller.load(url)
                            message = null
                        },
                        onShowBookmarks = { showBookmarks = true },
                        onShowHistory = { showHistory = true },
                        onShowExtensions = { showExtensions = true }
                    )
                } else {
                    key(tab.id) {
                        GeckoBrowserView(controller = controller, modifier = Modifier.fillMaxSize())
                    }
                }
                extensionPopup?.let { popup ->
                    ExtensionPopupOverlay(
                        popup = popup,
                        onClose = app.extensions::closePopup
                    )
                }
            }
        }
    }
}

private class BrowserTabRuntime private constructor(
    val id: String,
    val controller: GeckoSessionController,
    input: String
) {
    var input by mutableStateOf(input)
    var hasHomeBackEntry by mutableStateOf(GeckoSessionController.isHomeUrl(input))

    companion object {
        fun create(app: HyperBrowserApp, url: String): BrowserTabRuntime =
            BrowserTabRuntime(
                id = UUID.randomUUID().toString(),
                controller = GeckoSessionController(app, url),
                input = url
            )

        fun fromExtensionRequest(app: HyperBrowserApp, request: ExtensionNewTabRequest): BrowserTabRuntime =
            BrowserTabRuntime(
                id = UUID.randomUUID().toString(),
                controller = GeckoSessionController(app, request.url, request.session),
                input = request.url
            )
    }
}

private fun browserAddressText(url: String, input: String): String {
    if (GeckoSessionController.isHomeUrl(url)) return "搜索或输入网址"
    return url.ifBlank { input.ifBlank { "搜索或输入网址" } }
}

@Composable
private fun BrowserHomePage(
    history: List<BrowserHistoryEntry>,
    bookmarks: List<BrowserBookmark>,
    onSearch: () -> Unit,
    onOpen: (String) -> Unit,
    onShowBookmarks: () -> Unit,
    onShowHistory: () -> Unit,
    onShowExtensions: () -> Unit
) {
    val recentItems = (bookmarks.map { it.title.ifBlank { it.url } to it.url } +
        history.map { it.title.ifBlank { it.url } to it.url })
        .distinctBy { it.second }
        .filterNot { GeckoSessionController.isInternalUrl(it.second) }
        .take(6)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8F9FD))
            .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "Hyper Browser",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF202124)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color.White)
                        .border(1.dp, Color(0xFFDADCE0), RoundedCornerShape(28.dp))
                        .clickable(onClick = onSearch)
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("G", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4285F4))
                    Text(
                        text = "搜索或输入网址",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF6F737B),
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = onShowBookmarks) { Text("Bookmarks") }
                FilledTonalButton(onClick = onShowHistory) { Text("History") }
                FilledTonalButton(onClick = onShowExtensions) { Text("Extensions") }
            }
        }
        if (recentItems.isNotEmpty()) {
            item {
                Text(
                    text = "Recent",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF202124)
                )
            }
            items(recentItems) { (title, url) ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White)
                        .clickable { onOpen(url) }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = Color(0xFF202124)
                    )
                    Text(
                        text = url,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = Color(0xFF5F6368)
                    )
                }
            }
        }
    }
}

@Composable
private fun BrowserToolbar(
    input: String,
    pageState: GeckoPageState,
    message: String?,
    tabCount: Int,
    bookmarked: Boolean,
    installedExtensions: List<InstalledExtensionState>,
    extensionActions: Map<String, ExtensionMenuActionState>,
    onAddressClick: () -> Unit,
    onLoad: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onHome: () -> Unit,
    onShowTabs: () -> Unit,
    onNewTab: () -> Unit,
    onToggleBookmark: () -> Unit,
    onShowBookmarks: () -> Unit,
    onShowHistory: () -> Unit,
    onShowExtensions: () -> Unit,
    onExtensionClick: (InstalledExtensionState) -> Unit,
    onInstall: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var extensionsExpanded by remember { mutableStateOf(false) }
    val enabledExtensions = installedExtensions.filter { it.enabled }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.height(ChromeActionBarHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            IconButton(onClick = onHome, modifier = Modifier.size(ChromeActionButtonSize)) {
                Text("⌂", fontSize = ChromeActionIconSize, color = Color(0xFF202124))
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(ChromeActionButtonSize)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0xFFE9EAF1))
                    .clickable(onClick = onAddressClick)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (pageState.insecureHttp) "!" else "G", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (pageState.insecureHttp) Color(0xFFC5221F) else Color(0xFF4285F4))
                Text(
                    text = browserAddressText(pageState.url, input),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (pageState.url.isBlank() && input.isBlank()) Color(0xFF6F737B) else Color(0xFF202124),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp)
                )
            }
            IconButton(
                onClick = onShowTabs,
                modifier = Modifier
                    .defaultMinSize(minWidth = ChromeActionButtonSize, minHeight = ChromeActionButtonSize)
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Color.Transparent)
                        .borderForChromeTabCounter(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(tabCount.toString(), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF202124))
                }
            }
            Box {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(ChromeActionButtonSize)) {
                    Text("⋮", fontSize = ChromeActionIconSize)
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    BrowserMenuPanel(
                        bookmarked = bookmarked,
                        enabledExtensions = enabledExtensions,
                        installedExtensionCount = installedExtensions.size,
                        extensionActions = extensionActions,
                        extensionsExpanded = extensionsExpanded,
                        onExtensionsExpandedChange = { extensionsExpanded = it },
                        onNewTab = { showMenu = false; onNewTab() },
                        onBack = { showMenu = false; onBack() },
                        onForward = { showMenu = false; onForward() },
                        onReload = { showMenu = false; onReload() },
                        onToggleBookmark = { showMenu = false; onToggleBookmark() },
                        onShowBookmarks = { showMenu = false; onShowBookmarks() },
                        onShowHistory = { showMenu = false; onShowHistory() },
                        onShowExtensions = { showMenu = false; onShowExtensions() },
                        onExtensionClick = { showMenu = false; onExtensionClick(it) },
                        onInstall = { showMenu = false; onInstall() }
                    )
                }
            }
        }
        if (pageState.insecureHttp) Text("Insecure HTTP page", color = MaterialTheme.colorScheme.error)
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun BrowserMenuPanel(
    bookmarked: Boolean,
    enabledExtensions: List<InstalledExtensionState>,
    installedExtensionCount: Int,
    extensionActions: Map<String, ExtensionMenuActionState>,
    extensionsExpanded: Boolean,
    onExtensionsExpandedChange: (Boolean) -> Unit,
    onNewTab: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onToggleBookmark: () -> Unit,
    onShowBookmarks: () -> Unit,
    onShowHistory: () -> Unit,
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
            BrowserMenuRow(label = "New tab", leading = "+", onClick = onNewTab)
            BrowserMenuRow(
                label = if (bookmarked) "Edit bookmark" else "Bookmark this page",
                leading = if (bookmarked) "*" else "☆",
                onClick = onToggleBookmark
            )
            BrowserMenuRow(label = "Install as WebApp", leading = "A", onClick = onInstall)
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
                        leading = "+",
                        description = "Search Android add-ons",
                        indent = 28.dp,
                        onClick = onShowExtensions
                    )
                } else {
                    enabledExtensions.forEach { extension ->
                        val action = extensionActions[extension.guid]
                        BrowserMenuRow(
                            label = action?.title ?: extension.name,
                            leading = "E",
                            leadingIcon = action?.icon,
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
                        leading = ">",
                        description = "$installedExtensionCount installed",
                        indent = 28.dp,
                        onClick = onShowExtensions
                    )
                }
            }
        }
        MenuGroupBox {
            BrowserMenuRow(label = "History", leading = "H", onClick = onShowHistory)
            BrowserMenuRow(label = "Bookmarks", leading = "B", onClick = onShowBookmarks)
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
        leading = "E",
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
    leading: String,
    leadingIcon: android.graphics.Bitmap? = null,
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
            } else {
                Text(leading, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF202124))
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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(18.dp)
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Color(0x66000000))
        )
        Card(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth()
                .fillMaxHeight(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ChromeActionBarHeight)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        popup.title,
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    TextButton(onClick = onClose) {
                        Text("Close")
                    }
                }
                HorizontalDivider(color = Color(0xFFE0E3EA))
                GeckoSessionView(session = popup.session, modifier = Modifier.fillMaxSize())
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
            .statusBarsPadding()
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
            .statusBarsPadding()
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
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
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
            items(installed, key = { it.guid }) { extension ->
                InstalledExtensionRow(
                    extension = extension,
                    onToggle = { onToggleEnabled(extension) },
                    onUninstall = { onUninstall(extension) }
                )
            }
        }
        if (results.isNotEmpty()) {
            item {
                Text(
                    "Recommended from AMO",
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            items(results, key = { it.guid }) { addon ->
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
    selectedTabId: String,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNewTab: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF4F5FA))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ChromeTabHeader(count = tabs.size, onNewTab = onNewTab)
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                items(tabs, key = { it.id }) { tab ->
                    val pageState by tab.controller.state.collectAsState()
                    ChromeTabCard(
                        tab = tab,
                        pageState = pageState,
                        selected = tab.id == selectedTabId,
                        onSelect = { onSelect(tab.id) },
                        onClose = { onClose(tab.id) }
                    )
                }
                item {
                    Spacer(modifier = Modifier.height(120.dp))
                }
            }
        }
    }
}

@Composable
private fun ChromeTabHeader(count: Int, onNewTab: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ChromeActionBarHeight)
                .padding(horizontal = 18.dp)
        ) {
            IconButton(
                onClick = onNewTab,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(ChromeActionButtonSize)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFFD8DDEA))
            ) {
                Text("+", fontSize = ChromeActionIconSize, color = Color(0xFF202124))
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
                        .background(Color(0xFFF8F9FE)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .border(3.dp, Color(0xFF202124), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(count.toString(), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(26.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("▦", fontSize = ChromeActionIconSize, color = Color(0xFF202124))
                }
            }
            IconButton(
                onClick = { },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(ChromeActionButtonSize)
            ) {
                Text("⋮", fontSize = ChromeActionIconSize, color = Color(0xFF202124))
            }
        }
        HorizontalDivider(color = Color(0xFFDADCE3))
    }
}

@Composable
private fun ChromeTabCard(
    tab: BrowserTabRuntime,
    pageState: GeckoPageState,
    selected: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.72f),
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
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    Text("G", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4285F4))
                }
                Text(
                    pageState.title.ifBlank { "打开新的标签页" },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .clickable { onSelect() },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (selected) Color.White else Color(0xFF202124)
                )
                TextButton(onClick = onClose, shape = RectangleShape, modifier = Modifier.size(44.dp)) {
                    Text("×", fontSize = 34.sp, color = if (selected) Color.White else Color(0xFF202124))
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
