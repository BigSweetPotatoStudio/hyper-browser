package com.dadigua.hyperbrowser.ui.browser

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AddToHomeScreen
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dadigua.hyperbrowser.browser.BrowserDownloadEntry
import com.dadigua.hyperbrowser.browser.DownloadStatus
import com.dadigua.hyperbrowser.data.InstalledExtensionState
import com.dadigua.hyperbrowser.extensions.ExtensionMenuActionState

@Composable
internal fun BrowserMenuPanel(
    bookmarked: Boolean,
    webAppInstalled: Boolean,
    enabledExtensions: List<InstalledExtensionState>,
    downloads: List<BrowserDownloadEntry>,
    installedExtensionCount: Int,
    extensionActions: Map<String, ExtensionMenuActionState>,
    extensionsExpanded: Boolean,
    onExtensionsExpandedChange: (Boolean) -> Unit,
    onNewTab: () -> Unit,
    onNewPrivateTab: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onSharePage: () -> Unit,
    onCopyPageLink: () -> Unit,
    onFindInPage: () -> Unit,
    onToggleBookmark: () -> Unit,
    onShowSettings: () -> Unit,
    onShowDownloads: () -> Unit,
    onShowExtensions: () -> Unit,
    onExtensionClick: (InstalledExtensionState) -> Unit,
    onInstall: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(304.dp)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MenuNavRow(
            onBack = onBack,
            onForward = onForward,
            onReload = onReload
        )
        MenuGroupBox {
            BrowserMenuRow(label = "New tab", leadingIconVector = Icons.Outlined.Add, onClick = onNewTab)
            BrowserMenuRow(
                label = "New private tab",
                leadingIconVector = Icons.Outlined.VisibilityOff,
                onClick = onNewPrivateTab
            )
            BrowserMenuRow(
                label = "Share page",
                leadingIconVector = Icons.Outlined.Share,
                onClick = onSharePage
            )
            BrowserMenuRow(
                label = "Copy link",
                leadingIconVector = Icons.Outlined.ContentCopy,
                onClick = onCopyPageLink
            )
            BrowserMenuRow(
                label = "Find in page",
                leadingIconVector = Icons.Outlined.FindInPage,
                onClick = onFindInPage
            )
            BrowserMenuRow(
                label = if (bookmarked) "Remove bookmark" else "Bookmark this page",
                leadingIconVector = if (bookmarked) Icons.Outlined.BookmarkRemove else Icons.Outlined.BookmarkAdd,
                onClick = onToggleBookmark
            )
            BrowserMenuRow(
                label = if (webAppInstalled) "Uninstall WebApp" else "Install as WebApp",
                leadingIconVector = if (webAppInstalled) Icons.Outlined.Delete else Icons.AutoMirrored.Outlined.AddToHomeScreen,
                onClick = onInstall
            )
        }
        MenuGroupBox {
            ExtensionsMenuRow(
                enabledCount = enabledExtensions.size,
                installedCount = installedExtensionCount,
                expanded = extensionsExpanded,
                onClick = { onExtensionsExpandedChange(!extensionsExpanded) }
            )
            if (extensionsExpanded) {
                if (enabledExtensions.isEmpty()) {
                    BrowserMenuRow(
                        label = "Discover more extensions",
                        leadingIconVector = Icons.Outlined.Add,
                        description = "Search Android add-ons",
                        indent = 28.dp,
                        onClick = onShowExtensions
                    )
                } else {
                    enabledExtensions.forEach { extension ->
                        val action = extensionActions[extension.guid]
                        BrowserMenuRow(
                            label = action?.title ?: extension.name,
                            leadingIcon = action?.icon,
                            leadingIconVector = Icons.Outlined.Extension,
                            description = action?.badgeText?.takeIf { it.isNotBlank() }
                                ?: extension.version,
                            trailing = if (action?.enabled == false) "Disabled" else "Settings",
                            indent = 28.dp,
                            onClick = { onExtensionClick(extension) },
                            onTrailingClick = onShowExtensions
                        )
                    }
                    BrowserMenuRow(
                        label = "Manage extensions",
                        leadingIconVector = Icons.Outlined.Tune,
                        description = "$installedExtensionCount installed",
                        indent = 28.dp,
                        onClick = onShowExtensions
                    )
                }
            }
        }
        MenuGroupBox {
            val activeDownloads = downloads.count { it.status == DownloadStatus.Running || it.status == DownloadStatus.Queued }
            BrowserMenuRow(
                label = "Downloads",
                leadingIconVector = Icons.Outlined.Download,
                description = if (activeDownloads > 0) {
                    "$activeDownloads downloading"
                } else {
                    "${downloads.size} files"
                },
                onClick = onShowDownloads
            )
            BrowserMenuRow(label = "Settings", leadingIconVector = Icons.Outlined.Settings, onClick = onShowSettings)
        }
    }
}

@Composable
private fun MenuGroupBox(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.clip(RoundedCornerShape(18.dp)),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        content()
    }
}

@Composable
private fun MenuNavRow(
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFFF1F3F8))
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        MenuNavButton(label = "Back", icon = "‹", onClick = onBack, modifier = Modifier.weight(1f))
        MenuNavButton(label = "Forward", icon = "›", onClick = onForward, modifier = Modifier.weight(1f))
        MenuNavButton(label = "Reload", icon = "↻", onClick = onReload, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun MenuNavButton(
    label: String,
    icon: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(icon, fontSize = 24.sp, color = Color(0xFF202124))
        Text(label, fontSize = 11.sp, color = Color(0xFF5F6368), maxLines = 1)
    }
}

@Composable
private fun ExtensionsMenuRow(
    enabledCount: Int,
    installedCount: Int,
    expanded: Boolean,
    onClick: () -> Unit
) {
    BrowserMenuRow(
        label = "Extensions",
        leadingIconVector = Icons.Outlined.Extension,
        description = when {
            enabledCount > 0 -> "$enabledCount enabled"
            installedCount > 0 -> "$installedCount installed, none enabled"
            else -> "No extensions enabled"
        },
        trailing = if (expanded) "⌃" else "$enabledCount  ˅",
        onClick = onClick
    )
}

@Composable
private fun BrowserMenuRow(
    label: String,
    leadingIcon: Bitmap? = null,
    leadingIconVector: ImageVector? = null,
    description: String? = null,
    trailing: String? = null,
    indent: Dp = 0.dp,
    onTrailingClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indent)
            .background(Color(0xFFF1F3F8))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            if (leadingIcon != null) {
                Image(
                    bitmap = leadingIcon.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            } else if (leadingIconVector != null) {
                Icon(
                    imageVector = leadingIconVector,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp),
                    tint = Color(0xFF202124)
                )
            } else {
                Spacer(modifier = Modifier.size(19.dp))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Color(0xFF202124), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            description?.let {
                Text(it, color = Color(0xFF5F6368), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.let {
            Text(
                it,
                color = Color(0xFF5F6368),
                fontSize = 12.sp,
                maxLines = 1,
                modifier = if (onTrailingClick != null) {
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onTrailingClick)
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                } else {
                    Modifier
                }
            )
        }
    }
}
