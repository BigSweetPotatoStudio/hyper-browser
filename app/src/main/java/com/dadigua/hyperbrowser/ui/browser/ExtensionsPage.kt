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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dadigua.hyperbrowser.R
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
    var pendingUninstall by remember { mutableStateOf<InstalledExtensionState?>(null) }

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
                    stringResource(R.string.extensions_title),
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
                Text(stringResource(R.string.extensions_find_more), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                            textStyle = MaterialTheme.typography.titleMedium.copy(color = Color(0xFF202124)),
                            modifier = Modifier.weight(1f),
                            decorationBox = { inner ->
                                if (query.isBlank()) {
                                    Text(stringResource(R.string.extensions_search_placeholder), color = Color(0xFF6F737B), style = MaterialTheme.typography.titleMedium)
                                }
                                inner()
                            }
                        )
                    }
                    Button(onClick = onSearch) { Text(stringResource(R.string.common_action_search)) }
                }
                message?.let { Text(it, color = Color(0xFF126D6A)) }
            }
        }
        item {
            Text(
                stringResource(R.string.extensions_installed_title),
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        if (installed.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.extensions_empty_installed),
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
                    onUninstall = { pendingUninstall = extension }
                )
            }
        }
        if (installableResults.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.extensions_recommended_from_amo),
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
                stringResource(R.string.extensions_amo_catalog_note),
                modifier = Modifier.padding(horizontal = 18.dp),
                color = Color(0xFF5F6368),
                style = MaterialTheme.typography.bodySmall
            )
        }
        item { Spacer(modifier = Modifier.height(36.dp)) }
    }

    pendingUninstall?.let { extension ->
        AlertDialog(
            onDismissRequest = { pendingUninstall = null },
            title = { Text(stringResource(R.string.extensions_uninstall_confirm_title)) },
            text = {
                Text(stringResource(R.string.extensions_uninstall_confirm_message, extension.name))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUninstall(extension)
                        pendingUninstall = null
                    }
                ) {
                    Text(stringResource(R.string.extensions_uninstall))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstall = null }) {
                    Text(stringResource(R.string.common_action_cancel))
                }
            }
        )
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
                Text(stringResource(R.string.extensions_summary_title), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.extensions_summary_counts, enabledCount, installedCount),
                    color = Color(0xFF5F6368),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            TextButton(onClick = onSearch) {
                Text(stringResource(R.string.common_action_refresh))
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
                    Text(
                        "${extension.version} · ${if (extension.enabled) stringResource(R.string.extensions_status_enabled) else stringResource(R.string.extensions_status_disabled)}",
                        color = Color(0xFF5F6368)
                    )
                }
            }
            amoListing?.let { listing ->
                val updateAvailable = listing.version != extension.version
                Text(
                    if (updateAvailable) {
                        stringResource(R.string.extensions_amo_update_available, listing.version)
                    } else {
                        stringResource(R.string.extensions_amo_latest, listing.version)
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
                    Text(if (extension.enabled) stringResource(R.string.common_action_disable) else stringResource(R.string.common_action_enable))
                }
                TextButton(onClick = onUninstall) { Text(stringResource(R.string.extensions_uninstall)) }
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
                    Text(stringResource(R.string.extensions_version_users, addon.version, addon.userCount), color = Color(0xFF5F6368))
                }
            }
            Text(
                if (addon.permissions.isEmpty()) stringResource(R.string.extensions_no_permissions) else addon.permissions.take(5).joinToString(", "),
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
                        installed -> stringResource(R.string.extensions_installed)
                        installing -> stringResource(R.string.extensions_installing)
                        else -> stringResource(R.string.common_action_add)
                    }
                )
            }
        }
    }
}
