package com.stealth.bot.core

import android.accessibilityservice.AccessibilityService
import android.graphics.PointF
import android.graphics.Rect
import java.util.Random

class CoordinateMapper(
    private val service: AccessibilityService? = null,
    private val baseWidth: Int = 1080,
    private val baseHeight: Int = 1920
) {

    private val random = Random()

    fun currentScreenSize(): Pair<Int, Int> {
        val dm = service?.resources?.displayMetrics
        val w = (dm?.widthPixels ?: baseWidth).coerceAtLeast(200)
        val h = (dm?.heightPixels ?: baseHeight).coerceAtLeast(400)
        return Pair(w, h)
    }

    fun mapFromBase(x: Float, y: Float): PointF {
        val (w, h) = currentScreenSize()
        val safeBaseW = baseWidth.coerceAtLeast(1).toFloat()
        val safeBaseH = baseHeight.coerceAtLeast(1).toFloat()

        val mappedX = ((x / safeBaseW) * w).coerceIn(10f, (w - 10).toFloat())
        val mappedY = ((y / safeBaseH) * h).coerceIn(10f, (h - 10).toFloat())
        return PointF(mappedX, mappedY)
    }

    fun mapNormalized(normX: Float, normY: Float): PointF {
        val (w, h) = currentScreenSize()
        val clampedX = normX.coerceIn(0.02f, 0.98f) * w
        val clampedY = normY.coerceIn(0.02f, 0.98f) * h
        return PointF(clampedX, clampedY)
    }

    fun centerOfBounds(bounds: Rect, jitterPx: Float = 4f): PointF {
        val (w, h) = currentScreenSize()
        val jx = if (jitterPx > 0f) (random.nextFloat() * 2f - 1f) * jitterPx else 0f
        val jy = if (jitterPx > 0f) (random.nextFloat() * 2f - 1f) * jitterPx else 0f

        val cx = (bounds.exactCenterX() + jx).coerceIn(10f, (w - 10).toFloat())
        val cy = (bounds.exactCenterY() + jy).coerceIn(10f, (h - 10).toFloat())
        return PointF(cx, cy)
    }
}
