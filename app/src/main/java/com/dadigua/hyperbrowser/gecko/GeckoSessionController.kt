package com.dadigua.hyperbrowser.gecko

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession

data class GeckoPageState(
    val title: String = "",
    val url: String = "",
    val insecureHttp: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false
)

class GeckoSessionController(
    context: Context,
    initialUrl: String,
    existingSession: GeckoSession? = null
) {
    val session: GeckoSession = existingSession ?: GeckoSession()

    private val runtime = GeckoRuntimeProvider.get(context)
    private val _state = MutableStateFlow(GeckoPageState(url = initialUrl, insecureHttp = initialUrl.startsWith("http://")))
    val state: StateFlow<GeckoPageState> = _state

    init {
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                _state.value = _state.value.copy(title = title.orEmpty())
            }
        }
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                val target = if (url == "about:blank") HOME_URL else url.orEmpty()
                if (target.isBlank()) return
                _state.value = _state.value.copy(
                    title = if (isHomeUrl(target)) "Hyper Browser" else _state.value.title,
                    url = target,
                    insecureHttp = target.startsWith("http://")
                )
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                _state.value = _state.value.copy(canGoBack = canGoBack)
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                _state.value = _state.value.copy(canGoForward = canGoForward)
            }

            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? = null
        }
        if (existingSession == null) {
            session.open(runtime)
            load(initialUrl)
        }
    }

    fun load(input: String) {
        val target = normalizeUrl(input)
        if (isHomeUrl(target)) {
            _state.value = GeckoPageState(title = "Hyper Browser", url = HOME_URL)
            session.loadUri("about:blank")
            return
        }
        _state.value = _state.value.copy(url = target, insecureHttp = target.startsWith("http://"))
        session.loadUri(target)
    }

    fun goBack() {
        runCatching { session.goBack() }
    }

    fun goForward() {
        runCatching { session.goForward() }
    }

    fun reload() {
        runCatching { session.reload() }
    }

    fun close() {
        runCatching { session.close() }
    }

    companion object {
        const val HOME_URL = "hyper://home"

        fun isHomeUrl(url: String): Boolean = url == HOME_URL
        fun isInternalUrl(url: String): Boolean = isHomeUrl(url)
        fun isBrowserLoadableUrl(url: String): Boolean =
            url.startsWith("http://") || url.startsWith("https://") || url.startsWith("moz-extension://")

        fun normalizeUrl(input: String): String {
            val value = input.trim()
            if (value.isBlank()) return HOME_URL
            if (isHomeUrl(value)) return HOME_URL
            if (isBrowserLoadableUrl(value)) return value
            if (value.contains(".") && !value.contains(" ")) return "https://$value"
            return "https://www.google.com/search?q=${java.net.URLEncoder.encode(value, "UTF-8")}"
        }
    }
}
