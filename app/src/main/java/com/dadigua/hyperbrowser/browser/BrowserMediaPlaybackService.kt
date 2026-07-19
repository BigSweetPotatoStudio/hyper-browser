package com.dadigua.hyperbrowser.browser

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

class BrowserMediaPlaybackService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var foregroundNotificationId: Int? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        attach(this)

        // A service launched with startForegroundService() must be promoted even if
        // playback stopped while Android was creating the service.
        val controller = BrowserMediaNotificationController.get(this)
        promote(controller.foregroundNotification() ?: controller.startingForegroundNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        reconcileDesiredState()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        detach(this)
        super.onDestroy()
    }

    private fun reconcileDesiredState() {
        val controller = BrowserMediaNotificationController.get(this)
        val notificationEntry = controller.foregroundNotification()
        val desired = desiredState()

        if (desired.running && notificationEntry != null) {
            promote(notificationEntry)
            return
        }

        if (foregroundNotificationId == null) {
            promote(notificationEntry ?: controller.startingForegroundNotification())
        }

        val latest = desiredState()
        if (!latest.running || notificationEntry == null) {
            if (latest.running && notificationEntry == null) {
                abandonRunRequest()
            }
            stopForegroundCompat(removeNotification = latest.removeNotification || notificationEntry == null)
            stopSelf()
        }
    }

    private fun promote(entry: BrowserMediaNotificationController.ForegroundMediaNotification) {
        if (foregroundNotificationId == entry.id) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                entry.id,
                entry.notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(entry.id, entry.notification)
        }
        foregroundNotificationId = entry.id
    }

    private fun requestReconcile() {
        mainHandler.post(::reconcileDesiredState)
    }

    private fun stopForegroundCompat(removeNotification: Boolean) {
        if (foregroundNotificationId == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(removeNotification)
        }
        foregroundNotificationId = null
    }

    companion object {
        private const val TAG = "HyperMediaService"
        private const val ACTION_REFRESH = "com.dadigua.hyperbrowser.media.service.REFRESH"
        private val stateLock = Any()
        private val requestState = BrowserMediaServiceRequestState()
        private var instance: BrowserMediaPlaybackService? = null

        fun refresh(context: Context) {
            val decision = synchronized(stateLock) {
                requestState.requestRun() to instance
            }
            dispatch(context.applicationContext, decision.first, decision.second)
        }

        fun stop(context: Context, removeNotification: Boolean = false) {
            val service = synchronized(stateLock) {
                requestState.requestStop(removeNotification)
                instance
            }
            service?.requestReconcile()
        }

        private fun attach(service: BrowserMediaPlaybackService) {
            synchronized(stateLock) {
                instance = service
                requestState.onServiceAttached()
            }
        }

        private fun detach(service: BrowserMediaPlaybackService) {
            val shouldRestart = synchronized(stateLock) {
                if (instance === service) instance = null
                requestState.onServiceDetached()
            }
            if (shouldRestart) {
                startService(service.applicationContext)
            }
        }

        private fun desiredState(): BrowserMediaServiceDesiredState =
            synchronized(stateLock) { requestState.desiredState() }

        private fun abandonRunRequest() {
            synchronized(stateLock) { requestState.abandonRunRequest() }
        }

        private fun dispatch(
            context: Context,
            decision: BrowserMediaServiceRunDecision,
            service: BrowserMediaPlaybackService?
        ) {
            if (decision.startService) {
                startService(context)
            } else if (decision.reconcileAttachedService) {
                service?.requestReconcile()
            }
        }

        private fun startService(context: Context) {
            val intent = Intent(context, BrowserMediaPlaybackService::class.java).setAction(ACTION_REFRESH)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { error ->
                    synchronized(stateLock) { requestState.onServiceStartFailed() }
                    Log.e(TAG, "Unable to start media playback foreground service.", error)
                }
        }
    }
}

internal data class BrowserMediaServiceDesiredState(
    val running: Boolean,
    val removeNotification: Boolean
)

internal data class BrowserMediaServiceRunDecision(
    val startService: Boolean = false,
    val reconcileAttachedService: Boolean = false
)

internal class BrowserMediaServiceRequestState {
    private var desiredRunning = false
    private var removeNotificationOnStop = false
    private var startPending = false
    private var serviceAttached = false

    fun requestRun(): BrowserMediaServiceRunDecision {
        desiredRunning = true
        removeNotificationOnStop = false
        return when {
            serviceAttached -> BrowserMediaServiceRunDecision(reconcileAttachedService = true)
            startPending -> BrowserMediaServiceRunDecision()
            else -> {
                startPending = true
                BrowserMediaServiceRunDecision(startService = true)
            }
        }
    }

    fun requestStop(removeNotification: Boolean) {
        desiredRunning = false
        removeNotificationOnStop = removeNotificationOnStop || removeNotification
    }

    fun onServiceAttached() {
        serviceAttached = true
        startPending = false
    }

    fun onServiceDetached(): Boolean {
        serviceAttached = false
        if (!desiredRunning || startPending) return false
        startPending = true
        return true
    }

    fun onServiceStartFailed() {
        if (!serviceAttached) startPending = false
    }

    fun abandonRunRequest() {
        desiredRunning = false
        removeNotificationOnStop = true
    }

    fun desiredState(): BrowserMediaServiceDesiredState =
        BrowserMediaServiceDesiredState(desiredRunning, removeNotificationOnStop)
}
