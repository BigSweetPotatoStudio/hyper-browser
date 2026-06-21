package com.dadigua.hyperbrowser.gecko

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

object HyperBridge {
    private const val TAG = "HyperBridge"
    private const val EXTENSION_LOCATION = "resource://android/assets/"
    private const val EXTENSION_ID = "hyper-browser-internal@dadigua.com"
    private const val NATIVE_APP = "hyperBrowser"
    private val INTERNAL_PAGE_MESSAGE_TYPES = setOf(
        "data.home",
        "data.search",
        "data.bookmarks",
        "data.history",
        "data.apps",
        "data.settings",
        "search.submit",
        "settings.searchEngine.update",
        "settings.toolbarPosition.update",
        "settings.backgroundVideoEnhancement.update",
        "settings.openNewTabsInCurrentTab.update",
        "settings.locale.update",
        "settings.privacy.update",
        "settings.batteryOptimizationState",
        "settings.openBatteryOptimization",
        "backup.export",
        "backup.import",
        "update.check",
        "update.skip",
        "update.clearSkip",
        "update.downloadState",
        "update.install",
        "bookmarks.open",
        "bookmarks.remove",
        "bookmarks.edit",
        "history.open",
        "history.remove",
        "history.clear",
        "apps.open",
        "apps.pin",
        "apps.edit",
        "apps.delete",
        "panel.extensions"
    )
    private val CONTENT_SCRIPT_MESSAGE_TYPES = setOf(
        "pullRefresh.touch",
        "media.keepAlive.start",
        "media.keepAlive.pause",
        "media.keepAlive.stop",
        "settings.backgroundVideoEnhancement.enabled"
    )

    @Volatile
    private var extension: WebExtension? = null
    private var installing = false
    private val pendingReady = mutableListOf<(WebExtension) -> Unit>()
    private val handlers = mutableMapOf<GeckoSession, (JSONObject) -> GeckoResult<Any>>()
    private val fallbackEligibleHandlers = linkedMapOf<GeckoSession, (JSONObject) -> GeckoResult<Any>>()
    private var fallbackHandler: ((JSONObject) -> GeckoResult<Any>)? = null
    private var fallbackSession: GeckoSession? = null
    private val messageDelegate = object : WebExtension.MessageDelegate {
        override fun onMessage(
            nativeApp: String,
            message: Any,
            sender: WebExtension.MessageSender
        ): GeckoResult<Any> = handleNativeMessage(nativeApp, message, sender)
    }

    fun ensureInstalled(context: Context, onReady: (WebExtension) -> Unit = {}) {
        extension?.let {
            onReady(it)
            return
        }
        synchronized(this) {
            extension?.let {
                onReady(it)
                return
            }
            pendingReady += onReady
            if (installing) return
            installing = true
        }

        GeckoRuntimeProvider.get(context).webExtensionController
            .ensureBuiltIn(EXTENSION_LOCATION, EXTENSION_ID)
            .accept({ installed ->
                if (installed == null) {
                    synchronized(this) {
                        installing = false
                        pendingReady.clear()
                    }
                    Log.e(TAG, "Internal extension install returned null")
                    return@accept
                }
                installed.setMessageDelegate(messageDelegate, NATIVE_APP)
                val callbacks = synchronized(this) {
                    extension = installed
                    installing = false
                    pendingReady.toList().also { pendingReady.clear() }
                }
                handlers.keys.forEach { attachSessionDelegate(it, installed) }
                callbacks.forEach { it(installed) }
            }, { throwable ->
                synchronized(this) {
                    installing = false
                    pendingReady.clear()
                }
                Log.e(TAG, "Failed to install internal extension", throwable)
            })
    }

    fun register(session: GeckoSession, handler: (JSONObject) -> GeckoResult<Any>, useAsFallback: Boolean = true) {
        handlers[session] = handler
        if (useAsFallback) {
            fallbackEligibleHandlers.remove(session)
            fallbackEligibleHandlers[session] = handler
            fallbackSession = session
            fallbackHandler = handler
        }
        attachSessionDelegate(session)
    }

    fun unregister(session: GeckoSession) {
        handlers.remove(session)
        fallbackEligibleHandlers.remove(session)
        if (fallbackSession == session) {
            val fallback = fallbackEligibleHandlers.entries.lastOrNull()
            fallbackSession = fallback?.key
            fallbackHandler = fallback?.value
        }
        if (handlers.isEmpty()) {
            fallbackEligibleHandlers.clear()
            fallbackSession = null
            fallbackHandler = null
        }
        detachSessionDelegate(session)
    }

    fun pageUrl(page: String): String? =
        extension?.metaData?.baseUrl?.let { "$it$page" }

    fun isPageUrl(url: String, page: String): Boolean =
        pageUrl(page)?.let { url.startsWith(it) } == true

    fun isInternalPageUrl(url: String): Boolean =
        extension?.metaData?.baseUrl?.let { url.startsWith(it) } == true

    private fun handleNativeMessage(
        nativeApp: String,
        message: Any,
        sender: WebExtension.MessageSender
    ): GeckoResult<Any> {
        if (nativeApp != NATIVE_APP) {
            return GeckoResult.fromValue(error("Rejected bridge message.").toString())
        }
        val request = message as? JSONObject
            ?: return GeckoResult.fromValue(error("Invalid bridge payload.").toString())
        val type = request.optString("type").trim()
        if (!isKnownMessageType(type)) {
            Log.w(TAG, "Rejected unknown bridge message type=$type sender=${sender.url}")
            return GeckoResult.fromValue(error("Unknown bridge message.").toString())
        }
        val payload = normalizedPayload(request, sender.url)
            ?: return GeckoResult.fromValue(error("Invalid bridge payload.").toString())
        val normalizedRequest = JSONObject()
            .put("type", type)
            .put("payload", payload)
        if (!isTrustedSender(sender, request)) {
            Log.w(TAG, "Rejected bridge message type=$type sender=${sender.url}")
            return GeckoResult.fromValue(error("Rejected bridge message.").toString())
        }
        val handler = handlers[sender.session] ?: fallbackHandler.takeIf {
            canUseFallbackHandler(sender, type)
        }.also {
            if (it != null) {
                Log.d(TAG, "Using fallback bridge handler type=$type sender=${sender.url}")
            }
        }
        if (handler == null) {
            Log.w(TAG, "No bridge handler type=$type sender=${sender.url}")
            return GeckoResult.fromValue(error("No bridge handler for session.").toString())
        }
        return handler(normalizedRequest)
    }

    private fun attachSessionDelegate(session: GeckoSession, installed: WebExtension? = extension) {
        installed ?: return
        runCatching {
            session.webExtensionController.setMessageDelegate(installed, messageDelegate, NATIVE_APP)
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to attach session bridge delegate", throwable)
        }
    }

    private fun detachSessionDelegate(session: GeckoSession) {
        val installed = extension ?: return
        runCatching {
            session.webExtensionController.setMessageDelegate(installed, null, NATIVE_APP)
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to detach session bridge delegate", throwable)
        }
    }

    private fun isKnownMessageType(type: String): Boolean =
        type in INTERNAL_PAGE_MESSAGE_TYPES || type in CONTENT_SCRIPT_MESSAGE_TYPES

    private fun isTrustedSender(sender: WebExtension.MessageSender, request: JSONObject): Boolean {
        val type = request.optString("type").trim()
        if (type in INTERNAL_PAGE_MESSAGE_TYPES) return isInternalPageUrl(sender.url)
        if (type in CONTENT_SCRIPT_MESSAGE_TYPES) {
            return sender.url.startsWith("https://") || sender.url.startsWith("http://")
        }
        return false
    }

    private fun canUseFallbackHandler(sender: WebExtension.MessageSender, type: String): Boolean =
        isInternalPageUrl(sender.url) && type in INTERNAL_PAGE_MESSAGE_TYPES

    private fun normalizedPayload(request: JSONObject, senderUrl: String): JSONObject? {
        val rawPayload = if (request.has("payload") && !request.isNull("payload")) {
            request.opt("payload")
        } else {
            JSONObject()
        }
        val payload = rawPayload as? JSONObject ?: return null
        val normalized = JSONObject()
        val keys = payload.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = payload.opt(key)
            if (value != null &&
                value != JSONObject.NULL &&
                value !is String &&
                value !is Number &&
                value !is Boolean
            ) {
                return null
            }
            normalized.put(key, value)
        }
        normalized.put("sourceUrl", senderUrl)
        return normalized
    }

    private fun error(message: String): JSONObject =
        JSONObject().put("ok", false).put("error", message)
}
