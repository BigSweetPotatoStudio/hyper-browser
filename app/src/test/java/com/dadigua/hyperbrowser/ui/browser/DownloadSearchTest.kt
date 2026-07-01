package com.dadigua.hyperbrowser.ui.browser

import com.dadigua.hyperbrowser.browser.BrowserDownloadEntry
import com.dadigua.hyperbrowser.browser.DownloadStatus
import org.junit.Assert.assertEquals
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

    @Test
    fun completedDownloadWithSavedUriCanOfferFileDelete() {
        assertTrue(
            downloadHasSavedFile(
                download(
                    name = "installer.apk",
                    status = DownloadStatus.Completed,
                    contentUri = "content://downloads/my_downloads/42"
                )
            )
        )
    }

    @Test
    fun statusFilterGroupsActiveCompletedAndFailedOrCanceledDownloads() {
        val downloads = listOf(
            download(id = "queued", name = "queued.apk", status = DownloadStatus.Queued),
            download(id = "running", name = "running.apk", status = DownloadStatus.Running),
            download(id = "complete", name = "complete.apk", status = DownloadStatus.Completed),
            download(id = "failed", name = "failed.apk", status = DownloadStatus.Failed),
            download(id = "canceled", name = "canceled.apk", status = DownloadStatus.Canceled)
        )

        assertEquals(
            listOf("queued", "running"),
            filterDownloads(downloads, "", DownloadStatusFilter.Active, labels).map { it.id }
        )
        assertEquals(
            listOf("complete"),
            filterDownloads(downloads, "", DownloadStatusFilter.Completed, labels).map { it.id }
        )
        assertEquals(
            listOf("failed", "canceled"),
            filterDownloads(downloads, "", DownloadStatusFilter.FailedOrCanceled, labels).map { it.id }
        )
    }

    @Test
    fun unfinishedOrMissingUriDownloadsDoNotOfferFileDelete() {
        assertFalse(
            downloadHasSavedFile(
                download(
                    name = "failed.apk",
                    status = DownloadStatus.Failed,
                    contentUri = "content://downloads/my_downloads/42"
                )
            )
        )
        assertFalse(
            downloadHasSavedFile(
                download(
                    name = "canceled.apk",
                    status = DownloadStatus.Canceled,
                    contentUri = "content://downloads/my_downloads/42"
                )
            )
        )
        assertFalse(
            downloadHasSavedFile(
                download(
                    name = "missing-uri.apk",
                    status = DownloadStatus.Completed,
                    contentUri = null
                )
            )
        )
    }

    @Test
    fun statusFilterCombinesWithTextQuery() {
        val downloads = listOf(
            download(id = "failed-notes", name = "release-notes.pdf", status = DownloadStatus.Failed),
            download(id = "failed-image", name = "image.png", status = DownloadStatus.Failed),
            download(id = "complete-notes", name = "release-notes.pdf", status = DownloadStatus.Completed)
        )

        assertEquals(
            listOf("failed-notes"),
            filterDownloads(downloads, "notes", DownloadStatusFilter.FailedOrCanceled, labels).map { it.id }
        )
    }

    private fun download(
        id: String = "download-1",
        name: String,
        sourceUrl: String = "https://example.com/download",
        status: DownloadStatus = DownloadStatus.Completed,
        error: String? = null,
        contentUri: String? = null
    ): BrowserDownloadEntry = BrowserDownloadEntry(
        id = id,
        name = name,
        sourceUrl = sourceUrl,
        contentUri = contentUri,
        downloadManagerId = null,
        status = status,
        bytesDownloaded = 1024L,
        totalBytes = 2048L,
        createdAt = 1_700_000_000_000L,
        completedAt = 1_700_000_010_000L,
        error = error
    )
}
