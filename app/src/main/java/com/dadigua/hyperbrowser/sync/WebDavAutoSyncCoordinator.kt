package com.dadigua.hyperbrowser.sync

import android.util.Log
import com.dadigua.hyperbrowser.browser.BrowserProfileStore
import com.dadigua.hyperbrowser.browser.BrowserSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val WEB_DAV_AUTO_SYNC_DEBOUNCE_MS = 1800L
private const val TAG = "WebDavAutoSync"

class WebDavAutoSyncCoordinator(
    private val profileStore: BrowserProfileStore,
    private val syncManager: WebDavSyncManager,
    private val scope: CoroutineScope,
    private val debounceMs: Long = WEB_DAV_AUTO_SYNC_DEBOUNCE_MS
) {
    private var debounceJob: Job? = null
    private var syncJob: Job? = null
    private var remoteCheckJob: Job? = null
    private var pending = false
    private var remoteCheckPending = false
    private var lastSeenRemoteManifestUpdatedAt = 0L
    private val remoteCheckMutex = Mutex()

    fun markDirty() {
        pending = true
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(debounceMs)
            drainPending()
        }
    }

    fun checkRemoteChanges() {
        if (remoteCheckJob?.isActive == true) {
            remoteCheckPending = true
            return
        }
        remoteCheckJob = scope.launch {
            do {
                remoteCheckPending = false
                runCatching {
                    remoteCheckMutex.withLock {
                        checkRemoteChangesOnce(pageLastSeenRemoteManifestUpdatedAt = 0L)
                    }
                }
                    .onFailure { Log.w(TAG, "Remote sync failed.", it) }
            } while (remoteCheckPending)
        }
    }

    suspend fun checkRemoteChangesForPage(pageLastSeenRemoteManifestUpdatedAt: Long): WebDavRemoteCheckResult =
        remoteCheckMutex.withLock {
            checkRemoteChangesOnce(pageLastSeenRemoteManifestUpdatedAt.coerceAtLeast(0L))
        }

    private fun drainPending() {
        if (syncJob?.isActive == true) return
        syncJob = scope.launch {
            while (pending) {
                pending = false
                val settings = readySettings() ?: return@launch
                runCatching { syncManager.sync(settings) }
                    .onFailure { Log.w(TAG, "Auto sync failed.", it) }
            }
        }
    }

    private suspend fun checkRemoteChangesOnce(pageLastSeenRemoteManifestUpdatedAt: Long): WebDavRemoteCheckResult {
        if (syncJob?.isActive == true) {
            return WebDavRemoteCheckResult(
                changed = false,
                synced = false,
                updatedAt = lastSeenRemoteManifestUpdatedAt
            )
        }
        val settings = readySettings() ?: return WebDavRemoteCheckResult()
        val manifest = runCatching { syncManager.readManifest(settings) }
            .onFailure { Log.w(TAG, "Remote manifest check failed.", it) }
            .getOrNull()
            ?: return WebDavRemoteCheckResult()
        val pageNeedsRefresh = manifest.updatedAt > pageLastSeenRemoteManifestUpdatedAt &&
            manifest.lastWriter != settings.webDavSyncDeviceId
        if (manifest.lastWriter == settings.webDavSyncDeviceId) {
            lastSeenRemoteManifestUpdatedAt = manifest.updatedAt
            return WebDavRemoteCheckResult(
                changed = false,
                synced = false,
                updatedAt = manifest.updatedAt
            )
        }
        if (manifest.updatedAt <= lastSeenRemoteManifestUpdatedAt) {
            return WebDavRemoteCheckResult(
                changed = pageNeedsRefresh,
                synced = false,
                updatedAt = manifest.updatedAt
            )
        }
        return runCatching { syncManager.sync(settings) }
            .map { syncResult ->
                lastSeenRemoteManifestUpdatedAt = manifest.updatedAt
                WebDavRemoteCheckResult(
                    changed = true,
                    synced = true,
                    updatedAt = manifest.updatedAt,
                    syncResult = syncResult
                )
            }
            .onFailure { Log.w(TAG, "Remote sync failed.", it) }
            .getOrThrow()
    }

    private fun readySettings(): BrowserSettings? =
        profileStore.observeSettings().value.let { settings ->
            if (!settings.webDavSyncEnabled || settings.webDavSyncUrl.isBlank()) {
                null
            } else if (settings.webDavSyncDeviceId.isBlank()) {
                profileStore.updateWebDavSyncSettings(
                    enabled = settings.webDavSyncEnabled,
                    url = settings.webDavSyncUrl,
                    username = settings.webDavSyncUsername,
                    password = settings.webDavSyncPassword,
                    deviceName = settings.webDavSyncDeviceName
                )
            } else {
                settings
            }
        }
}

data class WebDavRemoteCheckResult(
    val changed: Boolean = false,
    val synced: Boolean = false,
    val updatedAt: Long = 0L,
    val syncResult: WebDavSyncResult? = null
)
