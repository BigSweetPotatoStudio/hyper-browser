package com.dadigua.hyperbrowser.gecko

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import java.net.URLEncoder

data class GeckoPageState(
    val title: String = "",
    val url: String = "",
    val insecureHttp: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isLoading: Boolean = false
)

class GeckoSessionController(
    context: Context,
    initialUrl: String,
    existingSession: GeckoSession? = null,
    private val onHyperRoute: (HyperRoute) -> Unit = {},
    private val onHyperBridgeMessage: ((org.json.JSONObject) -> org.json.JSONObject)? = null
) {
    var session: GeckoSession = existingSession ?: GeckoSession()
        private set

    private val appContext = context.applicationContext
    private val runtime = GeckoRuntimeProvider.get(context)
    private val _state = MutableStateFlow(GeckoPageState(url = initialUrl, insecureHttp = initialUrl.startsWith("http://")))
    private val _sessionChangeVersion = MutableStateFlow(0)
    private var currentRawUrl: String = initialUrl
    private var lastLoadTarget: String = initialUrl
    private var view: GeckoView? = null
    private var sessionCrashed = false
    private var automaticRecoveryTarget: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    val state: StateFlow<GeckoPageState> = _state
    val sessionChangeVersion: StateFlow<Int> = _sessionChangeVersion

    init {
        configureSession(session)
        onHyperBridgeMessage?.let { HyperBridge.register(session, it) }
        if (existingSession == null) {
            session.open(runtime)
            load(initialUrl)
        }
    }

    private fun configureSession(targetSession: GeckoSession) {
        targetSession.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                _state.value = _state.value.copy(title = title.orEmpty())
            }

            override fun onCrash(session: GeckoSession) {
                recoverAfterContentLoss()
            }

            override fun onKill(session: GeckoSession) {
                recoverAfterContentLoss()
            }
        }
        targetSession.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                _state.value = _state.value.copy(isLoading = true)
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                _state.value = _state.value.copy(isLoading = false)
                if (success) {
                    sessionCrashed = false
                    automaticRecoveryTarget = null
                }
            }
        }
        targetSession.navigationDelegate = object : GeckoSession.NavigationDelegate {
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
                        isSearchUrl(target) -> "Search"
                        isSettingsUrl(target) -> "设置"
                        isAppsUrl(target) -> "Apps"
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
                return null
            }
        }
    }

    fun load(input: String, searchUrlTemplate: String? = null) {
        val target = normalizeUrl(input, searchUrlTemplate)
        lastLoadTarget = target
        sessionCrashed = false
        automaticRecoveryTarget = null
        if (isHomeUrl(target)) {
            loadHome()
            return
        }
        if (isSearchUrl(target)) {
            loadSearch()
            return
        }
        if (isSettingsUrl(target)) {
            loadSettings()
            return
        }
        if (isAppsUrl(target)) {
            onHyperRoute(HyperRoute.Apps)
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

    fun loadHome(historyJson: String? = null) {
        lastLoadTarget = HOME_URL
        loadInternalPage(HOME_URL, "Hyper Browser", "home.html", historyJson)
    }

    fun loadSearch(query: String = "") {
        lastLoadTarget = SEARCH_URL
        _state.value = GeckoPageState(title = "Search", url = SEARCH_URL)
        val loadPage = {
            HyperBridge.pageUrl("search.html")?.let { url ->
                currentRawUrl = url
                session.loadUri(urlWithHashParams(url, mapOf("q" to query)))
            }
        }
        if (HyperBridge.pageUrl("search.html") == null) {
            HyperBridge.ensureInstalled(appContext) { loadPage() }
        } else {
            loadPage()
        }
    }

    fun loadSettings() {
        lastLoadTarget = SETTINGS_URL
        loadInternalPage(SETTINGS_URL, "设置", "settings.html", null)
    }

    fun loadApps(appsJson: String? = null) {
        lastLoadTarget = APPS_URL
        loadInternalPage(APPS_URL, "Apps", "apps.html", appsJson)
    }

    fun loadBookmarks(bookmarksJson: String? = null) {
        lastLoadTarget = BOOKMARKS_URL
        loadInternalPage(BOOKMARKS_URL, "Bookmarks", "bookmarks.html", bookmarksJson)
    }

    fun loadHistory(historyJson: String? = null) {
        lastLoadTarget = HISTORY_URL
        loadInternalPage(HISTORY_URL, "History", "history.html", historyJson)
    }

    fun goBack() {
        runCatching { session.goBack() }
    }

    fun goForward() {
        runCatching { session.goForward() }
    }

    fun reload() {
        if (sessionCrashed || !session.isOpen) {
            recoverSession(force = true)
            return
        }
        runCatching { session.reload(GeckoSession.LOAD_FLAGS_BYPASS_CACHE) }
            .onFailure { recoverSession(force = true) }
    }

    fun attachView(view: GeckoView?) {
        this.view = view
    }

    fun capturePixels(onCaptured: (Bitmap?) -> Unit) {
        val currentView = view
        if (currentView == null) {
            onCaptured(null)
            return
        }
        currentView.capturePixels().accept({ bitmap ->
            onCaptured(bitmap)
        }, {
            onCaptured(null)
        })
    }

    fun close() {
        onHyperBridgeMessage?.let { HyperBridge.unregister(session) }
        runCatching { session.close() }
    }

    private fun loadInternalPage(semanticUrl: String, title: String, page: String, bootstrapJson: String?) {
        lastLoadTarget = semanticUrl
        sessionCrashed = false
        _state.value = GeckoPageState(title = title, url = semanticUrl)
        val loadPage = {
            HyperBridge.pageUrl(page)?.let { url ->
                currentRawUrl = url
                session.loadUri(assetUrlWithData(url, bootstrapJson))
            }
        }
        if (HyperBridge.pageUrl(page) == null) {
            HyperBridge.ensureInstalled(appContext) { loadPage() }
        } else {
            loadPage()
        }
    }

    private fun recoverAfterContentLoss() {
        val target = currentRecoveryTarget()
        sessionCrashed = true
        _state.value = _state.value.copy(isLoading = false)
        if (automaticRecoveryTarget == target) return
        automaticRecoveryTarget = target
        mainHandler.post { recoverSession(force = false) }
    }

    private fun recoverSession(force: Boolean) {
        val target = currentRecoveryTarget()
        if (force) {
            automaticRecoveryTarget = null
        }
        onHyperBridgeMessage?.let { HyperBridge.unregister(session) }
        runCatching { session.close() }
        session = GeckoSession()
        configureSession(session)
        onHyperBridgeMessage?.let { HyperBridge.register(session, it) }
        session.open(runtime)
        view?.setSession(session)
        _sessionChangeVersion.value = _sessionChangeVersion.value + 1
        sessionCrashed = false
        load(target)
        if (!force) {
            automaticRecoveryTarget = target
        }
    }

    private fun currentRecoveryTarget(): String {
        val visibleUrl = _state.value.url
        return when {
            visibleUrl.isNotBlank() -> visibleUrl
            currentRawUrl.isNotBlank() -> semanticUrlForRawUrl(currentRawUrl)
            else -> lastLoadTarget
        }
    }

    companion object {
        const val HOME_URL = "hyper://home"
        const val SEARCH_URL = "hyper://search"
        const val SETTINGS_URL = "hyper://settings"
        const val APPS_URL = "hyper://apps"
        const val BOOKMARKS_URL = "hyper://bookmarks"
        const val HISTORY_URL = "hyper://history"
        private const val HOME_ASSET_URL = "resource://android/assets/home.html"
        private const val SEARCH_ASSET_URL = "resource://android/assets/search.html"
        private const val SETTINGS_ASSET_URL = "resource://android/assets/settings.html"
        private const val APPS_ASSET_URL = "resource://android/assets/apps.html"
        private const val BOOKMARKS_ASSET_URL = "resource://android/assets/bookmarks.html"
        private const val HISTORY_ASSET_URL = "resource://android/assets/history.html"

        fun isHomeUrl(url: String): Boolean = url == HOME_URL
        fun isSearchUrl(url: String): Boolean = url == SEARCH_URL
        fun isSettingsUrl(url: String): Boolean = url == SETTINGS_URL
        fun isAppsUrl(url: String): Boolean = url == APPS_URL
        fun isBookmarksUrl(url: String): Boolean = url == BOOKMARKS_URL
        fun isHistoryUrl(url: String): Boolean = url == HISTORY_URL
        fun isHomeDocumentUrl(url: String): Boolean =
            url.startsWith(HOME_ASSET_URL) || HyperBridge.isPageUrl(url, "home.html")
        fun isSearchDocumentUrl(url: String): Boolean =
            url.startsWith(SEARCH_ASSET_URL) || HyperBridge.isPageUrl(url, "search.html")
        fun isSettingsDocumentUrl(url: String): Boolean =
            url.startsWith(SETTINGS_ASSET_URL) || HyperBridge.isPageUrl(url, "settings.html")
        fun isAppsDocumentUrl(url: String): Boolean =
            url.startsWith(APPS_ASSET_URL) || HyperBridge.isPageUrl(url, "apps.html")
        fun isBookmarksDocumentUrl(url: String): Boolean =
            url.startsWith(BOOKMARKS_ASSET_URL) || HyperBridge.isPageUrl(url, "bookmarks.html")
        fun isHistoryDocumentUrl(url: String): Boolean =
            url.startsWith(HISTORY_ASSET_URL) || HyperBridge.isPageUrl(url, "history.html")
        fun isInternalUrl(url: String): Boolean =
            isHomeUrl(url) || isSearchUrl(url) || isSettingsUrl(url) || isAppsUrl(url) ||
                isBookmarksUrl(url) || isHistoryUrl(url) ||
                isHomeDocumentUrl(url) || isSearchDocumentUrl(url) || isSettingsDocumentUrl(url) ||
                isAppsDocumentUrl(url) || isBookmarksDocumentUrl(url) || isHistoryDocumentUrl(url)
        fun isBrowserLoadableUrl(url: String): Boolean =
            url.startsWith("http://") || url.startsWith("https://") || url.startsWith("moz-extension://")

        fun normalizeUrl(input: String, searchUrlTemplate: String? = null): String {
            val value = input.trim()
            if (value.isBlank()) return HOME_URL
            if (isHomeUrl(value)) return HOME_URL
            if (isSearchUrl(value)) return SEARCH_URL
            if (isSettingsUrl(value)) return SETTINGS_URL
            if (isAppsUrl(value)) return APPS_URL
            if (isBookmarksUrl(value)) return BOOKMARKS_URL
            if (isHistoryUrl(value)) return HISTORY_URL
            if (isBrowserLoadableUrl(value)) return value
            if (value.contains(".") && !value.contains(" ")) return "https://$value"
            val encodedQuery = java.net.URLEncoder.encode(value, "UTF-8")
            val template = searchUrlTemplate
                ?.takeIf { it.contains("%s") }
                ?: "https://www.google.com/search?q=%s"
            return template.replace("%s", encodedQuery)
        }

        private fun semanticUrlForRawUrl(url: String): String =
            when {
                isHomeDocumentUrl(url) -> HOME_URL
                isSearchDocumentUrl(url) -> SEARCH_URL
                isSettingsDocumentUrl(url) -> SETTINGS_URL
                isAppsDocumentUrl(url) -> APPS_URL
                isBookmarksDocumentUrl(url) -> BOOKMARKS_URL
                isHistoryDocumentUrl(url) -> HISTORY_URL
                else -> url
            }

        private fun routeFromUri(uri: String): HyperRoute? {
            if (!uri.startsWith("hyper://")) return null
            return when {
                uri == HOME_URL -> HyperRoute.Home
                uri == SEARCH_URL -> HyperRoute.Search
                uri == SETTINGS_URL -> HyperRoute.Settings
                uri == APPS_URL -> HyperRoute.Apps
                uri == BOOKMARKS_URL -> HyperRoute.Bookmarks
                uri == HISTORY_URL -> HyperRoute.History
                else -> null
            }
        }

        private fun assetUrlWithData(assetUrl: String, json: String?): String {
            if (json == null) return assetUrl
            val encoded = URLEncoder.encode(json, "UTF-8").replace("+", "%20")
            return "$assetUrl#data=$encoded"
        }

        private fun urlWithHashParams(assetUrl: String, params: Map<String, String>): String {
            val encoded = params
                .filterValues { it.isNotBlank() }
                .map { (key, value) ->
                    "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8").replace("+", "%20")}"
                }
                .joinToString("&")
            if (encoded.isBlank()) return assetUrl
            return "$assetUrl#$encoded"
        }
    }
}

sealed interface HyperRoute {
    data object Home : HyperRoute
    data object Search : HyperRoute
    data object Settings : HyperRoute
    data object Apps : HyperRoute
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

    sealed interface Apps : HyperCommand {
        data class Open(val id: String) : Apps
        data class Pin(val id: String) : Apps
        data class Edit(val id: String, val name: String, val startUrl: String) : Apps
        data class Delete(val id: String) : Apps
    }

    sealed interface Panel : HyperCommand {
        data object Extensions : Panel
    }
}
