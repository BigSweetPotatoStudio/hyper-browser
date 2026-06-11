package com.dadigua.hyperbrowser.ui.browser

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dadigua.hyperbrowser.browser.BrowserBookmark
import com.dadigua.hyperbrowser.browser.BrowserDownloadEntry
import com.dadigua.hyperbrowser.browser.BrowserHistoryEntry
import com.dadigua.hyperbrowser.browser.BrowserSettings
import com.dadigua.hyperbrowser.data.InstalledExtensionState
import com.dadigua.hyperbrowser.extensions.ExtensionMenuActionState
import com.dadigua.hyperbrowser.gecko.GeckoPageState
import com.dadigua.hyperbrowser.gecko.GeckoSessionController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ToolbarActionBarHeight = 48.dp
private val ToolbarActionButtonSize = 40.dp
private val ToolbarAddressBarHeight = 44.dp
private val ToolbarBottomGestureClearance = 12.dp
private val ToolbarActionIconSize = 24.sp

private fun browserAddressText(url: String, input: String): String {
    return url.ifBlank { input.ifBlank { "搜索或输入网址" } }
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
internal fun BrowserToolbar(
    input: String,
    pageState: GeckoPageState,
    tabCount: Int,
    bookmarked: Boolean,
    webAppInstalled: Boolean,
    privateMode: Boolean,
    installedExtensions: List<InstalledExtensionState>,
    extensionActions: Map<String, ExtensionMenuActionState>,
    toolbarPosition: String,
    editingAddress: Boolean,
    onEditingAddressChange: (Boolean) -> Unit,
    onStartSearch: () -> Unit,
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
    onNewPrivateTab: () -> Unit,
    onToggleBookmark: () -> Unit,
    onShowSettings: () -> Unit,
    onShowDownloads: () -> Unit,
    onShowExtensions: () -> Unit,
    onFindInPage: () -> Unit,
    onExtensionClick: (InstalledExtensionState) -> Unit,
    onInstall: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var extensionsExpanded by remember { mutableStateOf(false) }
    var addressDraft by remember { mutableStateOf(TextFieldValue(browserAddressText(pageState.url, input))) }
    val currentAddress = browserAddressText(pageState.url, input)
    val addressDraftText = addressDraft.text
    val context = LocalContext.current
    val view = LocalView.current
    val inputMethodManager = remember(context) {
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val addressFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var addressInputWasFocused by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val imeVisible = imeBottomPx > 0
    val enabledExtensions = installedExtensions.filter { it.enabled }
    val privateAddressBar = privateMode && !editingAddress
    val addressBarBackground = if (privateAddressBar) Color(0xFF202124) else Color(0xFFE9EAF1)
    val addressTextColor = if (privateAddressBar) Color.White else Color(0xFF202124)
    val addressPlaceholderColor = if (privateAddressBar) Color(0xFFDADCE3) else Color(0xFF6F737B)
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
    val toolbarPadding = if (toolbarPosition == BrowserSettings.TOOLBAR_POSITION_BOTTOM && !imeVisible) {
        Modifier
            .navigationBarsPadding()
            .padding(bottom = ToolbarBottomGestureClearance)
    } else {
        Modifier
    }
    val toolbarImeOffset = if (toolbarPosition == BrowserSettings.TOOLBAR_POSITION_BOTTOM && editingAddress && imeVisible) {
        Modifier.offset { IntOffset(0, -imeBottomPx) }
    } else {
        Modifier
    }

    fun submitAddress(value: String = addressDraftText) {
        val query = value.trim()
        if (query.isBlank()) return
        addressInputWasFocused = false
        onEditingAddressChange(false)
        keyboardController?.hide()
        inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
        onSubmitAddress(query)
    }

    fun startAddressEdit() {
        addressInputWasFocused = false
        onStartSearch()
    }

    fun editCurrentAddress() {
        addressInputWasFocused = false
        onEditingAddressChange(true)
        addressDraft = TextFieldValue(currentAddress, selection = TextRange(currentAddress.length))
    }

    fun cancelAddressEdit() {
        addressInputWasFocused = false
        onEditingAddressChange(false)
        addressDraft = TextFieldValue(currentAddress)
        keyboardController?.hide()
        inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
    }

    fun shareCurrentPage() {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, currentAddress)
        }
        context.startActivity(Intent.createChooser(sendIntent, pageState.title.ifBlank { currentAddress }))
    }

    fun copyCurrentPageLink() {
        scope.launch {
            clipboard.setClipEntry(
                ClipEntry(ClipData.newPlainText("url", currentAddress))
            )
        }
        Toast.makeText(context, "链接已复制", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(currentAddress, editingAddress) {
        if (!editingAddress) {
            addressDraft = TextFieldValue(currentAddress)
        }
    }

    LaunchedEffect(editingAddress) {
        if (editingAddress) {
            withFrameNanos { }
            runCatching { addressFocusRequester.requestFocus() }
            delay(80)
            runCatching { addressFocusRequester.requestFocus() }
            keyboardController?.show()
            inputMethodManager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
            delay(120)
            keyboardController?.show()
            inputMethodManager.showSoftInputForced(view)
        }
    }

    val suggestionPanel: @Composable () -> Unit = {
        if (editingAddress) {
            if (addressDraftText.isBlank()) {
                ToolbarCurrentPagePanel(
                    title = pageState.title,
                    url = currentAddress,
                    onShare = ::shareCurrentPage,
                    onCopy = ::copyCurrentPageLink,
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
            modifier = Modifier.height(ToolbarActionBarHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(onClick = onHome, modifier = Modifier.size(ToolbarActionButtonSize)) {
                Text("⌂", fontSize = ToolbarActionIconSize, color = Color(0xFF202124))
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(ToolbarAddressBarHeight)
                    .clip(RoundedCornerShape(28.dp))
                    .background(addressBarBackground)
            ) {
                Row(
                    modifier = Modifier
                        .matchParentSize()
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AddressSecurityIndicator(
                        privateMode = privateAddressBar,
                        insecure = pageState.insecureHttp && !editingAddress,
                        tint = addressTextColor,
                        modifier = Modifier.width(26.dp)
                    )
                    if (editingAddress) {
                        BasicTextField(
                            value = addressDraft,
                            onValueChange = {
                                addressDraft = it
                                if (!editingAddress) onEditingAddressChange(true)
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Go
                            ),
                            keyboardActions = KeyboardActions(onGo = { submitAddress() }),
                            textStyle = MaterialTheme.typography.titleMedium.copy(color = addressTextColor),
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(addressFocusRequester)
                                .onFocusChanged { focusState ->
                                    if (focusState.isFocused) {
                                        addressInputWasFocused = true
                                        keyboardController?.show()
                                    } else if (editingAddress && addressInputWasFocused) {
                                        cancelAddressEdit()
                                    }
                                },
                            decorationBox = { inner ->
                                if (addressDraftText.isBlank()) {
                                    Text("搜索或输入网址", color = addressPlaceholderColor, style = MaterialTheme.typography.titleMedium)
                                }
                                inner()
                            }
                        )
                    } else {
                        Text(
                            currentAddress.ifBlank { "搜索或输入网址" },
                            modifier = Modifier.weight(1f),
                            color = if (currentAddress.isBlank()) addressPlaceholderColor else addressTextColor,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (editingAddress && addressDraftText.isNotBlank()) {
                        Text(
                            "×",
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { addressDraft = TextFieldValue("") }
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            fontSize = 24.sp,
                            color = if (privateAddressBar) Color(0xFFDADCE3) else Color(0xFF5F6368)
                        )
                    }
                }
                if (!editingAddress) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable { startAddressEdit() }
                    )
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
                        onNewPrivateTab = { showMenu = false; onNewPrivateTab() },
                        onBack = { showMenu = false; onBack() },
                        onForward = { showMenu = false; onForward() },
                        onReload = { showMenu = false; onReload() },
                        onSharePage = { showMenu = false; shareCurrentPage() },
                        onCopyPageLink = { showMenu = false; copyCurrentPageLink() },
                        onFindInPage = { showMenu = false; onFindInPage() },
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
    privateMode: Boolean,
    insecure: Boolean,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        if (privateMode) {
            Icon(
                imageVector = Icons.Outlined.VisibilityOff,
                contentDescription = "Private tab",
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
        } else if (insecure) {
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
                tint = tint,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

private fun Modifier.borderForToolbarTabCounter(): Modifier =
    this.border(3.dp, Color(0xFF202124), RoundedCornerShape(9.dp))

@Suppress("DEPRECATION")
private fun InputMethodManager.showSoftInputForced(view: View) {
    showSoftInput(view, InputMethodManager.SHOW_FORCED)
}
