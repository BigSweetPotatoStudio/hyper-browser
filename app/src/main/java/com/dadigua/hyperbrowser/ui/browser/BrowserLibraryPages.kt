package com.dadigua.hyperbrowser.ui.browser

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dadigua.hyperbrowser.R
import com.dadigua.hyperbrowser.browser.BrowserBookmark
import com.dadigua.hyperbrowser.browser.BrowserDownloadEntry
import com.dadigua.hyperbrowser.browser.BrowserHistoryEntry
import com.dadigua.hyperbrowser.browser.DownloadStatus
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LibraryActionBarHeight = 48.dp
private val LibraryActionButtonSize = 40.dp
private val LibraryActionIconSize = 24.sp

private data class DownloadMetaLabels(
    val queued: String,
    val downloading: String,
    val complete: String,
    val failed: String,
    val canceled: String,
    val unknownSize: String,
    val unknown: String
)

@Composable
internal fun BookmarksPage(
    bookmarks: List<BrowserBookmark>,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    val savedLabel = stringResource(R.string.library_bookmarks_saved)
    BrowserLibraryPage(
        title = stringResource(R.string.library_bookmarks_title),
        emptyTitle = stringResource(R.string.library_bookmarks_empty_title),
        emptyBody = stringResource(R.string.library_bookmarks_empty_body),
        items = bookmarks,
        onBack = onBack,
        onOpen = { onOpen(it.url) },
        onRemove = { onRemove(it.url) },
        itemTitle = { it.title },
        itemUrl = { it.url },
        itemMeta = { savedLabel },
        leading = "★",
        action = null
    )
}

@Composable
internal fun HistoryPage(
    history: List<BrowserHistoryEntry>,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit
) {
    var pendingClearHistory by remember { mutableStateOf(false) }
    BrowserLibraryPage(
        title = stringResource(R.string.library_history_title),
        emptyTitle = stringResource(R.string.library_history_empty_title),
        emptyBody = stringResource(R.string.library_history_empty_body),
        items = history,
        onBack = onBack,
        onOpen = { onOpen(it.url) },
        onRemove = { onRemove(it.url) },
        itemTitle = { it.title },
        itemUrl = { it.url },
        itemMeta = { formatVisitTime(it.visitedAt) },
        leading = "◷",
        action = if (history.isEmpty()) null else stringResource(R.string.common_action_clear) to { pendingClearHistory = true }
    )

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
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(orderedDownloads, key = { it.id }) { entry ->
                    DownloadRow(
                        entry = entry,
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
    onOpen: () -> Unit,
    onCopyUrl: () -> Unit,
    onRemove: () -> Unit,
    onRetry: (() -> Unit)?,
    onCancel: (() -> Unit)?
) {
    val downloadMetaLabels = DownloadMetaLabels(
        queued = stringResource(R.string.download_status_queued),
        downloading = stringResource(R.string.download_status_downloading),
        complete = stringResource(R.string.download_status_complete),
        failed = stringResource(R.string.download_status_failed),
        canceled = stringResource(R.string.download_status_canceled),
        unknownSize = stringResource(R.string.download_unknown_size),
        unknown = stringResource(R.string.download_unknown)
    )
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
