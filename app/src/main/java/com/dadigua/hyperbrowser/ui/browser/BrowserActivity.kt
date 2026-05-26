package com.dadigua.hyperbrowser.ui.browser

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dadigua.hyperbrowser.HyperBrowserApp
import com.dadigua.hyperbrowser.browser.BrowserBookmark
import com.dadigua.hyperbrowser.browser.BrowserHistoryEntry
import com.dadigua.hyperbrowser.browser.BrowserProfileStore
import com.dadigua.hyperbrowser.gecko.GeckoBrowserView
import com.dadigua.hyperbrowser.gecko.GeckoPageState
import com.dadigua.hyperbrowser.gecko.GeckoSessionController
import com.dadigua.hyperbrowser.ui.theme.HyperBrowserTheme
import kotlinx.coroutines.launch
import java.util.UUID

class BrowserActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialUrl = intent.getStringExtra(EXTRA_URL) ?: "https://example.com"
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
    val profileStore = remember { BrowserProfileStore(app) }
    val history by profileStore.observeHistory().collectAsState()
    val bookmarks by profileStore.observeBookmarks().collectAsState()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var showSearch by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { tabs.forEach { it.controller.close() } }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (showSearch) {
            SearchPage(
                initialInput = tab.input,
                history = history,
                bookmarks = bookmarks,
                onCancel = { showSearch = false },
                onGo = { value ->
                    tab.input = value
                    val target = GeckoSessionController.normalizeUrl(value)
                    controller.load(target)
                    profileStore.recordVisit(target, pageState.title)
                    showSearch = false
                    message = null
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
                        val replacement = BrowserTabRuntime.create(app, "https://example.com")
                        tabs.add(replacement)
                        selectedTabId = replacement.id
                    } else if (selectedTabId == id) {
                        selectedTabId = tabs[oldIndex.coerceAtMost(tabs.lastIndex)].id
                    }
                },
                onNewTab = {
                    val newTab = BrowserTabRuntime.create(app, "https://example.com")
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
                bookmarked = profileStore.isBookmarked(pageState.url.ifBlank { tab.input }),
                onAddressClick = { showSearch = true },
                onLoad = {
                    val target = GeckoSessionController.normalizeUrl(tab.input)
                    controller.load(target)
                    profileStore.recordVisit(target, pageState.title)
                },
                onBack = controller::goBack,
                onForward = controller::goForward,
                onReload = controller::reload,
                onShowTabs = { showTabs = true },
                onNewTab = {
                    val newTab = BrowserTabRuntime.create(app, "https://example.com")
                    tabs.add(newTab)
                    selectedTabId = newTab.id
                    showTabs = false
                    message = null
                },
                onToggleBookmark = {
                    val url = pageState.url.ifBlank { tab.input }
                    profileStore.toggleBookmark(url, pageState.title)
                },
                onInstall = {
                    scope.launch {
                        val title = pageState.title.ifBlank { tab.input }
                        val url = pageState.url.ifBlank { tab.input }
                        runCatching { app.webApps.installFromPage(title, url) }
                            .onSuccess { message = "Installed ${it.name} as WebApp." }
                            .onFailure { message = it.message ?: "Install failed." }
                    }
                }
            )
            key(tab.id) {
                GeckoBrowserView(controller = controller, modifier = Modifier.fillMaxSize())
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

    companion object {
        fun create(app: HyperBrowserApp, url: String): BrowserTabRuntime =
            BrowserTabRuntime(
                id = UUID.randomUUID().toString(),
                controller = GeckoSessionController(app, url),
                input = url
            )
    }
}

@Composable
private fun BrowserToolbar(
    input: String,
    pageState: GeckoPageState,
    message: String?,
    tabCount: Int,
    bookmarked: Boolean,
    onAddressClick: () -> Unit,
    onLoad: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onShowTabs: () -> Unit,
    onNewTab: () -> Unit,
    onToggleBookmark: () -> Unit,
    onInstall: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IconButton(onClick = { }, modifier = Modifier.size(48.dp)) {
                Text("⌂", fontSize = 30.sp, color = Color(0xFF202124))
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0xFFE9EAF1))
                    .clickable(onClick = onAddressClick)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (pageState.insecureHttp) "!" else "G", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (pageState.insecureHttp) Color(0xFFC5221F) else Color(0xFF4285F4))
                Text(
                    text = pageState.url.ifBlank { input.ifBlank { "搜索或输入网址" } },
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
                    .defaultMinSize(minWidth = 52.dp, minHeight = 52.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Color.Transparent)
                        .borderForChromeTabCounter(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(tabCount.toString(), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF202124))
                }
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Text("⋮", fontSize = 30.sp)
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(text = { Text("New tab") }, onClick = { showMenu = false; onNewTab() })
                    DropdownMenuItem(text = { Text("Back") }, onClick = { showMenu = false; onBack() })
                    DropdownMenuItem(text = { Text("Forward") }, onClick = { showMenu = false; onForward() })
                    DropdownMenuItem(text = { Text("Refresh") }, onClick = { showMenu = false; onReload() })
                    DropdownMenuItem(text = { Text(if (bookmarked) "Remove bookmark" else "Bookmark") }, onClick = { showMenu = false; onToggleBookmark() })
                    DropdownMenuItem(text = { Text("Install as WebApp") }, onClick = { showMenu = false; onInstall() })
                }
            }
        }
        if (pageState.insecureHttp) Text("Insecure HTTP page", color = MaterialTheme.colorScheme.error)
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onCancel, modifier = Modifier.size(48.dp)) {
                Text("‹", fontSize = 38.sp, color = Color(0xFF202124))
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
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
                        fontSize = 26.sp,
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
            .padding(top = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp)
        ) {
            IconButton(
                onClick = onNewTab,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(54.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFFD8DDEA))
            ) {
                Text("+", fontSize = 38.sp, color = Color(0xFF202124))
            }
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .height(64.dp)
                    .clip(RoundedCornerShape(34.dp))
                    .background(Color(0xFFE6E8F0))
                    .padding(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(Color(0xFFF8F9FE)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(31.dp)
                            .border(3.dp, Color(0xFF202124), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(count.toString(), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(26.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("▦", fontSize = 30.sp, color = Color(0xFF202124))
                }
            }
            IconButton(
                onClick = { },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(54.dp)
            ) {
                Text("⋮", fontSize = 32.sp, color = Color(0xFF202124))
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
