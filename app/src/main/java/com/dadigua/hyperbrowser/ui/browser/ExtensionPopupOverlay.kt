package com.dadigua.hyperbrowser.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dadigua.hyperbrowser.extensions.ExtensionPopupState
import com.dadigua.hyperbrowser.gecko.GeckoSessionView
import org.mozilla.geckoview.GeckoView
import kotlin.math.roundToInt

private val PopupActionBarHeight = 48.dp
private val PopupActionButtonSize = 40.dp

@Composable
internal fun ExtensionPopupOverlay(
    popup: ExtensionPopupState,
    onClose: () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(18.dp)
    ) {
        val density = LocalDensity.current
        val availableWidth = maxWidth
        val availableHeight = maxHeight
        val popupWidth = if (availableWidth <= 360.dp) availableWidth else availableWidth * 0.92f
        val expandedHeight = if (availableHeight <= 560.dp) availableHeight else availableHeight * 0.88f
        var collapsed by remember(popup.guid) { mutableStateOf(false) }
        val popupHeight = if (collapsed) PopupActionBarHeight else expandedHeight
        val maxOffsetX = with(density) { (availableWidth - popupWidth).toPx().coerceAtLeast(0f) }
        val maxOffsetY = with(density) { (availableHeight - popupHeight).toPx().coerceAtLeast(0f) }
        var offsetX by remember(popup.guid) { mutableStateOf(maxOffsetX) }
        var offsetY by remember(popup.guid) { mutableStateOf(0f) }

        LaunchedEffect(maxOffsetX, maxOffsetY) {
            offsetX = offsetX.coerceIn(0f, maxOffsetX)
            offsetY = offsetY.coerceIn(0f, maxOffsetY)
        }

        Card(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .width(popupWidth)
                .height(popupHeight),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(PopupActionBarHeight)
                        .pointerInput(maxOffsetX, maxOffsetY, collapsed) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                offsetX = (offsetX + dragAmount.x).coerceIn(0f, maxOffsetX)
                                offsetY = (offsetY + dragAmount.y).coerceIn(0f, maxOffsetY)
                            }
                        }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        popup.title,
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    IconButton(
                        onClick = {
                            val nextCollapsed = !collapsed
                            collapsed = nextCollapsed
                            val nextHeight = if (nextCollapsed) PopupActionBarHeight else expandedHeight
                            val nextMaxOffsetY = with(density) { (availableHeight - nextHeight).toPx().coerceAtLeast(0f) }
                            offsetY = offsetY.coerceIn(0f, nextMaxOffsetY)
                        },
                        modifier = Modifier.size(PopupActionButtonSize)
                    ) {
                        Text(
                            if (collapsed) "□" else "−",
                            color = Color(0xFF202124),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = onClose, modifier = Modifier.size(PopupActionButtonSize)) {
                        Text(
                            "×",
                            color = Color(0xFF202124),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                if (!collapsed) {
                    HorizontalDivider(color = Color(0xFFE0E3EA))
                    GeckoSessionView(
                        session = popup.session,
                        modifier = Modifier.fillMaxSize(),
                        viewBackend = GeckoView.BACKEND_TEXTURE_VIEW
                    )
                }
            }
        }
    }
}
