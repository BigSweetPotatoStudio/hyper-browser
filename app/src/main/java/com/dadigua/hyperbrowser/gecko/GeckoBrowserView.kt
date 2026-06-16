package com.dadigua.hyperbrowser.gecko

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import kotlin.math.abs

@Composable
fun GeckoBrowserView(
    controller: GeckoSessionController,
    modifier: Modifier = Modifier,
    imeAvoidanceEnabled: Boolean = true
) {
    val pageState by controller.state.collectAsState()
    val sessionChangeVersion by controller.sessionChangeVersion.collectAsState()
    val pullRefreshEnabled = !GeckoSessionController.isInternalUrl(pageState.url)
    val density = LocalDensity.current
    val imeBottomPx = if (imeAvoidanceEnabled) WindowInsets.ime.getBottom(density) else 0
    val triggerDistancePx = with(density) { 72.dp.toPx() }
    val maxPullDistancePx = with(density) { 96.dp.toPx() }
    val indicatorSize = 44.dp
    var pullDistancePx by remember { mutableFloatStateOf(0f) }
    var refreshing by remember { mutableStateOf(false) }
    val attachedView = remember(controller) { arrayOfNulls<GeckoView>(1) }
    val indicatorOffset by animateDpAsState(
        targetValue = if (refreshing) 32.dp else with(density) { pullDistancePx.toDp() },
        label = "pull-refresh-offset"
    )

    LaunchedEffect(pageState.isLoading, refreshing) {
        if (refreshing && !pageState.isLoading) {
            delay(350)
            refreshing = false
        }
    }

    DisposableEffect(controller) {
        onDispose {
            val view = attachedView[0]
            if (view?.session == controller.session) {
                view.releaseSession()
            }
            controller.attachView(null)
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.matchParentSize(),
            factory = { context ->
                PullRefreshGeckoContainer(context).apply {
                    geckoView.configureAndroidAutofill()
                    geckoView.setSession(controller.session)
                    controller.attachView(geckoView)
                    attachedView[0] = geckoView
                }
            },
            update = { container ->
                if (sessionChangeVersion >= 0 && container.geckoView.session != controller.session) {
                    container.geckoView.setSession(controller.session)
                }
                controller.attachView(container.geckoView)
                attachedView[0] = container.geckoView
                container.configureImeBottom(imeBottomPx)
                container.configurePullRefresh(
                    enabled = pullRefreshEnabled && !refreshing,
                    triggerDistancePx = triggerDistancePx,
                    maxPullDistancePx = maxPullDistancePx,
                    onGestureStarted = controller::markPullRefreshGestureStarted,
                    onContentTouchStarted = controller::focusForUserInteraction,
                    contentCanStartPullRefresh = controller::canStartPullRefreshFromContent,
                    onPull = { pullDistancePx = it },
                    onRefresh = {
                        refreshing = true
                        pullDistancePx = 0f
                        controller.reload()
                    }
                )
            }
        )
        if (pullDistancePx > 0f || refreshing) {
            PullRefreshIndicator(
                refreshing = refreshing,
                modifier = Modifier
                    .offset { IntOffset(0, indicatorOffset.roundToPx()) }
                    .align(androidx.compose.ui.Alignment.TopCenter)
                    .size(indicatorSize)
            )
        }
    }
}

private class PullRefreshGeckoContainer(context: Context) : FrameLayout(context) {
    val geckoView = GeckoView(context)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var enabled = false
    private var triggerDistancePx = 0f
    private var maxPullDistancePx = 0f
    private var startX = 0f
    private var startY = 0f
    private var startedInUpperHalf = false
    private var pullDistancePx = 0f
    private var pulling = false
    private var onGestureStarted: () -> Unit = {}
    private var onContentTouchStarted: () -> Unit = {}
    private var contentCanStartPullRefresh: () -> Boolean = { true }
    private var onPull: (Float) -> Unit = {}
    private var onRefresh: () -> Unit = {}
    private var imeBottomPx = 0

    init {
        addView(
            geckoView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
    }

    fun configureImeBottom(imeBottomPx: Int) {
        if (this.imeBottomPx == imeBottomPx) return
        this.imeBottomPx = imeBottomPx
        post { applyImeClipping() }
    }

    fun configurePullRefresh(
        enabled: Boolean,
        triggerDistancePx: Float,
        maxPullDistancePx: Float,
        onGestureStarted: () -> Unit,
        onContentTouchStarted: () -> Unit,
        contentCanStartPullRefresh: () -> Boolean,
        onPull: (Float) -> Unit,
        onRefresh: () -> Unit
    ) {
        this.enabled = enabled
        this.triggerDistancePx = triggerDistancePx
        this.maxPullDistancePx = maxPullDistancePx
        this.onGestureStarted = onGestureStarted
        this.onContentTouchStarted = onContentTouchStarted
        this.contentCanStartPullRefresh = contentCanStartPullRefresh
        this.onPull = onPull
        this.onRefresh = onRefresh
        if (!enabled) {
            resetPull()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyImeClipping()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            geckoView.isFocusable = true
            geckoView.isFocusableInTouchMode = true
            geckoView.requestFocusFromTouch()
            onContentTouchStarted()
        }
        if (!enabled) {
            return super.dispatchTouchEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
                startedInUpperHalf = event.y <= height / 3f
                resetPull()
                if (startedInUpperHalf) {
                    onGestureStarted()
                }
                return super.dispatchTouchEvent(event)
            }

            MotionEvent.ACTION_MOVE -> {
                val dy = event.rawY - startY
                val dx = abs(event.rawX - startX)
                val canStartPull = !geckoView.canScrollVertically(-1) && contentCanStartPullRefresh()
                val shouldStartPull = startedInUpperHalf &&
                    canStartPull &&
                    dy > touchSlop &&
                    dy > dx * 1.25f
                if (!pulling && shouldStartPull) {
                    pulling = true
                    val cancelEvent = cancelEventFrom(event)
                    geckoView.dispatchTouchEvent(cancelEvent)
                    cancelEvent.recycle()
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (pulling) {
                    pullDistancePx = ((dy - touchSlop) * 0.55f).coerceIn(0f, maxPullDistancePx)
                    onPull(pullDistancePx)
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (pulling) {
                    val shouldRefresh = pullDistancePx >= triggerDistancePx
                    resetPull()
                    parent?.requestDisallowInterceptTouchEvent(false)
                    if (shouldRefresh) {
                        onRefresh()
                    }
                    return true
                }
            }
        }

        return super.dispatchTouchEvent(event)
    }

    private fun resetPull() {
        pulling = false
        pullDistancePx = 0f
        onPull(0f)
    }

    private fun cancelEventFrom(event: MotionEvent): MotionEvent =
        MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }

    private fun applyImeClipping() {
        if (height <= 0) return
        val overlapPx = calculateImeOverlapPx()
        val targetHeight = if (overlapPx > 0) {
            (height - overlapPx).coerceAtLeast(1)
        } else {
            LayoutParams.MATCH_PARENT
        }
        val params = geckoView.layoutParams as LayoutParams
        if (params.height != targetHeight || params.width != LayoutParams.MATCH_PARENT) {
            geckoView.layoutParams = params.apply {
                width = LayoutParams.MATCH_PARENT
                height = targetHeight
            }
        }
    }

    private fun calculateImeOverlapPx(): Int {
        if (imeBottomPx <= 0) return 0
        val root = rootView ?: return imeBottomPx.coerceIn(0, height)
        if (root.height <= 0) return imeBottomPx.coerceIn(0, height)

        val rootLocation = IntArray(2)
        val containerLocation = IntArray(2)
        root.getLocationOnScreen(rootLocation)
        getLocationOnScreen(containerLocation)

        val imeTopOnScreen = rootLocation[1] + root.height - imeBottomPx
        val containerBottomOnScreen = containerLocation[1] + height
        return (containerBottomOnScreen - imeTopOnScreen).coerceIn(0, height)
    }
}

@Composable
fun GeckoSessionView(
    session: GeckoSession,
    modifier: Modifier = Modifier,
    viewBackend: Int? = null,
    onViewChanged: (GeckoView?) -> Unit = {}
) {
    val attachedView = remember(session) { arrayOfNulls<GeckoView>(1) }

    DisposableEffect(session) {
        onDispose {
            val view = attachedView[0]
            if (view?.session == session) {
                view.releaseSession()
            }
            onViewChanged(null)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            GeckoView(context).apply {
                viewBackend?.let { setViewBackend(it) }
                configureAndroidAutofill()
                setSession(session)
                onViewChanged(this)
                attachedView[0] = this
            }
        },
        update = { view ->
            if (view.session != session) {
                view.setSession(session)
            }
            onViewChanged(view)
            attachedView[0] = view
        }
    )
}

private fun GeckoView.configureAndroidAutofill() {
    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
    isSaveEnabled = true
    setAutofillEnabled(true)
}

@Composable
private fun PullRefreshIndicator(refreshing: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .shadow(4.dp, CircleShape)
            .background(MaterialTheme.colorScheme.surface, CircleShape),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        if (refreshing) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}
