package com.dadigua.hyperbrowser.ui.browser

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dadigua.hyperbrowser.R
import com.dadigua.hyperbrowser.browser.BrowserBookmark
import com.dadigua.hyperbrowser.browser.BrowserHistoryEntry
import com.dadigua.hyperbrowser.data.WebAppDefinition
import com.dadigua.hyperbrowser.gecko.GeckoSessionController
import kotlinx.coroutines.launch

private val SearchActionBarHeight = 48.dp
private val SearchActionButtonSize = 40.dp
private val SearchActionIconSize = 24.sp

internal enum class BrowserSearchSuggestionKind {
    Bookmark,
    WebApp,
    History
}

internal data class BrowserSearchSuggestion(
    val title: String,
    val url: String,
    val source: String,
    val kind: BrowserSearchSuggestionKind,
    val appId: String? = null
)

internal fun buildBrowserSearchSuggestions(
    queryText: String,
    history: List<BrowserHistoryEntry>,
    bookmarks: List<BrowserBookmark>,
    webApps: List<WebAppDefinition>,
    bookmarkSource: String,
    webAppSource: String,
    historySource: String,
    limit: Int = 8
): List<BrowserSearchSuggestion> {
    val needle = queryText.trim().lowercase()
    if (needle.isBlank()) return emptyList()

    val seenPageUrls = mutableSetOf<String>()
    val suggestions = mutableListOf<BrowserSearchSuggestion>()
    bookmarks.forEach { bookmark ->
        if (seenPageUrls.add(bookmark.url)) {
            suggestions += BrowserSearchSuggestion(
                title = bookmark.title,
                url = bookmark.url,
                source = bookmarkSource,
                kind = BrowserSearchSuggestionKind.Bookmark
            )
        }
    }
    webApps.forEach { webApp ->
        if (webApp.id.isNotBlank() && webApp.startUrl.isNotBlank()) {
            suggestions += BrowserSearchSuggestion(
                title = webApp.name,
                url = webApp.startUrl,
                source = webAppSource,
                kind = BrowserSearchSuggestionKind.WebApp,
                appId = webApp.id
            )
        }
    }
    history.filterNot { GeckoSessionController.isInternalUrl(it.url) }.forEach { entry ->
        if (seenPageUrls.add(entry.url)) {
            suggestions += BrowserSearchSuggestion(
                title = entry.title,
                url = entry.url,
                source = historySource,
                kind = BrowserSearchSuggestionKind.History
            )
        }
    }

    return suggestions
        .filter {
            it.title.lowercase().contains(needle) ||
                it.url.lowercase().contains(needle)
        }
        .take(limit)
}

@Composable
internal fun SearchPage(
    initialInput: String,
    currentTitle: String,
    currentUrl: String,
    history: List<BrowserHistoryEntry>,
    bookmarks: List<BrowserBookmark>,
    webApps: List<WebAppDefinition>,
    onCancel: () -> Unit,
    onGo: (String) -> Unit,
    onOpenWebApp: (String) -> Unit
) {
    var query by remember { mutableStateOf(TextFieldValue(initialInput)) }
    val queryText = query.text
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val bookmarkSource = stringResource(R.string.browser_search_source_bookmark)
    val webAppSource = stringResource(R.string.browser_search_source_webapp)
    val historySource = stringResource(R.string.browser_search_source_history)
    val searchPlaceholder = stringResource(R.string.browser_placeholder_search_or_url)
    val goLabel = stringResource(R.string.common_action_go)
    val suggestions = remember(queryText, history, bookmarks, webApps, bookmarkSource, webAppSource, historySource) {
        buildBrowserSearchSuggestions(
            queryText = queryText,
            history = history,
            bookmarks = bookmarks,
            webApps = webApps,
            bookmarkSource = bookmarkSource,
            webAppSource = webAppSource,
            historySource = historySource
        )
    }

    fun submit(value: String = queryText) {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return
        keyboardController?.hide()
        onGo(trimmed)
    }

    fun openSuggestion(suggestion: BrowserSearchSuggestion) {
        keyboardController?.hide()
        if (suggestion.kind == BrowserSearchSuggestionKind.WebApp && !suggestion.appId.isNullOrBlank()) {
            onOpenWebApp(suggestion.appId)
        } else {
            onGo(suggestion.url)
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF7F8FC))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.height(SearchActionBarHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = onCancel, modifier = Modifier.size(SearchActionButtonSize)) {
                Text("‹", fontSize = SearchActionIconSize, color = Color(0xFF202124))
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(SearchActionButtonSize)
                    .clip(RoundedCornerShape(26.dp))
                    .background(Color(0xFFE7E9F1))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    textStyle = MaterialTheme.typography.titleMedium.copy(color = Color(0xFF202124)),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .padding(horizontal = 6.dp),
                    decorationBox = { inner ->
                        if (queryText.isBlank()) {
                            Text(searchPlaceholder, color = Color(0xFF6F737B), style = MaterialTheme.typography.titleMedium)
                        }
                        inner()
                    }
                )
                if (queryText.isNotBlank()) {
                    Text(
                        "×",
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { query = TextFieldValue("") }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        fontSize = 24.sp,
                        color = Color(0xFF5F6368)
                    )
                }
            }
            TextButton(onClick = { submit() }) {
                Text(goLabel, fontWeight = FontWeight.Bold)
            }
        }

        if (queryText.isBlank()) {
            CurrentPagePanel(
                title = currentTitle,
                url = currentUrl,
                onShare = {
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, currentUrl)
                    }
                    context.startActivity(Intent.createChooser(sendIntent, currentUrl))
                },
                onCopy = {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("url", currentUrl)))
                    }
                },
                onEdit = {
                    query = TextFieldValue(currentUrl, selection = TextRange(currentUrl.length))
                }
            )
        } else if (suggestions.isNotEmpty()) {
            SearchSuggestionPanel(
                suggestions = suggestions,
                onOpen = ::openSuggestion
            )
        }
    }
}

@Composable
private fun CurrentPagePanel(
    title: String,
    url: String,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit
) {
    val shareLabel = stringResource(R.string.common_action_share)
    val copyLabel = stringResource(R.string.common_action_copy)
    val editLabel = stringResource(R.string.common_action_edit)
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
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE8EAED)),
                contentAlignment = Alignment.Center
            ) {
                Text("⌂", fontSize = 20.sp, color = Color(0xFF5F6368))
            }
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
            CompactActionButton(
                icon = { Icon(Icons.Outlined.Share, contentDescription = shareLabel) },
                onClick = onShare
            )
            CompactActionButton(
                icon = { Icon(Icons.Outlined.ContentCopy, contentDescription = copyLabel) },
                onClick = onCopy
            )
            CompactActionButton(
                icon = { Icon(Icons.Outlined.Edit, contentDescription = editLabel) },
                onClick = onEdit
            )
        }
    }
}

@Composable
private fun CompactActionButton(
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(32.dp)
    ) {
        Box(
            modifier = Modifier.size(18.dp),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
    }
}

@Composable
private fun SearchSuggestionPanel(
    suggestions: List<BrowserSearchSuggestion>,
    onOpen: (BrowserSearchSuggestion) -> Unit
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
                        .clickable { onOpen(suggestion) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = when (suggestion.kind) {
                            BrowserSearchSuggestionKind.Bookmark -> "★"
                            BrowserSearchSuggestionKind.WebApp -> "▣"
                            BrowserSearchSuggestionKind.History -> "◷"
                        },
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
