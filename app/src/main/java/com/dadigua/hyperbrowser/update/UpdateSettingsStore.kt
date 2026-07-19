package com.dadigua.hyperbrowser.update

import android.content.Context
import com.dadigua.hyperbrowser.data.AtomicFileWriter
import org.json.JSONObject
import java.io.File

data class UpdateSettings(
    val skippedVersionCode: Long = 0L,
    val lastCheckedAt: Long = 0L
)

class UpdateSettingsStore(context: Context) {
    private val settingsFile = File(context.filesDir, "update_settings.json")

    fun load(): UpdateSettings {
        if (!settingsFile.exists()) return UpdateSettings()
        return runCatching {
            val item = JSONObject(settingsFile.readText())
            UpdateSettings(
                skippedVersionCode = item.optLong("skippedVersionCode", 0L),
                lastCheckedAt = item.optLong("lastCheckedAt", 0L)
            )
        }.getOrDefault(UpdateSettings())
    }

    fun markChecked() {
        val current = load()
        save(current.copy(lastCheckedAt = System.currentTimeMillis()))
    }

    fun skip(versionCode: Long) {
        val current = load()
        save(current.copy(skippedVersionCode = versionCode))
    }

    fun clearSkip() {
        val current = load()
        save(current.copy(skippedVersionCode = 0L))
    }

    private fun save(settings: UpdateSettings) {
        AtomicFileWriter.writeText(
            settingsFile,
            JSONObject()
                .put("skippedVersionCode", settings.skippedVersionCode)
                .put("lastCheckedAt", settings.lastCheckedAt)
                .toString(),
        )
    }
}
