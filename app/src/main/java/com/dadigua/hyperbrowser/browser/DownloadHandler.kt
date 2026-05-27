package com.dadigua.hyperbrowser.browser

import android.Manifest
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.URLUtil
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.dadigua.hyperbrowser.gecko.GeckoDownloadRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

class DownloadHandler(context: Context, private val store: DownloadStore) {
    private val appContext = context.applicationContext
    private val downloadManager = appContext.getSystemService(DownloadManager::class.java)
    private val notifications = NotificationManagerCompat.from(appContext)

    suspend fun enqueueUrlDownload(url: String): BrowserDownloadEntry = withContext(Dispatchers.IO) {
        val fileName = safeFileName(URLUtil.guessFileName(url, null, null))
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(fileName)
            .setDescription(url)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val id = downloadManager.enqueue(request)
        store.create(
            name = fileName,
            sourceUrl = url,
            downloadManagerId = id,
            status = DownloadStatus.Queued
        )
    }

    suspend fun refreshSystemDownloads() = withContext(Dispatchers.IO) {
        store.observeDownloads().value
            .filter { it.downloadManagerId != null && it.status != DownloadStatus.Completed && it.status != DownloadStatus.Failed }
            .forEach { entry ->
                val updated = queryDownloadManager(entry)
                if (updated != null) {
                    store.replaceFromSystem(updated)
                }
            }
    }

    suspend fun saveResponse(request: GeckoDownloadRequest, showNotification: Boolean): BrowserDownloadEntry =
        withContext(Dispatchers.IO) {
            ensureNotificationChannel()
            val fileName = safeFileName(request.fileName)
            val totalBytes = request.contentLength
            val entry = store.create(
                name = fileName,
                sourceUrl = request.url,
                status = DownloadStatus.Running,
                totalBytes = totalBytes
            )
            val notificationId = entry.notificationId()
            if (showNotification) {
                notifyProgress(notificationId, fileName, 0L, totalBytes, completed = false)
            }

            var bytesCopied = 0L
            var savedUri: Uri? = null
            runCatching {
                request.body.use { input ->
                    val output = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val resolver = appContext.contentResolver
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                            put(MediaStore.Downloads.MIME_TYPE, request.contentType ?: "application/octet-stream")
                            put(MediaStore.Downloads.IS_PENDING, 1)
                            if (totalBytes > 0L) {
                                put(MediaStore.Downloads.SIZE, totalBytes)
                            }
                            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        }
                        savedUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: error("Unable to create Downloads item.")
                        resolver.openOutputStream(savedUri!!)
                            ?: error("Unable to open Downloads output stream.")
                    } else {
                        val directory = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                            ?: error("Downloads directory unavailable.")
                        if (!directory.exists()) {
                            directory.mkdirs()
                        }
                        val file = File(directory, fileName)
                        savedUri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.files", file)
                        file.outputStream()
                    }

                    output.use { target ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var read = input.read(buffer)
                        var lastProgressAt = 0L
                        while (read >= 0) {
                            target.write(buffer, 0, read)
                            bytesCopied += read
                            val now = System.currentTimeMillis()
                            if (now - lastProgressAt > 250L) {
                                store.updateProgress(entry.id, bytesCopied, totalBytes)
                                if (showNotification) {
                                    notifyProgress(notificationId, fileName, bytesCopied, totalBytes, completed = false)
                                }
                                lastProgressAt = now
                            }
                            read = input.read(buffer)
                        }
                    }

                    val finishedUri = savedUri
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && finishedUri != null) {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.IS_PENDING, 0)
                        }
                        appContext.contentResolver.update(finishedUri, values, null, null)
                    }
                }
                store.markCompleted(entry.id, savedUri?.toString(), bytesCopied, totalBytes.takeIf { it > 0L } ?: bytesCopied)
                if (showNotification) {
                    notifyProgress(notificationId, fileName, bytesCopied, totalBytes.takeIf { it > 0L } ?: bytesCopied, completed = true)
                }
                entry.copy(
                    status = DownloadStatus.Completed,
                    bytesDownloaded = bytesCopied,
                    totalBytes = totalBytes.takeIf { it > 0L } ?: bytesCopied,
                    contentUri = savedUri?.toString(),
                    completedAt = System.currentTimeMillis()
                )
            }.getOrElse { throwable ->
                savedUri?.let { appContext.contentResolver.delete(it, null, null) }
                val error = throwable.message ?: "Download failed."
                store.markFailed(entry.id, error)
                if (showNotification) {
                    notifyFailed(notificationId, fileName, error)
                }
                throw throwable
            }
        }

    fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun openIntent(entry: BrowserDownloadEntry): Intent? {
        if (entry.status != DownloadStatus.Completed) return null
        val uri = entry.contentUri?.let(Uri::parse)
            ?: entry.downloadManagerId?.let { downloadManager.getUriForDownloadedFile(it) }
            ?: return null
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, appContext.contentResolver.getType(uri) ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    suspend fun delete(entry: BrowserDownloadEntry, deleteFile: Boolean) = withContext(Dispatchers.IO) {
        if (deleteFile) {
            entry.downloadManagerId?.let { runCatching { downloadManager.remove(it) } }
            entry.contentUri?.let { uri ->
                runCatching { appContext.contentResolver.delete(Uri.parse(uri), null, null) }
            }
        }
        store.remove(entry.id)
    }

    private fun queryDownloadManager(entry: BrowserDownloadEntry): BrowserDownloadEntry? {
        val id = entry.downloadManagerId ?: return null
        val cursor = downloadManager.query(DownloadManager.Query().setFilterById(id)) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return entry.copy(status = DownloadStatus.Failed, error = "Download not found.")
            val status = it.intColumn(DownloadManager.COLUMN_STATUS)
            val bytes = it.longColumn(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val total = it.longColumn(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val reason = it.intColumn(DownloadManager.COLUMN_REASON)
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> entry.copy(
                    status = DownloadStatus.Completed,
                    bytesDownloaded = bytes,
                    totalBytes = if (total > 0L) total else bytes,
                    contentUri = entry.contentUri,
                    completedAt = entry.completedAt ?: System.currentTimeMillis(),
                    error = null
                )
                DownloadManager.STATUS_FAILED -> entry.copy(
                    status = DownloadStatus.Failed,
                    bytesDownloaded = bytes,
                    totalBytes = total,
                    completedAt = entry.completedAt ?: System.currentTimeMillis(),
                    error = "DownloadManager error $reason"
                )
                DownloadManager.STATUS_RUNNING -> entry.copy(
                    status = DownloadStatus.Running,
                    bytesDownloaded = bytes,
                    totalBytes = total,
                    error = null
                )
                else -> entry.copy(
                    status = DownloadStatus.Queued,
                    bytesDownloaded = bytes,
                    totalBytes = total,
                    error = null
                )
            }
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            DOWNLOAD_CHANNEL_ID,
            "Downloads",
            NotificationManager.IMPORTANCE_LOW
        )
        appContext.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notifyProgress(id: Int, name: String, bytes: Long, total: Long, completed: Boolean) {
        if (!canPostNotifications()) return
        val builder = NotificationCompat.Builder(appContext, DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (completed) "Download complete" else "Downloading")
            .setContentText(name)
            .setOngoing(!completed)
            .setOnlyAlertOnce(true)
            .setAutoCancel(completed)

        if (!completed) {
            if (total > 0L) {
                builder.setProgress(100, ((bytes * 100) / total).toInt().coerceIn(0, 100), false)
                    .setSubText("${((bytes.toDouble() / total) * 100).roundToInt().coerceIn(0, 100)}%")
            } else {
                builder.setProgress(0, 0, true)
            }
        } else {
            builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setProgress(0, 0, false)
        }
        notifications.notify(id, builder.build())
    }

    private fun notifyFailed(id: Int, name: String, error: String) {
        if (!canPostNotifications()) return
        notifications.notify(
            id,
            NotificationCompat.Builder(appContext, DOWNLOAD_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Download failed")
                .setContentText(name)
                .setStyle(NotificationCompat.BigTextStyle().bigText(error))
                .setAutoCancel(true)
                .build()
        )
    }

    companion object {
        private const val DOWNLOAD_CHANNEL_ID = "downloads"

        fun fileNameFor(url: String, contentDisposition: String?, contentType: String?): String =
            safeFileName(URLUtil.guessFileName(url, contentDisposition, contentType))

        private fun safeFileName(value: String?): String {
            val name = value
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "download"
            return name.replace(Regex("""[\\/:*?"<>|]"""), "_")
                .replace(Regex("""\s+"""), " ")
                .take(160)
                .ifBlank { "download" }
        }
    }
}

private fun BrowserDownloadEntry.notificationId(): Int =
    id.hashCode().let { if (it == Int.MIN_VALUE) 1 else kotlin.math.abs(it) }

private fun android.database.Cursor.intColumn(name: String): Int =
    getInt(getColumnIndexOrThrow(name))

private fun android.database.Cursor.longColumn(name: String): Long =
    getLong(getColumnIndexOrThrow(name))
