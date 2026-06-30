package com.dadigua.hyperbrowser.ui.browser

import android.content.ClipData
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dadigua.hyperbrowser.R
import com.dadigua.hyperbrowser.browser.BrowserBookmark
import com.dadigua.hyperbrowser.browser.BrowserDownloadEntry
import com.dadigua.hyperbrowser.browser.BrowserHistoryEntry
import com.dadigua.hyperbrowser.browser.DownloadStatus
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LibraryActionBarHeight = 48.dp
private val LibraryActionButtonSize = 40.dp
private val LibraryActionIconSize = 24.sp

internal data class DownloadMetaLabels(
    val queued: String,
    val downloading: String,
    val complete: String,
    val failed: String,
    val canceled: String,
    val unknownSize: String,
    val unknown: String
)

internal enum class DownloadStatusFilter {
    All,
    Active,
    Completed,
    FailedOrCanceled
}

@Composable
internal fun BookmarksPage(
    bookmarks: List<BrowserBookmark>,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onRemove: (String) -> Unit,
    onEdit: (oldUrl: String, title: String, url: String) -> Unit,
    iconPathFor: (BrowserBookmark) -> String?
) {
    var query by remember { mutableStateOf("") }
    var editingUrl by remember { mutableStateOf<String?>(null) }
    var pendingRemove by remember { mutableStateOf<BrowserBookmark?>(null) }
    val orderedBookmarks = remember(bookmarks) { sortBookmarksByAddedOrder(bookmarks) }
    val visibleBookmarks = remember(orderedBookmarks, query) { filterBookmarks(orderedBookmarks, query) }

    BrowserLibraryScaffold(
        title = stringResource(R.string.library_bookmarks_title),
        onBack = onBack,
        action = null
    ) {
        when {
            orderedBookmarks.isEmpty() -> LibraryEmptyState(
                title = stringResource(R.string.library_bookmarks_empty_title),
                body = stringResource(R.string.library_bookmarks_empty_body),
                leading = "★"
            )
            else -> Column(modifier = Modifier.fillMaxSize()) {
                LibrarySearchField(
                    query = query,
                    onQueryChange = { query = it },
                    label = stringResource(R.string.library_bookmarks_search_label),
                    placeholder = stringResource(R.string.library_bookmarks_search_placeholder)
                )
                if (visibleBookmarks.isEmpty()) {
                    LibraryNoMatches(message = stringResource(R.string.library_bookmarks_no_matches))
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(visibleBookmarks, key = { it.url }) { bookmark ->
                            BookmarkRow(
                                bookmark = bookmark,
                                editing = editingUrl == bookmark.url,
                                iconPath = iconPathFor(bookmark),
                                onOpen = { onOpen(bookmark.url) },
                                onEdit = { editingUrl = bookmark.url },
                                onCancelEdit = { editingUrl = null },
                                onSave = { title, url ->
                                    onEdit(bookmark.url, title, url)
                                    editingUrl = null
                                },
                                onRemove = { pendingRemove = bookmark }
                            )
                            HorizontalDivider(color = Color(0xFFE8EAED))
                        }
                        item { Spacer(modifier = Modifier.height(36.dp)) }
                    }
                }
            }
        }
    }

    pendingRemove?.let { bookmark ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text(stringResource(R.string.library_bookmarks_remove_label)) },
            text = { Text(bookmark.title.ifBlank { bookmark.url }) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemove(bookmark.url)
                        if (editingUrl == bookmark.url) editingUrl = null
                        pendingRemove = null
                    }
                ) {
                    Text(stringResource(R.string.common_action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) {
                    Text(stringResource(R.string.common_action_cancel))
                }
            }
        )
    }
}

@Composable
internal fun HistoryPage(
    history: List<BrowserHistoryEntry>,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    iconPathFor: (BrowserHistoryEntry) -> String?
) {
    var pendingClearHistory by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val visibleHistory = remember(history, query) { filterHistory(history, query) }

    BrowserLibraryScaffold(
        title = stringResource(R.string.library_history_title),
        onBack = onBack,
        action = if (history.isEmpty()) null else stringResource(R.string.common_action_clear) to { pendingClearHistory = true }
    ) {
        when {
            history.isEmpty() -> LibraryEmptyState(
                title = stringResource(R.string.library_history_empty_title),
                body = stringResource(R.string.library_history_empty_body),
                leading = "◷"
            )
            else -> Column(modifier = Modifier.fillMaxSize()) {
                LibrarySearchField(
                    query = query,
                    onQueryChange = { query = it },
                    label = stringResource(R.string.library_history_search_label),
                    placeholder = stringResource(R.string.library_history_search_placeholder)
                )
                if (visibleHistory.isEmpty()) {
                    LibraryNoMatches(message = stringResource(R.string.library_history_no_matches))
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(visibleHistory, key = { "${it.url}:${it.visitedAt}" }) { entry ->
                            LibraryRow(
                                title = entry.title,
                                url = entry.url,
                                meta = formatVisitTime(entry.visitedAt),
                                leading = "◷",
                                iconPath = iconPathFor(entry),
                                removeContentDescription = stringResource(R.string.library_history_remove_label),
                                onOpen = { onOpen(entry.url) },
                                onRemove = { onRemove(entry.url) }
                            )
                            HorizontalDivider(color = Color(0xFFE8EAED))
                        }
                        item { Spacer(modifier = Modifier.height(36.dp)) }
                    }
                }
            }
        }
    }

    if (pendingClearHistory) {
        AlertDialog(
            onDismissRequest = { pendingClearHistory = false },
            title = { Text(stringResource(R.string.library_history_clear_title)) },
            text = {
                Text(stringResource(R.string.library_history_clear_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClear()
                        pendingClearHistory = false
                    }
                ) {
                    Text(stringResource(R.string.library_history_clear_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingClearHistory = false }) {
                    Text(stringResource(R.string.common_action_cancel))
                }
            }
        )
    }
}

@Composable
internal fun DownloadsPage(
    downloads: List<BrowserDownloadEntry>,
    onBack: () -> Unit,
    onOpen: (BrowserDownloadEntry) -> Unit,
    onRetry: (BrowserDownloadEntry) -> Unit,
    onCancel: (BrowserDownloadEntry) -> Unit,
    onRemove: (BrowserDownloadEntry, Boolean) -> Unit,
    onClearFinished: () -> Unit,
    canRetry: (BrowserDownloadEntry) -> Boolean,
    canCancel: (BrowserDownloadEntry) -> Boolean,
    canClear: (BrowserDownloadEntry) -> Boolean
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val orderedDownloads = remember(downloads) { downloads.sortedByDescending { it.createdAt } }
    var query by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf(DownloadStatusFilter.All) }
    val downloadMetaLabels = DownloadMetaLabels(
        queued = stringResource(R.string.download_status_queued),
        downloading = stringResource(R.string.download_status_downloading),
        complete = stringResource(R.string.download_status_complete),
        failed = stringResource(R.string.download_status_failed),
        canceled = stringResource(R.string.download_status_canceled),
        unknownSize = stringResource(R.string.download_unknown_size),
        unknown = stringResource(R.string.download_unknown)
    )
    val visibleDownloads = remember(orderedDownloads, query, statusFilter, downloadMetaLabels) {
        filterDownloads(orderedDownloads, query, statusFilter, downloadMetaLabels)
    }
    val clearableCount = orderedDownloads.count {
        canClear(it) &&
            (it.status == DownloadStatus.Completed ||
                it.status == DownloadStatus.Failed ||
                it.status == DownloadStatus.Canceled)
    }
    var pendingDelete by remember { mutableStateOf<BrowserDownloadEntry?>(null) }
    var pendingClearFinished by remember { mutableStateOf(false) }
    var deleteFile by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF7F8FC))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(LibraryActionBarHeight)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(LibraryActionButtonSize)) {
                Text("‹", fontSize = LibraryActionIconSize, color = Color(0xFF202124))
            }
            Text(
                stringResource(R.string.library_downloads_title),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF202124)
            )
            if (clearableCount > 0) {
                TextButton(onClick = { pendingClearFinished = true }) {
                    Text(stringResource(R.string.library_downloads_clear_records))
                }
            }
        }
        HorizontalDivider(color = Color(0xFFDADCE3))

        if (orderedDownloads.isEmpty()) {
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
                        Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(34.dp), tint = Color(0xFF5F6368))
                    }
                    Text(stringResource(R.string.library_downloads_empty_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.library_downloads_empty_body), color = Color(0xFF5F6368))
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                DownloadSearchField(
                    query = query,
                    onQueryChange = { query = it }
                )
                DownloadStatusFilterRow(
                    selectedFilter = statusFilter,
                    onFilterSelected = { statusFilter = it }
                )
                if (visibleDownloads.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            stringResource(R.string.library_downloads_no_matches),
                            modifier = Modifier.padding(28.dp),
                            color = Color(0xFF5F6368)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        items(visibleDownloads, key = { it.id }) { entry ->
                            DownloadRow(
                                entry = entry,
                                downloadMetaLabels = downloadMetaLabels,
                                onOpen = {
                                    if (canRetry(entry) && entry.status == DownloadStatus.Failed) {
                                        onRetry(entry)
                                    } else {
                                        onOpen(entry)
                                    }
                                },
                                onCopyUrl = {
                                    scope.launch {
                                        clipboard.setClipEntry(
                                            ClipEntry(ClipData.newPlainText("download_url", entry.sourceUrl))
                                        )
                                    }
                                    Toast.makeText(context, context.getString(R.string.library_downloads_url_copied), Toast.LENGTH_SHORT).show()
                                },
                                onRemove = {
                                    pendingDelete = entry
                                    deleteFile = true
                                },
                                onRetry = if (canRetry(entry)) {
                                    { onRetry(entry) }
                                } else {
                                    null
                                },
                                onCancel = if (
                                    canCancel(entry) &&
                                    (entry.status == DownloadStatus.Queued || entry.status == DownloadStatus.Running)
                                ) {
                                    { onCancel(entry) }
                                } else {
                                    null
                                }
                            )
                            HorizontalDivider(color = Color(0xFFE8EAED))
                        }
                        item { Spacer(modifier = Modifier.height(36.dp)) }
                    }
                }
            }
        }
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.library_downloads_delete_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.library_downloads_delete_message, entry.name))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { deleteFile = !deleteFile },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Checkbox(checked = deleteFile, onCheckedChange = { deleteFile = it })
                        Text(stringResource(R.string.library_downloads_delete_file))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemove(entry, deleteFile)
                        pendingDelete = null
                    }
                ) {
                    Text(stringResource(R.string.common_action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.common_action_cancel))
                }
            }
        )
    }

    if (pendingClearFinished) {
        AlertDialog(
            onDismissRequest = { pendingClearFinished = false },
            title = { Text(stringResource(R.string.library_downloads_clear_title)) },
            text = {
                Text(stringResource(R.string.library_downloads_clear_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearFinished()
                        pendingClearFinished = false
                    }
                ) {
                    Text(stringResource(R.string.library_downloads_clear_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingClearFinished = false }) {
                    Text(stringResource(R.string.common_action_cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadRow(
    entry: BrowserDownloadEntry,
    downloadMetaLabels: DownloadMetaLabels,
    onOpen: () -> Unit,
    onCopyUrl: () -> Unit,
    onRemove: () -> Unit,
    onRetry: (() -> Unit)?,
    onCancel: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpen,
                onLongClick = onCopyUrl
            )
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
            Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(22.dp), tint = Color(0xFF5F6368))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entry.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color(0xFF202124))
            Text(entry.sourceUrl, color = Color(0xFF5F6368), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
            if (entry.status == DownloadStatus.Running || entry.status == DownloadStatus.Queued) {
                if (entry.totalBytes > 0L) {
                    LinearProgressIndicator(
                        progress = {
                            (entry.bytesDownloaded.toFloat() / entry.totalBytes.toFloat()).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            Text(
                text = downloadMeta(entry, downloadMetaLabels),
                color = Color(0xFF6F737B),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if ((entry.status == DownloadStatus.Failed || entry.status == DownloadStatus.Canceled) && onRetry != null) {
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.common_action_retry))
            }
        }
        if ((entry.status == DownloadStatus.Running || entry.status == DownloadStatus.Queued) && onCancel != null) {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.common_action_cancel))
            }
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.library_downloads_remove_content_description))
        }
    }
}

@Composable
private fun DownloadStatusFilterRow(
    selectedFilter: DownloadStatusFilter,
    onFilterSelected: (DownloadStatusFilter) -> Unit
) {
    val labels = listOf(
        DownloadStatusFilter.All to stringResource(R.string.library_downloads_filter_all),
        DownloadStatusFilter.Active to stringResource(R.string.library_downloads_filter_active),
        DownloadStatusFilter.Completed to stringResource(R.string.library_downloads_filter_completed),
        DownloadStatusFilter.FailedOrCanceled to stringResource(R.string.library_downloads_filter_failed_canceled)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        labels.forEach { (filter, label) ->
            FilterChip(
                selected = selectedFilter == filter,
                onClick = { onFilterSelected(filter) },
                label = { Text(label) }
            )
        }
    }
}

@Composable
private fun DownloadSearchField(
    query: String,
    onQueryChange: (String) -> Unit
) {
    val searchLabel = stringResource(R.string.library_downloads_search_label)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp)
            .height(48.dp)
            .clip(CircleShape)
            .background(Color(0xFFE7E9F1))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            tint = Color(0xFF5F6368),
            modifier = Modifier.size(20.dp)
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            textStyle = MaterialTheme.typography.titleMedium.copy(color = Color(0xFF202124)),
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = searchLabel },
            decorationBox = { inner ->
                if (query.isBlank()) {
                    Text(
                        stringResource(R.string.library_downloads_search_placeholder),
                        color = Color(0xFF6F737B),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                inner()
            }
        )
        if (query.isNotBlank()) {
            Text(
                "×",
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onQueryChange("") }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                fontSize = 24.sp,
                color = Color(0xFF5F6368)
            )
        }
    }
}

@Composable
private fun BrowserLibraryScaffold(
    title: String,
    onBack: () -> Unit,
    action: (Pair<String, () -> Unit>)?,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF7F8FC))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(LibraryActionBarHeight)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(LibraryActionButtonSize)) {
                Text("‹", fontSize = LibraryActionIconSize, color = Color(0xFF202124))
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
        content()
    }
}

@Composable
private fun LibraryEmptyState(
    title: String,
    body: String,
    leading: String
) {
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
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(body, color = Color(0xFF5F6368))
        }
    }
}

@Composable
private fun LibraryNoMatches(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            message,
            modifier = Modifier.padding(28.dp),
            color = Color(0xFF5F6368)
        )
    }
}

@Composable
private fun LibrarySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    label: String,
    placeholder: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp)
            .height(48.dp)
            .clip(CircleShape)
            .background(Color(0xFFE7E9F1))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            tint = Color(0xFF5F6368),
            modifier = Modifier.size(20.dp)
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            textStyle = MaterialTheme.typography.titleMedium.copy(color = Color(0xFF202124)),
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = label },
            decorationBox = { inner ->
                if (query.isBlank()) {
                    Text(
                        placeholder,
                        color = Color(0xFF6F737B),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                inner()
            }
        )
        if (query.isNotBlank()) {
            Text(
                "×",
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onQueryChange("") }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                fontSize = 24.sp,
                color = Color(0xFF5F6368)
            )
        }
    }
}

@Composable
private fun BookmarkRow(
    bookmark: BrowserBookmark,
    editing: Boolean,
    iconPath: String?,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onSave: (title: String, url: String) -> Unit,
    onRemove: () -> Unit
) {
    var title by remember(bookmark.title) { mutableStateOf(bookmark.title) }
    var url by remember(bookmark.url) { mutableStateOf(bookmark.url) }
    val editLabel = stringResource(R.string.library_bookmarks_edit_label)

    Column(modifier = Modifier.fillMaxWidth()) {
        LibraryRow(
            title = bookmark.title,
            url = bookmark.url,
            meta = null,
            leading = "★",
            iconPath = iconPath,
            removeContentDescription = stringResource(R.string.library_bookmarks_remove_label),
            onOpen = onOpen,
            onRemove = onRemove,
            trailing = {
                TextButton(
                    onClick = onEdit,
                    shape = RectangleShape,
                    modifier = Modifier.semantics {
                        contentDescription = editLabel
                    }
                ) {
                    Text("✎", fontSize = 20.sp, color = Color(0xFF5F6368))
                }
            }
        )
        if (editing) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(start = 66.dp, end = 18.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.library_bookmarks_title_placeholder)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    label = { Text(stringResource(R.string.library_bookmarks_url_label)) },
                    isError = url.trim().isBlank(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onCancelEdit) {
                        Text(stringResource(R.string.common_action_cancel))
                    }
                    TextButton(
                        enabled = url.trim().isNotBlank(),
                        onClick = { onSave(title.trim(), url.trim()) }
                    ) {
                        Text(stringResource(R.string.common_action_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryRow(
    title: String,
    url: String,
    meta: String?,
    leading: String,
    iconPath: String?,
    removeContentDescription: String,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    trailing: @Composable (() -> Unit)? = null
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
            LibraryFavicon(iconPath = iconPath, fallback = leading)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                title.ifBlank { url },
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Color(0xFF202124)
            )
            Text(url, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color(0xFF5F6368), fontSize = 13.sp)
            if (!meta.isNullOrBlank()) {
                Text(meta, color = Color(0xFF80868B), fontSize = 12.sp)
            }
        }
        trailing?.invoke()
        IconButton(onClick = onRemove) {
            Text(
                "×",
                fontSize = 30.sp,
                color = Color(0xFF5F6368),
                modifier = Modifier.semantics { contentDescription = removeContentDescription }
            )
        }
    }
}

@Composable
private fun LibraryFavicon(iconPath: String?, fallback: String) {
    val bitmap = remember(iconPath) {
        iconPath
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?.takeIf { it.exists() && it.length() > 0L && it.length() <= 1_500_000L }
            ?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
        )
    } else {
        Text(fallback, color = Color(0xFF5F6368), fontSize = 20.sp)
    }
}

private fun sortBookmarksByAddedOrder(items: List<BrowserBookmark>): List<BrowserBookmark> =
    items
        .mapIndexed { index, item -> item to index }
        .sortedWith(
            compareByDescending<Pair<BrowserBookmark, Int>> { it.first.createdAt.takeIf { createdAt -> createdAt > 0L } ?: Long.MIN_VALUE }
                .thenBy { it.second }
        )
        .map { it.first }

private fun filterBookmarks(items: List<BrowserBookmark>, query: String): List<BrowserBookmark> {
    val normalizedQuery = query.trim().lowercase(Locale.getDefault())
    if (normalizedQuery.isBlank()) return items
    return items.filter { item ->
        item.title.lowercase(Locale.getDefault()).contains(normalizedQuery) ||
            item.url.lowercase(Locale.getDefault()).contains(normalizedQuery)
    }
}

private fun filterHistory(items: List<BrowserHistoryEntry>, query: String): List<BrowserHistoryEntry> {
    val normalizedQuery = query.trim().lowercase(Locale.getDefault())
    if (normalizedQuery.isBlank()) return items
    return items.filter { item ->
        item.title.lowercase(Locale.getDefault()).contains(normalizedQuery) ||
            item.url.lowercase(Locale.getDefault()).contains(normalizedQuery) ||
            formatVisitTime(item.visitedAt).lowercase(Locale.getDefault()).contains(normalizedQuery)
    }
}
private fun formatVisitTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return formatter.format(Date(timestamp))
}

private fun downloadMeta(entry: BrowserDownloadEntry, labels: DownloadMetaLabels): String {
    val status = when (entry.status) {
        DownloadStatus.Queued -> labels.queued
        DownloadStatus.Running -> labels.downloading
        DownloadStatus.Completed -> labels.complete
        DownloadStatus.Failed -> labels.failed
        DownloadStatus.Canceled -> labels.canceled
    }
    val size = when {
        entry.totalBytes > 0L -> "${formatBytes(entry.bytesDownloaded, labels.unknown)} / ${formatBytes(entry.totalBytes, labels.unknown)}"
        entry.bytesDownloaded > 0L -> formatBytes(entry.bytesDownloaded, labels.unknown)
        else -> labels.unknownSize
    }
    val time = formatVisitTime(entry.completedAt ?: entry.createdAt)
    val error = entry.error?.takeIf { it.isNotBlank() }
    return listOfNotNull(status, size, time, error).joinToString(" · ")
}

internal fun filterDownloads(
    items: List<BrowserDownloadEntry>,
    query: String,
    statusFilter: DownloadStatusFilter,
    labels: DownloadMetaLabels
): List<BrowserDownloadEntry> =
    items.filter { entry ->
        downloadMatchesStatusFilter(entry, statusFilter) &&
            downloadMatchesQuery(entry, query, labels)
    }

internal fun downloadMatchesStatusFilter(
    entry: BrowserDownloadEntry,
    statusFilter: DownloadStatusFilter
): Boolean =
    when (statusFilter) {
        DownloadStatusFilter.All -> true
        DownloadStatusFilter.Active -> entry.status == DownloadStatus.Queued || entry.status == DownloadStatus.Running
        DownloadStatusFilter.Completed -> entry.status == DownloadStatus.Completed
        DownloadStatusFilter.FailedOrCanceled -> entry.status == DownloadStatus.Failed || entry.status == DownloadStatus.Canceled
    }

internal fun downloadMatchesQuery(
    entry: BrowserDownloadEntry,
    query: String,
    labels: DownloadMetaLabels
): Boolean {
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    if (normalizedQuery.isBlank()) return true
    return listOf(
        entry.name,
        entry.sourceUrl,
        downloadMeta(entry, labels)
    ).any { field -> field.lowercase(Locale.ROOT).contains(normalizedQuery) }
}

private fun formatBytes(bytes: Long, unknownLabel: String): String {
    if (bytes < 0L) return unknownLabel
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    return if (unitIndex == 0) {
        "${bytes} B"
    } else {
        String.format(Locale.getDefault(), "%.1f %s", value, units[unitIndex])
    }
}
