package com.dadigua.hyperbrowser.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dadigua.hyperbrowser.gecko.GeckoContextMenuTarget

@Composable
internal fun PageContextMenuDialog(
    target: GeckoContextMenuTarget,
    onDismissRequest: () -> Unit,
    onDownloadImage: (String) -> Unit,
    onOpenImage: (String) -> Unit,
    onCopyImage: (String) -> Unit,
    onOpenLink: (String) -> Unit,
    onCopyLink: (String) -> Unit,
    openImageLabel: String = "在新标签页打开图片",
    openLinkLabel: String = "在新标签页打开链接"
) {
    val imageUrl = target.imageUrl
    val linkUrl = target.linkUrl?.takeUnless { it == imageUrl }
    val previewUrl = imageUrl ?: target.linkUrl
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = target.displayTitle(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column {
                previewUrl?.let { url ->
                    Text(
                        text = url,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                imageUrl?.let { url ->
                    if (canDownloadContextUrl(url)) {
                        ContextMenuActionRow(
                            icon = Icons.Outlined.Download,
                            label = "下载图片",
                            onClick = { onDownloadImage(url) }
                        )
                    }
                    ContextMenuActionRow(
                        icon = Icons.AutoMirrored.Outlined.OpenInNew,
                        label = openImageLabel,
                        onClick = { onOpenImage(url) }
                    )
                    ContextMenuActionRow(
                        icon = Icons.Outlined.ContentCopy,
                        label = "复制图片地址",
                        onClick = { onCopyImage(url) }
                    )
                }
                if (imageUrl != null && linkUrl != null) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                }
                linkUrl?.let { url ->
                    ContextMenuActionRow(
                        icon = Icons.AutoMirrored.Outlined.OpenInNew,
                        label = openLinkLabel,
                        onClick = { onOpenLink(url) }
                    )
                    ContextMenuActionRow(
                        icon = Icons.Outlined.ContentCopy,
                        label = "复制链接",
                        onClick = { onCopyLink(url) }
                    )
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun ContextMenuActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null)
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun GeckoContextMenuTarget.displayTitle(): String =
    label?.takeIf { it.isNotBlank() }
        ?: when {
            imageUrl != null && linkUrl != null && imageUrl != linkUrl -> "图片链接"
            imageUrl != null -> "图片"
            else -> "链接"
        }

private fun canDownloadContextUrl(url: String): Boolean =
    url.startsWith("http://", ignoreCase = true) ||
        url.startsWith("https://", ignoreCase = true)
