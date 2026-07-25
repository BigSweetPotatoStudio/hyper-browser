package com.dadigua.hyperbrowser.browser

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.IntentCompat
import com.dadigua.hyperbrowser.notification.notifyIfAllowed
import com.dadigua.hyperbrowser.ui.browser.BrowserActivity
import org.mozilla.geckoview.WebNotification
import org.mozilla.geckoview.WebNotificationDelegate
import kotlin.math.abs

class BrowserWebNotificationController(context: Context) : WebNotificationDelegate {
    private val appContext = context.applicationContext
    private val notifications = NotificationManagerCompat.from(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        ensureChannel()
    }

    override fun onShowNotification(notification: WebNotification) {
        mainHandler.post { showNotification(notification) }
    }

    private fun showNotification(notification: WebNotification) {
        val id = notification.androidNotificationId()
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(notification.title.orEmpty().ifBlank { notification.source.orEmpty() })
            .setContentText(notification.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notification.text))
            .setContentIntent(
                PendingIntent.getActivity(
                    appContext,
                    id,
                    BrowserActivity.webNotificationIntent(appContext, notification, null),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setDeleteIntent(
                PendingIntent.getBroadcast(
                    appContext,
                    id,
                    WebNotificationDismissReceiver.intent(appContext, notification),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setAutoCancel(true)
            .setOnlyAlertOnce(notification.silent)
            .setSilent(notification.silent)

        notification.actions.take(3).forEachIndexed { index, action ->
            builder.addAction(
                0,
                action.title,
                PendingIntent.getActivity(
                    appContext,
                    id * 31 + index + 1,
                    BrowserActivity.webNotificationIntent(appContext, notification, action.name),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }

        if (notifications.notifyIfAllowed(appContext, id, builder.build())) {
            notification.show()
        } else {
            notification.dismiss()
        }
    }

    override fun onCloseNotification(notification: WebNotification) {
        notifications.cancel(notification.androidNotificationId())
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Web notifications",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        appContext.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "web_notifications"
    }
}

class WebNotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        IntentCompat.getParcelableExtra(
            intent,
            EXTRA_NOTIFICATION,
            WebNotification::class.java
        )?.dismiss()
    }

    companion object {
        private const val EXTRA_NOTIFICATION = "web_notification"

        fun intent(context: Context, notification: WebNotification): Intent =
            Intent(context, WebNotificationDismissReceiver::class.java)
                .putExtra(EXTRA_NOTIFICATION, notification)
    }
}

private fun WebNotification.androidNotificationId(): Int {
    val key = if (tag.isNotBlank()) {
        "$origin\u0000$tag"
    } else {
        "$origin\u0000$title\u0000$text"
    }
    return key.hashCode().let { if (it == Int.MIN_VALUE) 1 else abs(it) }
}
