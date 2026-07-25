package com.dadigua.hyperbrowser

import android.app.Application
import com.dadigua.hyperbrowser.browser.BrowserProfileStore
import com.dadigua.hyperbrowser.browser.DownloadStore
import com.dadigua.hyperbrowser.extensions.ExtensionRepository
import com.dadigua.hyperbrowser.webapp.WebAppRepository

class HyperBrowserApp : Application() {
    val profileStore: BrowserProfileStore by lazy { BrowserProfileStore(this) }
    val downloads: DownloadStore by lazy { DownloadStore(this) }
    val webApps: WebAppRepository by lazy { WebAppRepository(this) }
    val extensions: ExtensionRepository by lazy { ExtensionRepository(this) }
}
