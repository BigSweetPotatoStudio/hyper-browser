package com.dadigua.hyperbrowser.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.dadigua.hyperbrowser.R

object BrowserIconComposer {
    fun badgedSiteIcon(context: Context, iconPath: String?, size: Int): Bitmap? {
        if (iconPath.isNullOrBlank()) return null
        val base = BitmapFactory.decodeFile(iconPath) ?: return null
        return badgedSiteIcon(context, base, size)
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
}
