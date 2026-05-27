package com.dadigua.hyperbrowser.gecko

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.mozilla.geckoview.GeckoSession

data class GeckoPageState(
    val title: String = "",
    val url: String = "",
    val insecureHttp: Boolean = false
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
        if (existingSession == null) {
            session.open(runtime)
            load(initialUrl)
        }
    }

    fun load(input: String) {
        val target = normalizeUrl(input)
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
        fun normalizeUrl(input: String): String {
            val value = input.trim()
            if (value.startsWith("http://") || value.startsWith("https://")) return value
            if (value.contains(".") && !value.contains(" ")) return "https://$value"
            return "https://www.google.com/search?q=${java.net.URLEncoder.encode(value, "UTF-8")}"
        }
    }
}
