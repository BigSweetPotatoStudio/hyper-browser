package com.dadigua.hyperbrowser.ui.browser

internal fun openerTabForRootBack(
    currentTabId: String,
    openerTabId: String?,
    openTabIds: Collection<String>
): String? {
    val opener = openerTabId?.takeIf { it.isNotBlank() } ?: return null
    return opener.takeIf { it != currentTabId && it in openTabIds }
}
