package com.dadigua.hyperbrowser.gecko

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

@Composable
fun GeckoBrowserView(controller: GeckoSessionController, modifier: Modifier = Modifier) {
    GeckoSessionView(
        session = controller.session,
        modifier = modifier,
        onViewChanged = controller::attachView
    )
}

@Composable
fun GeckoSessionView(
    session: GeckoSession,
    modifier: Modifier = Modifier,
    viewBackend: Int? = null,
    onViewChanged: (GeckoView?) -> Unit = {}
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            GeckoView(context).apply {
                viewBackend?.let { setViewBackend(it) }
                setSession(session)
                onViewChanged(this)
            }
        },
        update = { view ->
            if (view.session != session) {
                view.setSession(session)
            }
            onViewChanged(view)
        }
    )
}
