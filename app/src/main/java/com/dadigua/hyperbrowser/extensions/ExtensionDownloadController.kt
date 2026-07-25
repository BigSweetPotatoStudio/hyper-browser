package com.dadigua.hyperbrowser.extensions

import android.content.Context
import android.os.SystemClock
import com.dadigua.hyperbrowser.HyperBrowserApp
import com.dadigua.hyperbrowser.browser.DownloadHandler
import com.dadigua.hyperbrowser.browser.DownloadStore
import com.dadigua.hyperbrowser.browser.WebExtensionDownloadRegistry
import com.dadigua.hyperbrowser.gecko.GeckoDownloadRequest
import com.dadigua.hyperbrowser.gecko.GeckoRuntimeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebResponse
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class ExtensionDownloadController(context: Context) : WebExtension.DownloadDelegate {
    private val appContext = context.applicationContext
    private val runtime by lazy { GeckoRuntimeProvider.get(appContext) }
    private val executor by lazy { GeckoWebExecutor(runtime) }
    private val handler = DownloadHandler(
        appContext,
        (appContext as HyperBrowserApp).downloads
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val nextDownloadId = AtomicInteger(
        (SystemClock.elapsedRealtime() and Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
    )

    override fun onDownload(
        extension: WebExtension,
        request: WebExtension.DownloadRequest
    ): GeckoResult<WebExtension.DownloadInitData> {
        val result = GeckoResult<WebExtension.DownloadInitData>()
        scope.launch {
            runCatching {
                val response = executor.fetch(request.request, request.downloadFlags).awaitValue()
                validateResponse(response, request.allowHttpErrors)
                val contentType = response.header("content-type")
                val contentLength = if (response.header("content-encoding").isNullOrBlank()) {
                    response.header("content-length")?.toLongOrNull() ?: -1L
                } else {
                    -1L
                }
                val requestedName = request.filename
                    ?.takeIf { it.isNotBlank() }
                    ?.substringAfterLast('/')
                    ?.substringAfterLast('\\')
                val fileName = requestedName ?: DownloadHandler.fileNameFor(
                    request.request.uri,
                    response.header("content-disposition"),
                    contentType
                )
                val body = response.body ?: error("Download response is empty.")
                val download = runtime.webExtensionController.createDownload(nextId())
                    ?: error("GeckoView could not create a download.")
                var initialInfo: WebExtension.Download.Info? = null
                val entry = handler.saveResponse(
                    request = GeckoDownloadRequest(
                        url = request.request.uri,
                        fileName = fileName,
                        contentType = contentType,
                        contentLength = contentLength,
                        body = body
                    ),
                    showNotification = true,
                    onPrepared = { prepared ->
                        initialInfo = WebExtensionDownloadRegistry.register(
                            entry = prepared,
                            download = download,
                            mime = contentType,
                            referrer = request.request.referrer
                        )
                    }
                )
                WebExtension.DownloadInitData(
                    download,
                    checkNotNull(initialInfo) { "Download could not be registered." }
                ).also {
                    WebExtensionDownloadRegistry.update(entry)
                }
            }.fold(
                onSuccess = result::complete,
                onFailure = result::completeExceptionally
            )
        }
        return result
    }

    private fun validateResponse(response: WebResponse, allowHttpErrors: Boolean) {
        if (!allowHttpErrors && response.statusCode !in 200..299) {
            response.body?.close()
            error("Download failed: HTTP ${response.statusCode}")
        }
    }

    private fun WebResponse.header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun nextId(): Int =
        nextDownloadId.getAndUpdate { current ->
            if (current == Int.MAX_VALUE) 1 else current + 1
        }
}

private suspend fun <T : Any> GeckoResult<T>.awaitValue(): T =
    suspendCancellableCoroutine { continuation ->
        accept(
            { value ->
                if (continuation.isActive) {
                    continuation.resume(value ?: error("GeckoView returned no value."))
                }
            },
            { error ->
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        error ?: IllegalStateException("GeckoView request failed.")
                    )
                }
            }
        )
    }
