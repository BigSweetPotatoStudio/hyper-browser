package com.dadigua.hyperbrowser.browser

import android.os.Handler
import android.os.Looper
import org.mozilla.geckoview.WebExtension
import java.util.concurrent.ConcurrentHashMap

object WebExtensionDownloadRegistry {
    private data class Tracker(
        val download: WebExtension.Download,
        val mime: String?,
        val referrer: String?,
        val startTime: Long
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val trackers = ConcurrentHashMap<String, Tracker>()

    fun register(
        entry: BrowserDownloadEntry,
        download: WebExtension.Download,
        mime: String?,
        referrer: String?
    ): WebExtension.Download.Info {
        val tracker = Tracker(
            download = download,
            mime = mime,
            referrer = referrer,
            startTime = entry.createdAt
        )
        trackers[entry.id] = tracker
        return entry.toExtensionInfo(tracker)
    }

    fun update(entry: BrowserDownloadEntry) {
        val tracker = trackers[entry.id] ?: return
        mainHandler.post {
            tracker.download.update(entry.toExtensionInfo(tracker))
            if (entry.status.isFinished()) {
                trackers.remove(entry.id, tracker)
            }
        }
    }

    private fun BrowserDownloadEntry.toExtensionInfo(
        tracker: Tracker
    ): WebExtension.Download.Info =
        object : WebExtension.Download.Info {
            override fun bytesReceived(): Long = bytesDownloaded
            override fun canResume(): Boolean = false
            override fun endTime(): Long? = completedAt
            override fun error(): Int? =
                when (status) {
                    DownloadStatus.Canceled -> WebExtension.Download.INTERRUPT_REASON_USER_CANCELED
                    DownloadStatus.Failed -> WebExtension.Download.INTERRUPT_REASON_NETWORK_FAILED
                    else -> null
                }

            override fun fileExists(): Boolean =
                status == DownloadStatus.Completed && !contentUri.isNullOrBlank()

            override fun filename(): String = name
            override fun fileSize(): Long = totalBytes.coerceAtLeast(bytesDownloaded).coerceAtLeast(0L)
            override fun mime(): String = tracker.mime.orEmpty()
            override fun paused(): Boolean = false
            override fun referrer(): String = tracker.referrer.orEmpty()
            override fun startTime(): Long = tracker.startTime
            override fun state(): Int =
                when (status) {
                    DownloadStatus.Completed -> WebExtension.Download.STATE_COMPLETE
                    DownloadStatus.Failed,
                    DownloadStatus.Canceled -> WebExtension.Download.STATE_INTERRUPTED
                    else -> WebExtension.Download.STATE_IN_PROGRESS
                }

            override fun totalBytes(): Long = totalBytes.coerceAtLeast(0L)
        }

    private fun DownloadStatus.isFinished(): Boolean =
        this == DownloadStatus.Completed ||
            this == DownloadStatus.Failed ||
            this == DownloadStatus.Canceled
}
