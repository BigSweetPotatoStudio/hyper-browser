package com.dadigua.hyperbrowser.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.dadigua.hyperbrowser.R
import com.dadigua.hyperbrowser.browser.BrowserDownloadEntry
import com.dadigua.hyperbrowser.browser.BrowserSettings
import com.dadigua.hyperbrowser.data.InstalledExtensionState
import com.dadigua.hyperbrowser.extensions.ExtensionMenuActionState
import com.dadigua.hyperbrowser.gecko.GeckoPageState
import kotlin.math.roundToInt

private val ToolbarActionBarHeight = 44.dp
private val ToolbarActionButtonSize = 36.dp
private val ToolbarAddressBarHeight = 40.dp
private val ToolbarActionIconSize = 24.sp

internal fun browserAddressText(url: String, input: String, placeholder: String = ""): String {
    return url.ifBlank { input.ifBlank { placeholder } }
}

internal enum class AddressSecurityLevel {
    Neutral,
    Insecure,
    Secure,
    Enhanced
}

@Composable
internal fun BrowserToolbar(
    input: String,
    pageState: GeckoPageState,
    tabCount: Int,
    bookmarked: Boolean,
    webAppInstalled: Boolean,
    installedExtensions: List<InstalledExtensionState>,
    extensionActions: Map<String, ExtensionMenuActionState>,
    toolbarPosition: String,
    collapseFraction: Float = 0f,
    downloads: List<BrowserDownloadEntry>,
    addressSecurityLevel: AddressSecurityLevel,
    onOpenSearchPage: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onHome: () -> Unit,
    onShowTabs: () -> Unit,
    onNewTab: () -> Unit,
    onToggleBookmark: () -> Unit,
    onShowBookmarks: () -> Unit,
    onShowHistory: () -> Unit,
    onShowSettings: () -> Unit,
    onShowDownloads: () -> Unit,
    onShowExtensions: () -> Unit,
    onExtensionClick: (InstalledExtensionState) -> Unit,
    onInstall: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var extensionsExpanded by remember { mutableStateOf(false) }
    val placeholder = stringResource(R.string.browser_placeholder_search_or_url)
    val currentAddress = browserAddressText(pageState.url, input, placeholder)
    val hasCurrentAddress = currentAddress.isNotBlank() && currentAddress != placeholder
    val currentTitle = pageState.title.trim().ifBlank { currentAddress.ifBlank { placeholder } }
    val normalizedCollapseFraction = collapseFraction.coerceIn(0f, 1f)
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val imeVisible = imeBottomPx > 0
    val enabledExtensions = installedExtensions.filter { it.enabled }
    val toolbarPadding = if (BrowserSettings.isBottomToolbarPosition(toolbarPosition) && !imeVisible) {
        Modifier.navigationBarsPadding()
    } else {
        Modifier
    }

    val toolbarRow: @Composable () -> Unit = {
        Row(
            modifier = Modifier.height(ToolbarActionBarHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            IconButton(onClick = onHome, modifier = Modifier.size(ToolbarActionButtonSize)) {
                Text("⌂", fontSize = ToolbarActionIconSize, color = Color(0xFF202124))
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(ToolbarAddressBarHeight)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0xFFE9EAF1))
                    .clickable(onClick = onOpenSearchPage)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AddressSecurityIndicator(
                    level = addressSecurityLevel,
                    modifier = Modifier.width(22.dp)
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = currentTitle,
                        color = if (hasCurrentAddress) Color(0xFF202124) else Color(0xFF6F737B),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = 13.sp,
                            lineHeight = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (hasCurrentAddress) {
                        Text(
                            text = currentAddress,
                            color = Color(0xFF5F6368),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                lineHeight = 13.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            IconButton(onClick = onNewTab, modifier = Modifier.size(ToolbarActionButtonSize)) {
                Text("+", fontSize = ToolbarActionIconSize, color = Color(0xFF202124))
            }
            IconButton(
                onClick = onShowTabs,
                modifier = Modifier
                    .size(ToolbarActionButtonSize)
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Color.Transparent)
                        .borderForToolbarTabCounter(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(tabCount.toString(), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF202124))
                }
            }
            Box {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(ToolbarActionButtonSize)) {
                    Text("⋮", fontSize = ToolbarActionIconSize)
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
                        onShowBookmarks = { showMenu = false; onShowBookmarks() },
                        onShowHistory = { showMenu = false; onShowHistory() },
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
            .collapsibleToolbarLayout(normalizedCollapseFraction, toolbarPosition)
            .then(toolbarPadding)
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        toolbarRow()
    }
}

private fun Modifier.collapsibleToolbarLayout(
    collapseFraction: Float,
    toolbarPosition: String
): Modifier {
    val fraction = collapseFraction.coerceIn(0f, 1f)
    if (fraction <= 0f) return this
    val isBottomToolbar = BrowserSettings.isBottomToolbarPosition(toolbarPosition)
    return this
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val visibleHeight = (placeable.height * (1f - fraction))
                .roundToInt()
                .coerceIn(0, placeable.height)
            layout(placeable.width, visibleHeight) {
                val y = if (isBottomToolbar) {
                    0
                } else {
                    visibleHeight - placeable.height
                }
                placeable.placeRelative(0, y)
            }
        }
        .clipToBounds()
        .graphicsLayer {
            alpha = 1f - fraction
        }
}

@Composable
private fun AddressSecurityIndicator(
    level: AddressSecurityLevel,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        when (level) {
            AddressSecurityLevel.Insecure -> Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
            AddressSecurityLevel.Secure -> Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                tint = Color(0xFF188038),
                modifier = Modifier.size(18.dp)
            )
            AddressSecurityLevel.Enhanced -> Icon(
                imageVector = Icons.Outlined.Security,
                contentDescription = null,
                tint = Color(0xFF0B8043),
                modifier = Modifier.size(20.dp)
            )
            AddressSecurityLevel.Neutral -> Icon(
                imageVector = Icons.Outlined.Public,
                contentDescription = null,
                tint = Color(0xFF5F6368),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

private fun Modifier.borderForToolbarTabCounter(): Modifier =
    this.border(3.dp, Color(0xFF202124), RoundedCornerShape(9.dp))
