package com.dadigua.hyperbrowser.ui.browser

import android.content.Intent
import android.webkit.URLUtil

internal data class ExternalBrowserIntent(
    val url: String?,
    val download: Boolean,
    val showDownloads: Boolean = false,
    val selectTabId: String? = null,
    val openInNewTab: Boolean = false
)

internal fun Intent.toExternalBrowserIntent(): ExternalBrowserIntent? {
    getStringExtra(BrowserActivity.EXTRA_SELECT_TAB_ID)?.takeIf { it.isNotBlank() }?.let {
        return ExternalBrowserIntent(url = null, download = false, selectTabId = it)
    }
    if (getBooleanExtra(BrowserActivity.EXTRA_SHOW_DOWNLOADS, false)) {
        return ExternalBrowserIntent(url = null, download = false, showDownloads = true)
    }
    getStringExtra(BrowserActivity.EXTRA_URL)?.takeIf { it.isNotBlank() }?.let {
        return ExternalBrowserIntent(
            url = it,
            download = false,
            openInNewTab = getBooleanExtra(BrowserActivity.EXTRA_OPEN_IN_NEW_TAB, false)
        )
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
        ?.trimSharedTextUrlSuffix()

private val SharedTextUrlTrailingPunctuation = setOf(
    '.', ',', ';', ')', ']', '}', '>', '"', '\'',
    '\u3002', '\uFF0C', '\u3001', '\uFF1B', '\uFF01', '\uFF1F',
    '\uFF09', '\u3011', '\u3009', '\u300B', '\u300D', '\u300F', '\uFF1E',
    '\u2019', '\u201D'
)

private fun String.trimSharedTextUrlSuffix(): String =
    trimEnd { it in SharedTextUrlTrailingPunctuation }
