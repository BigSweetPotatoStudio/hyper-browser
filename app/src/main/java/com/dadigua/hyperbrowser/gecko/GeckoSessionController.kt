package com.dadigua.hyperbrowser.gecko

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import java.net.URLDecoder
import java.net.URLEncoder

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
    existingSession: GeckoSession? = null,
    private val onHyperRoute: (HyperRoute) -> Unit = {},
    private val onHyperCommand: (HyperCommand) -> Unit = {}
) {
    val session: GeckoSession = existingSession ?: GeckoSession()

    private val runtime = GeckoRuntimeProvider.get(context)
    private val _state = MutableStateFlow(GeckoPageState(url = initialUrl, insecureHttp = initialUrl.startsWith("http://")))
    private var currentRawUrl: String = initialUrl
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
                val rawUrl = url.orEmpty()
                currentRawUrl = rawUrl
                val target = semanticUrlForRawUrl(rawUrl)
                if (target.isBlank()) return
                _state.value = _state.value.copy(
                    title = when {
                        isHomeUrl(target) -> "Hyper Browser"
                        isBookmarksUrl(target) -> "Bookmarks"
                        isHistoryUrl(target) -> "History"
                        else -> _state.value.title
                    },
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
            ): GeckoResult<AllowOrDeny>? {
                routeFromUri(request.uri)?.let { route ->
                    onHyperRoute(route)
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                if (!request.uri.startsWith(COMMAND_PREFIX)) return null
                if (!isTrustedInternalRawUrl(currentRawUrl)) {
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                commandFromUri(request.uri)?.let(onHyperCommand)
                return GeckoResult.fromValue(AllowOrDeny.DENY)
            }
        }
        if (existingSession == null) {
            session.open(runtime)
            load(initialUrl)
        }
    }

    fun load(input: String) {
        val target = normalizeUrl(input)
        if (isHomeUrl(target)) {
            loadHome()
            return
        }
        if (isBookmarksUrl(target)) {
            onHyperRoute(HyperRoute.Bookmarks)
            return
        }
        if (isHistoryUrl(target)) {
            onHyperRoute(HyperRoute.History)
            return
        }
        _state.value = _state.value.copy(url = target, insecureHttp = target.startsWith("http://"))
        session.loadUri(target)
    }

    fun loadHome(historyJson: String = "[]") {
        val encoded = URLEncoder.encode(historyJson, "UTF-8").replace("+", "%20")
        _state.value = GeckoPageState(title = "Hyper Browser", url = HOME_URL)
        session.loadUri("$HOME_ASSET_URL#data=$encoded")
    }

    fun loadBookmarks(bookmarksJson: String) {
        val encoded = URLEncoder.encode(bookmarksJson, "UTF-8").replace("+", "%20")
        _state.value = GeckoPageState(title = "Bookmarks", url = BOOKMARKS_URL)
        session.loadUri("$BOOKMARKS_ASSET_URL#data=$encoded")
    }

    fun loadHistory(historyJson: String) {
        val encoded = URLEncoder.encode(historyJson, "UTF-8").replace("+", "%20")
        _state.value = GeckoPageState(title = "History", url = HISTORY_URL)
        session.loadUri("$HISTORY_ASSET_URL#data=$encoded")
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
        const val BOOKMARKS_URL = "hyper://bookmarks"
        const val HISTORY_URL = "hyper://history"
        private const val COMMAND_PREFIX = "hyper://command/"
        private const val HOME_ASSET_URL = "resource://android/assets/home.html"
        private const val BOOKMARKS_ASSET_URL = "resource://android/assets/bookmarks.html"
        private const val HISTORY_ASSET_URL = "resource://android/assets/history.html"

        fun isHomeUrl(url: String): Boolean = url == HOME_URL
        fun isBookmarksUrl(url: String): Boolean = url == BOOKMARKS_URL
        fun isHistoryUrl(url: String): Boolean = url == HISTORY_URL
        fun isHomeDocumentUrl(url: String): Boolean = url.startsWith(HOME_ASSET_URL)
        fun isBookmarksDocumentUrl(url: String): Boolean = url.startsWith(BOOKMARKS_ASSET_URL)
        fun isHistoryDocumentUrl(url: String): Boolean = url.startsWith(HISTORY_ASSET_URL)
        fun isInternalUrl(url: String): Boolean =
            isHomeUrl(url) || isBookmarksUrl(url) || isHistoryUrl(url) ||
                isHomeDocumentUrl(url) || isBookmarksDocumentUrl(url) || isHistoryDocumentUrl(url)
        fun isBrowserLoadableUrl(url: String): Boolean =
            url.startsWith("http://") || url.startsWith("https://") || url.startsWith("moz-extension://")

        fun normalizeUrl(input: String): String {
            val value = input.trim()
            if (value.isBlank()) return HOME_URL
            if (isHomeUrl(value)) return HOME_URL
            if (isBookmarksUrl(value)) return BOOKMARKS_URL
            if (isHistoryUrl(value)) return HISTORY_URL
            if (isBrowserLoadableUrl(value)) return value
            if (value.contains(".") && !value.contains(" ")) return "https://$value"
            return "https://www.google.com/search?q=${java.net.URLEncoder.encode(value, "UTF-8")}"
        }

        private fun semanticUrlForRawUrl(url: String): String =
            when {
                isHomeDocumentUrl(url) -> HOME_URL
                isBookmarksDocumentUrl(url) -> BOOKMARKS_URL
                isHistoryDocumentUrl(url) -> HISTORY_URL
                else -> url
            }

        private fun isTrustedInternalRawUrl(url: String): Boolean =
            isHomeDocumentUrl(url) || isBookmarksDocumentUrl(url) || isHistoryDocumentUrl(url)

        private fun routeFromUri(uri: String): HyperRoute? {
            if (!uri.startsWith("hyper://")) return null
            return when {
                uri == HOME_URL -> HyperRoute.Home
                uri == BOOKMARKS_URL -> HyperRoute.Bookmarks
                uri == HISTORY_URL -> HyperRoute.History
                else -> null
            }
        }

        private fun commandFromUri(uri: String): HyperCommand? =
            when {
                uri.startsWith("${COMMAND_PREFIX}search/submit?") -> {
                    val query = uri.substringAfter("query=", "")
                    HyperCommand.Search.Submit(URLDecoder.decode(query, "UTF-8"))
                }
                uri.startsWith("${COMMAND_PREFIX}bookmarks/open?") -> {
                    val url = uri.substringAfter("url=", "")
                    HyperCommand.Bookmarks.Open(URLDecoder.decode(url, "UTF-8"))
                }
                uri.startsWith("${COMMAND_PREFIX}bookmarks/remove?") -> {
                    val url = uri.substringAfter("url=", "")
                    HyperCommand.Bookmarks.Remove(URLDecoder.decode(url, "UTF-8"))
                }
                uri.startsWith("${COMMAND_PREFIX}bookmarks/edit?") -> {
                    val query = uri.substringAfter("?", "")
                    val params = query.split("&").mapNotNull {
                        val key = it.substringBefore("=", "")
                        val value = it.substringAfter("=", "")
                        if (key.isBlank()) null else key to URLDecoder.decode(value, "UTF-8")
                    }.toMap()
                    HyperCommand.Bookmarks.Edit(
                        oldUrl = params["oldUrl"].orEmpty(),
                        title = params["title"].orEmpty(),
                        url = params["url"].orEmpty()
                    )
                }
                uri.startsWith("${COMMAND_PREFIX}history/open?") -> {
                    val url = uri.substringAfter("url=", "")
                    HyperCommand.History.Open(URLDecoder.decode(url, "UTF-8"))
                }
                uri.startsWith("${COMMAND_PREFIX}history/remove?") -> {
                    val url = uri.substringAfter("url=", "")
                    HyperCommand.History.Remove(URLDecoder.decode(url, "UTF-8"))
                }
                uri == "${COMMAND_PREFIX}history/clear" -> HyperCommand.History.Clear
                uri == "${COMMAND_PREFIX}panel/extensions" -> HyperCommand.Panel.Extensions
                else -> null
            }
    }
}

sealed interface HyperRoute {
    data object Home : HyperRoute
    data object Bookmarks : HyperRoute
    data object History : HyperRoute
}

sealed interface HyperCommand {
    sealed interface Search : HyperCommand {
        data class Submit(val query: String) : Search
    }

    sealed interface Bookmarks : HyperCommand {
        data class Open(val url: String) : Bookmarks
        data class Remove(val url: String) : Bookmarks
        data class Edit(
            val oldUrl: String,
            val title: String,
            val url: String
        ) : Bookmarks
    }

    sealed interface History : HyperCommand {
        data class Open(val url: String) : History
        data class Remove(val url: String) : History
        data object Clear : History
    }

    sealed interface Panel : HyperCommand {
        data object Extensions : Panel
    }
}
