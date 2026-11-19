package com.stealth.bot.core

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import kotlin.math.abs

class ImageRecognitionHandler(
    private val coordinateMapper: CoordinateMapper = CoordinateMapper()
) {

    private val TAG = "StealthImageRecognition"

    data class MatchResult(
        val found: Boolean,
        val center: PointF? = null,
        val bounds: Rect? = null,
        val confidence: Float = 0f
    )

    fun findColorRegion(
        bitmap: Bitmap?,
        targetColor: Int,
        tolerance: Int = 24,
        minPixels: Int = 25,
        step: Int = 4
    ): MatchResult {
        if (bitmap == null || bitmap.isRecycled) {
            Log.w(TAG, "Bitmap is null or recycled.")
            return MatchResult(false)
        }

        val width = bitmap.width
        val height = bitmap.height
        val safeStep = step.coerceAtLeast(1)

        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        var matchCount = 0

        val tr = Color.red(targetColor)
        val tg = Color.green(targetColor)
        val tb = Color.blue(targetColor)

        try {
            var y = 0
            while (y < height) {
                var x = 0
                while (x < width) {
                    val px = bitmap.getPixel(x, y)
                    val diff = abs(Color.red(px) - tr) +
                        abs(Color.green(px) - tg) +
                        abs(Color.blue(px) - tb)

                    if (diff <= tolerance * 3) {
                        matchCount++
                        if (x < minX) minX = x
                        if (y < minY) minY = y
                        if (x > maxX) maxX = x
                        if (y > maxY) maxY = y
                    }
                    x += safeStep
                }
                y += safeStep
            }
        } catch (e: Exception) {
            Log.e(TAG, "Color scan error: ${e.message}")
            return MatchResult(false)
        }

        if (matchCount < minPixels || maxX < minX || maxY < minY) {
            return MatchResult(false)
        }

        val rect = Rect(minX, minY, maxX, maxY)
        val mappedCenter = coordinateMapper.centerOfBounds(rect, jitterPx = 2f)
        val confidence = (matchCount.toFloat() / (minPixels * 4f)).coerceIn(0.1f, 1.0f)

        return MatchResult(
            found = true,
            center = mappedCenter,
            bounds = rect,
            confidence = confidence
        )
    }

    fun findNormalizedPoint(normX: Float, normY: Float): MatchResult {
        val pt = coordinateMapper.mapNormalized(normX, normY)
        return MatchResult(
            found = true,
            center = pt,
            confidence = 1.0f
        )
    }
}
