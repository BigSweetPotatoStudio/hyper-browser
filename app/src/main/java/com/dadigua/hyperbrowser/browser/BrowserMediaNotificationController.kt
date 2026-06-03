package com.dadigua.hyperbrowser.browser

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
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
    private val androidSession = MediaSessionCompat(appContext, "HyperBrowserMedia").apply {
        setCallback(
            object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    activeGeckoSession?.play()
                }

                override fun onPause() {
                    activeGeckoSession?.pause()
                }

                override fun onStop() {
                    activeGeckoSession?.stop()
                    clear()
                }

                override fun onSeekTo(pos: Long) {
                    activeGeckoSession?.seekTo(pos / 1000.0, false)
                }

                override fun onSkipToNext() {
                    activeGeckoSession?.nextTrack()
                }

                override fun onSkipToPrevious() {
                    activeGeckoSession?.previousTrack()
                }

                override fun onFastForward() {
                    activeGeckoSession?.seekForward()
                }

                override fun onRewind() {
                    activeGeckoSession?.seekBackward()
                }
            }
        )
    }

    private var activeOwner: GeckoSession? = null
    private var activeGeckoSession: MediaSession? = null
    private var activeLaunchIntent: Intent? = null
    private var metadata: MediaSession.Metadata? = null
    private var features: Long = Feature.NONE
    private var positionState: MediaSession.PositionState? = null
    private var playing: Boolean = false
    private var active: Boolean = false

    fun onActivated(owner: GeckoSession, mediaSession: MediaSession, launchIntent: Intent?) {
        activeOwner = owner
        activeGeckoSession = mediaSession
        activeLaunchIntent = launchIntent
        active = true
        androidSession.isActive = true
        publishIfNeeded()
    }

    fun onDeactivated(owner: GeckoSession, mediaSession: MediaSession) {
        if (!matches(owner, mediaSession)) return
        clear()
    }

    fun onMetadata(owner: GeckoSession, mediaSession: MediaSession, value: MediaSession.Metadata) {
        if (!matches(owner, mediaSession)) return
        metadata = value
        publishIfNeeded()
    }

    fun onFeatures(owner: GeckoSession, mediaSession: MediaSession, value: Long) {
        if (!matches(owner, mediaSession)) return
        features = value
        publishIfNeeded()
    }

    fun onPlay(owner: GeckoSession, mediaSession: MediaSession) {
        if (!matches(owner, mediaSession)) return
        playing = true
        publishIfNeeded(force = true)
    }

    fun onPause(owner: GeckoSession, mediaSession: MediaSession) {
        if (!matches(owner, mediaSession)) return
        playing = false
        publishIfNeeded(force = true)
    }

    fun onStop(owner: GeckoSession, mediaSession: MediaSession) {
        if (!matches(owner, mediaSession)) return
        clear()
    }

    fun onPositionState(owner: GeckoSession, mediaSession: MediaSession, value: MediaSession.PositionState) {
        if (!matches(owner, mediaSession)) return
        positionState = value
        publishIfNeeded()
    }

    fun handleAction(action: String?) {
        when (action) {
            BrowserMediaActionReceiver.ACTION_PLAY -> activeGeckoSession?.play()
            BrowserMediaActionReceiver.ACTION_PAUSE -> activeGeckoSession?.pause()
            BrowserMediaActionReceiver.ACTION_STOP -> {
                activeGeckoSession?.stop()
                clear()
            }
            BrowserMediaActionReceiver.ACTION_SEEK_FORWARD -> activeGeckoSession?.seekForward()
            BrowserMediaActionReceiver.ACTION_SEEK_BACKWARD -> activeGeckoSession?.seekBackward()
            BrowserMediaActionReceiver.ACTION_NEXT -> activeGeckoSession?.nextTrack()
            BrowserMediaActionReceiver.ACTION_PREVIOUS -> activeGeckoSession?.previousTrack()
        }
    }

    fun clear() {
        active = false
        playing = false
        activeOwner = null
        activeGeckoSession = null
        activeLaunchIntent = null
        metadata = null
        features = Feature.NONE
        positionState = null
        androidSession.isActive = false
        notifications.cancel(MEDIA_NOTIFICATION_ID)
    }

    fun clearIfOwner(owner: GeckoSession) {
        if (activeOwner == owner) {
            clear()
        }
    }

    private fun publishIfNeeded(force: Boolean = false) {
        if (!active || (!playing && !force)) return
        ensureNotificationChannel()
        androidSession.setMetadata(mediaMetadata())
        androidSession.setPlaybackState(playbackState())
        runCatching {
            notifications.notify(MEDIA_NOTIFICATION_ID, notification())
        }
    }

    private fun mediaMetadata(): MediaMetadataCompat {
        val item = metadata
        return MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, item?.title?.ifBlank { null } ?: "Playing media")
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, item?.artist?.ifBlank { null } ?: "Hyper Browser")
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, item?.album.orEmpty())
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, positionState?.duration?.secondsToMillis() ?: 0L)
            .build()
    }

    private fun playbackState(): PlaybackStateCompat {
        val actions = supportedPlaybackActions()
        return PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(
                if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                positionState?.position?.secondsToMillis() ?: PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                positionState?.playbackRate?.toFloat() ?: 1f
            )
            .build()
    }

    private fun notification(): android.app.Notification {
        val title = metadata?.title?.ifBlank { null } ?: "Playing media"
        val text = listOfNotNull(
            metadata?.artist?.ifBlank { null },
            metadata?.album?.ifBlank { null }
        ).joinToString(" · ").ifBlank { "Hyper Browser" }
        val actions = notificationActions()
        val compactActions = actions.indices.take(3).toList().toIntArray()

        return NotificationCompat.Builder(appContext, MEDIA_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentPendingIntent())
            .setOngoing(playing)
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

    private fun notificationActions(): List<NotificationCompat.Action> {
        val items = mutableListOf<NotificationCompat.Action>()
        if (hasFeature(Feature.SEEK_BACKWARD)) {
            items += action(android.R.drawable.ic_media_rew, "Rewind", BrowserMediaActionReceiver.ACTION_SEEK_BACKWARD)
        }
        if (playing && hasFeature(Feature.PAUSE)) {
            items += action(android.R.drawable.ic_media_pause, "Pause", BrowserMediaActionReceiver.ACTION_PAUSE)
        } else if (!playing && hasFeature(Feature.PLAY)) {
            items += action(android.R.drawable.ic_media_play, "Play", BrowserMediaActionReceiver.ACTION_PLAY)
        }
        if (hasFeature(Feature.SEEK_FORWARD)) {
            items += action(android.R.drawable.ic_media_ff, "Forward", BrowserMediaActionReceiver.ACTION_SEEK_FORWARD)
        }
        if (hasFeature(Feature.PREVIOUS_TRACK)) {
            items += action(android.R.drawable.ic_media_previous, "Previous", BrowserMediaActionReceiver.ACTION_PREVIOUS)
        }
        if (hasFeature(Feature.NEXT_TRACK)) {
            items += action(android.R.drawable.ic_media_next, "Next", BrowserMediaActionReceiver.ACTION_NEXT)
        }
        if (items.isEmpty()) {
            items += if (playing) {
                action(android.R.drawable.ic_media_pause, "Pause", BrowserMediaActionReceiver.ACTION_PAUSE)
            } else {
                action(android.R.drawable.ic_media_play, "Play", BrowserMediaActionReceiver.ACTION_PLAY)
            }
        }
        return items.take(5)
    }

    private fun supportedPlaybackActions(): Long {
        var actions = 0L
        if (hasFeature(Feature.PLAY)) actions = actions or PlaybackStateCompat.ACTION_PLAY
        if (hasFeature(Feature.PAUSE)) actions = actions or PlaybackStateCompat.ACTION_PAUSE
        if (hasFeature(Feature.STOP)) actions = actions or PlaybackStateCompat.ACTION_STOP
        if (hasFeature(Feature.SEEK_TO)) actions = actions or PlaybackStateCompat.ACTION_SEEK_TO
        if (hasFeature(Feature.SEEK_FORWARD)) actions = actions or PlaybackStateCompat.ACTION_FAST_FORWARD
        if (hasFeature(Feature.SEEK_BACKWARD)) actions = actions or PlaybackStateCompat.ACTION_REWIND
        if (hasFeature(Feature.NEXT_TRACK)) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        if (hasFeature(Feature.PREVIOUS_TRACK)) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        return actions
    }

    private fun action(icon: Int, title: String, action: String): NotificationCompat.Action =
        NotificationCompat.Action.Builder(icon, title, BrowserMediaActionReceiver.pendingIntent(appContext, action)).build()

    private fun contentPendingIntent(): PendingIntent? {
        val target = activeLaunchIntent
            ?: appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
            ?: return null
        target.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            appContext,
            CONTENT_REQUEST_CODE,
            target,
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

    private fun hasFeature(feature: Long): Boolean = features and feature != 0L

    private fun matches(owner: GeckoSession, mediaSession: MediaSession): Boolean =
        activeOwner == owner && activeGeckoSession == mediaSession

    companion object {
        private const val MEDIA_CHANNEL_ID = "media_playback"
        private const val MEDIA_NOTIFICATION_ID = 3901
        private const val CONTENT_REQUEST_CODE = 3902

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
