package com.dadigua.hyperbrowser.ui.browser

import com.dadigua.hyperbrowser.browser.BrowserDownloadEntry
import com.dadigua.hyperbrowser.browser.DownloadStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadSearchTest {
    private val labels = DownloadMetaLabels(
        queued = "Queued",
        downloading = "Downloading",
        complete = "Complete",
        failed = "Failed",
        canceled = "Canceled",
        unknownSize = "Unknown size",
        unknown = "Unknown"
    )

    @Test
    fun blankQueryMatchesEveryDownload() {
        assertTrue(downloadMatchesQuery(download(name = "report.pdf"), "   ", labels))
    }

    @Test
    fun queryMatchesFileNameSourceUrlStatusAndError() {
        val failedDownload = download(
            name = "release-notes.pdf",
            sourceUrl = "https://example.com/releases/latest",
            status = DownloadStatus.Failed,
            error = "Network timeout"
        )

        assertTrue(downloadMatchesQuery(failedDownload, "notes", labels))
        assertTrue(downloadMatchesQuery(failedDownload, "example.com", labels))
        assertTrue(downloadMatchesQuery(failedDownload, "failed", labels))
        assertTrue(downloadMatchesQuery(failedDownload, "timeout", labels))
    }

    @Test
    fun unrelatedQueryDoesNotMatch() {
        val completedDownload = download(
            name = "installer.apk",
            sourceUrl = "https://example.com/app.apk",
            status = DownloadStatus.Completed
        )

        assertFalse(downloadMatchesQuery(completedDownload, "presentation", labels))
    }

    private fun download(
        name: String,
        sourceUrl: String = "https://example.com/download",
        status: DownloadStatus = DownloadStatus.Completed,
        error: String? = null
    ): BrowserDownloadEntry = BrowserDownloadEntry(
        id = "download-1",
        name = name,
        sourceUrl = sourceUrl,
        contentUri = null,
        downloadManagerId = null,
        status = status,
        bytesDownloaded = 1024L,
        totalBytes = 2048L,
        createdAt = 1_700_000_000_000L,
        completedAt = 1_700_000_010_000L,
        error = error
    )
}
