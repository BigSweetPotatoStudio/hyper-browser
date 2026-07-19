package com.dadigua.hyperbrowser.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dadigua.hyperbrowser.notification.notifyIfAllowed
import com.dadigua.hyperbrowser.ui.browser.BrowserActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AppUpdateDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var downloadJob: Job? = null
    private lateinit var updateManager: AppUpdateManager
    private lateinit var notifications: NotificationManagerCompat

    override fun onCreate() {
        super.onCreate()
        updateManager = AppUpdateManager(applicationContext, UpdateSettingsStore(applicationContext))
        notifications = NotificationManagerCompat.from(this)
        ensureNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val update = intent?.toAvailableUpdate()
        if (update == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (downloadJob?.isActive == true) {
            return START_REDELIVER_INTENT
        }

        val initialState = UpdateDownloadState(
            status = UpdateDownloadState.STATUS_DOWNLOADING,
            versionCode = update.versionCode,
            versionName = update.versionName,
            totalBytes = update.asset.sizeBytes,
            message = "正在下载更新..."
        )
        startForeground(AppUpdateManager.UPDATE_NOTIFICATION_ID, buildNotification(update, initialState))

        downloadJob = scope.launch(Dispatchers.IO) {
            val finalState = runCatching {
                updateManager.downloadUpdatePackage(update) { state ->
                    if (isActive(state)) {
                        publish(update, state)
                    }
                }
            }.getOrElse { throwable ->
                updateManager.markDownloadError(update, throwable.message ?: "更新下载失败。")
            }
            finishForegroundNotification(update, finalState)
            stopSelf(startId)
        }

        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun publish(update: AvailableUpdate, state: UpdateDownloadState) {
        runCatching {
            val notification = buildNotification(update, state)
            if (isActive(state)) {
                startForeground(AppUpdateManager.UPDATE_NOTIFICATION_ID, notification)
            } else {
                notifications.notifyIfAllowed(this, AppUpdateManager.UPDATE_NOTIFICATION_ID, notification)
            }
        }
    }

    private fun buildNotification(update: AvailableUpdate, state: UpdateDownloadState): Notification {
        val isActive = isActive(state)
        val title = when (state.status) {
            UpdateDownloadState.STATUS_READY -> "更新已下载"
            UpdateDownloadState.STATUS_ERROR -> "更新下载失败"
            UpdateDownloadState.STATUS_VERIFYING -> "正在校验 Hyper Browser ${update.versionName}"
            else -> "正在下载 Hyper Browser ${update.versionName}"
        }
        val text = when (state.status) {
            UpdateDownloadState.STATUS_READY -> "点击打开下载内容安装 ${update.versionName}"
            UpdateDownloadState.STATUS_ERROR -> state.message.ifBlank { "请返回设置重试。" }
            else -> state.message.ifBlank { "正在下载更新..." }
        }

        val builder = NotificationCompat.Builder(this, AppUpdateManager.UPDATE_CHANNEL_ID)
            .setSmallIcon(
                when (state.status) {
                    UpdateDownloadState.STATUS_READY -> android.R.drawable.stat_sys_download_done
                    UpdateDownloadState.STATUS_ERROR -> android.R.drawable.stat_notify_error
                    else -> android.R.drawable.stat_sys_download
                }
            )
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(downloadsPendingIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(isActive)
            .setAutoCancel(!isActive)

        if (isActive) {
            val total = state.totalBytes
            if (total > 0L) {
                val percent = ((state.bytesDownloaded * 100) / total).toInt().coerceIn(0, 100)
                builder.setProgress(100, percent, false).setSubText("$percent%")
            } else {
                builder.setProgress(0, 0, true)
            }
        }

        return builder.build()
    }

    private fun downloadsPendingIntent(): PendingIntent {
        val intent = BrowserActivity.downloadsIntent(this)
        return PendingIntent.getActivity(
            this,
            4101,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            AppUpdateManager.UPDATE_CHANNEL_ID,
            "App updates",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun finishForegroundNotification(update: AvailableUpdate, state: UpdateDownloadState) {
        removeForegroundNotification()
        runCatching {
            notifications.notifyIfAllowed(
                this,
                AppUpdateManager.UPDATE_NOTIFICATION_ID,
                buildNotification(update, state),
            )
        }
    }

    private fun removeForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun isActive(state: UpdateDownloadState): Boolean =
        state.status == UpdateDownloadState.STATUS_DOWNLOADING ||
            state.status == UpdateDownloadState.STATUS_VERIFYING

    companion object {
        private const val EXTRA_VERSION_CODE = "version_code"
        private const val EXTRA_VERSION_NAME = "version_name"
        private const val EXTRA_NOTES = "notes"
        private const val EXTRA_RELEASE_URL = "release_url"
        private const val EXTRA_ABI = "abi"
        private const val EXTRA_URL = "url"
        private const val EXTRA_SHA256 = "sha256"
        private const val EXTRA_SIZE_BYTES = "size_bytes"

        fun intent(context: Context, update: AvailableUpdate): Intent =
            Intent(context, AppUpdateDownloadService::class.java)
                .putExtra(EXTRA_VERSION_CODE, update.versionCode)
                .putExtra(EXTRA_VERSION_NAME, update.versionName)
                .putExtra(EXTRA_NOTES, update.notes)
                .putExtra(EXTRA_RELEASE_URL, update.releaseUrl)
                .putExtra(EXTRA_ABI, update.asset.abi)
                .putExtra(EXTRA_URL, update.asset.url)
                .putExtra(EXTRA_SHA256, update.asset.sha256)
                .putExtra(EXTRA_SIZE_BYTES, update.asset.sizeBytes)

        private fun Intent.toAvailableUpdate(): AvailableUpdate? {
            val versionCode = getLongExtra(EXTRA_VERSION_CODE, 0L)
            val versionName = getStringExtra(EXTRA_VERSION_NAME).orEmpty()
            val abi = getStringExtra(EXTRA_ABI).orEmpty()
            val url = getStringExtra(EXTRA_URL).orEmpty()
            if (versionCode <= 0L || versionName.isBlank() || abi.isBlank() || url.isBlank()) return null
            return AvailableUpdate(
                versionCode = versionCode,
                versionName = versionName,
                notes = getStringExtra(EXTRA_NOTES).orEmpty(),
                releaseUrl = getStringExtra(EXTRA_RELEASE_URL).orEmpty(),
                asset = UpdateAsset(
                    abi = abi,
                    url = url,
                    sha256 = getStringExtra(EXTRA_SHA256).orEmpty(),
                    sizeBytes = getLongExtra(EXTRA_SIZE_BYTES, 0L)
                )
            )
        }
    }
}
