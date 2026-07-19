package com.dadigua.hyperbrowser.browser

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.dadigua.hyperbrowser.R
import com.dadigua.hyperbrowser.notification.notifyIfAllowed
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.MediaSession
import org.mozilla.geckoview.MediaSession.Feature

class BrowserMediaNotificationController private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val notifications = NotificationManagerCompat.from(appContext)
    private val faviconStore = FaviconRepository(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingServiceStop: Runnable? = null

    private val owners = linkedMapOf<GeckoSession, PlaybackOwnerState>()
    private var primaryOwner: GeckoSession? = null
    private var backgroundResumeOwnerKey: String? = null
    private var backgroundResumeUntilMillis: Long = 0L
    private var backgroundResumeAttempts: Int = 0
    private var explicitPauseSuppressUntilMillis: Long = 0L

    val hasActivePlayback: Boolean
        get() = owners.values.any { it.hasActivePlayback }

    fun allowBackgroundPlaybackResume(durationMs: Long = BACKGROUND_RESUME_WINDOW_MS) {
        if (!BrowserProfileStore.loadBrowserSettings(appContext).backgroundVideoEnhancementEnabled) {
            logControllerEvent("backgroundResume.disabled", primaryState())
            return
        }
        val state = primaryState()?.takeIf { it.hasRealActivePlayback } ?: owners.values
            .filter { it.hasRealActivePlayback }
            .maxByOrNull { it.lastActiveAt }
            ?: run {
                logControllerEvent("backgroundResume.skipped", primaryState(), "no-active-playback")
                return
            }
        backgroundResumeOwnerKey = state.actionKey
        backgroundResumeUntilMillis = SystemClock.uptimeMillis() + durationMs
        backgroundResumeAttempts = 0
        logControllerEvent("backgroundResume.armed", state, "durationMs=$durationMs")
    }

    fun cancelBackgroundPlaybackResume(reason: String) {
        if (backgroundResumeOwnerKey == null && backgroundResumeUntilMillis == 0L) return
        val state = backgroundResumeOwnerKey
            ?.let { key -> owners.values.firstOrNull { it.actionKey == key } }
            ?: primaryState()
        backgroundResumeOwnerKey = null
        backgroundResumeUntilMillis = 0L
        backgroundResumeAttempts = 0
        logControllerEvent("backgroundResume.cancel", state, reason)
    }

    fun onActivated(owner: GeckoSession, mediaSession: MediaSession, ownerInfo: BrowserMediaOwnerInfo) {
        val state = stateFor(owner, ownerInfo)
        state.mediaSession = mediaSession
        state.active = true
        state.touch()
        primaryOwner = owner
        logControllerEvent("activated", state, "media=${System.identityHashCode(mediaSession)}")
        publishIfNeeded()
    }

    fun startPageKeepAlive(
        owner: GeckoSession,
        ownerInfo: BrowserMediaOwnerInfo,
        title: String,
        url: String,
        mediaKind: String
    ) {
        val normalizedMediaKind = mediaKind.ifBlank { null }
        if (!shouldPublishFallback(normalizedMediaKind) && owners[owner]?.mediaSession == null) {
            logControllerEvent(
                "keepAlive.deferred",
                owners[owner],
                "mediaKind=${normalizedMediaKind.orEmpty()} owner=${System.identityHashCode(owner)}"
            )
            return
        }
        val state = stateFor(owner, ownerInfo.withPageUrl(url))
        state.active = true
        state.playing = true
        state.fallbackTitle = title.ifBlank { ownerInfo.displayName ?: "Playing media" }
        state.fallbackUrl = url.ifBlank { ownerInfo.url.orEmpty() }
        state.fallbackMediaKind = normalizedMediaKind
        if (state.mediaSession == null) {
            state.features = Feature.STOP
        }
        state.touch()
        if (primaryState()?.hasRealActivePlayback != true) {
            primaryOwner = owner
        }
        logControllerEvent("keepAlive.start", state, "mediaKind=${state.fallbackMediaKind.orEmpty()}")
        publishIfNeeded(force = true)
    }

    fun stopPageKeepAlive(owner: GeckoSession) {
        val state = owners[owner] ?: run {
            logControllerEvent("keepAlive.stop.missing", null, "owner=${System.identityHashCode(owner)}")
            return
        }
        if (state.mediaSession == null) {
            logControllerEvent("keepAlive.stop", state, "fallback-only")
            removeOwner(owner)
            return
        }
        state.fallbackTitle = null
        state.fallbackUrl = null
        state.fallbackMediaKind = null
        logControllerEvent("keepAlive.stop", state)
        publishIfNeeded(force = true)
    }

    fun pausePageKeepAlive(
        owner: GeckoSession,
        ownerInfo: BrowserMediaOwnerInfo,
        title: String,
        url: String,
        mediaKind: String
    ) {
        val state = owners[owner] ?: run {
            logControllerEvent("keepAlive.pause.missing", null, "owner=${System.identityHashCode(owner)}")
            return
        }
        val normalizedMediaKind = mediaKind.ifBlank { null }
        if (!shouldPublishFallback(normalizedMediaKind) && state.mediaSession == null) {
            logControllerEvent(
                "keepAlive.pause.deferred",
                state,
                "mediaKind=${normalizedMediaKind.orEmpty()} fallback-only"
            )
            return
        }
        state.info = state.info.merge(ownerInfo.withPageUrl(url))
        state.active = true
        state.playing = false
        state.fallbackTitle = title.ifBlank { state.fallbackTitle ?: ownerInfo.displayName ?: "Playing media" }
        state.fallbackUrl = url.ifBlank { state.fallbackUrl ?: ownerInfo.url.orEmpty() }
        state.fallbackMediaKind = normalizedMediaKind ?: state.fallbackMediaKind
        state.touch()
        logControllerEvent("keepAlive.pause", state, "mediaKind=${state.fallbackMediaKind.orEmpty()}")
        publishIfNeeded(force = true)
    }

    fun onDeactivated(owner: GeckoSession, mediaSession: MediaSession) {
        val state = owners[owner] ?: run {
            logControllerEvent(
                "deactivated.missing",
                null,
                "owner=${System.identityHashCode(owner)} media=${System.identityHashCode(mediaSession)}"
            )
            return
        }
        if (state.mediaSession != mediaSession) {
            logControllerEvent("deactivated.ignored", state, "staleMedia=${System.identityHashCode(mediaSession)}")
            return
        }
        state.mediaSession = null
        state.metadata = null
        state.positionState = null
        logControllerEvent("deactivated", state)
        if (state.fallbackTitle != null && state.playing) {
            publishIfNeeded(force = true)
        } else {
            removeOwner(owner)
        }
    }

    fun onMetadata(owner: GeckoSession, mediaSession: MediaSession, value: MediaSession.Metadata) {
        val state = owners[owner] ?: run {
            logControllerEvent(
                "metadata.missing",
                null,
                "owner=${System.identityHashCode(owner)} title=${value.title.orEmpty()}"
            )
            return
        }
        if (state.mediaSession != mediaSession) {
            logControllerEvent("metadata.ignored", state, "title=${value.title.orEmpty()}")
            return
        }
        state.metadata = value
        state.touch()
        logControllerEvent("metadata", state, "title=${value.title.orEmpty()} artist=${value.artist.orEmpty()}")
        publishIfNeeded()
    }

    fun onFeatures(owner: GeckoSession, mediaSession: MediaSession, value: Long) {
        val state = owners[owner] ?: run {
            logControllerEvent("features.missing", null, "owner=${System.identityHashCode(owner)} features=$value")
            return
        }
        if (state.mediaSession != mediaSession) {
            logControllerEvent("features.ignored", state, "features=$value")
            return
        }
        state.features = value
        logControllerEvent("features", state, "features=$value")
        publishIfNeeded()
    }

    fun onPlay(owner: GeckoSession, mediaSession: MediaSession, ownerInfo: BrowserMediaOwnerInfo) {
        val recovered = !owners.containsKey(owner)
        val state = stateFor(owner, ownerInfo)
        if (state.mediaSession == null) {
            state.mediaSession = mediaSession
        }
        if (state.mediaSession != mediaSession) {
            logControllerEvent("play.ignored", state, "staleMedia=${System.identityHashCode(mediaSession)}")
            return
        }
        state.active = true
        state.playing = true
        state.touch()
        primaryOwner = owner
        logControllerEvent(if (recovered) "play.recovered" else "play", state)
        publishIfNeeded(force = true)
        if (backgroundResumeOwnerKey == state.actionKey && backgroundResumeAttempts > 0) {
            cancelBackgroundPlaybackResume("resumed")
        }
    }

    fun onPause(owner: GeckoSession, mediaSession: MediaSession) {
        val state = owners[owner] ?: run {
            logControllerEvent(
                "pause.missing",
                null,
                "owner=${System.identityHashCode(owner)} media=${System.identityHashCode(mediaSession)}"
            )
            return
        }
        if (state.mediaSession != mediaSession) {
            logControllerEvent("pause.ignored", state, "staleMedia=${System.identityHashCode(mediaSession)}")
            return
        }
        state.playing = false
        state.touch()
        logControllerEvent("pause", state)
        publishIfNeeded(force = true)
        maybeResumeBackgroundPause(state, mediaSession)
    }

    fun onStop(owner: GeckoSession, mediaSession: MediaSession) {
        val state = owners[owner] ?: run {
            logControllerEvent(
                "stop.missing",
                null,
                "owner=${System.identityHashCode(owner)} media=${System.identityHashCode(mediaSession)}"
            )
            return
        }
        if (state.mediaSession != mediaSession) {
            logControllerEvent("stop.ignored", state, "staleMedia=${System.identityHashCode(mediaSession)}")
            return
        }
        logControllerEvent("stop", state)
        removeOwner(owner)
    }

    fun onPositionState(owner: GeckoSession, mediaSession: MediaSession, value: MediaSession.PositionState) {
        val state = owners[owner] ?: return
        if (state.mediaSession != mediaSession) return
        state.positionState = value
        publishIfNeeded()
    }

    fun handleAction(action: String?, ownerKey: String?) {
        val state = ownerKey
            ?.let { key -> owners.values.firstOrNull { it.actionKey == key } }
            ?: primaryState()
            ?: return
        when (action) {
            BrowserMediaActionReceiver.ACTION_PLAY -> {
                explicitPauseSuppressUntilMillis = 0L
                state.mediaSession?.play()
            }
            BrowserMediaActionReceiver.ACTION_PAUSE -> {
                markExplicitPause(state)
                state.mediaSession?.pause()
            }
            BrowserMediaActionReceiver.ACTION_STOP -> Unit
            BrowserMediaActionReceiver.ACTION_SEEK_FORWARD -> state.mediaSession?.seekForward()
            BrowserMediaActionReceiver.ACTION_SEEK_BACKWARD -> state.mediaSession?.seekBackward()
            BrowserMediaActionReceiver.ACTION_NEXT -> state.mediaSession?.nextTrack()
            BrowserMediaActionReceiver.ACTION_PREVIOUS -> state.mediaSession?.previousTrack()
        }
    }

    fun clear() {
        logControllerEvent("clear", null)
        owners.values.forEach { state ->
            notifications.cancel(state.notificationId)
            state.release()
        }
        owners.clear()
        primaryOwner = null
        stopPlaybackServiceImmediately(removeNotification = true)
    }

    fun clearIfOwner(owner: GeckoSession) {
        if (owners.containsKey(owner)) {
            removeOwner(owner)
        }
    }

    fun clearIfOwner(ownerInfo: BrowserMediaOwnerInfo) {
        owners.values
            .filter { it.matchesOwner(ownerInfo) }
            .map { it.owner }
            .toList()
            .forEach(::removeOwner)
    }

    fun ownsActivePlayback(owner: GeckoSession): Boolean =
        owners[owner]?.hasActivePlayback == true

    fun ownsActivePlayback(ownerInfo: BrowserMediaOwnerInfo): Boolean =
        owners.values.any { it.matchesOwner(ownerInfo) && it.hasActivePlayback }

    fun sessionsForOwner(ownerInfo: BrowserMediaOwnerInfo): List<GeckoSession> =
        owners.values
            .filter { it.matchesOwner(ownerInfo) }
            .map { it.owner }
            .distinct()

    private fun publishIfNeeded(force: Boolean = false) {
        if (owners.isEmpty()) return
        ensureNotificationChannel()
        val primaryState = primaryState()
        var posted = false
        owners.values.forEach { state ->
            updateAndroidMediaSession(state)
            if (state.active && (state.playing || force)) {
                val primary = state == primaryState && state.active
                logControllerEvent(
                    "publish",
                    state,
                    "force=$force notificationId=${state.notificationId} style=${if (primary) "media" else "secondary"}"
                )
                runCatching {
                    notifications.notifyIfAllowed(
                        appContext,
                        state.notificationId,
                        if (primary) mediaNotification(state) else secondaryNotification(state)
                    )
                }
                posted = true
            } else {
                notifications.cancel(state.notificationId)
            }
        }
        if (!posted) return
        if (hasActivePlayback) {
            cancelPendingServiceStop()
            BrowserMediaPlaybackService.refresh(appContext)
        } else {
            schedulePlaybackServiceStop()
        }
    }

    fun foregroundNotification(): ForegroundMediaNotification? {
        val state = primaryState() ?: return null
        if (!state.active) return null
        ensureNotificationChannel()
        updateAndroidMediaSession(state)
        return ForegroundMediaNotification(state.notificationId, mediaNotification(state))
    }

    fun startingForegroundNotification(): ForegroundMediaNotification {
        ensureNotificationChannel()
        return ForegroundMediaNotification(
            STARTING_FOREGROUND_NOTIFICATION_ID,
            NotificationCompat.Builder(appContext, MEDIA_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Hyper Browser")
                .setContentText("Updating media playback")
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()
        )
    }

    private fun updateAndroidMediaSession(state: PlaybackOwnerState) {
        state.androidSession.isActive = state.active
        state.androidSession.setMetadata(mediaMetadata(state))
        state.androidSession.setPlaybackState(playbackState(state))
    }

    data class ForegroundMediaNotification(
        val id: Int,
        val notification: android.app.Notification
    )

    private fun mediaMetadata(state: PlaybackOwnerState): MediaMetadataCompat =
        MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, state.notificationTitle())
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, state.ownerDisplayText())
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, state.metadata?.album.orEmpty())
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, state.positionState?.duration?.secondsToMillis() ?: 0L)
            .build()

    private fun playbackState(state: PlaybackOwnerState): PlaybackStateCompat {
        val actions = supportedPlaybackActions(state)
        return PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(
                if (state.playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                state.positionState?.position?.secondsToMillis() ?: PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                state.positionState?.playbackRate?.toFloat() ?: 1f
            )
            .build()
    }

    private fun mediaNotification(state: PlaybackOwnerState): android.app.Notification {
        val actions = notificationActions(state)
        val compactActions = actions.indices.take(3).toList().toIntArray()

        return NotificationCompat.Builder(appContext, MEDIA_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setLargeIcon(largeIcon(state))
            .setContentTitle(state.notificationTitle())
            .setContentText(state.notificationText())
            .setContentIntent(contentPendingIntent(state))
            .setOngoing(state.playing)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setStyle(
                MediaStyle()
                    .setMediaSession(state.androidSession.sessionToken)
                    .setShowActionsInCompactView(*compactActions)
            )
            .also { builder -> actions.forEach(builder::addAction) }
            .build()
    }

    private fun secondaryNotification(state: PlaybackOwnerState): android.app.Notification =
        NotificationCompat.Builder(appContext, MEDIA_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setLargeIcon(largeIcon(state))
            .setContentTitle(state.notificationTitle())
            .setContentText(state.notificationText())
            .setContentIntent(contentPendingIntent(state))
            .setOngoing(state.playing)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .also { builder -> secondaryNotificationActions(state).forEach(builder::addAction) }
            .build()

    private fun notificationActions(state: PlaybackOwnerState): List<NotificationCompat.Action> {
        if (state.mediaSession == null) {
            return emptyList()
        }
        val items = mutableListOf<NotificationCompat.Action>()
        if (state.hasFeature(Feature.SEEK_BACKWARD)) {
            items += action(state, android.R.drawable.ic_media_rew, "Rewind", BrowserMediaActionReceiver.ACTION_SEEK_BACKWARD)
        }
        items += playPauseAction(state)
        if (state.hasFeature(Feature.SEEK_FORWARD)) {
            items += action(state, android.R.drawable.ic_media_ff, "Forward", BrowserMediaActionReceiver.ACTION_SEEK_FORWARD)
        }
        if (state.hasFeature(Feature.PREVIOUS_TRACK)) {
            items += action(state, android.R.drawable.ic_media_previous, "Previous", BrowserMediaActionReceiver.ACTION_PREVIOUS)
        }
        if (state.hasFeature(Feature.NEXT_TRACK)) {
            items += action(state, android.R.drawable.ic_media_next, "Next", BrowserMediaActionReceiver.ACTION_NEXT)
        }
        return items.take(5)
    }

    private fun secondaryNotificationActions(state: PlaybackOwnerState): List<NotificationCompat.Action> {
        val items = mutableListOf<NotificationCompat.Action>()
        contentPendingIntent(state)?.let { intent ->
            items += NotificationCompat.Action.Builder(android.R.drawable.ic_menu_view, "Open", intent).build()
        }
        if (state.mediaSession == null) {
            return items
        }
        items += playPauseAction(state)
        return items.take(3)
    }

    private fun supportedPlaybackActions(state: PlaybackOwnerState): Long {
        var actions = 0L
        if (state.mediaSession != null) {
            actions = actions or PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE
        }
        if (state.hasFeature(Feature.SEEK_TO)) actions = actions or PlaybackStateCompat.ACTION_SEEK_TO
        if (state.hasFeature(Feature.SEEK_FORWARD)) actions = actions or PlaybackStateCompat.ACTION_FAST_FORWARD
        if (state.hasFeature(Feature.SEEK_BACKWARD)) actions = actions or PlaybackStateCompat.ACTION_REWIND
        if (state.hasFeature(Feature.NEXT_TRACK)) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        if (state.hasFeature(Feature.PREVIOUS_TRACK)) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        return actions
    }

    private fun playPauseAction(state: PlaybackOwnerState): NotificationCompat.Action =
        if (state.playing) {
            action(state, android.R.drawable.ic_media_pause, "Pause", BrowserMediaActionReceiver.ACTION_PAUSE)
        } else {
            action(state, android.R.drawable.ic_media_play, "Play", BrowserMediaActionReceiver.ACTION_PLAY)
        }

    private fun action(state: PlaybackOwnerState, icon: Int, title: String, action: String): NotificationCompat.Action =
        NotificationCompat.Action.Builder(
            icon,
            title,
            BrowserMediaActionReceiver.pendingIntent(
                appContext,
                action,
                state.actionKey,
                state.actionRequestCode(action)
            )
        ).build()

    private fun contentPendingIntent(state: PlaybackOwnerState): PendingIntent? {
        val target = state.info.launchIntent
            ?: appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
            ?: return null
        val launchIntent = Intent(target).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            appContext,
            state.notificationId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            MEDIA_CHANNEL_ID,
            "Media playback",
            NotificationManager.IMPORTANCE_LOW
        )
        appContext.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun stateFor(owner: GeckoSession, ownerInfo: BrowserMediaOwnerInfo): PlaybackOwnerState {
        val existing = owners[owner]
        if (existing != null) {
            existing.info = existing.info.merge(ownerInfo)
            return existing
        }
        return PlaybackOwnerState(owner, ownerInfo).also {
            owners[owner] = it
        }
    }

    private fun removeOwner(owner: GeckoSession) {
        val removed = owners.remove(owner)
        logControllerEvent("removeOwner", removed, "owner=${System.identityHashCode(owner)}")
        if (removed != null) {
            notifications.cancel(removed.notificationId)
            removed.release()
        }
        if (primaryOwner == owner) {
            primaryOwner = owners.values.maxByOrNull { it.lastActiveAt }?.owner
        }
        if (owners.isEmpty() || owners.values.none { it.active }) {
            primaryOwner = null
            stopPlaybackServiceImmediately(removeNotification = true)
        } else {
            publishIfNeeded(force = true)
        }
    }

    private fun schedulePlaybackServiceStop() {
        cancelPendingServiceStop()
        val task = Runnable {
            pendingServiceStop = null
            if (hasActivePlayback) {
                BrowserMediaPlaybackService.refresh(appContext)
            } else {
                BrowserMediaPlaybackService.stop(appContext)
            }
        }
        pendingServiceStop = task
        mainHandler.postDelayed(task, MEDIA_PAUSE_SERVICE_GRACE_MS)
    }

    private fun stopPlaybackServiceImmediately(removeNotification: Boolean) {
        cancelPendingServiceStop()
        BrowserMediaPlaybackService.stop(appContext, removeNotification)
    }

    private fun cancelPendingServiceStop() {
        pendingServiceStop?.let(mainHandler::removeCallbacks)
        pendingServiceStop = null
    }

    private fun maybeResumeBackgroundPause(state: PlaybackOwnerState, mediaSession: MediaSession) {
        val now = SystemClock.uptimeMillis()
        if (now > backgroundResumeUntilMillis) return
        if (now < explicitPauseSuppressUntilMillis) return
        if (backgroundResumeOwnerKey != state.actionKey) return
        if (backgroundResumeAttempts >= BACKGROUND_RESUME_MAX_ATTEMPTS) return
        if (!state.active || state.mediaSession != mediaSession) return

        val attempt = ++backgroundResumeAttempts
        logControllerEvent("backgroundResume.schedule", state, "attempt=$attempt")
        mainHandler.postDelayed({
            val latest = owners[state.owner] ?: return@postDelayed
            val currentTime = SystemClock.uptimeMillis()
            if (currentTime > backgroundResumeUntilMillis) return@postDelayed
            if (currentTime < explicitPauseSuppressUntilMillis) return@postDelayed
            if (backgroundResumeOwnerKey != latest.actionKey) return@postDelayed
            if (latest.mediaSession != mediaSession || latest.playing || !latest.active) {
                return@postDelayed
            }
            logControllerEvent("backgroundResume.play", latest, "attempt=$attempt")
            runCatching { mediaSession.play() }
                .onFailure { error -> logControllerEvent("backgroundResume.failed", latest, error.message.orEmpty()) }
        }, BACKGROUND_RESUME_DELAY_MS)
    }

    private fun markExplicitPause(state: PlaybackOwnerState) {
        explicitPauseSuppressUntilMillis = SystemClock.uptimeMillis() + EXPLICIT_PAUSE_SUPPRESS_MS
        if (backgroundResumeOwnerKey == state.actionKey) cancelBackgroundPlaybackResume("explicit-pause")
        logControllerEvent("backgroundResume.explicitPause", state)
    }

    private fun primaryState(): PlaybackOwnerState? {
        val selected = primaryOwner?.let { owners[it] }
        if (selected?.hasRealActivePlayback == true) return selected
        val realPlaying = owners.values
            .filter { it.hasRealActivePlayback }
            .maxByOrNull { it.lastActiveAt }
        if (realPlaying != null) {
            primaryOwner = realPlaying.owner
            return realPlaying
        }
        if (selected?.hasActivePlayback == true) return selected
        val playing = owners.values
            .filter { it.hasActivePlayback }
            .maxByOrNull { it.lastActiveAt }
        if (playing != null) {
            primaryOwner = playing.owner
            return playing
        }
        return selected?.takeIf { it.active }
            ?: owners.values.filter { it.active }.maxByOrNull { it.lastActiveAt }
    }

    private fun logControllerEvent(event: String, state: PlaybackOwnerState?, detail: String = "") {
        Log.d(
            MEDIA_DEBUG_TAG,
            buildString {
                append("controller event=").append(event)
                append(" primary=").append(primaryOwner?.let { System.identityHashCode(it) } ?: "none")
                state?.let {
                    append(" owner=").append(it.debugLabel())
                }
                append(" owners=").append(owners.values.joinToString(prefix = "[", postfix = "]") { it.debugLabel() })
                if (detail.isNotBlank()) append(" ").append(detail)
            }
        )
    }

    private fun PlaybackOwnerState.debugLabel(): String =
        buildString {
            append(System.identityHashCode(owner))
            append(":").append(info.kind)
            append(":").append(info.id)
            append("#").append(notificationId)
            append("{active=").append(active)
            append(",playing=").append(playing)
            append(",media=").append(mediaSession?.let { System.identityHashCode(it) } ?: "none")
            append(",title=").append(notificationTitle())
            append(",url=").append(fallbackUrl.ifNullOrBlank { info.url }.orEmpty())
            append("}")
        }

    private fun largeIcon(state: PlaybackOwnerState) =
        BrowserIconComposer.badgedSiteIcon(
            appContext,
            state.iconPath(),
            state.fallbackUrl.ifNullOrBlank { state.info.url },
            LARGE_ICON_SIZE
        )

    private fun ownerKey(owner: GeckoSession, info: BrowserMediaOwnerInfo): String {
        return info.mediaOwnerKey(System.identityHashCode(owner))
    }

    private fun shouldPublishFallback(mediaKind: String?): Boolean =
        mediaKind == FALLBACK_MEDIA_KIND_WEBRTC_AUDIO

    private fun notificationIdFor(ownerKey: String): Int =
        MEDIA_NOTIFICATION_ID_BASE + (ownerKey.hashCode() and MEDIA_NOTIFICATION_ID_MASK)

    private fun BrowserMediaOwnerInfo.withPageUrl(url: String): BrowserMediaOwnerInfo {
        val cleanUrl = url.ifBlank { this.url.orEmpty() }
        return if (cleanUrl.isBlank()) this else copy(url = cleanUrl)
    }

    private fun BrowserMediaOwnerInfo.merge(next: BrowserMediaOwnerInfo): BrowserMediaOwnerInfo =
        BrowserMediaOwnerInfo(
            id = next.id.ifBlank { id },
            kind = next.kind,
            displayName = next.displayName.ifNullOrBlank { displayName },
            url = next.url.ifNullOrBlank { url },
            iconPath = next.iconPath.ifNullOrBlank { iconPath },
            launchIntent = next.launchIntent ?: launchIntent
        )

    private fun String?.ifNullOrBlank(fallback: () -> String?): String? =
        if (isNullOrBlank()) fallback() else this

    private inner class PlaybackOwnerState(
        val owner: GeckoSession,
        var info: BrowserMediaOwnerInfo
    ) {
        val actionKey: String = ownerKey(owner, info)
        val notificationId: Int = notificationIdFor(actionKey)
        val androidSession: MediaSessionCompat =
            MediaSessionCompat(appContext, "HyperBrowserMedia-$notificationId").apply {
                setCallback(
                    object : MediaSessionCompat.Callback() {
                        override fun onPlay() {
                            explicitPauseSuppressUntilMillis = 0L
                            this@PlaybackOwnerState.mediaSession?.play()
                        }

                        override fun onPause() {
                            markExplicitPause(this@PlaybackOwnerState)
                            this@PlaybackOwnerState.mediaSession?.pause()
                        }

                        override fun onStop() {
                            // Closing the owning tab/WebApp is the only in-app stop boundary.
                        }

                        override fun onSeekTo(pos: Long) {
                            this@PlaybackOwnerState.mediaSession?.seekTo(pos / 1000.0, false)
                        }

                        override fun onSkipToNext() {
                            this@PlaybackOwnerState.mediaSession?.nextTrack()
                        }

                        override fun onSkipToPrevious() {
                            this@PlaybackOwnerState.mediaSession?.previousTrack()
                        }

                        override fun onFastForward() {
                            this@PlaybackOwnerState.mediaSession?.seekForward()
                        }

                        override fun onRewind() {
                            this@PlaybackOwnerState.mediaSession?.seekBackward()
                        }
                    }
                )
            }
        var mediaSession: MediaSession? = null
        var metadata: MediaSession.Metadata? = null
        var fallbackTitle: String? = null
        var fallbackUrl: String? = null
        var fallbackMediaKind: String? = null
        var features: Long = Feature.NONE
        var positionState: MediaSession.PositionState? = null
        var playing: Boolean = false
        var active: Boolean = false
        var lastActiveAt: Long = System.currentTimeMillis()

        val hasActivePlayback: Boolean
            get() = active && playing

        val hasRealActivePlayback: Boolean
            get() = hasActivePlayback && mediaSession != null

        fun touch() {
            lastActiveAt = System.currentTimeMillis()
        }

        fun hasFeature(feature: Long): Boolean = features and feature != 0L

        fun actionRequestCode(action: String): Int =
            notificationId xor action.hashCode()

        fun release() {
            androidSession.isActive = false
            androidSession.release()
        }

        fun matchesOwner(ownerInfo: BrowserMediaOwnerInfo): Boolean =
            ownerKey(owner, info) == ownerKey(owner, ownerInfo)

        fun notificationTitle(): String =
            metadata?.title.ifNullOrBlank { null }
                ?: fallbackNotificationTitle()
                ?: "Playing media"

        fun notificationText(): String {
            val metadataText = listOfNotNull(
                metadata?.artist?.ifBlank { null },
                metadata?.album?.ifBlank { null }
            ).joinToString(" · ")
            if (metadataText.isNotBlank()) return metadataText
            return ownerDisplayText()
        }

        fun ownerDisplayText(): String =
            listOfNotNull(
                fallbackMediaKind,
                ownerContextText(),
                ownerKindLabel()
            ).joinToString(" · ").ifBlank { "Hyper Browser" }

        fun iconPath(): String? =
            info.iconPath.ifNullOrBlank {
                bestUrl()?.let { faviconStore.cachedIconPath(it) }
            }

        private fun bestUrl(): String? =
            fallbackUrl.ifNullOrBlank { info.url }

        private fun siteLabel(): String? =
            bestUrl()?.let { url ->
                runCatching { Uri.parse(url).host?.removePrefix("www.") }.getOrNull()
            }

        private fun fallbackNotificationTitle(): String? =
            when (info.kind) {
                BrowserMediaOwnerKind.WebApp -> info.displayName
                    .ifNullOrBlank { fallbackTitle }
                    .ifNullOrBlank { bestUrl() }
                    .ifNullOrBlank { siteLabel() }
                BrowserMediaOwnerKind.BrowserTab -> fallbackTitle
                    .ifNullOrBlank { info.displayName }
                    .ifNullOrBlank { bestUrl() }
                    .ifNullOrBlank { siteLabel() }
                BrowserMediaOwnerKind.ExtensionTab -> bestUrl()
                    .ifNullOrBlank { info.displayName }
                    .ifNullOrBlank { fallbackTitle }
                    .ifNullOrBlank { siteLabel() }
            }

        private fun ownerContextText(): String? {
            val primaryTitle = notificationTitle().trim()
            val candidates = when (info.kind) {
                BrowserMediaOwnerKind.WebApp -> listOf(fallbackTitle, siteLabel(), bestUrl(), info.displayName)
                BrowserMediaOwnerKind.BrowserTab -> listOf(fallbackTitle, info.displayName, siteLabel(), bestUrl())
                BrowserMediaOwnerKind.ExtensionTab -> listOf(bestUrl(), info.displayName, fallbackTitle, siteLabel())
            }
            return candidates
                .mapNotNull { it?.takeIf { value -> value.isNotBlank() } }
                .firstOrNull { it.trim() != primaryTitle }
        }

        private fun ownerKindLabel(): String =
            when (info.kind) {
                BrowserMediaOwnerKind.WebApp -> "WebApp"
                BrowserMediaOwnerKind.ExtensionTab -> "Extension"
                BrowserMediaOwnerKind.BrowserTab -> "Hyper Browser"
            }
    }

    companion object {
        private const val MEDIA_NOTIFICATION_ID_BASE = 390100
        private const val MEDIA_NOTIFICATION_ID_MASK = 0x0000ffff
        private const val STARTING_FOREGROUND_NOTIFICATION_ID = MEDIA_NOTIFICATION_ID_BASE - 1
        private const val MEDIA_CHANNEL_ID = "media_playback"
        private const val MEDIA_DEBUG_TAG = "HyperMediaDebug"
        private const val FALLBACK_MEDIA_KIND_WEBRTC_AUDIO = "webrtc-audio"
        private const val LARGE_ICON_SIZE = 128
        private const val BACKGROUND_RESUME_WINDOW_MS = 12_000L
        private const val BACKGROUND_RESUME_DELAY_MS = 700L
        private const val BACKGROUND_RESUME_MAX_ATTEMPTS = 3
        private const val EXPLICIT_PAUSE_SUPPRESS_MS = 4_000L
        private const val MEDIA_PAUSE_SERVICE_GRACE_MS = 2_000L

        @Volatile
        private var instance: BrowserMediaNotificationController? = null

        fun get(context: Context): BrowserMediaNotificationController =
            instance ?: synchronized(this) {
                instance ?: BrowserMediaNotificationController(context).also { instance = it }
            }
    }
}

private fun Double.secondsToMillis(): Long =
    takeIf { it.isFinite() && it > 0.0 }
        ?.let { (it * 1000.0).toLong() }
        ?: 0L
