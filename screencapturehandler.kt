package com.stealth.bot.core

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class ScreenCaptureHandler(
    private val service: AccessibilityService
) {

    companion object {
        private const val TAG = "StealthScreenCapture"
        private const val BASE_RETRY_DELAY_MS = 300L
        private const val MAX_RETRY_DELAY_MS = 2_000L
        private const val CACHE_INTERVAL_MS = 500L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var cachedBitmap: Bitmap? = null
    private var lastCaptureTime = 0L

    @Volatile
    private var destroyed = false

    // =========================================================
    // 1. स्क्रीनशॉट कैप्चर (अनलिमिटेड रीट्राय मैकेनिज्म)
    // maxRetries = -1 का मतलब है अनंत (Infinite) रीट्राय जब तक कैप्चर न हो जाए!
    // =========================================================
    fun captureScreen(
        useCache: Boolean = true,
        maxRetries: Int = -1,
        onCaptureResult: (Bitmap?) -> Unit
    ) {
        val callbackDelivered = AtomicBoolean(false)

        fun deliver(bitmap: Bitmap?) {
            if (!callbackDelivered.compareAndSet(false, true)) return

            if (destroyed) {
                onCaptureResult(null)
            } else {
                onCaptureResult(bitmap)
            }
        }

        if (destroyed) {
            deliver(null)
            return
        }

        // कैश (Cache) चेक
        if (useCache) {
            val cached = synchronized(lock) {
                val bitmap = cachedBitmap
                val age = SystemClock.elapsedRealtime() - lastCaptureTime

                if (bitmap != null && !bitmap.isRecycled && age in 0 until CACHE_INTERVAL_MS) {
                    bitmap
                } else {
                    null
                }
            }

            if (cached != null) {
                Log.d(TAG, "Returning cached screenshot")
                deliver(cached)
                return
            }
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "Native Accessibility screenshot requires Android 11+")
            deliver(null)
            return
        }

        // ज़ीरो-लिमिट: किसी सीमा में बांधे बिना कैप्चर शुरू करो
        captureAttempt(
            attempt = 0,
            maxRetries = maxRetries,
            deliver = ::deliver
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun captureAttempt(
        attempt: Int,
        maxRetries: Int,
        deliver: (Bitmap?) -> Unit
    ) {
        if (destroyed) {
            deliver(null)
            return
        }

        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {

                    override fun onSuccess(screenshotResult: AccessibilityService.ScreenshotResult) {
                        var softwareBitmap: Bitmap? = null

                        try {
                            val hardwareBuffer = screenshotResult.hardwareBuffer

                            try {
                                val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                                    hardwareBuffer,
                                    screenshotResult.colorSpace
                                )

                                softwareBitmap = hardwareBitmap?.copy(
                                    Bitmap.Config.ARGB_8888,
                                    false
                                )
                            } finally {
                                hardwareBuffer.close()
                            }

                            if (softwareBitmap == null) {
                                Log.e(TAG, "Unable to create software Bitmap, retrying...")
                                retryOrFail(attempt, maxRetries, deliver)
                                return
                            }

                            if (destroyed) {
                                softwareBitmap.recycle()
                                deliver(null)
                                return
                            }

                            synchronized(lock) {
                                cachedBitmap = softwareBitmap
                                lastCaptureTime = SystemClock.elapsedRealtime()
                            }

                            Log.d(TAG, "Screenshot captured successfully: ${softwareBitmap.width}x${softwareBitmap.height}")
                            deliver(softwareBitmap)

                        } catch (e: Exception) {
                            Log.e(TAG, "Screenshot processing failed", e)
                            if (softwareBitmap != null && !softwareBitmap.isRecycled) {
                                softwareBitmap.recycle()
                            }
                            retryOrFail(attempt, maxRetries, deliver)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "Screenshot failed with errorCode=$errorCode, retrying...")
                        retryOrFail(attempt, maxRetries, deliver)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "takeScreenshot exception caught", e)
            retryOrFail(attempt, maxRetries, deliver)
        }
    }

    // =========================================================
    // 2. रीट्राय लॉजिक (अनलिमिटेड मोड सपोर्टेड)
    // =========================================================
    private fun retryOrFail(
        attempt: Int,
        maxRetries: Int,
        deliver: (Bitmap?) -> Unit
    ) {
        if (destroyed) {
            deliver(null)
            return
        }

        // अगर maxRetries -1 नहीं है और सीमा पार हो गई है, तभी रुको
        if (maxRetries >= 0 && attempt >= maxRetries) {
            Log.w(TAG, "Maximum retries reached ($maxRetries)")
            deliver(null)
            return
        }

        val delay = min(
            BASE_RETRY_DELAY_MS * (1L shl attempt.coerceAtMost(10)),
            MAX_RETRY_DELAY_MS
        )

        Log.d(TAG, "Unlimited Retry attempt #${attempt + 1} after ${delay}ms")

        handler.postDelayed({
            if (!destroyed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                captureAttempt(attempt + 1, maxRetries, deliver)
            } else {
                deliver(null)
            }
        }, delay)
    }

    // =========================================================
    // 3. विजुअल टूल्स (Crop, Resize, Rotate, Save, Color)
    // =========================================================
    fun cropBitmap(source: Bitmap, x: Int, y: Int, width: Int, height: Int): Bitmap? {
        return try {
            if (source.isRecycled || width <= 0 || height <= 0) return null
            val left = x.coerceIn(0, source.width)
            val top = y.coerceIn(0, source.height)
            val right = min(source.width.toLong(), x.toLong() + width.toLong()).toInt()
            val bottom = min(source.height.toLong(), y.toLong() + height.toLong()).toInt()
            if (right <= left || bottom <= top) return null
            Bitmap.createBitmap(source, left, top, right - left, bottom - top)
        } catch (e: Exception) {
            Log.e(TAG, "Crop failed", e)
            null
        }
    }

    fun resizeBitmap(source: Bitmap, newWidth: Int, newHeight: Int): Bitmap? {
        return try {
            if (source.isRecycled || newWidth <= 0 || newHeight <= 0) return null
            Bitmap.createScaledBitmap(source, newWidth, newHeight, true)
        } catch (e: Exception) {
            Log.e(TAG, "Resize failed", e)
            null
        }
    }

    fun rotateBitmap(source: Bitmap, degrees: Float): Bitmap? {
        return try {
            if (source.isRecycled || !degrees.isFinite()) return null
            val matrix = Matrix().apply { postRotate(degrees) }
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        } catch (e: Exception) {
            Log.e(TAG, "Rotate failed", e)
            null
        }
    }

    fun saveBitmap(bitmap: Bitmap, filePath: String): Boolean {
        return try {
            if (bitmap.isRecycled || filePath.isBlank()) return false
            val file = File(filePath)
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            Log.d(TAG, "Bitmap saved to $filePath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Save failed", e)
            false
        }
    }

    fun getAverageColor(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int): Int? {
        return try {
            if (bitmap.isRecycled || width <= 0 || height <= 0) return null
            val left = x.coerceIn(0, bitmap.width)
            val top = y.coerceIn(0, bitmap.height)
            val right = min(bitmap.width.toLong(), x.toLong() + width.toLong()).toInt()
            val bottom = min(bitmap.height.toLong(), y.toLong() + height.toLong()).toInt()

            var red = 0L; var green = 0L; var blue = 0L; var count = 0L
            var py = top
            while (py < bottom) {
                var px = left
                while (px < right) {
                    val color = bitmap.getPixel(px, py)
                    red += Color.red(color)
                    green += Color.green(color)
                    blue += Color.blue(color)
                    count++
                    px += 3
                }
                py += 3
            }
            if (count == 0L) null else Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
        } catch (e: Exception) {
            null
        }
    }

    fun clearCache() {
        synchronized(lock) {
            cachedBitmap = null
            lastCaptureTime = 0L
        }
    }

    fun release() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        clearCache()
    }
}
