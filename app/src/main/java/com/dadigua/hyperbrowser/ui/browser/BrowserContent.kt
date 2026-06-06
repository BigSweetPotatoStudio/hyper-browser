package com.dadigua.hyperbrowser.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dadigua.hyperbrowser.browser.BrowserSettings
import com.dadigua.hyperbrowser.extensions.ExtensionPopupState
import com.dadigua.hyperbrowser.gecko.GeckoBrowserView
import com.dadigua.hyperbrowser.gecko.GeckoSessionController

@Composable
internal fun BrowserContent(
    controller: GeckoSessionController,
    tabId: String,
    extensionPopup: ExtensionPopupState?,
    onClosePopup: () -> Unit,
    modifier: Modifier = Modifier
) {
    val contentPageState by controller.state.collectAsState()
    Box(modifier = modifier.fillMaxSize()) {
        key(tabId) {
            GeckoBrowserView(controller = controller, modifier = Modifier.fillMaxSize())
        }
        TopPageLoadingProgressBar(
            loading = contentPageState.isLoading,
            progress = contentPageState.loadProgress,
            modifier = Modifier.align(Alignment.TopCenter)
        )
        extensionPopup?.let { popup ->
            ExtensionPopupOverlay(
                popup = popup,
                onClose = onClosePopup
            )
        }
    }
}

@Composable
private fun TopPageLoadingProgressBar(
    loading: Boolean,
    progress: Int,
    modifier: Modifier = Modifier
) {
    if (!loading) return
    val normalizedProgress = (progress.coerceIn(0, 100) / 100f).coerceAtLeast(0.08f)
    LinearProgressIndicator(
        progress = { normalizedProgress },
        modifier = modifier
            .fillMaxWidth()
            .height(2.dp),
        color = MaterialTheme.colorScheme.primary,
        trackColor = Color.Transparent
    )
}

@Composable
internal fun BrowserTip(
    message: String?,
    toolbarPosition: String,
    modifier: Modifier = Modifier
) {
    if (message == null) return
    val bottomPadding = if (toolbarPosition == BrowserSettings.TOOLBAR_POSITION_BOTTOM) 72.dp else 24.dp
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 18.dp, end = 18.dp, bottom = bottomPadding),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0xE6202124))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
