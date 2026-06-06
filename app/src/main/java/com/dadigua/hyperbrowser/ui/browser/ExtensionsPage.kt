package com.dadigua.hyperbrowser.ui.browser

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dadigua.hyperbrowser.data.InstalledExtensionState
import com.dadigua.hyperbrowser.extensions.AmoAddonListing

private val ExtensionActionBarHeight = 48.dp
private val ExtensionActionButtonSize = 40.dp
private val ExtensionActionIconSize = 24.sp

@Composable
internal fun ExtensionsPage(
    query: String,
    installed: List<InstalledExtensionState>,
    results: List<AmoAddonListing>,
    message: String?,
    installingAddonGuid: String?,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onInstall: (AmoAddonListing) -> Unit,
    onToggleEnabled: (InstalledExtensionState) -> Unit,
    onUninstall: (InstalledExtensionState) -> Unit
) {
    val installedGuids = installed.map { it.guid }.toSet()
    val installedAmoListings = results.associateBy { it.guid }
    val installableResults = results.filterNot { it.guid in installedGuids }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF7F8FC)),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ExtensionActionBarHeight)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(ExtensionActionButtonSize)) {
                    Text("‹", fontSize = ExtensionActionIconSize, color = Color(0xFF202124))
                }
                Text(
                    "扩展",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF202124)
                )
            }
            HorizontalDivider(color = Color(0xFFDADCE3))
        }
        item {
            ExtensionSummaryCard(
                installedCount = installed.size,
                enabledCount = installed.count { it.enabled },
                onSearch = onSearch
            )
        }
        item {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Find more add-ons", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .clip(RoundedCornerShape(26.dp))
                            .background(Color(0xFFE7E9F1))
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicTextField(
                            value = query,
                            onValueChange = onQueryChange,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.titleMedium.copy(color = Color(0xFF202124)),
                            modifier = Modifier.weight(1f),
                            decorationBox = { inner ->
                                if (query.isBlank()) {
                                    Text("搜索扩展", color = Color(0xFF6F737B), style = MaterialTheme.typography.titleMedium)
                                }
                                inner()
                            }
                        )
                    }
                    Button(onClick = onSearch) { Text("Search") }
                }
                message?.let { Text(it, color = Color(0xFF126D6A)) }
            }
        }
        item {
            Text(
                "Installed add-ons",
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        if (installed.isEmpty()) {
            item {
                Text(
                    "还没有安装扩展。",
                    modifier = Modifier.padding(horizontal = 18.dp),
                    color = Color(0xFF5F6368)
                )
            }
        } else {
            items(installed, key = { "installed:${it.guid}" }) { extension ->
                InstalledExtensionRow(
                    extension = extension,
                    amoListing = installedAmoListings[extension.guid],
                    onToggle = { onToggleEnabled(extension) },
                    onUninstall = { onUninstall(extension) }
                )
            }
        }
        if (installableResults.isNotEmpty()) {
            item {
                Text(
                    "Recommended from AMO",
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            items(installableResults, key = { "result:${it.guid}" }) { addon ->
                AddonResultRow(
                    addon = addon,
                    installing = installingAddonGuid == addon.guid,
                    installed = installed.any { it.guid == addon.guid },
                    onInstall = { onInstall(addon) }
                )
            }
        }
        item {
            Text(
                "Search uses the Android AMO catalog. Installed add-ons appear in the browser menu like Iceraven's extensions section.",
                modifier = Modifier.padding(horizontal = 18.dp),
                color = Color(0xFF5F6368),
                style = MaterialTheme.typography.bodySmall
            )
        }
        item { Spacer(modifier = Modifier.height(36.dp)) }
    }
}

@Composable
private fun ExtensionSummaryCard(
    installedCount: Int,
    enabledCount: Int,
    onSearch: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE8EAED)),
                contentAlignment = Alignment.Center
            ) {
                Text("E", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Color(0xFF202124))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Extensions", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(
                    "$enabledCount enabled · $installedCount installed",
                    color = Color(0xFF5F6368),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            TextButton(onClick = onSearch) {
                Text("Refresh")
            }
        }
    }
}

@Composable
private fun InstalledExtensionRow(
    extension: InstalledExtensionState,
    amoListing: AmoAddonListing?,
    onToggle: () -> Unit,
    onUninstall: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE8EAED)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("🧩", fontSize = 20.sp)
                }
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(extension.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${extension.version} · ${if (extension.enabled) "Enabled" else "Disabled"}", color = Color(0xFF5F6368))
                }
            }
            amoListing?.let { listing ->
                val updateAvailable = listing.version != extension.version
                Text(
                    if (updateAvailable) {
                        "AMO update available: ${listing.version}"
                    } else {
                        "AMO latest: ${listing.version}"
                    },
                    color = if (updateAvailable) Color(0xFF126D6A) else Color(0xFF5F6368),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (updateAvailable) FontWeight.SemiBold else FontWeight.Normal
                )
            }
            if (extension.permissionsSnapshot.isNotBlank()) {
                Text(
                    extension.permissionsSnapshot.lines().take(3).joinToString(", "),
                    color = Color(0xFF5F6368),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onToggle) {
                    Text(if (extension.enabled) "Disable" else "Enable")
                }
                TextButton(onClick = onUninstall) { Text("Uninstall") }
            }
        }
    }
}

@Composable
private fun AddonResultRow(
    addon: AmoAddonListing,
    installing: Boolean,
    installed: Boolean,
    onInstall: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE8EAED)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("🧩", fontSize = 20.sp)
                }
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(addon.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Version ${addon.version} · ${addon.userCount} users", color = Color(0xFF5F6368))
                }
            }
            Text(
                if (addon.permissions.isEmpty()) "No declared permissions" else addon.permissions.take(5).joinToString(", "),
                color = Color(0xFF5F6368),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Button(
                onClick = onInstall,
                enabled = !installing && !installed
            ) {
                Text(
                    when {
                        installed -> "Installed"
                        installing -> "Installing..."
                        else -> "Add"
                    }
                )
            }
        }
    }
}
