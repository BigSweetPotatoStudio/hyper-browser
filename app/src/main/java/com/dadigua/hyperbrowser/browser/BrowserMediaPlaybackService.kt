package com.dadigua.hyperbrowser.browser

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

class BrowserMediaPlaybackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundCompat(removeNotification = false)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val controller = BrowserMediaNotificationController.get(this)
        val notification = controller.foregroundNotification()
        if (notification == null) {
            stopForegroundCompat(removeNotification = false)
            stopSelf()
            return START_NOT_STICKY
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                BrowserMediaNotificationController.MEDIA_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(BrowserMediaNotificationController.MEDIA_NOTIFICATION_ID, notification)
        }
        if (!controller.hasActivePlayback) {
            stopForegroundCompat(removeNotification = false)
            stopSelf()
        }
        return START_STICKY
    }

    private fun stopForegroundCompat(removeNotification: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(removeNotification)
        }
    }

    companion object {
        private const val ACTION_REFRESH = "com.dadigua.hyperbrowser.media.service.REFRESH"
        private const val ACTION_STOP = "com.dadigua.hyperbrowser.media.service.STOP"

        fun refresh(context: Context) {
            val intent = Intent(context.applicationContext, BrowserMediaPlaybackService::class.java)
                .setAction(ACTION_REFRESH)
            runCatching { ContextCompat.startForegroundService(context.applicationContext, intent) }
        }

        fun stop(context: Context) {
            val intent = Intent(context.applicationContext, BrowserMediaPlaybackService::class.java)
                .setAction(ACTION_STOP)
            runCatching { context.applicationContext.startService(intent) }
        }
    }
}
