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

    @Volatile
    private var extension: WebExtension? = null
    private var installing = false
    private val pendingReady = mutableListOf<(WebExtension) -> Unit>()
    private val handlers = mutableMapOf<GeckoSession, (JSONObject) -> JSONObject>()
    private val fallbackEligibleHandlers = linkedMapOf<GeckoSession, (JSONObject) -> JSONObject>()
    private var fallbackHandler: ((JSONObject) -> JSONObject)? = null
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

    fun register(session: GeckoSession, handler: (JSONObject) -> JSONObject, useAsFallback: Boolean = true) {
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
        val type = request.optString("type")
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
        return GeckoResult.fromValue(handler(request).toString())
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

    private fun isTrustedSender(sender: WebExtension.MessageSender, request: JSONObject): Boolean {
        if (isInternalPageUrl(sender.url)) return true
        val type = request.optString("type")
        return (type == "pullRefresh.touch" ||
            type == "media.keepAlive.start" ||
            type == "media.keepAlive.pause" ||
            type == "media.keepAlive.stop" ||
            type == "settings.backgroundVideoEnhancement.enabled") &&
            (sender.url.startsWith("https://") || sender.url.startsWith("http://"))
    }

    private fun canUseFallbackHandler(sender: WebExtension.MessageSender, type: String): Boolean =
        isInternalPageUrl(sender.url) || type == "pullRefresh.touch"

    private fun error(message: String): JSONObject =
        JSONObject().put("ok", false).put("error", message)
}
