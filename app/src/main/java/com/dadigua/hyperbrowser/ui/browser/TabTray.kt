package com.dadigua.hyperbrowser.ui.browser

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dadigua.hyperbrowser.browser.FaviconRepository
import com.dadigua.hyperbrowser.gecko.GeckoPageState

private val TabActionBarHeight = 48.dp
private val TabActionButtonSize = 40.dp
private val TabActionIconSize = 24.sp

@Composable
internal fun TabTray(
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
                .height(TabActionBarHeight)
                .padding(horizontal = 18.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(TabActionButtonSize)
            ) {
                Text("‹", fontSize = TabActionIconSize, color = Color(0xFF202124))
            }
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .height(TabActionBarHeight)
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
                    Text("▣", fontSize = TabActionIconSize, color = Color(0xFF202124))
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(if (mode == TabTrayMode.List) Color(0xFFF8F9FE) else Color.Transparent)
                        .clickable { onModeChange(TabTrayMode.List) },
                    contentAlignment = Alignment.Center
                ) {
                    Text("≡", fontSize = TabActionIconSize, color = Color(0xFF202124))
                }
            }
            IconButton(
                onClick = onNewTab,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(TabActionButtonSize)
            ) {
                Text("+", fontSize = TabActionIconSize, color = Color(0xFF202124))
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
    val displayTitle = pageState.title.ifBlank { tab.restoredTitle.orEmpty() }
    val displayUrl = pageState.url.ifBlank { tab.input }
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
                    pageUrl = displayUrl,
                    fallbackLabel = displayTitle.ifBlank { displayUrl },
                    faviconStore = faviconStore,
                    selected = selected,
                    size = 30.dp
                )
                Text(
                    displayTitle.ifBlank { "打开新的标签页" },
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
                            displayTitle.ifBlank { "New tab" },
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            displayUrl,
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
    val displayTitle = pageState.title.ifBlank { tab.restoredTitle.orEmpty() }
    val displayUrl = pageState.url.ifBlank { tab.input }
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
            pageUrl = displayUrl,
            fallbackLabel = displayTitle.ifBlank { displayUrl },
            faviconStore = faviconStore,
            selected = selected,
            size = 42.dp
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                displayTitle.ifBlank { "New tab" },
                color = if (selected) Color.White else Color(0xFF202124),
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                displayUrl,
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
