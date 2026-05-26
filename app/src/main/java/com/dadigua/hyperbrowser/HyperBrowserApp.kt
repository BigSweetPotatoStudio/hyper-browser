package com.dadigua.hyperbrowser

import android.app.Application
import com.dadigua.hyperbrowser.extensions.ExtensionRepository
import com.dadigua.hyperbrowser.webapp.WebAppRepository

class HyperBrowserApp : Application() {
    val webApps: WebAppRepository by lazy { WebAppRepository(this) }
    val extensions: ExtensionRepository by lazy { ExtensionRepository(this) }
}
