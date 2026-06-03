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
    private var fallbackHandler: ((JSONObject) -> JSONObject)? = null

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
                installed.setMessageDelegate(
                    object : WebExtension.MessageDelegate {
                        override fun onMessage(
                            nativeApp: String,
                            message: Any,
                            sender: WebExtension.MessageSender
                        ): GeckoResult<Any> {
                            if (nativeApp != NATIVE_APP) {
                                return GeckoResult.fromValue(error("Rejected bridge message.").toString())
                            }
                            val request = message as? JSONObject
                                ?: return GeckoResult.fromValue(error("Invalid bridge payload.").toString())
                            if (!isTrustedSender(sender, request)) {
                                return GeckoResult.fromValue(error("Rejected bridge message.").toString())
                            }
                            val handler = handlers[sender.session] ?: fallbackHandler
                                ?: return GeckoResult.fromValue(error("No bridge handler for session.").toString())
                            return GeckoResult.fromValue(handler(request).toString())
                        }
                    },
                    NATIVE_APP
                )
                val callbacks = synchronized(this) {
                    extension = installed
                    installing = false
                    pendingReady.toList().also { pendingReady.clear() }
                }
                callbacks.forEach { it(installed) }
            }, { throwable ->
                synchronized(this) {
                    installing = false
                    pendingReady.clear()
                }
                Log.e(TAG, "Failed to install internal extension", throwable)
            })
    }

    fun register(session: GeckoSession, handler: (JSONObject) -> JSONObject) {
        handlers[session] = handler
        fallbackHandler = handler
    }

    fun unregister(session: GeckoSession) {
        handlers.remove(session)
        if (handlers.isEmpty()) fallbackHandler = null
    }

    fun pageUrl(page: String): String? =
        extension?.metaData?.baseUrl?.let { "$it$page" }

    fun isPageUrl(url: String, page: String): Boolean =
        pageUrl(page)?.let { url.startsWith(it) } == true

    fun isInternalPageUrl(url: String): Boolean =
        extension?.metaData?.baseUrl?.let { url.startsWith(it) } == true

    private fun isTrustedSender(sender: WebExtension.MessageSender, request: JSONObject): Boolean {
        if (isInternalPageUrl(sender.url)) return true
        val type = request.optString("type")
        return type == "pullRefresh.touch" &&
            (sender.url.startsWith("https://") || sender.url.startsWith("http://"))
    }

    private fun error(message: String): JSONObject =
        JSONObject().put("ok", false).put("error", message)
}
