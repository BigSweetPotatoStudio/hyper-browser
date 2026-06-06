package com.dadigua.hyperbrowser.browser

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.dadigua.hyperbrowser.R
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.MediaSession
import org.mozilla.geckoview.MediaSession.Feature

class BrowserMediaNotificationController private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val notifications = NotificationManagerCompat.from(appContext)
    private val faviconStore = FaviconRepository(appContext)
    private val androidSession = MediaSessionCompat(appContext, "HyperBrowserMedia").apply {
        setCallback(
            object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    primaryState()?.mediaSession?.play()
                }

                override fun onPause() {
                    primaryState()?.mediaSession?.pause()
                }

                override fun onStop() {
                    val state = primaryState() ?: return
                    state.mediaSession?.stop()
                    removeOwner(state.owner)
                }

                override fun onSeekTo(pos: Long) {
                    primaryState()?.mediaSession?.seekTo(pos / 1000.0, false)
                }

                override fun onSkipToNext() {
                    primaryState()?.mediaSession?.nextTrack()
                }

                override fun onSkipToPrevious() {
                    primaryState()?.mediaSession?.previousTrack()
                }

                override fun onFastForward() {
                    primaryState()?.mediaSession?.seekForward()
                }

                override fun onRewind() {
                    primaryState()?.mediaSession?.seekBackward()
                }
            }
        )
    }

    private val owners = linkedMapOf<GeckoSession, PlaybackOwnerState>()
    private var primaryOwner: GeckoSession? = null

    val hasActivePlayback: Boolean
        get() = owners.values.any { it.hasActivePlayback }

    val hasActiveVideoPlayback: Boolean
        get() = owners.values.any { it.hasActivePlayback && it.fullscreenVideo }

    fun onActivated(owner: GeckoSession, mediaSession: MediaSession, ownerInfo: BrowserMediaOwnerInfo) {
        val state = stateFor(owner, ownerInfo)
        state.mediaSession = mediaSession
        state.active = true
        state.touch()
        primaryOwner = owner
        androidSession.isActive = true
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
        val state = stateFor(owner, ownerInfo.withPageUrl(url))
        state.active = true
        state.playing = true
        state.fallbackTitle = title.ifBlank { ownerInfo.displayName ?: "Playing media" }
        state.fallbackUrl = url.ifBlank { ownerInfo.url.orEmpty() }
        state.fallbackMediaKind = mediaKind.ifBlank { null }
        if (state.mediaSession == null) {
            state.features = Feature.STOP
        }
        state.touch()
        primaryOwner = owner
        androidSession.isActive = true
        logControllerEvent("keepAlive.start", state, "mediaKind=${state.fallbackMediaKind.orEmpty()}")
        publishIfNeeded(force = true)
    }

    fun stopPageKeepAlive(owner: GeckoSession) {
        val state = owners[owner] ?: run {
            logControllerEvent("keepAlive.stop.missing", null, "owner=${System.identityHashCode(owner)}")
            return
        }
        state.fallbackTitle = null
        state.fallbackUrl = null
        state.fallbackMediaKind = null
        logControllerEvent("keepAlive.stop", state)
        if (state.mediaSession == null) {
            removeOwner(owner)
        } else {
            publishIfNeeded(force = true)
        }
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

    fun onPlay(owner: GeckoSession, mediaSession: MediaSession) {
        val state = owners[owner] ?: run {
            logControllerEvent(
                "play.missing",
                null,
                "owner=${System.identityHashCode(owner)} media=${System.identityHashCode(mediaSession)}"
            )
            return
        }
        if (state.mediaSession != mediaSession) {
            logControllerEvent("play.ignored", state, "staleMedia=${System.identityHashCode(mediaSession)}")
            return
        }
        state.active = true
        state.playing = true
        state.touch()
        primaryOwner = owner
        logControllerEvent("play", state)
        publishIfNeeded(force = true)
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

    fun onFullscreen(
        owner: GeckoSession,
        mediaSession: MediaSession,
        enabled: Boolean,
        elementMetadata: MediaSession.ElementMetadata?
    ) {
        val state = owners[owner] ?: return
        if (state.mediaSession != mediaSession) return
        state.fullscreenVideo = enabled && (elementMetadata?.videoTrackCount ?: 0) > 0
        logControllerEvent(
            "fullscreen",
            state,
            "enabled=$enabled videoTracks=${elementMetadata?.videoTrackCount ?: 0}"
        )
    }

    fun onPositionState(owner: GeckoSession, mediaSession: MediaSession, value: MediaSession.PositionState) {
        val state = owners[owner] ?: return
        if (state.mediaSession != mediaSession) return
        state.positionState = value
        publishIfNeeded()
    }

    fun handleAction(action: String?) {
        val state = primaryState() ?: return
        when (action) {
            BrowserMediaActionReceiver.ACTION_PLAY -> state.mediaSession?.play()
            BrowserMediaActionReceiver.ACTION_PAUSE -> state.mediaSession?.pause()
            BrowserMediaActionReceiver.ACTION_STOP -> {
                state.mediaSession?.stop()
                removeOwner(state.owner)
            }
            BrowserMediaActionReceiver.ACTION_SEEK_FORWARD -> state.mediaSession?.seekForward()
            BrowserMediaActionReceiver.ACTION_SEEK_BACKWARD -> state.mediaSession?.seekBackward()
            BrowserMediaActionReceiver.ACTION_NEXT -> state.mediaSession?.nextTrack()
            BrowserMediaActionReceiver.ACTION_PREVIOUS -> state.mediaSession?.previousTrack()
        }
    }

    fun clear() {
        logControllerEvent("clear", null)
        owners.clear()
        primaryOwner = null
        androidSession.isActive = false
        notifications.cancel(MEDIA_NOTIFICATION_ID)
        BrowserMediaPlaybackService.stop(appContext)
    }

    fun clearIfOwner(owner: GeckoSession) {
        if (owners.containsKey(owner)) {
            removeOwner(owner)
        }
    }

    fun ownsActivePlayback(owner: GeckoSession): Boolean =
        owners[owner]?.hasActivePlayback == true

    private fun publishIfNeeded(force: Boolean = false) {
        val state = primaryState() ?: return
        if (!state.active || (!state.playing && !force)) return
        logControllerEvent("publish", state, "force=$force notificationId=$MEDIA_NOTIFICATION_ID")
        ensureNotificationChannel()
        androidSession.isActive = true
        androidSession.setMetadata(mediaMetadata(state))
        androidSession.setPlaybackState(playbackState(state))
        if (state.playing) {
            BrowserMediaPlaybackService.refresh(appContext)
        } else {
            BrowserMediaPlaybackService.refresh(appContext)
            runCatching {
                notifications.notify(MEDIA_NOTIFICATION_ID, notification(state))
            }
        }
    }

    fun foregroundNotification(): android.app.Notification? {
        val state = primaryState() ?: return null
        if (!state.active) return null
        ensureNotificationChannel()
        androidSession.isActive = true
        androidSession.setMetadata(mediaMetadata(state))
        androidSession.setPlaybackState(playbackState(state))
        return notification(state)
    }

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

    private fun notification(state: PlaybackOwnerState): android.app.Notification {
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
                    .setMediaSession(androidSession.sessionToken)
                    .setShowActionsInCompactView(*compactActions)
            )
            .also { builder -> actions.forEach(builder::addAction) }
            .build()
    }

    private fun notificationActions(state: PlaybackOwnerState): List<NotificationCompat.Action> {
        if (state.mediaSession == null) {
            return listOf(action(android.R.drawable.ic_menu_close_clear_cancel, "Stop", BrowserMediaActionReceiver.ACTION_STOP))
        }
        val items = mutableListOf<NotificationCompat.Action>()
        if (state.hasFeature(Feature.SEEK_BACKWARD)) {
            items += action(android.R.drawable.ic_media_rew, "Rewind", BrowserMediaActionReceiver.ACTION_SEEK_BACKWARD)
        }
        if (state.playing && state.hasFeature(Feature.PAUSE)) {
            items += action(android.R.drawable.ic_media_pause, "Pause", BrowserMediaActionReceiver.ACTION_PAUSE)
        } else if (!state.playing && state.hasFeature(Feature.PLAY)) {
            items += action(android.R.drawable.ic_media_play, "Play", BrowserMediaActionReceiver.ACTION_PLAY)
        }
        if (state.hasFeature(Feature.SEEK_FORWARD)) {
            items += action(android.R.drawable.ic_media_ff, "Forward", BrowserMediaActionReceiver.ACTION_SEEK_FORWARD)
        }
        if (state.hasFeature(Feature.PREVIOUS_TRACK)) {
            items += action(android.R.drawable.ic_media_previous, "Previous", BrowserMediaActionReceiver.ACTION_PREVIOUS)
        }
        if (state.hasFeature(Feature.NEXT_TRACK)) {
            items += action(android.R.drawable.ic_media_next, "Next", BrowserMediaActionReceiver.ACTION_NEXT)
        }
        if (state.hasFeature(Feature.STOP)) {
            items += action(android.R.drawable.ic_menu_close_clear_cancel, "Stop", BrowserMediaActionReceiver.ACTION_STOP)
        }
        if (items.isEmpty()) {
            items += if (state.playing) {
                action(android.R.drawable.ic_media_pause, "Pause", BrowserMediaActionReceiver.ACTION_PAUSE)
            } else {
                action(android.R.drawable.ic_media_play, "Play", BrowserMediaActionReceiver.ACTION_PLAY)
            }
        }
        return items.take(5)
    }

    private fun supportedPlaybackActions(state: PlaybackOwnerState): Long {
        var actions = 0L
        if (state.hasFeature(Feature.PLAY)) actions = actions or PlaybackStateCompat.ACTION_PLAY
        if (state.hasFeature(Feature.PAUSE)) actions = actions or PlaybackStateCompat.ACTION_PAUSE
        if (state.hasFeature(Feature.STOP)) actions = actions or PlaybackStateCompat.ACTION_STOP
        if (state.hasFeature(Feature.SEEK_TO)) actions = actions or PlaybackStateCompat.ACTION_SEEK_TO
        if (state.hasFeature(Feature.SEEK_FORWARD)) actions = actions or PlaybackStateCompat.ACTION_FAST_FORWARD
        if (state.hasFeature(Feature.SEEK_BACKWARD)) actions = actions or PlaybackStateCompat.ACTION_REWIND
        if (state.hasFeature(Feature.NEXT_TRACK)) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        if (state.hasFeature(Feature.PREVIOUS_TRACK)) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        return actions
    }

    private fun action(icon: Int, title: String, action: String): NotificationCompat.Action =
        NotificationCompat.Action.Builder(icon, title, BrowserMediaActionReceiver.pendingIntent(appContext, action)).build()

    private fun contentPendingIntent(state: PlaybackOwnerState): PendingIntent? {
        val target = state.info.launchIntent
            ?: appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
            ?: return null
        val launchIntent = Intent(target).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            appContext,
            CONTENT_REQUEST_CODE,
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
        if (primaryOwner == owner) {
            primaryOwner = owners.values.maxByOrNull { it.lastActiveAt }?.owner
        }
        if (owners.values.none { it.active }) {
            clear()
        } else {
            publishIfNeeded(force = true)
        }
    }

    private fun primaryState(): PlaybackOwnerState? {
        val selected = primaryOwner?.let { owners[it] }
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
            append("{active=").append(active)
            append(",playing=").append(playing)
            append(",media=").append(mediaSession?.let { System.identityHashCode(it) } ?: "none")
            append(",title=").append(notificationTitle())
            append(",url=").append(fallbackUrl.ifNullOrBlank { info.url }.orEmpty())
            append("}")
        }

    private fun largeIcon(state: PlaybackOwnerState) =
        BrowserIconComposer.badgedSiteIcon(appContext, state.iconPath(), LARGE_ICON_SIZE)

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
        var mediaSession: MediaSession? = null
        var metadata: MediaSession.Metadata? = null
        var fallbackTitle: String? = null
        var fallbackUrl: String? = null
        var fallbackMediaKind: String? = null
        var features: Long = Feature.NONE
        var positionState: MediaSession.PositionState? = null
        var playing: Boolean = false
        var active: Boolean = false
        var fullscreenVideo: Boolean = false
        var lastActiveAt: Long = System.currentTimeMillis()

        val hasActivePlayback: Boolean
            get() = active && playing

        fun touch() {
            lastActiveAt = System.currentTimeMillis()
        }

        fun hasFeature(feature: Long): Boolean = features and feature != 0L

        fun notificationTitle(): String =
            metadata?.title.ifNullOrBlank { fallbackTitle }
                ?: info.displayName.ifNullOrBlank { siteLabel() }
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
                info.displayName.ifNullOrBlank { siteLabel() },
                when (info.kind) {
                    BrowserMediaOwnerKind.WebApp -> "WebApp"
                    BrowserMediaOwnerKind.ExtensionTab -> "Extension"
                    BrowserMediaOwnerKind.BrowserTab -> "Hyper Browser"
                }
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
    }

    companion object {
        const val MEDIA_NOTIFICATION_ID = 3901
        private const val MEDIA_CHANNEL_ID = "media_playback"
        private const val MEDIA_DEBUG_TAG = "HyperMediaDebug"
        private const val CONTENT_REQUEST_CODE = 3902
        private const val LARGE_ICON_SIZE = 128

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
