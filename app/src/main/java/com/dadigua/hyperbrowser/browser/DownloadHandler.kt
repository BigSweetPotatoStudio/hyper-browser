package com.dadigua.hyperbrowser.browser

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.URLUtil
import androidx.core.content.ContextCompat
import com.dadigua.hyperbrowser.gecko.GeckoDownloadRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DownloadHandler(context: Context, private val store: DownloadStore) {
    private val appContext = context.applicationContext

    suspend fun enqueueUrlDownload(url: String): BrowserDownloadEntry = withContext(Dispatchers.IO) {
        val fileName = safeFileName(URLUtil.guessFileName(url, null, null))
        val entry = store.create(
            name = fileName,
            sourceUrl = url,
            status = DownloadStatus.Queued
        )
        ContextCompat.startForegroundService(appContext, BrowserDownloadService.urlIntent(appContext, entry.id))
        entry
    }

    suspend fun refreshSystemDownloads() = withContext(Dispatchers.IO) {
        store.refreshFromDisk()
    }

    suspend fun saveResponse(request: GeckoDownloadRequest, showNotification: Boolean): BrowserDownloadEntry =
        withContext(Dispatchers.IO) {
            val entry = store.create(
                name = safeFileName(request.fileName),
                sourceUrl = request.url,
                status = DownloadStatus.Queued,
                totalBytes = request.contentLength
            )
            BrowserDownloadQueue.putGeckoRequest(entry.id, request)
            ContextCompat.startForegroundService(appContext, BrowserDownloadService.geckoIntent(appContext, entry.id))
            entry
        }

    fun canPostNotifications(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun openIntent(entry: BrowserDownloadEntry): Intent? {
        if (entry.status != DownloadStatus.Completed) return null
        val uri = entry.contentUri?.let(Uri::parse) ?: return null
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, appContext.contentResolver.getType(uri) ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    suspend fun delete(entry: BrowserDownloadEntry, deleteFile: Boolean) = withContext(Dispatchers.IO) {
        if (deleteFile) {
            entry.contentUri?.let { uri ->
                runCatching { appContext.contentResolver.delete(Uri.parse(uri), null, null) }
            }
        }
        store.remove(entry.id)
    }

    companion object {
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
