package com.dadigua.hyperbrowser.browser

import android.content.Context
import com.dadigua.hyperbrowser.gecko.HyperBridge

fun closeBrowserMediaPlaybackOwner(context: Context, ownerInfo: BrowserMediaOwnerInfo) {
    val mediaNotifications = BrowserMediaNotificationController.get(context)
    mediaNotifications.sessionsForOwner(ownerInfo).forEach { session ->
        HyperBridge.unregister(session)
        mediaNotifications.clearIfOwner(session)
        runCatching { session.close() }
    }
    mediaNotifications.clearIfOwner(ownerInfo)
}
