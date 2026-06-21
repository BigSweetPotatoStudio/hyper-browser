package com.dadigua.hyperbrowser.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import com.dadigua.hyperbrowser.R
import java.text.BreakIterator

object BrowserIconComposer {
    fun badgedSiteIcon(context: Context, iconPath: String?, size: Int): Bitmap? {
        if (iconPath.isNullOrBlank()) return null
        val base = BitmapFactory.decodeFile(iconPath) ?: return null
        return badgedSiteIcon(context, base, size)
    }

    fun badgedSiteIcon(context: Context, iconPath: String?, pageUrl: String?, size: Int): Bitmap? {
        badgedSiteIcon(context, iconPath, size)?.let { return it }
        val fallback = defaultSiteIcon(pageUrl, size) ?: return null
        return badgedSiteIcon(context, fallback, size)
    }

    fun badgedSiteIcon(context: Context, base: Bitmap, size: Int): Bitmap? {
        if (base.isRecycled || size <= 0) return null
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val bounds = RectF(0f, 0f, size.toFloat(), size.toFloat())
        canvas.drawBitmap(base, null, bounds, bitmapPaint)

        val badgeSize = (size * 0.34f).toInt()
        val badgeMargin = (size * 0.04f).toInt()
        val badgeLeft = size - badgeSize - badgeMargin
        val badgeTop = size - badgeSize - badgeMargin
        val badgeRect = RectF(
            badgeLeft.toFloat(),
            badgeTop.toFloat(),
            (badgeLeft + badgeSize).toFloat(),
            (badgeTop + badgeSize).toFloat()
        )
        val badgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawRoundRect(badgeRect, badgeSize * 0.24f, badgeSize * 0.24f, badgeBg)
        val badge = BitmapFactory.decodeResource(context.resources, R.drawable.ic_launcher_foreground)
            ?: BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
        if (badge != null && !badge.isRecycled) {
            canvas.drawBitmap(badge, null, badgeRect, bitmapPaint)
        }
        return output
    }

    fun defaultWebAppIcon(name: String, pageUrl: String?, size: Int): Bitmap? {
        val host = pageUrl
            ?.let { runCatching { Uri.parse(it).host.orEmpty() }.getOrNull() }
            ?.removePrefix("www.")
            .orEmpty()
        val labelSource = name.ifBlank { host }.ifBlank { "W" }
        return defaultLabelIcon(labelSource, pageUrl ?: labelSource, size)
    }

    private fun defaultSiteIcon(pageUrl: String?, size: Int): Bitmap? {
        if (size <= 0) return null
        val host = pageUrl
            ?.let { runCatching { Uri.parse(it).host.orEmpty() }.getOrNull() }
            ?.removePrefix("www.")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return defaultLabelIcon(host, host, size)
    }

    private fun defaultLabelIcon(labelSource: String, colorSource: String?, size: Int): Bitmap? {
        if (size <= 0) return null
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val hue = (((colorSource ?: labelSource).hashCode() and 0x7fffffff) % 360).toFloat()
        val background = Color.HSVToColor(floatArrayOf(hue, 0.42f, 0.76f))
        canvas.drawColor(background)

        val label = firstDisplaySymbol(labelSource)
        if (label.isNotBlank()) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                textSize = size * (if (label.length > 2) 0.42f else 0.52f)
                typeface = Typeface.DEFAULT_BOLD
            }
            val metrics = paint.fontMetrics
            val baseline = size / 2f - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(label, size / 2f, baseline, paint)
        }
        return output
    }

    private fun firstDisplaySymbol(value: String): String {
        val trimmed = value.trimStart()
        if (trimmed.isBlank()) return ""
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(trimmed)
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val symbol = trimmed.substring(start, end).trim()
            if (symbol.isNotEmpty()) return symbol.uppercase()
            start = end
            end = iterator.next()
        }
        return ""
    }
}
