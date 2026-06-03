package com.dadigua.hyperbrowser.browser

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BrowserMediaActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        BrowserMediaNotificationController.get(context).handleAction(intent.action)
    }

    companion object {
        const val ACTION_PLAY = "com.dadigua.hyperbrowser.media.PLAY"
        const val ACTION_PAUSE = "com.dadigua.hyperbrowser.media.PAUSE"
        const val ACTION_STOP = "com.dadigua.hyperbrowser.media.STOP"
        const val ACTION_SEEK_FORWARD = "com.dadigua.hyperbrowser.media.SEEK_FORWARD"
        const val ACTION_SEEK_BACKWARD = "com.dadigua.hyperbrowser.media.SEEK_BACKWARD"
        const val ACTION_NEXT = "com.dadigua.hyperbrowser.media.NEXT"
        const val ACTION_PREVIOUS = "com.dadigua.hyperbrowser.media.PREVIOUS"

        fun pendingIntent(context: Context, action: String): PendingIntent =
            PendingIntent.getBroadcast(
                context.applicationContext,
                action.hashCode(),
                Intent(context.applicationContext, BrowserMediaActionReceiver::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}
