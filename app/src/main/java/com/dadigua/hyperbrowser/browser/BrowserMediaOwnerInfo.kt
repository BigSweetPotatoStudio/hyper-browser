package com.dadigua.hyperbrowser.browser

import android.content.Intent

enum class BrowserMediaOwnerKind {
    BrowserTab,
    WebApp,
    ExtensionTab
}

data class BrowserMediaOwnerInfo(
    val id: String,
    val kind: BrowserMediaOwnerKind,
    val displayName: String? = null,
    val url: String? = null,
    val iconPath: String? = null,
    val launchIntent: Intent? = null
)

internal fun BrowserMediaOwnerInfo.mediaOwnerKey(fallbackIdentity: Int): String {
    val ownerId = id.ifBlank { fallbackIdentity.toString() }
    return "$kind:$ownerId"
}
