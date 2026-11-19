package com.stealth.bot.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Random

class GestureHandler(internal val service: AccessibilityService) {

private val TAG = "StealthGestureHandler"
private val random = Random()
private val handler = Handler(Looper.getMainLooper())

// =========================================================
// 1. इंसानी टैप (थरथराहट + random delay)
// =========================================================
fun performTap(
    x: Float,
    y: Float,
    callback: AccessibilityService.GestureResultCallback? = null
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
        Log.e(TAG, "Gesture API requires Android N (Nougat) or higher.")
        return
    }

    val wobbleX = (random.nextInt(10) - 5).toFloat()
    val wobbleY = (random.nextInt(10) - 5).toFloat()

    val path = Path().apply {
        moveTo(x + wobbleX, y + wobbleY)
    }

    val duration = 40 + random.nextInt(40)

    val stroke = GestureDescription.StrokeDescription(path, 0, duration.toLong())
    val gesture = GestureDescription.Builder().addStroke(stroke).build()

    service.dispatchGesture(
        gesture,
        object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d(TAG, "Tap at ($x, $y) with ${duration}ms")
                callback?.onCompleted(gestureDescription)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.w(TAG, "Tap cancelled!")
                callback?.onCancelled(gestureDescription)
            }
        },
        null
    )
}

// =========================================================
// 2. इंसानी डबल-टैप
// =========================================================
fun performDoubleTap(x: Float, y: Float) {
    performTap(x, y, object : AccessibilityService.GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            super.onCompleted(gestureDescription)
            handler.postDelayed({
                performTap(x, y)
            }, (100 + random.nextInt(100)).toLong())
        }
    })
}

// =========================================================
// 3. इंसानी लॉन्ग-प्रेस
// =========================================================
fun performLongPress(x: Float, y: Float) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
        Log.e(TAG, "Gesture API requires Android N+")
        return
    }

    val path = Path().apply {
        moveTo(x, y)
    }

    val duration = 800 + random.nextInt(400)

    val stroke = GestureDescription.StrokeDescription(path, 0, duration.toLong())
    val gesture = GestureDescription.Builder().addStroke(stroke).build()

    service.dispatchGesture(gesture, null, null)
    Log.d(TAG, "Long press at ($x, $y) with ${duration}ms")
}

// =========================================================
// 4. इंसानी स्वाइप
// =========================================================
fun performSwipe(
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
    duration: Long = 0,
    callback: AccessibilityService.GestureResultCallback? = null
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
        Log.e(TAG, "Gesture API requires Android N+")
        return
    }

    val path = Path().apply {
        moveTo(startX, startY)

        val steps = 3 + random.nextInt(3)
        for (i in 1..steps) {
            val t = i.toFloat() / (steps + 1)
            val wobbleX = (random.nextInt(30) - 15).toFloat()
            val wobbleY = (random.nextInt(30) - 15).toFloat()
            lineTo(
                startX + (endX - startX) * t + wobbleX,
                startY + (endY - startY) * t + wobbleY
            )
        }

        lineTo(endX, endY)
    }

    val actualDuration = if (duration > 0) duration else (200 + random.nextInt(400)).toLong()

    val stroke = GestureDescription.StrokeDescription(path, 0, actualDuration)
    val gesture = GestureDescription.Builder().addStroke(stroke).build()

    service.dispatchGesture(
        gesture,
        object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d(TAG, "Swipe ($startX, $startY) → ($endX, $endY) in ${actualDuration}ms")
                callback?.onCompleted(gestureDescription)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.w(TAG, "Swipe cancelled!")
                callback?.onCancelled(gestureDescription)
            }
        },
        null
    )
}

// =========================================================
// 5. इंसानी स्क्रॉल
// =========================================================
fun performScroll(direction: String) {
    val dm = service.resources.displayMetrics
    val centerX = dm.widthPixels / 2f
    val centerY = dm.heightPixels / 2f

    when (direction.lowercase()) {
        "down" -> performSwipe(centerX, centerY + 300, centerX, centerY - 300)
        "up" -> performSwipe(centerX, centerY - 300, centerX, centerY + 300)
        "left" -> performSwipe(centerX + 300, centerY, centerX - 300, centerY)
        "right" -> performSwipe(centerX - 300, centerY, centerX + 300, centerY)
        else -> Log.w(TAG, "Unknown scroll direction: $direction")
    }
}

}