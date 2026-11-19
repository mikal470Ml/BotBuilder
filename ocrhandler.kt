package com.stealth.bot.core

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class OcrHandler(
    private val maxRetries: Int = -1, // -1 मतलब अनंत (अनलिमिटेड कोशिश जब तक टेक्स्ट न मिले)
    private val retryDelayMs: Long = 300L,
    private val callbackExecutor: Executor = Executors.newSingleThreadExecutor()
) : Closeable {

    companion object {
        private const val TAG = "OcrHandler"
        private const val MAX_RETRY_DELAY_MS = 2_000L
    }

    data class OcrResult(
        val text: String,
        val blocks: List<Text.TextBlock>,
        val attempts: Int
    )

    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor()

    private val recognizer = TextRecognition.getClient(
        TextRecognizerOptions.DEFAULT_OPTIONS
    )

    fun extractTextFromBitmap(
        bitmap: Bitmap?,
        onResult: (Result<OcrResult>) -> Unit
    ) {
        if (closed.get()) {
            deliver(onResult, failure("OcrHandler is closed"))
            return
        }

        if (bitmap == null || bitmap.isRecycled) {
            deliver(onResult, failure("Bitmap is null or recycled"))
            return
        }

        try {
            worker.execute {
                processBitmap(bitmap, 0, onResult)
            }
        } catch (e: Exception) {
            deliver(onResult, Result.failure(e))
        }
    }

    fun extractTextFromRegion(
        bitmap: Bitmap?,
        region: Rect?,
        onResult: (Result<OcrResult>) -> Unit
    ) {
        if (bitmap == null || bitmap.isRecycled) {
            deliver(onResult, failure("Bitmap is null or recycled"))
            return
        }

        if (region == null) {
            deliver(onResult, failure("Region is null"))
            return
        }

        val left = region.left.coerceIn(0, bitmap.width)
        val top = region.top.coerceIn(0, bitmap.height)
        val right = region.right.coerceIn(0, bitmap.width)
        val bottom = region.bottom.coerceIn(0, bitmap.height)

        if (right <= left || bottom <= top) {
            deliver(onResult, failure("Invalid or out-of-bounds region"))
            return
        }

        val cropped = try {
            Bitmap.createBitmap(
                bitmap,
                left,
                top,
                right - left,
                bottom - top
            )
        } catch (e: Exception) {
            deliver(onResult, Result.failure(e))
            return
        }

        extractTextFromBitmap(cropped) { result ->
            try {
                onResult(result)
            } finally {
                if (cropped !== bitmap && !cropped.isRecycled) {
                    cropped.recycle()
                }
            }
        }
    }

    private fun processBitmap(
        bitmap: Bitmap,
        retry: Int,
        callback: (Result<OcrResult>) -> Unit
    ) {
        if (closed.get()) {
            deliver(callback, failure("OcrHandler is closed"))
            return
        }

        if (bitmap.isRecycled) {
            deliver(callback, failure("Bitmap was recycled"))
            return
        }

        val inputImage = try {
            InputImage.fromBitmap(bitmap, 0)
        } catch (e: Exception) {
            deliver(callback, Result.failure(e))
            return
        }

        val task = try {
            synchronized(lock) {
                if (closed.get()) {
                    null
                } else {
                    recognizer.process(inputImage)
                }
            }
        } catch (e: Exception) {
            retryOrFail(bitmap, retry, callback, e)
            return
        }

        if (task == null) {
            deliver(callback, failure("OCR could not be started"))
            return
        }

        task.addOnSuccessListener { result ->
            val extractedText = result.text.trim()
            if (extractedText.isEmpty() && (maxRetries < 0 || retry < maxRetries)) {
                // अगर टेक्स्ट खाली मिला है और अनलिमिटेड मोड ऑन है, तो दोबारा कोशिश करो!
                retryOrFail(bitmap, retry, callback, Exception("Empty OCR text result"))
            } else {
                deliver(
                    callback,
                    Result.success(
                        OcrResult(
                            text = extractedText,
                            blocks = result.textBlocks.toList(),
                            attempts = retry + 1
                        )
                    )
                )
            }
        }.addOnFailureListener { error ->
            retryOrFail(bitmap, retry, callback, error)
        }
    }

    private fun retryOrFail(
        bitmap: Bitmap,
        retry: Int,
        callback: (Result<OcrResult>) -> Unit,
        error: Throwable
    ) {
        Log.w(TAG, "OCR attempt ${retry + 1} failed: ${error.message}")

        if (closed.get()) {
            deliver(callback, Result.failure(error))
            return
        }

        // अगर maxRetries -1 (अनलिमिटेड) है, तो यह सीमा कभी खत्म नहीं होगी!
        if (maxRetries >= 0 && retry >= maxRetries) {
            deliver(callback, Result.failure(error))
            return
        }

        val exponent = retry.coerceIn(0, 10)
        val multiplier = 1L shl exponent
        val delay = (retryDelayMs.coerceAtLeast(0L) * multiplier).coerceAtMost(MAX_RETRY_DELAY_MS)

        try {
            worker.execute {
                try {
                    Thread.sleep(delay)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    deliver(callback, failure("OCR retry interrupted"))
                    return@execute
                }

                if (closed.get()) {
                    deliver(callback, failure("OcrHandler is closed"))
                } else {
                    processBitmap(bitmap, retry + 1, callback)
                }
            }
        } catch (e: Exception) {
            deliver(callback, Result.failure(e))
        }
    }

    private fun <T> deliver(
        callback: (Result<T>) -> Unit,
        result: Result<T>
    ) {
        try {
            callbackExecutor.execute {
                try {
                    callback(result)
                } catch (e: Exception) {
                    Log.e(TAG, "OCR callback failed", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to dispatch OCR callback", e)
        }
    }

    private fun <T> failure(message: String): Result<T> =
        Result.failure(IllegalStateException(message))

    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        synchronized(lock) {
            recognizer.close()
        }

        worker.shutdownNow()
    }
}
