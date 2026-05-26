package com.dadigua.hyperbrowser

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dadigua.hyperbrowser.data.WebAppDefinition
import com.dadigua.hyperbrowser.extensions.AmoAddonListing
import com.dadigua.hyperbrowser.extensions.ExtensionRepository
import com.dadigua.hyperbrowser.ui.browser.BrowserActivity
import com.dadigua.hyperbrowser.ui.theme.HyperBrowserTheme
import com.dadigua.hyperbrowser.ui.webapp.WebAppActivity
import com.dadigua.hyperbrowser.webapp.WebAppRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        val app = application as HyperBrowserApp
        MainViewModel.factory(app.webApps, app.extensions)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HyperBrowserTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        viewModel = viewModel,
                        onOpenBrowser = { url ->
                            startActivity(BrowserActivity.intent(this, url))
                        },
                        onOpenWebApp = { webApp ->
                            startActivity(WebAppActivity.intent(this, webApp.id, true))
                        }
                    )
                }
            }
        }
    }
}

class MainViewModel(
    private val webApps: WebAppRepository,
    private val extensions: ExtensionRepository
) : ViewModel() {
    val installedWebApps: StateFlow<List<WebAppDefinition>> =
        webApps.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val installedExtensions =
        extensions.observeInstalled().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var extensionResults by mutableStateOf<List<AmoAddonListing>>(emptyList())
        private set

    var extensionMessage by mutableStateOf<String?>(null)
        private set

    fun searchExtensions(query: String) {
        viewModelScope.launch {
            extensionMessage = "Searching AMO..."
            runCatching { extensions.searchAndroidAddons(query) }
                .onSuccess {
                    extensionResults = it
                    extensionMessage = if (it.isEmpty()) "No Android add-ons found." else null
                }
                .onFailure { extensionMessage = it.message ?: "AMO search failed." }
        }
    }

    fun installExtension(addon: AmoAddonListing) {
        viewModelScope.launch {
            extensionMessage = "Downloading ${addon.name}..."
            runCatching { extensions.downloadAndInstall(addon) }
                .onSuccess { extensionMessage = "Installed ${addon.name}." }
                .onFailure { extensionMessage = it.message ?: "Extension install failed." }
        }
    }

    fun setExtensionEnabled(guid: String, enabled: Boolean) {
        viewModelScope.launch {
            runCatching { extensions.setEnabled(guid, enabled) }
                .onFailure { extensionMessage = it.message ?: "Unable to update extension." }
        }
    }

    fun uninstallExtension(guid: String) {
        viewModelScope.launch {
            runCatching { extensions.uninstall(guid) }
                .onFailure { extensionMessage = it.message ?: "Unable to uninstall extension." }
        }
    }

    companion object {
        fun factory(webApps: WebAppRepository, extensions: ExtensionRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MainViewModel(webApps, extensions) as T
            }
    }
}

@Composable
private fun MainScreen(
    viewModel: MainViewModel,
    onOpenBrowser: (String) -> Unit,
    onOpenWebApp: (WebAppDefinition) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val webApps by viewModel.installedWebApps.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        FirefoxLikeTopBar(
            onOpenBrowser = onOpenBrowser,
            tabCount = webApps.size.coerceAtLeast(1),
            onOpenExtensions = { selectedTab = 2 }
        )
        if (selectedTab != 0) {
            TabRow(selectedTabIndex = selectedTab - 1) {
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("WebApps") })
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Extensions") })
            }
        }
        when (selectedTab) {
            0 -> BrowserHome(
                viewModel = viewModel,
                webApps = webApps,
                onOpenBrowser = onOpenBrowser,
                onOpenWebApp = onOpenWebApp,
                onShowWebApps = { selectedTab = 1 },
                onShowExtensions = { selectedTab = 2 }
            )
            1 -> WebAppList(webApps, onOpenWebApp)
            2 -> ExtensionStore(viewModel)
        }
    }
}

@Composable
private fun FirefoxLikeTopBar(
    onOpenBrowser: (String) -> Unit,
    tabCount: Int,
    onOpenExtensions: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(32.dp))
                .background(Color(0xFFE7E8EE))
                .clickable { if (query.isNotBlank()) onOpenBrowser(query) }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Text("G", color = Color(0xFF4285F4), fontWeight = FontWeight.Bold, fontSize = 22.sp)
            }
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge.copy(color = Color(0xFF202124)),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                decorationBox = { inner ->
                    if (query.isBlank()) {
                        Text("搜索或输入网址", color = Color(0xFF6F737B), style = MaterialTheme.typography.titleLarge)
                    }
                    inner()
                }
            )
        }
        IconButton(onClick = { }) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                Text(tabCount.toString(), fontWeight = FontWeight.Bold)
            }
        }
        IconButton(onClick = onOpenExtensions) {
            Text("⋮", fontSize = 30.sp)
        }
    }
}

@Composable
private fun BrowserHome(
    viewModel: MainViewModel,
    webApps: List<WebAppDefinition>,
    onOpenBrowser: (String) -> Unit,
    onOpenWebApp: (WebAppDefinition) -> Unit,
    onShowWebApps: () -> Unit,
    onShowExtensions: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        BrandHeader()
        ShortcutSection(onOpenBrowser)
        WebAppPreview(webApps, onOpenBrowser, onOpenWebApp, onShowWebApps)
        ExtensionPreview(viewModel, onShowExtensions)
        Spacer(Modifier.height(36.dp))
    }
}

@Composable
private fun BrandHeader() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .size(66.dp)
                .clip(CircleShape)
                .background(Color(0xFFE6F4F1)),
            contentAlignment = Alignment.Center
        ) {
            Text("H", color = Color(0xFF126D6A), fontWeight = FontWeight.Black, fontSize = 36.sp)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text("Hyper Browser", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("把任意网站安装成 App", color = Color(0xFF5F6368))
        }
        Spacer(Modifier.weight(1f))
        Text("⌘", fontSize = 26.sp, color = Color(0xFF202124))
    }
}

@Composable
private fun ShortcutSection(onOpenBrowser: (String) -> Unit) {
    val shortcuts = listOf(
        "Google" to "https://google.com",
        "Bilibili" to "https://m.bilibili.com",
        "GitHub" to "https://github.com",
        "AMO" to "https://addons.mozilla.org/android/",
        "NeverSSL" to "http://neverssl.com"
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(title = "快捷方式", action = "显示全部", onAction = { })
        LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
            items(shortcuts, key = { it.first }) { shortcut ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(92.dp)
                        .clickable { onOpenBrowser(shortcut.second) }
                ) {
                    Card(
                        shape = CircleShape,
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                        modifier = Modifier.size(68.dp)
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(shortcut.first.first().toString(), fontWeight = FontWeight.Bold, fontSize = 24.sp)
                        }
                    }
                    Text(
                        shortcut.first,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun WebAppPreview(
    webApps: List<WebAppDefinition>,
    onOpenBrowser: (String) -> Unit,
    onOpenWebApp: (WebAppDefinition) -> Unit,
    onShowWebApps: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title = "WebApps", action = "管理", onAction = onShowWebApps)
        if (webApps.isEmpty()) {
            InfoCard(
                title = "还没有安装 WebApp",
                body = "打开网站后点 Install，HTTP、HTTPS、内网页都可以变成独立任务卡片。",
                button = "打开示例",
                onClick = { onOpenBrowser("http://neverssl.com") }
            )
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(webApps.take(8), key = { it.id }) { webApp ->
                    Card(
                        modifier = Modifier
                            .width(220.dp)
                            .clickable { onOpenWebApp(webApp) },
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(webApp.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(webApp.startUrl, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Color(0xFF5F6368))
                            if (webApp.startUrl.startsWith("http://")) {
                                Text("HTTP", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtensionPreview(viewModel: MainViewModel, onShowExtensions: () -> Unit) {
    val installed by viewModel.installedExtensions.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title = "扩展", action = "打开商店", onAction = onShowExtensions)
        InfoCard(
            title = if (installed.isEmpty()) "Firefox Android 插件商店" else "已安装 ${installed.size} 个扩展",
            body = "搜索 AMO Android 插件，查看权限后下载 XPI 并安装到 GeckoView。",
            button = "搜索扩展",
            onClick = onShowExtensions
        )
    }
}

@Composable
private fun SectionHeader(title: String, action: String, onAction: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onAction) { Text("$action  ›") }
    }
}

@Composable
private fun InfoCard(title: String, body: String, button: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(body, color = Color(0xFF5F6368))
            }
            FilledTonalButton(onClick = onClick) { Text(button) }
        }
    }
}

@Composable
private fun WebAppList(webApps: List<WebAppDefinition>, onOpenWebApp: (WebAppDefinition) -> Unit) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Text("Installed WebApps", style = MaterialTheme.typography.headlineSmall) }
        if (webApps.isEmpty()) {
            item { Text("Open a page in Browser, then install it as an app.") }
        }
        items(webApps, key = { it.id }) { webApp ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenWebApp(webApp) },
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(webApp.name, style = MaterialTheme.typography.titleMedium)
                    Text(webApp.startUrl, style = MaterialTheme.typography.bodySmall)
                    if (webApp.startUrl.startsWith("http://")) {
                        Text("Insecure HTTP app", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtensionStore(viewModel: MainViewModel) {
    var query by remember { mutableStateOf("ublock") }
    val installed by viewModel.installedExtensions.collectAsState()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Firefox Android Extensions", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search AMO") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = { viewModel.searchExtensions(query) }) {
                    Text("Search")
                }
            }
            viewModel.extensionMessage?.let {
                Spacer(Modifier.height(8.dp))
                Text(it)
            }
        }
        if (installed.isNotEmpty()) {
            item { Text("Installed", style = MaterialTheme.typography.titleLarge) }
            items(installed, key = { it.guid }) { ext ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(ext.name, style = MaterialTheme.typography.titleMedium)
                        Text("${ext.version} - ${if (ext.enabled) "Enabled" else "Disabled"}")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.setExtensionEnabled(ext.guid, !ext.enabled) }) {
                                Text(if (ext.enabled) "Disable" else "Enable")
                            }
                            Button(onClick = { viewModel.uninstallExtension(ext.guid) }) {
                                Text("Uninstall")
                            }
                        }
                    }
                }
            }
        }
        if (viewModel.extensionResults.isNotEmpty()) {
            item { Text("AMO Android Results", style = MaterialTheme.typography.titleLarge) }
        }
        items(viewModel.extensionResults, key = { it.guid }) { addon ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(addon.name, style = MaterialTheme.typography.titleMedium)
                    Text("Version ${addon.version} - ${addon.userCount} users")
                    Text("Permissions: ${addon.permissions.take(6).joinToString()}")
                    Button(onClick = { viewModel.installExtension(addon) }) {
                        Text("Install from AMO")
                    }
                }
            }
        }
    }
}
