package com.dadigua.hyperbrowser.ui.browser

import com.dadigua.hyperbrowser.browser.SavedBrowserTab
import com.dadigua.hyperbrowser.browser.SavedBrowserTabs

internal data class BrowserTabRestoreSpec(
    val id: String?,
    val title: String?,
    val url: String,
    val input: String,
    val iconPath: String?,
    val loadImmediately: Boolean
)

internal data class BrowserTabRestorePlan(
    val tabs: List<BrowserTabRestoreSpec>,
    val selectedSavedTabId: String?,
    val selectLastTab: Boolean
)

internal fun planBrowserTabRestore(
    savedTabs: SavedBrowserTabs,
    launchUrl: String?,
    fallbackUrl: String
): BrowserTabRestorePlan {
    val restoredTabs = savedTabs.tabs.mapNotNull { it.toRestoreSpec() }
    val launchTab = launchUrl
        ?.takeIf { it.isNotBlank() }
        ?.let {
            BrowserTabRestoreSpec(
                id = null,
                title = null,
                url = it,
                input = it,
                iconPath = null,
                loadImmediately = false
            )
        }
    val tabs = when {
        restoredTabs.isNotEmpty() && launchTab != null -> restoredTabs + launchTab
        restoredTabs.isNotEmpty() -> restoredTabs
        launchTab != null -> listOf(launchTab)
        else -> listOf(
            BrowserTabRestoreSpec(
                id = null,
                title = null,
                url = fallbackUrl,
                input = fallbackUrl,
                iconPath = null,
                loadImmediately = false
            )
        )
    }
    val selectedSavedTabId = savedTabs.selectedTabId?.takeIf { id ->
        restoredTabs.any { it.id == id }
    }
    val selectedIndex = when {
        launchTab != null -> tabs.lastIndex
        selectedSavedTabId != null -> tabs.indexOfFirst { it.id == selectedSavedTabId }
        else -> 0
    }.coerceAtLeast(0)
    return BrowserTabRestorePlan(
        tabs = tabs.mapIndexed { index, tab ->
            tab.copy(loadImmediately = index == selectedIndex)
        },
        selectedSavedTabId = selectedSavedTabId,
        selectLastTab = launchTab != null
    )
}

private fun SavedBrowserTab.toRestoreSpec(): BrowserTabRestoreSpec? {
    if (id.isBlank() || !isRecoverableBrowserTabUrl(url)) return null
    return BrowserTabRestoreSpec(
        id = id,
        title = title,
        url = url,
        input = input.ifBlank { url },
        iconPath = iconPath,
        loadImmediately = false
    )
}
