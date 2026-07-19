package com.dadigua.hyperbrowser.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.dadigua.hyperbrowser.browser.BrowserDownloadEntry
import com.dadigua.hyperbrowser.browser.DownloadStatus
import com.dadigua.hyperbrowser.data.AtomicFileWriter
import com.dadigua.hyperbrowser.notification.notifyIfAllowed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class UpdateAsset(
    val abi: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long = 0L
)

data class AvailableUpdate(
    val versionCode: Long,
    val versionName: String,
    val notes: String,
    val releaseUrl: String,
    val asset: UpdateAsset
)

data class UpdateCheckResult(
    val status: String,
    val currentVersionCode: Long,
    val currentVersionName: String,
    val skippedVersionCode: Long,
    val update: AvailableUpdate? = null,
    val message: String = ""
)

data class UpdateDownloadState(
    val status: String = STATUS_IDLE,
    val versionCode: Long = 0L,
    val versionName: String = "",
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val message: String = ""
) {
    companion object {
        const val STATUS_IDLE = "idle"
        const val STATUS_PREPARING = "preparing"
        const val STATUS_PERMISSION_REQUIRED = "permissionRequired"
        const val STATUS_DOWNLOADING = "downloading"
        const val STATUS_VERIFYING = "verifying"
        const val STATUS_READY = "ready"
        const val STATUS_ERROR = "error"

        fun idle() = UpdateDownloadState()
    }
}

class AppUpdateManager(context: Context, private val settingsStore: UpdateSettingsStore) {
    private val appContext = context.applicationContext
    private val client = OkHttpClient()
    private val notifications = NotificationManagerCompat.from(appContext)
    private val downloadStateFile = File(appContext.filesDir, "app_update_download.json")

    suspend fun check(ignoreSkipped: Boolean): UpdateCheckResult = withContext(Dispatchers.IO) {
        val current = currentVersion()
        val settings = settingsStore.load()
        runCatching {
            val root = fetchJson(UPDATE_INDEX_URL)
            settingsStore.markChecked()
            val remoteVersionCode = root.optLong("versionCode", 0L)
            val remoteVersionName = root.optString("versionName", remoteVersionCode.toString())
            if (remoteVersionCode <= current.first) {
                return@withContext UpdateCheckResult(
                    status = "upToDate",
                    currentVersionCode = current.first,
                    currentVersionName = current.second,
                    skippedVersionCode = settings.skippedVersionCode,
                    message = "当前已是最新版本。"
                )
            }
            if (root.optInt("minSdk", 0) > Build.VERSION.SDK_INT) {
                return@withContext UpdateCheckResult(
                    status = "unsupported",
                    currentVersionCode = current.first,
                    currentVersionName = current.second,
                    skippedVersionCode = settings.skippedVersionCode,
                    message = "新版本不支持当前 Android 版本。"
                )
            }
            val asset = selectAsset(root.optJSONArray("assets") ?: JSONArray())
                ?: return@withContext UpdateCheckResult(
                    status = "unsupported",
                    currentVersionCode = current.first,
                    currentVersionName = current.second,
                    skippedVersionCode = settings.skippedVersionCode,
                    message = "当前设备架构暂无可用安装包。"
                )
            val update = AvailableUpdate(
                versionCode = remoteVersionCode,
                versionName = remoteVersionName,
                notes = root.optString("notes"),
                releaseUrl = root.optString("releaseUrl"),
                asset = asset
            )
            UpdateCheckResult(
                status = if (!ignoreSkipped && remoteVersionCode == settings.skippedVersionCode) "skipped" else "available",
                currentVersionCode = current.first,
                currentVersionName = current.second,
                skippedVersionCode = settings.skippedVersionCode,
                update = update,
                message = if (remoteVersionCode == settings.skippedVersionCode) "此版本已被跳过。" else "发现新版本。"
            )
        }.getOrElse { throwable ->
            UpdateCheckResult(
                status = "error",
                currentVersionCode = current.first,
                currentVersionName = current.second,
                skippedVersionCode = settings.skippedVersionCode,
                message = throwable.message ?: "检查更新失败。"
            )
        }
    }

    fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || appContext.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${appContext.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    suspend fun startOrResumeDownload(update: AvailableUpdate): UpdateDownloadState = withContext(Dispatchers.IO) {
        readyStateIfValid(update)?.let { return@withContext it }

        val existing = loadDownloadRecord()
        if (existing != null &&
            existing.matches(update) &&
            existing.source == UPDATE_SOURCE_FOREGROUND &&
            existing.status in ACTIVE_STATUSES
        ) {
            ContextCompat.startForegroundService(appContext, AppUpdateDownloadService.intent(appContext, update))
            return@withContext existing.toState()
        }

        deleteUpdateFile(update)
        val state = update.state(
            status = UpdateDownloadState.STATUS_DOWNLOADING,
            totalBytes = update.asset.sizeBytes,
            message = "正在下载更新..."
        )
        saveDownloadRecord(UpdateDownloadRecord.from(update, state, source = UPDATE_SOURCE_FOREGROUND))
        ContextCompat.startForegroundService(appContext, AppUpdateDownloadService.intent(appContext, update))
        state
    }

    suspend fun refreshDownloadState(): UpdateDownloadState = withContext(Dispatchers.IO) {
        val record = loadDownloadRecord() ?: return@withContext UpdateDownloadState.idle()
        record.toState()
    }

    suspend fun createInstallIntentIfReady(update: AvailableUpdate): Intent? = withContext(Dispatchers.IO) {
        val state = readyStateIfValid(update) ?: return@withContext null
        val file = updateFile(update)
        if (state.status != UpdateDownloadState.STATUS_READY || !file.exists()) return@withContext null
        createInstallIntent(file)
    }

    suspend fun createInstallIntentForReadyDownload(): Intent? = withContext(Dispatchers.IO) {
        val update = loadDownloadRecord()?.toAvailableUpdate() ?: return@withContext null
        val state = readyStateIfValid(update) ?: return@withContext null
        val file = updateFile(update)
        if (state.status != UpdateDownloadState.STATUS_READY || !file.exists()) return@withContext null
        createInstallIntent(file)
    }

    suspend fun currentDownloadEntry(): BrowserDownloadEntry? = withContext(Dispatchers.IO) {
        val record = loadDownloadRecord() ?: return@withContext null
        val state = record.toState()
        if (state.status == UpdateDownloadState.STATUS_IDLE) return@withContext null
        BrowserDownloadEntry(
            id = APP_UPDATE_DOWNLOAD_ID,
            name = updateFileName(record.toAvailableUpdate()),
            sourceUrl = record.url,
            contentUri = null,
            downloadManagerId = null,
            status = state.toDownloadStatus(),
            bytesDownloaded = state.bytesDownloaded,
            totalBytes = state.totalBytes,
            createdAt = record.updatedAt,
            completedAt = if (state.status == UpdateDownloadState.STATUS_READY || state.status == UpdateDownloadState.STATUS_ERROR) record.updatedAt else null,
            error = if (state.status == UpdateDownloadState.STATUS_ERROR) state.message else null
        )
    }

    fun clearDownload(deleteFile: Boolean) {
        val record = loadDownloadRecord()
        if (deleteFile) {
            record?.toAvailableUpdate()?.let { deleteUpdateFile(it) }
        }
        runCatching { downloadStateFile.delete() }
    }

    suspend fun downloadUpdatePackage(
        update: AvailableUpdate,
        onState: (UpdateDownloadState) -> Unit
    ): UpdateDownloadState = withContext(Dispatchers.IO) {
        readyStateIfValid(update)?.let {
            onState(it)
            return@withContext it
        }

        val file = updateFile(update)
        deleteUpdateFile(update)
        var state = update.state(
            status = UpdateDownloadState.STATUS_DOWNLOADING,
            totalBytes = update.asset.sizeBytes,
            message = "正在下载更新..."
        )
        saveDownloadRecord(UpdateDownloadRecord.from(update, state, source = UPDATE_SOURCE_FOREGROUND))
        onState(state)

        val request = Request.Builder().url(update.asset.url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("APK 下载失败：HTTP ${response.code}")
            val body = response.body ?: error("APK 下载响应为空。")
            val totalBytes = body.contentLength().takeIf { it > 0L } ?: update.asset.sizeBytes
            file.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var bytesCopied = 0L
                    var lastProgressAt = 0L
                    var read = input.read(buffer)
                    while (read >= 0) {
                        output.write(buffer, 0, read)
                        bytesCopied += read
                        val now = System.currentTimeMillis()
                        if (now - lastProgressAt > 250L) {
                            state = update.state(
                                status = UpdateDownloadState.STATUS_DOWNLOADING,
                                bytesDownloaded = bytesCopied,
                                totalBytes = totalBytes,
                                message = progressMessage(bytesCopied, totalBytes)
                            )
                            saveDownloadRecord(UpdateDownloadRecord.from(update, state, source = UPDATE_SOURCE_FOREGROUND))
                            onState(state)
                            lastProgressAt = now
                        }
                        read = input.read(buffer)
                    }
                    state = update.state(
                        status = UpdateDownloadState.STATUS_DOWNLOADING,
                        bytesDownloaded = bytesCopied,
                        totalBytes = totalBytes,
                        message = progressMessage(bytesCopied, totalBytes)
                    )
                    saveDownloadRecord(UpdateDownloadRecord.from(update, state, source = UPDATE_SOURCE_FOREGROUND))
                    onState(state)
                }
            }
        }

        state = update.state(
            status = UpdateDownloadState.STATUS_VERIFYING,
            bytesDownloaded = file.length(),
            totalBytes = file.length().takeIf { it > 0L } ?: update.asset.sizeBytes,
            message = "正在校验安装包..."
        )
        saveDownloadRecord(UpdateDownloadRecord.from(update, state, source = UPDATE_SOURCE_FOREGROUND))
        onState(state)

        verifyDownloadedFile(update).also(onState)
    }

    fun markDownloadError(update: AvailableUpdate, error: String): UpdateDownloadState {
        deleteUpdateFile(update)
        return update.state(
            status = UpdateDownloadState.STATUS_ERROR,
            totalBytes = update.asset.sizeBytes,
            message = error
        ).also { state ->
            saveDownloadRecord(UpdateDownloadRecord.from(update, state, source = UPDATE_SOURCE_FOREGROUND))
        }
    }

    fun skip(versionCode: Long) {
        if (versionCode > 0L) settingsStore.skip(versionCode)
    }

    fun clearSkip() {
        settingsStore.clearSkip()
    }

    fun notifyUpdateError(update: AvailableUpdate, error: String) {
        ensureNotificationChannel()
        notifications.notifyIfAllowed(
            appContext,
            UPDATE_NOTIFICATION_ID,
            NotificationCompat.Builder(appContext, UPDATE_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("更新下载失败")
                .setContentText(update.versionName)
                .setStyle(NotificationCompat.BigTextStyle().bigText(error))
                .setAutoCancel(true)
                .build()
        )
    }

    private fun fetchJson(url: String): JSONObject {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("更新索引请求失败：HTTP ${response.code}")
            return JSONObject(response.body?.string().orEmpty())
        }
    }

    private fun selectAsset(array: JSONArray): UpdateAsset? {
        val assets = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val abi = item.optString("abi")
                val url = item.optString("url")
                if (abi.isBlank() || url.isBlank()) continue
                add(
                    UpdateAsset(
                        abi = abi,
                        url = url,
                        sha256 = item.optString("sha256"),
                        sizeBytes = item.optLong("sizeBytes", 0L)
                    )
                )
            }
        }
        return Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi ->
            assets.firstOrNull { it.abi == abi }
        }
    }

    private fun verifyDownloadedFile(update: AvailableUpdate): UpdateDownloadState {
        val file = updateFile(update)
        if (!file.exists()) {
            return markDownloadError(update, "下载完成但安装包不存在。")
        }
        if (update.asset.sizeBytes > 0L && file.length() != update.asset.sizeBytes) {
            return markDownloadError(update, "APK 文件大小不匹配。")
        }
        val expectedSha = update.asset.sha256.trim().lowercase()
        if (expectedSha.isNotBlank() && sha256(file) != expectedSha) {
            return markDownloadError(update, "APK 校验失败。")
        }
        return update.state(
            status = UpdateDownloadState.STATUS_READY,
            bytesDownloaded = file.length(),
            totalBytes = file.length(),
            message = "下载完成，点击安装。"
        ).also { state ->
            saveDownloadRecord(UpdateDownloadRecord.from(update, state, source = UPDATE_SOURCE_FOREGROUND))
        }
    }

    private fun readyStateIfValid(update: AvailableUpdate): UpdateDownloadState? {
        val record = loadDownloadRecord()
        if (record != null && !record.matches(update)) return null
        val file = updateFile(update)
        if (!file.exists()) return null
        if (update.asset.sizeBytes > 0L && file.length() != update.asset.sizeBytes) return null
        val expectedSha = update.asset.sha256.trim().lowercase()
        if (expectedSha.isNotBlank() && sha256(file) != expectedSha) return null
        return update.state(
            status = UpdateDownloadState.STATUS_READY,
            bytesDownloaded = file.length(),
            totalBytes = file.length(),
            message = "下载完成，点击安装。"
        ).also { state ->
            saveDownloadRecord(UpdateDownloadRecord.from(update, state, source = UPDATE_SOURCE_FOREGROUND))
        }
    }

    private fun updateDirectory(): File =
        File(
            appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "updates"
        )

    private fun updateFileName(update: AvailableUpdate): String =
        "hyper-browser-${update.versionName}-${update.asset.abi}.apk"

    private fun updateFile(update: AvailableUpdate): File {
        val directory = updateDirectory()
        if (!directory.exists()) directory.mkdirs()
        return File(directory, updateFileName(update))
    }

    private fun deleteUpdateFile(update: AvailableUpdate) {
        runCatching { updateFile(update).delete() }
    }

    private fun createInstallIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.files", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = android.app.NotificationChannel(
            UPDATE_CHANNEL_ID,
            "App updates",
            android.app.NotificationManager.IMPORTANCE_LOW
        )
        appContext.getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun AvailableUpdate.state(
        status: String,
        bytesDownloaded: Long = 0L,
        totalBytes: Long = asset.sizeBytes,
        message: String
    ): UpdateDownloadState =
        UpdateDownloadState(
            status = status,
            versionCode = versionCode,
            versionName = versionName,
            bytesDownloaded = bytesDownloaded,
            totalBytes = totalBytes,
            message = message
        )

    private fun progressMessage(bytes: Long, total: Long): String {
        if (total <= 0L) return "正在下载更新..."
        val percent = ((bytes * 100) / total).coerceIn(0, 100)
        return "正在下载更新... $percent%"
    }

    private fun loadDownloadRecord(): UpdateDownloadRecord? {
        if (!downloadStateFile.exists()) return null
        return runCatching {
            UpdateDownloadRecord.fromJson(JSONObject(downloadStateFile.readText()))
        }.getOrNull()
    }

    private fun saveDownloadRecord(record: UpdateDownloadRecord) {
        AtomicFileWriter.writeText(downloadStateFile, record.toJson().toString())
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var read = input.read(buffer)
            while (read >= 0) {
                digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun currentVersion(): Pair<Long, String> {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return code to (info.versionName ?: code.toString())
    }

    companion object {
        const val UPDATE_INDEX_URL =
            "https://raw.githubusercontent.com/BigSweetPotatoStudio/hyper-browser/main/update/stable.json"
        const val UPDATE_CHANNEL_ID = "app_updates"
        const val UPDATE_NOTIFICATION_ID = 4101
        private const val UPDATE_SOURCE_FOREGROUND = "foreground"
        const val APP_UPDATE_DOWNLOAD_ID = "app-update-download"

        private val ACTIVE_STATUSES = setOf(
            UpdateDownloadState.STATUS_DOWNLOADING,
            UpdateDownloadState.STATUS_VERIFYING
        )
    }
}

private fun UpdateDownloadState.toDownloadStatus(): DownloadStatus =
    when (status) {
        UpdateDownloadState.STATUS_READY -> DownloadStatus.Completed
        UpdateDownloadState.STATUS_ERROR -> DownloadStatus.Failed
        UpdateDownloadState.STATUS_PREPARING,
        UpdateDownloadState.STATUS_DOWNLOADING,
        UpdateDownloadState.STATUS_VERIFYING -> DownloadStatus.Running
        else -> DownloadStatus.Queued
    }

private data class UpdateDownloadRecord(
    val versionCode: Long,
    val versionName: String,
    val notes: String,
    val releaseUrl: String,
    val abi: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val status: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val message: String,
    val source: String,
    val updatedAt: Long
) {
    fun matches(update: AvailableUpdate): Boolean =
        versionCode == update.versionCode &&
            abi == update.asset.abi &&
            url == update.asset.url &&
            sha256 == update.asset.sha256 &&
            sizeBytes == update.asset.sizeBytes

    fun toAvailableUpdate(): AvailableUpdate =
        AvailableUpdate(
            versionCode = versionCode,
            versionName = versionName,
            notes = notes,
            releaseUrl = releaseUrl,
            asset = UpdateAsset(
                abi = abi,
                url = url,
                sha256 = sha256,
                sizeBytes = sizeBytes
            )
        )

    fun toState(): UpdateDownloadState =
        UpdateDownloadState(
            status = status,
            versionCode = versionCode,
            versionName = versionName,
            bytesDownloaded = bytesDownloaded,
            totalBytes = totalBytes,
            message = message
        )

    fun toJson(): JSONObject =
        JSONObject()
            .put("versionCode", versionCode)
            .put("versionName", versionName)
            .put("notes", notes)
            .put("releaseUrl", releaseUrl)
            .put("abi", abi)
            .put("url", url)
            .put("sha256", sha256)
            .put("sizeBytes", sizeBytes)
            .put("status", status)
            .put("bytesDownloaded", bytesDownloaded)
            .put("totalBytes", totalBytes)
            .put("message", message)
            .put("source", source)
            .put("updatedAt", updatedAt)

    companion object {
        fun from(update: AvailableUpdate, state: UpdateDownloadState, source: String): UpdateDownloadRecord =
            UpdateDownloadRecord(
                versionCode = update.versionCode,
                versionName = update.versionName,
                notes = update.notes,
                releaseUrl = update.releaseUrl,
                abi = update.asset.abi,
                url = update.asset.url,
                sha256 = update.asset.sha256,
                sizeBytes = update.asset.sizeBytes,
                status = state.status,
                bytesDownloaded = state.bytesDownloaded,
                totalBytes = state.totalBytes,
                message = state.message,
                source = source,
                updatedAt = System.currentTimeMillis()
            )

        fun fromJson(json: JSONObject): UpdateDownloadRecord =
            UpdateDownloadRecord(
                versionCode = json.optLong("versionCode", 0L),
                versionName = json.optString("versionName"),
                notes = json.optString("notes"),
                releaseUrl = json.optString("releaseUrl"),
                abi = json.optString("abi"),
                url = json.optString("url"),
                sha256 = json.optString("sha256"),
                sizeBytes = json.optLong("sizeBytes", 0L),
                status = json.optString("status", UpdateDownloadState.STATUS_IDLE),
                bytesDownloaded = json.optLong("bytesDownloaded", 0L),
                totalBytes = json.optLong("totalBytes", json.optLong("sizeBytes", 0L)),
                message = json.optString("message"),
                source = json.optString("source"),
                updatedAt = json.optLong("updatedAt", 0L)
            )
    }
}
