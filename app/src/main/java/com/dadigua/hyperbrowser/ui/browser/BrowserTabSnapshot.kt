package com.dadigua.hyperbrowser.ui.browser

import com.dadigua.hyperbrowser.browser.SavedBrowserTab
import com.dadigua.hyperbrowser.gecko.GeckoPageState

internal fun savedBrowserTabFromState(
    id: String,
    input: String,
    iconPath: String?,
    state: GeckoPageState,
    loaded: Boolean = true,
    restoreUrl: String? = null,
    restoredTitle: String? = null
): SavedBrowserTab? {
    val stateUrl = state.url.takeIf(::isRecoverableBrowserTabUrl)
    val savedRestoreUrl = restoreUrl?.takeIf(::isRecoverableBrowserTabUrl)
    val inputUrl = input.takeIf(::isRecoverableBrowserTabUrl)
    val url = when {
        loaded && stateUrl != null -> stateUrl
        !loaded && savedRestoreUrl != null -> savedRestoreUrl
        !loaded && inputUrl != null -> inputUrl
        stateUrl != null -> stateUrl
        savedRestoreUrl != null -> savedRestoreUrl
        inputUrl != null -> inputUrl
        else -> return null
    }
    if (id.isBlank() || url.isBlank()) return null
    val title = when {
        loaded -> state.title.ifBlank { restoredTitle ?: url }
        else -> restoredTitle ?: state.title.ifBlank { url }
    }
    val savedInput = when {
        loaded && stateUrl != null -> stateUrl
        input.isNotBlank() -> input
        else -> url
    }
    return SavedBrowserTab(
        id = id,
        title = title,
        url = url,
        input = savedInput,
        iconPath = iconPath,
        loaded = loaded
    )
}

internal fun savedBrowserTabFromSnapshot(
    id: String,
    input: String,
    iconPath: String?,
    loaded: Boolean,
    restoreUrl: String,
    title: String?
): SavedBrowserTab? {
    val url = restoreUrl.takeIf(::isRecoverableBrowserTabUrl)
        ?: input.takeIf(::isRecoverableBrowserTabUrl)
        ?: return null
    if (id.isBlank()) return null
    return SavedBrowserTab(
        id = id,
        title = title?.takeIf { it.isNotBlank() } ?: url,
        url = url,
        input = if (loaded) url else input.ifBlank { url },
        iconPath = iconPath,
        loaded = loaded
    )
}

internal fun isRecoverableBrowserTabUrl(url: String): Boolean {
    if (url.isBlank()) return false
    val normalized = url.trim()
    return normalized != "about:blank" &&
        !normalized.startsWith("resource://") &&
        !normalized.startsWith("moz-extension://")
}
