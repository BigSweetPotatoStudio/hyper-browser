package com.dadigua.hyperbrowser.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.dadigua.hyperbrowser.gecko.GeckoDownloadRequest
import com.dadigua.hyperbrowser.ui.browser.BrowserActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

class BrowserDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val client = OkHttpClient()
    private lateinit var store: DownloadStore
    private lateinit var notifications: NotificationManagerCompat
    private var foregroundEntryId: String? = null

    override fun onCreate() {
        super.onCreate()
        store = DownloadStore(applicationContext)
        notifications = NotificationManagerCompat.from(this)
        ensureNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val entryId = intent?.getStringExtra(EXTRA_ENTRY_ID).orEmpty()
        if (entryId.isBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (jobs[entryId]?.isActive == true) {
            return START_REDELIVER_INTENT
        }

        val entry = store.observeDownloads().value.firstOrNull { it.id == entryId }
        if (entry == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val source = intent?.getStringExtra(EXTRA_SOURCE)

        publish(entry)
        jobs[entryId] = scope.launch(Dispatchers.IO) {
            runCatching {
                when (source) {
                    SOURCE_GECKO -> downloadGecko(entry)
                    else -> downloadUrl(entry)
                }
            }.onFailure { throwable ->
                store.markFailed(entry.id, throwable.message ?: "Download failed.")
                store.observeDownloads().value.firstOrNull { it.id == entry.id }?.let { publishFinal(it) }
            }
            jobs.remove(entryId)
            if (foregroundEntryId == entryId) {
                foregroundEntryId = null
            }
            promoteNextForeground()
            if (jobs.isEmpty()) {
                stopSelf(startId)
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun downloadUrl(entry: BrowserDownloadEntry) {
        val request = Request.Builder().url(entry.sourceUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Download failed: HTTP ${response.code}")
            val body = response.body ?: error("Download response is empty.")
            saveStream(
                entry = entry,
                input = body.byteStream(),
                fileName = entry.name,
                contentType = response.header("Content-Type"),
                totalBytes = body.contentLength().takeIf { it > 0L } ?: entry.totalBytes
            )
        }
    }

    private fun downloadGecko(entry: BrowserDownloadEntry) {
        val request = BrowserDownloadQueue.takeGeckoRequest(entry.id)
            ?: error("Download response is no longer available.")
        request.body.use { body ->
            saveStream(
                entry = entry,
                input = body,
                fileName = entry.name,
                contentType = request.contentType,
                totalBytes = request.contentLength
            )
        }
    }

    private fun saveStream(
        entry: BrowserDownloadEntry,
        input: InputStream,
        fileName: String,
        contentType: String?,
        totalBytes: Long
    ) {
        var bytesCopied = 0L
        var savedUri: Uri? = null
        runCatching {
            val target = createTarget(fileName, contentType, totalBytes)
            savedUri = target.uri
            target.output.use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var lastProgressAt = 0L
                var read = input.read(buffer)
                while (read >= 0) {
                    output.write(buffer, 0, read)
                    bytesCopied += read
                    val now = System.currentTimeMillis()
                    if (now - lastProgressAt > 250L) {
                        store.updateProgress(entry.id, bytesCopied, totalBytes)
                        store.observeDownloads().value.firstOrNull { it.id == entry.id }?.let { publish(it) }
                        lastProgressAt = now
                    }
                    read = input.read(buffer)
                }
            }
            target.finish()
            val normalizedTotal = totalBytes.takeIf { it > 0L } ?: bytesCopied
            store.markCompleted(entry.id, savedUri.toString(), bytesCopied, normalizedTotal)
            store.observeDownloads().value.firstOrNull { it.id == entry.id }?.let { publishFinal(it) }
        }.getOrElse { throwable ->
            savedUri?.let { runCatching { contentResolver.delete(it, null, null) } }
            throw throwable
        }
    }

    private fun createTarget(fileName: String, contentType: String?, totalBytes: Long): DownloadTarget {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, contentType ?: "application/octet-stream")
                put(MediaStore.Downloads.IS_PENDING, 1)
                if (totalBytes > 0L) {
                    put(MediaStore.Downloads.SIZE, totalBytes)
                }
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Unable to create Downloads item.")
            val output = contentResolver.openOutputStream(uri)
                ?: error("Unable to open Downloads output stream.")
            return DownloadTarget(
                uri = uri,
                output = output,
                finish = {
                    contentResolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                        null,
                        null
                    )
                }
            )
        }

        val directory = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: error("Downloads directory unavailable.")
        if (!directory.exists()) {
            directory.mkdirs()
        }
        val file = java.io.File(directory, fileName)
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        return DownloadTarget(uri = uri, output = file.outputStream(), finish = {})
    }

    private fun publish(entry: BrowserDownloadEntry) {
        val notification = buildNotification(entry)
        if (foregroundEntryId == null || foregroundEntryId == entry.id) {
            foregroundEntryId = entry.id
            startForeground(entry.notificationId(), notification)
        } else {
            notifications.notify(entry.notificationId(), notification)
        }
    }

    private fun publishFinal(entry: BrowserDownloadEntry) {
        if (foregroundEntryId == entry.id) {
            removeForegroundNotification()
            foregroundEntryId = null
        }
        notifications.notify(entry.notificationId(), buildNotification(entry))
    }

    private fun promoteNextForeground() {
        if (foregroundEntryId != null) return
        val next = store.observeDownloads().value.firstOrNull {
            it.status == DownloadStatus.Running || it.status == DownloadStatus.Queued
        } ?: return
        foregroundEntryId = next.id
        startForeground(next.notificationId(), buildNotification(next))
    }

    private fun buildNotification(entry: BrowserDownloadEntry): Notification {
        val isActive = entry.status == DownloadStatus.Running || entry.status == DownloadStatus.Queued
        val builder = NotificationCompat.Builder(this, DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(
                when (entry.status) {
                    DownloadStatus.Completed -> android.R.drawable.stat_sys_download_done
                    DownloadStatus.Failed -> android.R.drawable.stat_notify_error
                    else -> android.R.drawable.stat_sys_download
                }
            )
            .setContentTitle(
                when (entry.status) {
                    DownloadStatus.Completed -> "Download complete"
                    DownloadStatus.Failed -> "Download failed"
                    else -> "Downloading"
                }
            )
            .setContentText(entry.name)
            .setContentIntent(downloadsPendingIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(isActive)
            .setAutoCancel(!isActive)

        if (isActive) {
            if (entry.totalBytes > 0L) {
                val percent = ((entry.bytesDownloaded.toDouble() / entry.totalBytes) * 100)
                    .roundToInt()
                    .coerceIn(0, 100)
                builder.setProgress(100, percent, false).setSubText("$percent%")
            } else {
                builder.setProgress(0, 0, true)
            }
        }
        if (entry.status == DownloadStatus.Failed && !entry.error.isNullOrBlank()) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(entry.error))
        }
        return builder.build()
    }

    private fun downloadsPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            1201,
            BrowserActivity.downloadsIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            DOWNLOAD_CHANNEL_ID,
            "Downloads",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun removeForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        const val DOWNLOAD_CHANNEL_ID = "downloads"
        private const val EXTRA_ENTRY_ID = "entry_id"
        private const val EXTRA_SOURCE = "source"
        private const val SOURCE_URL = "url"
        private const val SOURCE_GECKO = "gecko"

        fun urlIntent(context: Context, entryId: String): Intent =
            Intent(context, BrowserDownloadService::class.java)
                .putExtra(EXTRA_ENTRY_ID, entryId)
                .putExtra(EXTRA_SOURCE, SOURCE_URL)

        fun geckoIntent(context: Context, entryId: String): Intent =
            Intent(context, BrowserDownloadService::class.java)
                .putExtra(EXTRA_ENTRY_ID, entryId)
                .putExtra(EXTRA_SOURCE, SOURCE_GECKO)
    }
}

private data class DownloadTarget(
    val uri: Uri,
    val output: OutputStream,
    val finish: () -> Unit
)

private fun BrowserDownloadEntry.notificationId(): Int =
    id.hashCode().let { if (it == Int.MIN_VALUE) 1 else kotlin.math.abs(it) }
