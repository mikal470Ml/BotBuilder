package com.stealth.bot.core

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class ScrollerHandler(
    private val gestureHandler: GestureHandler,
    private val service: AccessibilityService? = null
) {

    private val TAG = "StealthScrollerHandler"
    private val handler = Handler(Looper.getMainLooper())
    private val random = Random()

    private fun screenCenter(): Pair<Float, Float> {
        val dm = service?.resources?.displayMetrics
        val w = (dm?.widthPixels ?: 1080).coerceAtLeast(200)
        val h = (dm?.heightPixels ?: 1920).coerceAtLeast(400)
        return Pair(w / 2f, h / 2f)
    }

    private fun randomScreenPoint(): Pair<Float, Float> {
        val dm = service?.resources?.displayMetrics
        val w = (dm?.widthPixels ?: 1080).coerceAtLeast(200)
        val h = (dm?.heightPixels ?: 1920).coerceAtLeast(400)
        val x = (w * 0.2f) + random.nextFloat() * (w * 0.6f)
        val y = (h * 0.2f) + random.nextFloat() * (h * 0.6f)
        return Pair(x, y)
    }

    fun scrollPage(direction: String) {
        Log.d(TAG, "Scrolling: $direction")
        gestureHandler.performScroll(direction)
    }

    fun scrollDiagonal(angleDegrees: Float, distance: Int = 400) {
        val (cx, cy) = screenCenter()
        val rad = angleDegrees * PI / 180.0
        val endX = (cx + (distance * cos(rad))).toFloat()
        val endY = (cy + (distance * sin(rad))).toFloat()

        Log.d(TAG, "Diagonal scroll at ${angleDegrees}°")
        gestureHandler.performSwipe(cx, cy, endX, endY)
    }

    fun scrollCustom(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 0
    ) {
        Log.d(TAG, "Custom scroll ($startX,$startY) -> ($endX,$endY)")
        gestureHandler.performSwipe(startX, startY, endX, endY, durationMs)
    }

    fun scrollZigzag(direction: String) {
        val (cx, cy) = screenCenter()
        val wobble = (random.nextInt(60) - 30).toFloat()

        val (endX, endY) = when (direction.lowercase()) {
            "down" -> Pair(cx + wobble, cy - 400f)
            "up" -> Pair(cx + wobble, cy + 400f)
            "left" -> Pair(cx + 400f, cy + wobble)
            "right" -> Pair(cx - 400f, cy + wobble)
            else -> Pair(cx + wobble, cy - 400f)
        }

        Log.d(TAG, "Zigzag scroll $direction with wobble $wobble")
        gestureHandler.performSwipe(cx, cy, endX, endY)
    }

    fun scrollCircular(
        centerX: Float,
        centerY: Float,
        radius: Int = 200,
        startAngle: Float = 0f,
        endAngle: Float = 360f
    ) {
        Log.d(TAG, "Circular scroll around ($centerX,$centerY)")

        val steps = 4
        val angleStep = (endAngle - startAngle) / steps

        for (i in 0 until steps) {
            val a1 = startAngle + angleStep * i
            val a2 = startAngle + angleStep * (i + 1)

            val x1 = (centerX + radius * cos(a1 * PI / 180)).toFloat()
            val y1 = (centerY + radius * sin(a1 * PI / 180)).toFloat()
            val x2 = (centerX + radius * cos(a2 * PI / 180)).toFloat()
            val y2 = (centerY + radius * sin(a2 * PI / 180)).toFloat()

            gestureHandler.performSwipe(x1, y1, x2, y2, 150)
        }
    }

    fun scrollUntilTargetFound(
        direction: String,
        actionCheck: () -> Boolean,
        onResult: (Boolean) -> Unit
    ) {
        val startTime = System.currentTimeMillis()
        val maxDuration = 30_000L
        var attempts = 0

        fun tryScroll() {
            if (System.currentTimeMillis() - startTime > maxDuration) {
                Log.w(TAG, "Time limit reached after $attempts attempts.")
                onResult(false)
                return
            }

            if (actionCheck()) {
                Log.d(TAG, "Target found at attempt ${attempts + 1}")
                onResult(true)
                return
            }

            when (random.nextInt(3)) {
                0 -> scrollPage(direction)
                1 -> scrollZigzag(direction)
                2 -> scrollDiagonal(random.nextInt(360).toFloat())
            }
            attempts++

            val delay = (400 + random.nextInt(500)).toLong()
            handler.postDelayed({ tryScroll() }, delay)
        }

        tryScroll()
    }

    fun scrollBySeverity(
        direction: String,
        severity: Int,
        actionCheck: () -> Boolean,
        onResult: (Boolean) -> Unit
    ) {
        val maxDuration = when {
            severity < 2 -> 5_000L
            severity < 5 -> 15_000L
            severity < 8 -> 30_000L
            else -> 60_000L
        }

        val startTime = System.currentTimeMillis()
        var attempts = 0

        fun tryScroll() {
            if (System.currentTimeMillis() - startTime > maxDuration) {
                onResult(false)
                return
            }

            if (actionCheck()) {
                onResult(true)
                return
            }

            when (random.nextInt(4)) {
                0 -> scrollPage(direction)
                1 -> scrollZigzag(direction)
                2 -> scrollDiagonal(random.nextInt(360).toFloat())
                3 -> {
                    val (sx, sy) = randomScreenPoint()
                    val (ex, ey) = randomScreenPoint()
                    scrollCustom(sx, sy, ex, ey)
                }
            }
            attempts++

            val delay = (400 + random.nextInt(500)).toLong()
            handler.postDelayed({ tryScroll() }, delay)
        }

        tryScroll()
    }

    fun autoScroll(actionCheck: () -> Boolean, onResult: (Boolean) -> Unit) {
        val directions = listOf("down", "up", "left", "right")
        val maxDuration = 45_000L
        val startTime = System.currentTimeMillis()
        var attempts = 0

        fun tryScroll() {
            if (System.currentTimeMillis() - startTime > maxDuration) {
                onResult(false)
                return
            }

            if (actionCheck()) {
                onResult(true)
                return
            }

            val mode = random.nextInt(5)
            val dir = directions[random.nextInt(directions.size)]
            when (mode) {
                0 -> scrollPage(dir)
                1 -> scrollZigzag(dir)
                2 -> scrollDiagonal(random.nextInt(360).toFloat())
                3 -> {
                    val (sx, sy) = randomScreenPoint()
                    val (ex, ey) = randomScreenPoint()
                    scrollCustom(sx, sy, ex, ey)
                }
                4 -> {
                    val (cx, cy) = randomScreenPoint()
                    scrollCircular(cx, cy)
                }
            }
            attempts++

            val delay = (400 + random.nextInt(500)).toLong()
            handler.postDelayed({ tryScroll() }, delay)
        }

        tryScroll()
    }

    fun scrollAdaptive(
        platform: String,
        actionCheck: () -> Boolean,
        onResult: (Boolean) -> Unit
    ) {
        val maxDuration = when (platform.lowercase()) {
            "app" -> 30_000L
            "web" -> 45_000L
            "game" -> 60_000L
            "canvas" -> 60_000L
            else -> 30_000L
        }

        val startTime = System.currentTimeMillis()
        var attempts = 0

        fun tryScroll() {
            if (System.currentTimeMillis() - startTime > maxDuration) {
                onResult(false)
                return
            }

            if (actionCheck()) {
                onResult(true)
                return
            }

            when (platform.lowercase()) {
                "app" -> scrollPage("down")
                "web" -> scrollZigzag("down")
                "game" -> scrollDiagonal(random.nextInt(360).toFloat())
                "canvas" -> {
                    val (cx, cy) = randomScreenPoint()
                    scrollCircular(cx, cy)
                }
                else -> scrollPage("down")
            }
            attempts++

            val delay = (400 + random.nextInt(500)).toLong()
            handler.postDelayed({ tryScroll() }, delay)
        }

        tryScroll()
    }
}
