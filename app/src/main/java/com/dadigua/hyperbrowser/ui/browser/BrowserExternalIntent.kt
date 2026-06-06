package com.dadigua.hyperbrowser.ui.browser

import android.content.Intent
import android.webkit.URLUtil

internal data class ExternalBrowserIntent(
    val url: String?,
    val download: Boolean,
    val showDownloads: Boolean = false
)

internal fun Intent.toExternalBrowserIntent(): ExternalBrowserIntent? {
    if (getBooleanExtra(BrowserActivity.EXTRA_SHOW_DOWNLOADS, false)) {
        return ExternalBrowserIntent(url = null, download = false, showDownloads = true)
    }
    getStringExtra(BrowserActivity.EXTRA_URL)?.takeIf { it.isNotBlank() }?.let {
        return ExternalBrowserIntent(it, download = false)
    }
    if (action == Intent.ACTION_SEND && type?.startsWith("text/") == true) {
        val text = getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        extractFirstHttpUrl(text)?.let {
            return ExternalBrowserIntent(it, download = true)
        }
    }
    if (action == Intent.ACTION_VIEW) {
        dataString?.takeIf { URLUtil.isHttpUrl(it) || URLUtil.isHttpsUrl(it) }?.let {
            return ExternalBrowserIntent(it, download = false)
        }
    }
    return null
}

internal fun extractFirstHttpUrl(text: String): String? =
    Regex("""https?://\S+""")
        .find(text)
        ?.value
        ?.trimEnd('.', ',', ';', ')', ']', '}', '"', '\'')
