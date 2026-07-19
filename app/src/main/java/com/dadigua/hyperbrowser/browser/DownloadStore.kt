package com.dadigua.hyperbrowser.browser

import com.dadigua.hyperbrowser.data.AtomicFileWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class BrowserDownloadEntry(
    val id: String,
    val name: String,
    val sourceUrl: String,
    val contentUri: String?,
    val downloadManagerId: Long?,
    val status: DownloadStatus,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val createdAt: Long,
    val completedAt: Long?,
    val error: String?
)

enum class DownloadStatus {
    Queued,
    Running,
    Completed,
    Failed,
    Canceled
}

class DownloadStore(context: android.content.Context) {
    private val file = File(context.filesDir, "browser_downloads.json")
    private val state = MutableStateFlow(load())

    fun observeDownloads(): StateFlow<List<BrowserDownloadEntry>> = state

    fun refreshFromDisk() {
        state.value = sorted(load())
    }

    fun create(
        name: String,
        sourceUrl: String,
        contentUri: String? = null,
        downloadManagerId: Long? = null,
        status: DownloadStatus = DownloadStatus.Queued,
        totalBytes: Long = -1L
    ): BrowserDownloadEntry {
        val entry = BrowserDownloadEntry(
            id = UUID.randomUUID().toString(),
            name = name,
            sourceUrl = sourceUrl,
            contentUri = contentUri,
            downloadManagerId = downloadManagerId,
            status = status,
            bytesDownloaded = 0L,
            totalBytes = totalBytes,
            createdAt = System.currentTimeMillis(),
            completedAt = null,
            error = null
        )
        update(listOf(entry) + state.value)
        return entry
    }

    fun updateProgress(id: String, bytesDownloaded: Long, totalBytes: Long, status: DownloadStatus = DownloadStatus.Running) {
        updateEntry(id) {
            it.copy(
                status = status,
                bytesDownloaded = bytesDownloaded.coerceAtLeast(0L),
                totalBytes = totalBytes,
                error = null
            )
        }
    }

    fun markCompleted(id: String, contentUri: String?, bytesDownloaded: Long, totalBytes: Long) {
        updateEntry(id) {
            it.copy(
                status = DownloadStatus.Completed,
                contentUri = contentUri ?: it.contentUri,
                bytesDownloaded = bytesDownloaded.coerceAtLeast(0L),
                totalBytes = totalBytes,
                completedAt = System.currentTimeMillis(),
                error = null
            )
        }
    }

    fun markFailed(id: String, error: String) {
        updateEntry(id) {
            it.copy(
                status = DownloadStatus.Failed,
                completedAt = System.currentTimeMillis(),
                error = error
            )
        }
    }

    fun markCanceled(id: String) {
        updateEntry(id) {
            it.copy(
                status = DownloadStatus.Canceled,
                completedAt = System.currentTimeMillis(),
                error = null
            )
        }
    }

    fun prepareRetry(id: String): BrowserDownloadEntry? {
        var retryEntry: BrowserDownloadEntry? = null
        updateEntry(id) {
            it.copy(
                contentUri = null,
                downloadManagerId = null,
                status = DownloadStatus.Queued,
                bytesDownloaded = 0L,
                completedAt = null,
                error = null,
                createdAt = System.currentTimeMillis()
            ).also { updated -> retryEntry = updated }
        }
        return retryEntry
    }

    fun replaceFromSystem(entry: BrowserDownloadEntry) {
        updateEntry(entry.id) { entry }
    }

    fun remove(id: String) {
        update(state.value.filterNot { it.id == id })
    }

    fun clearFinished(): Int {
        val finishedStatuses = setOf(DownloadStatus.Completed, DownloadStatus.Failed, DownloadStatus.Canceled)
        val current = state.value
        val next = current.filterNot { it.status in finishedStatuses }
        if (next.size == current.size) return 0
        update(next)
        return current.size - next.size
    }

    private fun updateEntry(id: String, transform: (BrowserDownloadEntry) -> BrowserDownloadEntry) {
        val next = state.value.map { if (it.id == id) transform(it) else it }
        update(next)
    }

    private fun update(items: List<BrowserDownloadEntry>) {
        state.value = sorted(items)
        save(state.value)
    }

    private fun sorted(items: List<BrowserDownloadEntry>): List<BrowserDownloadEntry> =
        items.sortedByDescending { it.createdAt }.take(200)

    private fun load(): List<BrowserDownloadEntry> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        BrowserDownloadEntry(
                            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                            name = item.optString("name", "download"),
                            sourceUrl = item.optString("sourceUrl"),
                            contentUri = item.optStringOrNull("contentUri"),
                            downloadManagerId = item.optLongOrNull("downloadManagerId"),
                            status = item.optDownloadStatus(),
                            bytesDownloaded = item.optLong("bytesDownloaded", 0L),
                            totalBytes = item.optLong("totalBytes", -1L),
                            createdAt = item.optLong("createdAt", System.currentTimeMillis()),
                            completedAt = item.optLongOrNull("completedAt"),
                            error = item.optStringOrNull("error")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun save(items: List<BrowserDownloadEntry>) {
        val array = JSONArray()
        items.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("sourceUrl", it.sourceUrl)
                    .put("contentUri", it.contentUri)
                    .put("downloadManagerId", it.downloadManagerId)
                    .put("status", it.status.name)
                    .put("bytesDownloaded", it.bytesDownloaded)
                    .put("totalBytes", it.totalBytes)
                    .put("createdAt", it.createdAt)
                    .put("completedAt", it.completedAt)
                    .put("error", it.error)
            )
        }
        AtomicFileWriter.writeText(file, array.toString())
    }
}

private fun JSONObject.optLongOrNull(name: String): Long? {
    if (!has(name) || isNull(name)) return null
    val value = optLong(name, Long.MIN_VALUE)
    return value.takeIf { it != Long.MIN_VALUE }
}

private fun JSONObject.optStringOrNull(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name)
        .trim()
        .takeUnless { it.isBlank() || it == "null" }
}

private fun JSONObject.optDownloadStatus(): DownloadStatus =
    runCatching { DownloadStatus.valueOf(optString("status")) }.getOrDefault(DownloadStatus.Queued)
