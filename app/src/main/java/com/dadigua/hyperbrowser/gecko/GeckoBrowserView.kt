package com.dadigua.hyperbrowser.gecko

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.mozilla.geckoview.GeckoView

@Composable
fun GeckoBrowserView(controller: GeckoSessionController, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            GeckoView(context).apply {
                setSession(controller.session)
            }
        },
        update = { view ->
            if (view.session != controller.session) {
                view.setSession(controller.session)
            }
        }
    )
}
