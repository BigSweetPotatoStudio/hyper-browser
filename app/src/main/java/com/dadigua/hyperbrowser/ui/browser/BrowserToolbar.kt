package com.dadigua.hyperbrowser.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
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
private val FloatingDotSize = 54.dp
private val FloatingDotInnerSize = 12.dp
private val FloatingDotMargin = 18.dp
private val FloatingPanelWidth = 320.dp
private val FloatingPanelSearchHeight = 48.dp
private val FloatingPanelButtonSize = 46.dp
private val FloatingMoreMenuWidth = 320.dp
private val FloatingMoreMenuMargin = 16.dp

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
    floatingDotXRatio: Float,
    floatingDotYRatio: Float,
    websiteDisplayModeAvailable: Boolean,
    websiteDisplayMode: String,
    temporaryWebsiteDisplayMode: String?,
    collapseFraction: Float = 0f,
    downloads: List<BrowserDownloadEntry>,
    addressSecurityLevel: AddressSecurityLevel,
    onOpenSearchPage: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onTemporaryWebsiteDisplayModeChange: (String) -> Unit,
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
    onFloatingDotPositionChange: (Float, Float) -> Unit,
    onInstall: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var extensionsExpanded by remember { mutableStateOf(false) }
    var displayModeExpanded by remember { mutableStateOf(false) }
    val placeholder = stringResource(R.string.browser_placeholder_search_or_url)
    val currentAddress = browserAddressText(pageState.url, input, placeholder)
    val hasCurrentAddress = currentAddress.isNotBlank() && currentAddress != placeholder
    val currentTitle = pageState.title.trim().ifBlank { currentAddress.ifBlank { placeholder } }
    val normalizedCollapseFraction = collapseFraction.coerceIn(0f, 1f)
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val imeVisible = imeBottomPx > 0
    val enabledExtensions = installedExtensions.filter { it.enabled }
    if (BrowserSettings.isFloatingDotToolbarPosition(toolbarPosition)) {
        FloatingDotToolbar(
            tabCount = tabCount,
            bookmarked = bookmarked,
            webAppInstalled = webAppInstalled,
            enabledExtensions = enabledExtensions,
            downloads = downloads,
            installedExtensionCount = installedExtensions.size,
            extensionActions = extensionActions,
            websiteDisplayModeAvailable = websiteDisplayModeAvailable,
            websiteDisplayMode = websiteDisplayMode,
            temporaryWebsiteDisplayMode = temporaryWebsiteDisplayMode,
            addressSecurityLevel = addressSecurityLevel,
            currentTitle = currentTitle,
            currentAddress = currentAddress,
            hasCurrentAddress = hasCurrentAddress,
            floatingDotXRatio = floatingDotXRatio,
            floatingDotYRatio = floatingDotYRatio,
            onOpenSearchPage = onOpenSearchPage,
            onBack = onBack,
            onForward = onForward,
            onReload = onReload,
            onTemporaryWebsiteDisplayModeChange = onTemporaryWebsiteDisplayModeChange,
            onHome = onHome,
            onShowTabs = onShowTabs,
            onNewTab = onNewTab,
            onToggleBookmark = onToggleBookmark,
            onShowBookmarks = onShowBookmarks,
            onShowHistory = onShowHistory,
            onShowSettings = onShowSettings,
            onShowDownloads = onShowDownloads,
            onShowExtensions = onShowExtensions,
            onExtensionClick = onExtensionClick,
            onFloatingDotPositionChange = onFloatingDotPositionChange,
            onInstall = onInstall
        )
        return
    }
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
                        websiteDisplayModeAvailable = websiteDisplayModeAvailable,
                        websiteDisplayMode = websiteDisplayMode,
                        temporaryWebsiteDisplayMode = temporaryWebsiteDisplayMode,
                        displayModeExpanded = displayModeExpanded,
                        onDisplayModeExpandedChange = { displayModeExpanded = it },
                        onNewTab = { showMenu = false; onNewTab() },
                        onBack = { showMenu = false; onBack() },
                        onForward = { showMenu = false; onForward() },
                        onReload = { showMenu = false; onReload() },
                        onTemporaryWebsiteDisplayModeChange = {
                            showMenu = false
                            onTemporaryWebsiteDisplayModeChange(it)
                        },
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

@Composable
private fun FloatingDotToolbar(
    tabCount: Int,
    bookmarked: Boolean,
    webAppInstalled: Boolean,
    enabledExtensions: List<InstalledExtensionState>,
    downloads: List<BrowserDownloadEntry>,
    installedExtensionCount: Int,
    extensionActions: Map<String, ExtensionMenuActionState>,
    websiteDisplayModeAvailable: Boolean,
    websiteDisplayMode: String,
    temporaryWebsiteDisplayMode: String?,
    addressSecurityLevel: AddressSecurityLevel,
    currentTitle: String,
    currentAddress: String,
    hasCurrentAddress: Boolean,
    floatingDotXRatio: Float,
    floatingDotYRatio: Float,
    onOpenSearchPage: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onTemporaryWebsiteDisplayModeChange: (String) -> Unit,
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
    onFloatingDotPositionChange: (Float, Float) -> Unit,
    onInstall: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var extensionsExpanded by remember { mutableStateOf(false) }
    var displayModeExpanded by remember { mutableStateOf(false) }
    var dotOffset by remember { mutableStateOf<Offset?>(null) }
    val dotInteractionSource = remember { MutableInteractionSource() }
    val scrimInteractionSource = remember { MutableInteractionSource() }
    val scrollState = rememberScrollState()
    val homeLabel = stringResource(R.string.floating_action_home)
    val newTabLabel = stringResource(R.string.menu_new_tab)
    val tabsLabel = stringResource(R.string.floating_action_tabs)
    val moreLabel = stringResource(R.string.floating_action_more)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
    ) {
        val density = LocalDensity.current
        val maxWidthPx = with(density) { maxWidth.toPx() }
        val maxHeightPx = with(density) { maxHeight.toPx() }
        val dotSizePx = with(density) { FloatingDotSize.toPx() }
        val dotMarginPx = with(density) { FloatingDotMargin.toPx() }
        val panelMarginPx = with(density) { FloatingMoreMenuMargin.toPx() }
        val panelWidthPx = with(density) { FloatingPanelWidth.toPx() }
            .coerceAtMost((maxWidthPx - panelMarginPx * 2f).coerceAtLeast(0f))
            .coerceAtLeast(0f)
        val panelHeightPx = with(density) { (FloatingPanelSearchHeight + FloatingPanelButtonSize + 34.dp).toPx() }
        val moreMenuWidthPx = with(density) { FloatingMoreMenuWidth.toPx() }
        val moreMenuMarginPx = with(density) { FloatingMoreMenuMargin.toPx() }
        val moreMenuMaxHeight = with(density) {
            (maxHeightPx - moreMenuMarginPx * 2f).coerceAtLeast(0f).toDp()
        }

        fun clampDotOffset(offset: Offset): Offset {
            val maxX = (maxWidthPx - dotSizePx).coerceAtLeast(0f)
            val maxY = (maxHeightPx - dotSizePx).coerceAtLeast(0f)
            return Offset(
                x = offset.x.coerceIn(0f, maxX),
                y = offset.y.coerceIn(0f, maxY)
            )
        }

        fun defaultDotOffset(): Offset {
            val maxX = (maxWidthPx - dotSizePx).coerceAtLeast(0f)
            val maxY = (maxHeightPx - dotSizePx).coerceAtLeast(0f)
            return Offset(
                x = (maxX - dotMarginPx).coerceIn(0f, maxX),
                y = (maxY - dotMarginPx).coerceIn(0f, maxY)
            )
        }

        fun savedDotOffset(): Offset? {
            val xRatio = BrowserSettings.normalizedFloatingDotRatio(floatingDotXRatio)
            val yRatio = BrowserSettings.normalizedFloatingDotRatio(floatingDotYRatio)
            if (xRatio == BrowserSettings.FLOATING_DOT_POSITION_UNSET ||
                yRatio == BrowserSettings.FLOATING_DOT_POSITION_UNSET
            ) {
                return null
            }
            val maxX = (maxWidthPx - dotSizePx).coerceAtLeast(0f)
            val maxY = (maxHeightPx - dotSizePx).coerceAtLeast(0f)
            return Offset(
                x = maxX * xRatio,
                y = maxY * yRatio
            )
        }

        fun saveDotOffset(offset: Offset) {
            val clamped = clampDotOffset(offset)
            val maxX = (maxWidthPx - dotSizePx).coerceAtLeast(0f)
            val maxY = (maxHeightPx - dotSizePx).coerceAtLeast(0f)
            val xRatio = if (maxX > 0f) clamped.x / maxX else 0f
            val yRatio = if (maxY > 0f) clamped.y / maxY else 0f
            onFloatingDotPositionChange(xRatio.coerceIn(0f, 1f), yRatio.coerceIn(0f, 1f))
        }

        val currentDotOffset = clampDotOffset(dotOffset ?: savedDotOffset() ?: defaultDotOffset())
        val dotCenter = Offset(
            currentDotOffset.x + dotSizePx / 2f,
            currentDotOffset.y + dotSizePx / 2f
        )
        val panelX = if (maxWidthPx >= panelWidthPx + panelMarginPx * 2f) {
            (dotCenter.x - panelWidthPx / 2f)
                .coerceIn(panelMarginPx, maxWidthPx - panelWidthPx - panelMarginPx)
        } else {
            0f
        }
        val preferredPanelY = if (dotCenter.y > maxHeightPx / 2f) {
            currentDotOffset.y - panelHeightPx - panelMarginPx
        } else {
            currentDotOffset.y + dotSizePx + panelMarginPx
        }
        val panelY = preferredPanelY
            .coerceIn(panelMarginPx, (maxHeightPx - panelHeightPx - panelMarginPx).coerceAtLeast(panelMarginPx))

        LaunchedEffect(maxWidthPx, maxHeightPx, floatingDotXRatio, floatingDotYRatio) {
            dotOffset = clampDotOffset(dotOffset ?: savedDotOffset() ?: defaultDotOffset())
        }

        fun closeFloatingMenuAnd(action: () -> Unit) {
            expanded = false
            showMoreMenu = false
            action()
        }

        if (expanded || showMoreMenu) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = scrimInteractionSource,
                        indication = null
                    ) {
                        expanded = false
                        showMoreMenu = false
                    }
            )
        }

        if (expanded) {
            FloatingExpandedPanel(
                title = currentTitle,
                address = currentAddress,
                hasAddress = hasCurrentAddress,
                addressSecurityLevel = addressSecurityLevel,
                tabCount = tabCount,
                panelWidth = with(density) { panelWidthPx.toDp() },
                modifier = Modifier.offset {
                    IntOffset(panelX.roundToInt(), panelY.roundToInt())
                },
                onOpenSearchPage = { closeFloatingMenuAnd(onOpenSearchPage) },
                onHome = { closeFloatingMenuAnd(onHome) },
                onNewTab = { closeFloatingMenuAnd(onNewTab) },
                onShowTabs = { closeFloatingMenuAnd(onShowTabs) },
                onMore = {
                    expanded = false
                    showMoreMenu = true
                },
                homeLabel = homeLabel,
                newTabLabel = newTabLabel,
                tabsLabel = tabsLabel,
                moreLabel = moreLabel
            )
        }

        if (showMoreMenu) {
            val menuX = if (maxWidthPx >= moreMenuWidthPx + moreMenuMarginPx * 2f) {
                (dotCenter.x - moreMenuWidthPx / 2f)
                    .coerceIn(moreMenuMarginPx, maxWidthPx - moreMenuWidthPx - moreMenuMarginPx)
            } else {
                0f
            }
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            menuX.roundToInt(),
                            moreMenuMarginPx.roundToInt()
                        )
                    }
                    .width(FloatingMoreMenuWidth)
                    .heightIn(max = moreMenuMaxHeight)
                    .shadow(18.dp, RoundedCornerShape(28.dp))
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0xF8FFFFFF))
                    .verticalScroll(scrollState)
            ) {
                BrowserMenuPanel(
                    bookmarked = bookmarked,
                    webAppInstalled = webAppInstalled,
                    enabledExtensions = enabledExtensions,
                    downloads = downloads,
                    installedExtensionCount = installedExtensionCount,
                    extensionActions = extensionActions,
                    extensionsExpanded = extensionsExpanded,
                    onExtensionsExpandedChange = { extensionsExpanded = it },
                    websiteDisplayModeAvailable = websiteDisplayModeAvailable,
                    websiteDisplayMode = websiteDisplayMode,
                    temporaryWebsiteDisplayMode = temporaryWebsiteDisplayMode,
                    displayModeExpanded = displayModeExpanded,
                    onDisplayModeExpandedChange = { displayModeExpanded = it },
                    onNewTab = { closeFloatingMenuAnd(onNewTab) },
                    onBack = { closeFloatingMenuAnd(onBack) },
                    onForward = { closeFloatingMenuAnd(onForward) },
                    onReload = { closeFloatingMenuAnd(onReload) },
                    onTemporaryWebsiteDisplayModeChange = {
                        expanded = false
                        showMoreMenu = false
                        onTemporaryWebsiteDisplayModeChange(it)
                    },
                    onToggleBookmark = { closeFloatingMenuAnd(onToggleBookmark) },
                    onShowBookmarks = { closeFloatingMenuAnd(onShowBookmarks) },
                    onShowHistory = { closeFloatingMenuAnd(onShowHistory) },
                    onShowSettings = { closeFloatingMenuAnd(onShowSettings) },
                    onShowDownloads = { closeFloatingMenuAnd(onShowDownloads) },
                    onShowExtensions = { closeFloatingMenuAnd(onShowExtensions) },
                    onExtensionClick = { closeFloatingMenuAnd { onExtensionClick(it) } },
                    onInstall = { closeFloatingMenuAnd(onInstall) }
                )
            }
        }

        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        currentDotOffset.x.roundToInt(),
                        currentDotOffset.y.roundToInt()
                    )
                }
                .size(FloatingDotSize)
                .pointerInput(maxWidthPx, maxHeightPx) {
                    detectDragGestures(
                        onDragStart = {
                            expanded = false
                            showMoreMenu = false
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val baseOffset = dotOffset ?: currentDotOffset
                            dotOffset = clampDotOffset(
                                Offset(
                                    x = baseOffset.x + dragAmount.x,
                                    y = baseOffset.y + dragAmount.y
                                )
                            )
                        },
                        onDragEnd = {
                            saveDotOffset(dotOffset ?: currentDotOffset)
                        },
                        onDragCancel = {
                            saveDotOffset(dotOffset ?: currentDotOffset)
                        }
                    )
                }
                .shadow(14.dp, CircleShape)
                .clip(CircleShape)
                .background(Color(0xA6202124))
                .border(1.dp, Color(0x66FFFFFF), CircleShape)
                .clickable(
                    interactionSource = dotInteractionSource,
                    indication = null
                ) {
                    expanded = !expanded
                    showMoreMenu = false
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(FloatingDotInnerSize)
                    .clip(CircleShape)
                    .background(Color(0x99DADCE0))
            )
        }
    }
}

@Composable
private fun FloatingExpandedPanel(
    title: String,
    address: String,
    hasAddress: Boolean,
    addressSecurityLevel: AddressSecurityLevel,
    tabCount: Int,
    panelWidth: Dp,
    modifier: Modifier = Modifier,
    onOpenSearchPage: () -> Unit,
    onHome: () -> Unit,
    onNewTab: () -> Unit,
    onShowTabs: () -> Unit,
    onMore: () -> Unit,
    homeLabel: String,
    newTabLabel: String,
    tabsLabel: String,
    moreLabel: String
) {
    Column(
        modifier = modifier
            .width(panelWidth)
            .shadow(18.dp, RoundedCornerShape(26.dp))
            .clip(RoundedCornerShape(26.dp))
            .background(Color(0xF2FFFFFF))
            .border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(26.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(FloatingPanelSearchHeight)
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0xFFE9EAF1))
                .clickable(onClick = onOpenSearchPage)
                .padding(horizontal = 12.dp),
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
                    text = title,
                    color = if (hasAddress) Color(0xFF202124) else Color(0xFF6F737B),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 13.sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (hasAddress) {
                    Text(
                        text = address,
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            FloatingPanelActionButton(label = homeLabel, icon = "⌂", onClick = onHome)
            FloatingPanelActionButton(label = newTabLabel, icon = "+", onClick = onNewTab)
            FloatingPanelActionButton(label = tabsLabel, icon = tabCount.toString(), onClick = onShowTabs)
            FloatingPanelActionButton(label = moreLabel, icon = "⋮", onClick = onMore)
        }
    }
}

@Composable
private fun FloatingPanelActionButton(
    label: String,
    icon: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(FloatingPanelButtonSize)
            .clip(CircleShape)
            .background(Color(0xFFF1F3F8))
            .border(1.dp, Color(0xFFE0E3EB), CircleShape)
            .semantics { contentDescription = label }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = icon,
            color = Color(0xFF202124),
            fontSize = if (icon.length > 2) 13.sp else 24.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
