package com.dadigua.hyperbrowser.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

@SuppressLint("MissingPermission")
internal fun NotificationManagerCompat.notifyIfAllowed(
    context: Context,
    id: Int,
    notification: Notification,
): Boolean {
    val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    if (!permissionGranted) return false

    return try {
        notify(id, notification)
        true
    } catch (_: SecurityException) {
        false
    }
}
