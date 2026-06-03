package com.dadigua.hyperbrowser.ui.webapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dadigua.hyperbrowser.HyperBrowserApp
import com.dadigua.hyperbrowser.data.WebAppDefinition
import com.dadigua.hyperbrowser.gecko.GeckoBrowserView
import com.dadigua.hyperbrowser.gecko.GeckoSessionController
import com.dadigua.hyperbrowser.ui.theme.HyperBrowserTheme

class WebAppActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val webAppId = intent.getStringExtra(EXTRA_WEB_APP_ID)
            ?: intent.data?.lastPathSegment
            ?: return finish()
        setContent {
            HyperBrowserTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WebAppScreen(
                        activity = this,
                        app = application as HyperBrowserApp,
                        webAppId = webAppId
                    )
                }
            }
        }
    }

    companion object {
        private const val EXTRA_WEB_APP_ID = "extra_web_app_id"

        fun intent(context: Context, webAppId: String, asDocument: Boolean): Intent {
            val flags = if (asDocument) {
                Intent.FLAG_ACTIVITY_NEW_DOCUMENT
            } else {
                0
            }
            return Intent(context, WebAppActivity::class.java)
                .putExtra(EXTRA_WEB_APP_ID, webAppId)
                .setData(Uri.parse("hyperbrowser://webapp/$webAppId"))
                .addFlags(flags)
        }
    }
}

@Composable
private fun WebAppScreen(activity: WebAppActivity, app: HyperBrowserApp, webAppId: String) {
    var webApp by remember { mutableStateOf<WebAppDefinition?>(null) }

    LaunchedEffect(webAppId) {
        webApp = app.webApps.get(webAppId)
        webApp?.let {
            app.webApps.markOpened(it.id)
            val updated = app.webApps.get(it.id) ?: it
            webApp = updated
            app.webApps.applyTaskDescription(activity, updated)
        }
    }

    val current = webApp
    if (current == null) {
        Text("WebApp not found", modifier = Modifier.padding(16.dp))
        return
    }

    val controller = remember(current.id) {
        GeckoSessionController(
            context = app,
            initialUrl = current.startUrl,
            mediaNotificationIntent = WebAppActivity.intent(app, current.id, true)
        )
    }
    val pageState by controller.state.collectAsState()

    DisposableEffect(current.id) {
        onDispose { controller.close() }
    }

    BackHandler {
        when {
            pageState.canGoBack -> controller.goBack()
            else -> controller.goBack()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            GeckoBrowserView(controller = controller, modifier = Modifier.fillMaxSize())
            WebAppLoadingProgressBar(
                loading = pageState.isLoading,
                progress = pageState.loadProgress,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }
}

@Composable
private fun WebAppLoadingProgressBar(
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
