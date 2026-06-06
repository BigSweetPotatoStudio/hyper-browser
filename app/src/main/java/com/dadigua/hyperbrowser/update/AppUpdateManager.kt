package com.dadigua.hyperbrowser.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
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

    suspend fun downloadAndCreateInstallIntent(
        update: AvailableUpdate,
        onState: suspend (UpdateDownloadState) -> Unit
    ): Intent = withContext(Dispatchers.IO) {
        ensureNotificationChannel()
        emitState(update.state(UpdateDownloadState.STATUS_DOWNLOADING, message = "正在下载更新..."), onState)
        notifyProgress(update, 0L, update.asset.sizeBytes, completed = false)
        val file = downloadApk(update) { bytes, total ->
            val normalizedTotal = total.takeIf { it > 0L } ?: update.asset.sizeBytes
            emitState(
                update.state(
                    status = UpdateDownloadState.STATUS_DOWNLOADING,
                    bytesDownloaded = bytes,
                    totalBytes = normalizedTotal,
                    message = progressMessage(bytes, normalizedTotal)
                ),
                onState
            )
            notifyProgress(update, bytes, normalizedTotal, completed = false)
        }
        emitState(update.state(UpdateDownloadState.STATUS_VERIFYING, totalBytes = file.length(), message = "正在校验安装包..."), onState)
        notifyProgress(update, file.length(), file.length(), completed = true)
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.files", file)
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun skip(versionCode: Long) {
        if (versionCode > 0L) settingsStore.skip(versionCode)
    }

    fun clearSkip() {
        settingsStore.clearSkip()
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

    fun notifyUpdateError(update: AvailableUpdate, error: String) {
        if (!canPostNotifications()) return
        ensureNotificationChannel()
        notifications.notify(
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

    private suspend fun downloadApk(update: AvailableUpdate, onProgress: suspend (Long, Long) -> Unit): File {
        val directory = File(
            appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "updates"
        )
        if (!directory.exists()) directory.mkdirs()
        val file = File(directory, "hyper-browser-${update.versionName}-${update.asset.abi}.apk")
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
                            onProgress(bytesCopied, totalBytes)
                            lastProgressAt = now
                        }
                        read = input.read(buffer)
                    }
                    onProgress(bytesCopied, totalBytes)
                }
            }
        }
        val expectedSha = update.asset.sha256.trim().lowercase()
        if (expectedSha.isNotBlank() && sha256(file) != expectedSha) {
            file.delete()
            error("APK 校验失败。")
        }
        return file
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                appContext,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = android.app.NotificationChannel(
            UPDATE_CHANNEL_ID,
            "App updates",
            android.app.NotificationManager.IMPORTANCE_LOW
        )
        appContext.getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notifyProgress(update: AvailableUpdate, bytes: Long, total: Long, completed: Boolean) {
        if (!canPostNotifications()) return
        val builder = NotificationCompat.Builder(appContext, UPDATE_CHANNEL_ID)
            .setSmallIcon(if (completed) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_sys_download)
            .setContentTitle(if (completed) "更新下载完成" else "正在下载更新")
            .setContentText(update.versionName)
            .setOngoing(!completed)
            .setOnlyAlertOnce(true)
            .setAutoCancel(completed)

        if (!completed) {
            if (total > 0L) {
                val percent = ((bytes * 100) / total).toInt().coerceIn(0, 100)
                builder.setProgress(100, percent, false).setSubText("$percent%")
            } else {
                builder.setProgress(0, 0, true)
            }
        }
        notifications.notify(UPDATE_NOTIFICATION_ID, builder.build())
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

    private suspend fun emitState(
        state: UpdateDownloadState,
        onState: suspend (UpdateDownloadState) -> Unit
    ) {
        withContext(Dispatchers.Main.immediate) {
            onState(state)
        }
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
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        return code to (info.versionName ?: code.toString())
    }

    companion object {
        const val UPDATE_INDEX_URL =
            "https://raw.githubusercontent.com/BigSweetPotatoStudio/hyper-browser/main/update/stable.json"
        private const val UPDATE_CHANNEL_ID = "app_updates"
        private const val UPDATE_NOTIFICATION_ID = 4101
    }
}
