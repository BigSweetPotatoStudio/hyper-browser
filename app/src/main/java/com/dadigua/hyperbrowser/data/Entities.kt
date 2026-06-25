package com.dadigua.hyperbrowser.data

data class WebAppDefinition(
    val id: String,
    val name: String,
    val startUrl: String,
    val iconPath: String?,
    val themeColor: Int,
    val displayMode: String,
    val createdAt: Long,
    val lastOpenedAt: Long
)

data class BrowserTabState(
    val id: String,
    val title: String,
    val url: String,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    val loading: Boolean
)

data class InstalledExtensionState(
    val guid: String,
    val name: String,
    val version: String,
    val enabled: Boolean,
    val source: String,
    val permissionsSnapshot: String,
    val xpiPath: String?,
    val installedAt: Long
)
