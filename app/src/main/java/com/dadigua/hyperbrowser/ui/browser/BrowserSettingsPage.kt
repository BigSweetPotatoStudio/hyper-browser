package com.dadigua.hyperbrowser.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.dadigua.hyperbrowser.browser.BrowserSettings
import com.dadigua.hyperbrowser.update.AvailableUpdate
import com.dadigua.hyperbrowser.update.UpdateDownloadState

@Composable
internal fun SettingsPage(
    settings: BrowserSettings,
    checkedUpdate: AvailableUpdate?,
    updateDownloadState: UpdateDownloadState,
    ignoringBatteryOptimizations: Boolean,
    updateMessage: String?,
    onBack: () -> Unit,
    onUpdateSearchEngine: (searchEngineId: String, customSearchUrl: String) -> Unit,
    onUpdateToolbarPosition: (String) -> Unit,
    onUpdateWebsiteDisplayMode: (String) -> Unit,
    onUpdateBackgroundVideoEnhancement: (Boolean) -> Unit,
    onUpdateOpenNewTabsInCurrentTab: (Boolean) -> Unit,
    onUpdateLocalePreference: (String) -> Unit,
    onUpdatePrivacySettings: (dohEnabled: Boolean, dohProviderUrl: String, httpsOnlyEnabled: Boolean, privacyProtectionLevel: String) -> Unit,
    onOpenBatteryOptimizationSettings: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: (AvailableUpdate) -> Unit,
    onSkipUpdate: (AvailableUpdate) -> Unit,
    onClearSkippedUpdate: () -> Unit
) {
    LaunchedEffect(Unit) {
        onCheckUpdate()
    }

    var query by remember { mutableStateOf("") }
    var searchEngineExpanded by remember { mutableStateOf(false) }
    var toolbarExpanded by remember { mutableStateOf(false) }
    var displayExpanded by remember { mutableStateOf(false) }
    var languageExpanded by remember { mutableStateOf(false) }
    var tabExpanded by remember { mutableStateOf(false) }
    var dnsExpanded by remember { mutableStateOf(false) }
    var privacyExpanded by remember { mutableStateOf(false) }
    var backupExpanded by remember { mutableStateOf(false) }
    var backgroundExpanded by remember { mutableStateOf(false) }
    var updateExpanded by remember { mutableStateOf(false) }
    var customDraft by remember(settings.customSearchUrl) { mutableStateOf(settings.customSearchUrl) }
    var customError by remember { mutableStateOf("") }
    var dohDraft by remember(settings.dohProviderUrl) { mutableStateOf(settings.dohProviderUrl) }
    var dohError by remember { mutableStateOf("") }

    val customTokenError = stringResource(R.string.settings_custom_url_missing_token)
    val dohHttpsError = stringResource(R.string.settings_doh_https_required)
    val forceExpanded = query.trim().isNotBlank()
    val updateRowValue = checkedUpdate?.versionName
        ?: updateMessage?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.settings_update_github_release)

    fun commitCustomSearchUrl() {
        val nextCustomUrl = customDraft.trim()
        if (!nextCustomUrl.contains("%s")) {
            customError = customTokenError
            return
        }
        customError = ""
        onUpdateSearchEngine(BrowserSettings.SEARCH_ENGINE_CUSTOM, nextCustomUrl)
    }

    fun updatePrivacy(
        dohEnabled: Boolean = settings.dohEnabled,
        dohProviderUrl: String = dohDraft,
        httpsOnlyEnabled: Boolean = settings.httpsOnlyEnabled,
        privacyProtectionLevel: String = settings.privacyProtectionLevel
    ) {
        val cleanDohUrl = dohProviderUrl.trim()
        if (!isHttpsSettingsUrl(cleanDohUrl)) {
            dohError = dohHttpsError
            return
        }
        dohError = ""
        onUpdatePrivacySettings(dohEnabled, cleanDohUrl, httpsOnlyEnabled, privacyProtectionLevel)
    }

    val sections = settingsSections(settings, ignoringBatteryOptimizations, checkedUpdate, query)
    val visibleKeys = sections.map { it.key }.toSet()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF7F8FC))
    ) {
        SettingsHeader(title = stringResource(R.string.settings_title), onBack = onBack)
        SettingsSearchField(query = query, onQueryChange = { query = it })
        if (sections.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.settings_no_matches), color = Color(0xFF5F6368))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if ("search" in visibleKeys) {
                    item {
                        SettingsSection(
                            title = stringResource(R.string.settings_search_engine),
                            value = searchEngineLabel(settings.searchEngineId),
                            expanded = forceExpanded || searchEngineExpanded,
                            onToggle = { searchEngineExpanded = !searchEngineExpanded }
                        ) {
                            SettingsOption("Google", BrowserSettings.DEFAULT_SEARCH_URL_TEMPLATE, settings.searchEngineId == BrowserSettings.SEARCH_ENGINE_GOOGLE) {
                                customError = ""
                                onUpdateSearchEngine(BrowserSettings.SEARCH_ENGINE_GOOGLE, settings.customSearchUrl)
                            }
                            SettingsOption("Bing", "https://www.bing.com/search?q=%s", settings.searchEngineId == BrowserSettings.SEARCH_ENGINE_BING) {
                                customError = ""
                                onUpdateSearchEngine(BrowserSettings.SEARCH_ENGINE_BING, settings.customSearchUrl)
                            }
                            SettingsOption(
                                stringResource(R.string.settings_custom_search),
                                stringResource(R.string.settings_custom_search_help),
                                settings.searchEngineId == BrowserSettings.SEARCH_ENGINE_CUSTOM
                            ) {
                                customError = ""
                                onUpdateSearchEngine(BrowserSettings.SEARCH_ENGINE_CUSTOM, customDraft)
                            }
                            OutlinedTextField(
                                value = customDraft,
                                onValueChange = {
                                    customDraft = it
                                    customError = ""
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                                label = { Text(stringResource(R.string.settings_custom_search_url)) },
                                isError = customError.isNotBlank(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                            SettingsInlineMessage(customError, error = true)
                            SettingsActions {
                                TextButton(onClick = ::commitCustomSearchUrl) {
                                    Text(stringResource(R.string.common_action_save))
                                }
                            }
                        }
                    }
                }

                if ("toolbar" in visibleKeys) {
                    item {
                        SettingsSection(
                            title = stringResource(R.string.settings_toolbar_position),
                            value = toolbarPositionLabel(settings.toolbarPosition),
                            expanded = forceExpanded || toolbarExpanded,
                            onToggle = { toolbarExpanded = !toolbarExpanded }
                        ) {
                            SettingsOption(stringResource(R.string.settings_toolbar_top), stringResource(R.string.settings_toolbar_top_help), settings.toolbarPosition == BrowserSettings.TOOLBAR_POSITION_TOP) {
                                onUpdateToolbarPosition(BrowserSettings.TOOLBAR_POSITION_TOP)
                            }
                            SettingsOption(stringResource(R.string.settings_toolbar_bottom), stringResource(R.string.settings_toolbar_bottom_help), settings.toolbarPosition == BrowserSettings.TOOLBAR_POSITION_BOTTOM) {
                                onUpdateToolbarPosition(BrowserSettings.TOOLBAR_POSITION_BOTTOM)
                            }
                            SettingsOption(stringResource(R.string.settings_toolbar_dynamic_bottom), stringResource(R.string.settings_toolbar_dynamic_bottom_help), settings.toolbarPosition == BrowserSettings.TOOLBAR_POSITION_DYNAMIC_BOTTOM) {
                                onUpdateToolbarPosition(BrowserSettings.TOOLBAR_POSITION_DYNAMIC_BOTTOM)
                            }
                            SettingsOption(stringResource(R.string.settings_toolbar_floating_dot), stringResource(R.string.settings_toolbar_floating_dot_help), settings.toolbarPosition == BrowserSettings.TOOLBAR_POSITION_FLOATING_DOT) {
                                onUpdateToolbarPosition(BrowserSettings.TOOLBAR_POSITION_FLOATING_DOT)
                            }
                        }
                    }
                }

                if ("display" in visibleKeys) {
                    item {
                        SettingsSection(
                            title = stringResource(R.string.settings_website_display_mode),
                            value = websiteDisplayModeLabel(settings.websiteDisplayMode),
                            expanded = forceExpanded || displayExpanded,
                            onToggle = { displayExpanded = !displayExpanded }
                        ) {
                            SettingsOption(stringResource(R.string.browser_website_display_mobile), stringResource(R.string.settings_display_mobile_help), settings.websiteDisplayMode == BrowserSettings.WEBSITE_DISPLAY_MOBILE) {
                                onUpdateWebsiteDisplayMode(BrowserSettings.WEBSITE_DISPLAY_MOBILE)
                            }
                            SettingsOption(stringResource(R.string.browser_website_display_tablet), stringResource(R.string.settings_display_tablet_help), settings.websiteDisplayMode == BrowserSettings.WEBSITE_DISPLAY_TABLET) {
                                onUpdateWebsiteDisplayMode(BrowserSettings.WEBSITE_DISPLAY_TABLET)
                            }
                            SettingsOption(stringResource(R.string.browser_website_display_desktop), stringResource(R.string.settings_display_desktop_help), settings.websiteDisplayMode == BrowserSettings.WEBSITE_DISPLAY_DESKTOP) {
                                onUpdateWebsiteDisplayMode(BrowserSettings.WEBSITE_DISPLAY_DESKTOP)
                            }
                        }
                    }
                }

                if ("language" in visibleKeys) {
                    item {
                        SettingsSection(
                            title = stringResource(R.string.settings_language),
                            value = localePreferenceLabel(settings.localePreference),
                            expanded = forceExpanded || languageExpanded,
                            onToggle = { languageExpanded = !languageExpanded }
                        ) {
                            SettingsOption(stringResource(R.string.settings_language_default), null, settings.localePreference == BrowserSettings.LOCALE_DEFAULT) {
                                onUpdateLocalePreference(BrowserSettings.LOCALE_DEFAULT)
                            }
                            SettingsOption(stringResource(R.string.settings_language_chinese), null, settings.localePreference == BrowserSettings.LOCALE_CHINESE) {
                                onUpdateLocalePreference(BrowserSettings.LOCALE_CHINESE)
                            }
                            SettingsOption(stringResource(R.string.settings_language_english), null, settings.localePreference == BrowserSettings.LOCALE_ENGLISH) {
                                onUpdateLocalePreference(BrowserSettings.LOCALE_ENGLISH)
                            }
                        }
                    }
                }

                if ("tabs" in visibleKeys) {
                    item {
                        SettingsSection(
                            title = stringResource(R.string.settings_new_tab),
                            value = tabBehaviorLabel(settings.openNewTabsInCurrentTab),
                            expanded = forceExpanded || tabExpanded,
                            onToggle = { tabExpanded = !tabExpanded }
                        ) {
                            SettingsOption(stringResource(R.string.settings_new_tab_value), stringResource(R.string.settings_new_tab_help), !settings.openNewTabsInCurrentTab) {
                                onUpdateOpenNewTabsInCurrentTab(false)
                            }
                            SettingsOption(stringResource(R.string.settings_current_tab_value), stringResource(R.string.settings_current_tab_help), settings.openNewTabsInCurrentTab) {
                                onUpdateOpenNewTabsInCurrentTab(true)
                            }
                        }
                    }
                }

                if ("dns" in visibleKeys) {
                    item {
                        SettingsSection(
                            title = stringResource(R.string.settings_http_dns),
                            value = if (settings.dohEnabled || settings.httpsOnlyEnabled) stringResource(R.string.settings_http_dns_active) else stringResource(R.string.settings_http_dns_disabled),
                            expanded = forceExpanded || dnsExpanded,
                            onToggle = { dnsExpanded = !dnsExpanded }
                        ) {
                            SettingsToggle("DNS over HTTPS", stringResource(R.string.settings_doh_help), settings.dohEnabled) {
                                updatePrivacy(dohEnabled = it)
                            }
                            OutlinedTextField(
                                value = dohDraft,
                                onValueChange = {
                                    dohDraft = it
                                    dohError = ""
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                                label = { Text(stringResource(R.string.settings_doh_address)) },
                                isError = dohError.isNotBlank(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                            SettingsInlineMessage(dohError, error = true)
                            SettingsActions {
                                TextButton(onClick = { updatePrivacy(dohProviderUrl = dohDraft) }) {
                                    Text(stringResource(R.string.common_action_save))
                                }
                            }
                            SettingsToggle("HTTPS-Only", stringResource(R.string.settings_https_only_help), settings.httpsOnlyEnabled) {
                                updatePrivacy(httpsOnlyEnabled = it)
                            }
                        }
                    }
                }

                if ("privacy" in visibleKeys) {
                    item {
                        SettingsSection(
                            title = stringResource(R.string.settings_privacy),
                            value = privacyProtectionLabel(settings.privacyProtectionLevel),
                            expanded = forceExpanded || privacyExpanded,
                            onToggle = { privacyExpanded = !privacyExpanded }
                        ) {
                            SettingsOption(stringResource(R.string.settings_privacy_none), stringResource(R.string.settings_privacy_none_help), settings.privacyProtectionLevel == BrowserSettings.PRIVACY_PROTECTION_NONE) {
                                updatePrivacy(privacyProtectionLevel = BrowserSettings.PRIVACY_PROTECTION_NONE)
                            }
                            SettingsOption(stringResource(R.string.settings_privacy_standard), stringResource(R.string.settings_privacy_standard_help), settings.privacyProtectionLevel == BrowserSettings.PRIVACY_PROTECTION_STANDARD) {
                                updatePrivacy(privacyProtectionLevel = BrowserSettings.PRIVACY_PROTECTION_STANDARD)
                            }
                            SettingsOption(stringResource(R.string.settings_privacy_strict), stringResource(R.string.settings_privacy_strict_help), settings.privacyProtectionLevel == BrowserSettings.PRIVACY_PROTECTION_STRICT) {
                                updatePrivacy(privacyProtectionLevel = BrowserSettings.PRIVACY_PROTECTION_STRICT)
                            }
                        }
                    }
                }

                if ("backup" in visibleKeys) {
                    item {
                        SettingsSection(
                            title = stringResource(R.string.settings_backup),
                            value = stringResource(R.string.settings_backup_value),
                            expanded = forceExpanded || backupExpanded,
                            onToggle = { backupExpanded = !backupExpanded }
                        ) {
                            SettingsActions {
                                TextButton(onClick = onExportBackup) { Text(stringResource(R.string.settings_export_json)) }
                                TextButton(onClick = onImportBackup) { Text(stringResource(R.string.settings_import_json)) }
                            }
                            SettingsInlineMessage(stringResource(R.string.settings_backup_help))
                        }
                    }
                }

                if ("background" in visibleKeys) {
                    item {
                        SettingsSection(
                            title = stringResource(R.string.settings_background_runtime),
                            value = if (ignoringBatteryOptimizations) stringResource(R.string.settings_battery_allowed) else stringResource(R.string.settings_battery_restricted),
                            expanded = forceExpanded || backgroundExpanded,
                            onToggle = { backgroundExpanded = !backgroundExpanded }
                        ) {
                            SettingsToggle(
                                title = stringResource(R.string.settings_background_video),
                                description = stringResource(R.string.settings_background_video_help),
                                checked = settings.backgroundVideoEnhancementEnabled,
                                onCheckedChange = onUpdateBackgroundVideoEnhancement
                            )
                            SettingsInlineMessage(stringResource(R.string.settings_battery_help))
                            SettingsActions {
                                TextButton(onClick = onOpenBatteryOptimizationSettings) {
                                    Text(stringResource(R.string.settings_open_battery_settings))
                                }
                            }
                        }
                    }
                }

                if ("update" in visibleKeys) {
                    item {
                        SettingsSection(
                            title = stringResource(R.string.settings_update),
                            value = updateRowValue,
                            expanded = forceExpanded || updateExpanded,
                            onToggle = { updateExpanded = !updateExpanded }
                        ) {
                            checkedUpdate?.let { update ->
                                SettingsOption(
                                    title = update.versionName,
                                    description = listOf(update.asset.abi, formatSettingsBytes(update.asset.sizeBytes)).filter { it.isNotBlank() }.joinToString(" · "),
                                    selected = false,
                                    onClick = { }
                                )
                                SettingsInlineMessage(update.notes)
                            }
                            SettingsActions {
                                TextButton(onClick = onCheckUpdate) {
                                    Text(stringResource(R.string.settings_check_update))
                                }
                                checkedUpdate?.let { update ->
                                    TextButton(
                                        enabled = !isActiveSettingsUpdateDownload(updateDownloadState.status),
                                        onClick = { onInstallUpdate(update) }
                                    ) {
                                        Text(updateActionLabel(updateDownloadState))
                                    }
                                    TextButton(onClick = { onSkipUpdate(update) }) {
                                        Text(stringResource(R.string.settings_skip_version))
                                    }
                                }
                                TextButton(onClick = onClearSkippedUpdate) {
                                    Text(stringResource(R.string.settings_clear_skip))
                                }
                            }
                            if (updateDownloadState.status != UpdateDownloadState.STATUS_IDLE) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    LinearProgressIndicator(
                                        progress = { updateProgressFraction(updateDownloadState) },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Text(updateDownloadState.message.ifBlank { updateActionLabel(updateDownloadState) }, color = Color(0xFF5F6368))
                                    Text(
                                        "${formatSettingsBytes(updateDownloadState.bytesDownloaded)} / ${formatSettingsBytes(updateDownloadState.totalBytes)}",
                                        color = Color(0xFF5F6368),
                                        fontSize = 12.sp
                                    )
                                }
                            }
                            if (checkedUpdate != null || updateDownloadState.status != UpdateDownloadState.STATUS_IDLE) {
                                SettingsInlineMessage(updateMessage.orEmpty())
                            }
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(36.dp)) }
            }
        }
    }
}

internal data class SettingsSectionInfo(
    val key: String,
    val terms: List<String>
)

@Composable
private fun settingsSections(
    settings: BrowserSettings,
    ignoringBatteryOptimizations: Boolean,
    checkedUpdate: AvailableUpdate?,
    query: String
): List<SettingsSectionInfo> {
    val sections = listOf(
        SettingsSectionInfo(
            "search",
            listOf(
                stringResource(R.string.settings_search_engine),
                settings.searchEngineName,
                stringResource(R.string.settings_custom_search),
                stringResource(R.string.settings_custom_search_help),
                stringResource(R.string.settings_custom_search_url),
                "google",
                "bing",
                "custom",
                "search url"
            )
        ),
        SettingsSectionInfo(
            "toolbar",
            listOf(
                stringResource(R.string.settings_toolbar_position),
                stringResource(R.string.settings_toolbar_top),
                stringResource(R.string.settings_toolbar_top_help),
                stringResource(R.string.settings_toolbar_bottom),
                stringResource(R.string.settings_toolbar_bottom_help),
                stringResource(R.string.settings_toolbar_dynamic_bottom),
                stringResource(R.string.settings_toolbar_dynamic_bottom_help),
                stringResource(R.string.settings_toolbar_floating_dot),
                stringResource(R.string.settings_toolbar_floating_dot_help),
                "address bar",
                "toolbar",
                "auto hide",
                "floating"
            )
        ),
        SettingsSectionInfo(
            "display",
            listOf(
                stringResource(R.string.settings_website_display_mode),
                stringResource(R.string.browser_website_display_mobile),
                stringResource(R.string.settings_display_mobile_help),
                stringResource(R.string.browser_website_display_tablet),
                stringResource(R.string.settings_display_tablet_help),
                stringResource(R.string.browser_website_display_desktop),
                stringResource(R.string.settings_display_desktop_help),
                "ua",
                "user agent",
                "pc"
            )
        ),
        SettingsSectionInfo(
            "language",
            listOf(
                stringResource(R.string.settings_language),
                stringResource(R.string.settings_language_default),
                stringResource(R.string.settings_language_chinese),
                stringResource(R.string.settings_language_english),
                "locale",
                "translation"
            )
        ),
        SettingsSectionInfo(
            "tabs",
            listOf(
                stringResource(R.string.settings_new_tab),
                stringResource(R.string.settings_new_tab_value),
                stringResource(R.string.settings_new_tab_help),
                stringResource(R.string.settings_current_tab_value),
                stringResource(R.string.settings_current_tab_help),
                "popup",
                "window open",
                "login redirect"
            )
        ),
        SettingsSectionInfo(
            "dns",
            listOf(
                stringResource(R.string.settings_http_dns),
                stringResource(R.string.settings_http_dns_active),
                stringResource(R.string.settings_http_dns_disabled),
                stringResource(R.string.settings_doh_help),
                stringResource(R.string.settings_doh_address),
                stringResource(R.string.settings_https_only_help),
                "dns",
                "doh",
                "https",
                "https-only",
                "security"
            )
        ),
        SettingsSectionInfo(
            "privacy",
            listOf(
                stringResource(R.string.settings_privacy),
                stringResource(R.string.settings_privacy_none),
                stringResource(R.string.settings_privacy_none_help),
                stringResource(R.string.settings_privacy_standard),
                stringResource(R.string.settings_privacy_standard_help),
                stringResource(R.string.settings_privacy_strict),
                stringResource(R.string.settings_privacy_strict_help),
                "tracking",
                "fingerprint",
                "cookie"
            )
        ),
        SettingsSectionInfo(
            "backup",
            listOf(
                stringResource(R.string.settings_backup),
                stringResource(R.string.settings_backup_value),
                stringResource(R.string.settings_export_json),
                stringResource(R.string.settings_import_json),
                stringResource(R.string.settings_backup_help),
                "json",
                "restore",
                "webapp"
            )
        ),
        SettingsSectionInfo(
            "background",
            listOf(
                stringResource(R.string.settings_background_runtime),
                stringResource(R.string.settings_battery_allowed),
                stringResource(R.string.settings_battery_restricted),
                stringResource(R.string.settings_background_video),
                stringResource(R.string.settings_background_video_help),
                stringResource(R.string.settings_battery_help),
                if (ignoringBatteryOptimizations) stringResource(R.string.settings_battery_allowed) else stringResource(R.string.settings_battery_restricted),
                "video",
                "battery",
                "music",
                "playback",
                "lock screen"
            )
        ),
        SettingsSectionInfo(
            "update",
            listOf(
                stringResource(R.string.settings_update),
                stringResource(R.string.settings_update_github_release),
                stringResource(R.string.settings_check_update),
                checkedUpdate?.versionName.orEmpty(),
                "github",
                "release",
                "apk",
                "version"
            )
        )
    )
    return filterSettingsSections(sections, query)
}

internal fun filterSettingsSections(
    sections: List<SettingsSectionInfo>,
    query: String
): List<SettingsSectionInfo> =
    sections.filter { settingsSectionMatchesQuery(it.terms, query) }

internal fun settingsSectionMatchesQuery(terms: List<String>, query: String): Boolean {
    val normalizedQuery = query.trim().lowercase()
    if (normalizedQuery.isBlank()) return true
    return terms.any { it.lowercase().contains(normalizedQuery) }
}

@Composable
private fun SettingsHeader(title: String, onBack: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Text("‹", fontSize = 24.sp, color = Color(0xFF202124))
            }
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF202124)
            )
        }
        HorizontalDivider(color = Color(0xFFDADCE3))
    }
}

@Composable
private fun SettingsSearchField(query: String, onQueryChange: (String) -> Unit) {
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
                .semantics { contentDescription = "Search settings" },
            decorationBox = { inner ->
                if (query.isBlank()) {
                    Text(
                        stringResource(R.string.settings_search_placeholder),
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
private fun SettingsSection(
    title: String,
    value: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 14.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(Color.White)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, color = Color(0xFF202124))
            Text(value, color = Color(0xFF5F6368), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (expanded) {
            HorizontalDivider(color = Color(0xFFE8EAED))
            content()
        }
    }
}

@Composable
private fun SettingsOption(
    title: String,
    description: String?,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = Color(0xFF202124))
            if (!description.isNullOrBlank()) {
                Text(description, color = Color(0xFF5F6368), fontSize = 12.sp)
            }
        }
        if (selected) {
            Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SettingsToggle(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = Color(0xFF202124))
            Text(description, color = Color(0xFF5F6368), fontSize = 12.sp)
        }
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsActions(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        content()
    }
}

@Composable
private fun SettingsInlineMessage(message: String, error: Boolean = false) {
    if (message.isBlank()) return
    Text(
        message,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        color = if (error) MaterialTheme.colorScheme.error else Color(0xFF5F6368),
        fontSize = 13.sp
    )
}

@Composable
private fun searchEngineLabel(value: String): String =
    when (value) {
        BrowserSettings.SEARCH_ENGINE_BING -> "Bing"
        BrowserSettings.SEARCH_ENGINE_CUSTOM -> stringResource(R.string.settings_custom_search)
        else -> "Google"
    }

@Composable
private fun toolbarPositionLabel(value: String): String =
    when (BrowserSettings.normalizedToolbarPosition(value)) {
        BrowserSettings.TOOLBAR_POSITION_TOP -> stringResource(R.string.settings_toolbar_top)
        BrowserSettings.TOOLBAR_POSITION_BOTTOM -> stringResource(R.string.settings_toolbar_bottom)
        BrowserSettings.TOOLBAR_POSITION_FLOATING_DOT -> stringResource(R.string.settings_toolbar_floating_dot)
        else -> stringResource(R.string.settings_toolbar_dynamic_bottom)
    }

@Composable
private fun websiteDisplayModeLabel(value: String): String =
    when (BrowserSettings.normalizedWebsiteDisplayMode(value)) {
        BrowserSettings.WEBSITE_DISPLAY_TABLET -> stringResource(R.string.browser_website_display_tablet)
        BrowserSettings.WEBSITE_DISPLAY_DESKTOP -> stringResource(R.string.browser_website_display_desktop)
        else -> stringResource(R.string.browser_website_display_mobile)
    }

@Composable
private fun localePreferenceLabel(value: String): String =
    when (BrowserSettings.normalizedLocalePreference(value)) {
        BrowserSettings.LOCALE_CHINESE -> stringResource(R.string.settings_language_chinese)
        BrowserSettings.LOCALE_ENGLISH -> stringResource(R.string.settings_language_english)
        else -> stringResource(R.string.settings_language_default)
    }

@Composable
private fun tabBehaviorLabel(openInCurrentTab: Boolean): String =
    if (openInCurrentTab) stringResource(R.string.settings_current_tab_value) else stringResource(R.string.settings_new_tab_value)

@Composable
private fun privacyProtectionLabel(value: String): String =
    when (value) {
        BrowserSettings.PRIVACY_PROTECTION_NONE -> stringResource(R.string.settings_privacy_none)
        BrowserSettings.PRIVACY_PROTECTION_STRICT -> stringResource(R.string.settings_privacy_strict)
        else -> stringResource(R.string.settings_privacy_standard)
    }

@Composable
private fun updateActionLabel(state: UpdateDownloadState): String =
    when (state.status) {
        UpdateDownloadState.STATUS_PREPARING -> stringResource(R.string.settings_update_preparing_short)
        UpdateDownloadState.STATUS_DOWNLOADING -> stringResource(R.string.settings_update_downloading)
        UpdateDownloadState.STATUS_VERIFYING -> stringResource(R.string.settings_update_verifying)
        UpdateDownloadState.STATUS_PERMISSION_REQUIRED -> stringResource(R.string.settings_update_permission_required)
        UpdateDownloadState.STATUS_READY -> stringResource(R.string.settings_install_update)
        UpdateDownloadState.STATUS_ERROR -> stringResource(R.string.common_action_retry)
        else -> stringResource(R.string.settings_update_now)
    }

private fun isHttpsSettingsUrl(value: String): Boolean =
    value.startsWith("https://", ignoreCase = true)

private fun isActiveSettingsUpdateDownload(status: String): Boolean =
    status == UpdateDownloadState.STATUS_PREPARING ||
        status == UpdateDownloadState.STATUS_DOWNLOADING ||
        status == UpdateDownloadState.STATUS_VERIFYING

private fun updateProgressFraction(state: UpdateDownloadState): Float =
    if (state.totalBytes <= 0L) 0f else (state.bytesDownloaded.toFloat() / state.totalBytes.toFloat()).coerceIn(0f, 1f)

private fun formatSettingsBytes(value: Long): String {
    if (value <= 0L) return ""
    if (value >= 1024L * 1024L) return "%.1f MB".format(value / 1024f / 1024f)
    if (value >= 1024L) return "%.0f KB".format(value / 1024f)
    return "$value B"
}
