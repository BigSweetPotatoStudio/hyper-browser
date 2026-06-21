package com.dadigua.hyperbrowser.webapp

import android.graphics.Color
import com.dadigua.hyperbrowser.R

data class WebAppIconPreset(
    val id: String,
    val labelRes: Int,
    val drawableRes: Int,
    val backgroundColor: Int,
    val foregroundColor: Int
)

object WebAppIconPresets {
    val all: List<WebAppIconPreset> = listOf(
        WebAppIconPreset(
            id = "news",
            labelRes = R.string.webapp_icon_preset_news,
            drawableRes = R.drawable.webapp_preset_news,
            backgroundColor = Color.rgb(25, 103, 210),
            foregroundColor = Color.WHITE
        ),
        WebAppIconPreset(
            id = "video",
            labelRes = R.string.webapp_icon_preset_video,
            drawableRes = R.drawable.webapp_preset_video,
            backgroundColor = Color.rgb(213, 0, 50),
            foregroundColor = Color.WHITE
        ),
        WebAppIconPreset(
            id = "music",
            labelRes = R.string.webapp_icon_preset_music,
            drawableRes = R.drawable.webapp_preset_music,
            backgroundColor = Color.rgb(123, 31, 162),
            foregroundColor = Color.WHITE
        ),
        WebAppIconPreset(
            id = "shop",
            labelRes = R.string.webapp_icon_preset_shop,
            drawableRes = R.drawable.webapp_preset_shop,
            backgroundColor = Color.rgb(15, 121, 67),
            foregroundColor = Color.WHITE
        ),
        WebAppIconPreset(
            id = "chat",
            labelRes = R.string.webapp_icon_preset_chat,
            drawableRes = R.drawable.webapp_preset_chat,
            backgroundColor = Color.rgb(0, 137, 123),
            foregroundColor = Color.WHITE
        ),
        WebAppIconPreset(
            id = "docs",
            labelRes = R.string.webapp_icon_preset_docs,
            drawableRes = R.drawable.webapp_preset_docs,
            backgroundColor = Color.rgb(95, 99, 104),
            foregroundColor = Color.WHITE
        ),
        WebAppIconPreset(
            id = "work",
            labelRes = R.string.webapp_icon_preset_work,
            drawableRes = R.drawable.webapp_preset_work,
            backgroundColor = Color.rgb(245, 124, 0),
            foregroundColor = Color.WHITE
        ),
        WebAppIconPreset(
            id = "star",
            labelRes = R.string.webapp_icon_preset_star,
            drawableRes = R.drawable.webapp_preset_star,
            backgroundColor = Color.rgb(251, 188, 4),
            foregroundColor = Color.rgb(32, 33, 36)
        )
    )

    fun find(id: String): WebAppIconPreset? = all.firstOrNull { it.id == id }
}
