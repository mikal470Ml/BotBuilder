package com.stealth.bot.core

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class KeyboardSimulator(private val service: AccessibilityService?) {

    private val TAG = "StealthKeyboardSimulator"
    private val handler = Handler(Looper.getMainLooper())
    private val random = Random()
    private val worker = Executors.newSingleThreadExecutor()
    private val closed = AtomicBoolean(false)

    private val failHistory = mutableMapOf<String, Int>()

    fun typeIntoNode(
        node: AccessibilityNodeInfo?,
        text: String,
        onComplete: (Boolean) -> Unit
    ) {
        if (closed.get()) {
            onComplete(false)
            return
        }

        if (node == null || text.isEmpty()) {
            onComplete(text.isEmpty())
            return
        }

        if (trySetText(node, text)) {
            Log.d(TAG, "Typed via ACTION_SET_TEXT: $text")
            onComplete(true)
            return
        }

        if (tryPasteText(node, text)) {
            Log.d(TAG, "Pasted via clipboard: $text")
            onComplete(true)
            return
        }

        typeByKeys(node, text, onComplete)
    }

    private fun trySetText(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) {
            Log.e(TAG, "ACTION_SET_TEXT failed: ${e.message}")
            false
        }
    }

    private fun tryPasteText(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            val clipboard = service?.getSystemService(Context.CLIPBOARD_SERVICE)
                as? ClipboardManager ?: return false
            clipboard.setPrimaryClip(ClipData.newPlainText("text", text))

            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (e: Exception) {
            Log.e(TAG, "Paste failed: ${e.message}")
            false
        }
    }

    private fun typeByKeys(
        node: AccessibilityNodeInfo,
        text: String,
        onComplete: (Boolean) -> Unit
    ) {
        worker.execute {
            if (closed.get()) {
                handler.post { onComplete(false) }
                return@execute
            }

            try {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

                var successCount = 0
                for (char in text) {
                    if (closed.get()) break

                    val keyCode = getKeyCodeForChar(char)
                    val stepped = if (keyCode != -1) {
                        val bundle = Bundle().apply {
                            putInt(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
                                keyCode
                            )
                        }
                        node.performAction(
                            AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY,
                            bundle
                        ) || tryPasteText(node, char.toString())
                    } else {
                        tryPasteText(node, char.toString())
                    }

                    if (stepped) successCount++

                    val delay = (30 + random.nextInt(80)).toLong()
                    try {
                        Thread.sleep(delay)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }

                handler.post {
                    Log.d(TAG, "Key simulation finished ($successCount/${text.length})")
                    onComplete(successCount > 0 || trySetText(node, text))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Key type failed: ${e.message}")
                handler.post { onComplete(false) }
            }
        }
    }

    private fun getKeyCodeForChar(c: Char): Int {
        return when (c) {
            in 'a'..'z' -> c - 'a' + 29
            in 'A'..'Z' -> c - 'A' + 29
            in '0'..'9' -> c - '0' + 7
            ' ' -> 62
            '\n' -> 66
            '\t' -> 61
            '.' -> 56
            ',' -> 55
            '@' -> 77
            '-' -> 69
            '+' -> 81
            '/' -> 76
            '\\' -> 73
            '*' -> 17
            '#' -> 18
            '(' -> 9
            ')' -> 10
            '!' -> 73
            '?' -> 72
            ':' -> 74
            ';' -> 74
            '=' -> 70
            '<' -> 71
            '>' -> 71
            '[' -> 71
            ']' -> 72
            '{' -> 71
            '}' -> 72
            '|' -> 73
            '~' -> 73
            '`' -> 68
            '"' -> 68
            '\'' -> 75
            '$' -> 11
            '%' -> 12
            '&' -> 13
            '_' -> 69
            '^' -> 14
            else -> -1
        }
    }

    fun pressEnter(node: AccessibilityNodeInfo?, onComplete: (Boolean) -> Unit) {
        performAction(node, "Enter", onComplete) { target ->
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val currentText = target.text?.toString() ?: ""
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    "$currentText\n"
                )
            }
            target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
    }

    fun pressBack(node: AccessibilityNodeInfo?, onComplete: (Boolean) -> Unit) {
        if (service == null || closed.get()) {
            onComplete(false)
            return
        }
        worker.execute {
            try {
                val res = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                handler.post { onComplete(res) }
            } catch (_: Exception) {
                handler.post { onComplete(false) }
            }
        }
    }

    fun pressHome(node: AccessibilityNodeInfo?, onComplete: (Boolean) -> Unit) {
        if (service == null || closed.get()) {
            onComplete(false)
            return
        }
        worker.execute {
            try {
                val res = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                handler.post { onComplete(res) }
            } catch (_: Exception) {
                handler.post { onComplete(false) }
            }
        }
    }

    fun pressRecents(node: AccessibilityNodeInfo?, onComplete: (Boolean) -> Unit) {
        if (service == null || closed.get()) {
            onComplete(false)
            return
        }
        worker.execute {
            try {
                val res = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                handler.post { onComplete(res) }
            } catch (_: Exception) {
                handler.post { onComplete(false) }
            }
        }
    }

    private fun performAction(
        node: AccessibilityNodeInfo?,
        name: String,
        onComplete: (Boolean) -> Unit,
        action: (AccessibilityNodeInfo) -> Boolean
    ) {
        if (node == null || closed.get()) {
            onComplete(false)
            return
        }
        worker.execute {
            val result = try {
                action(node)
            } catch (_: Exception) {
                false
            }
            handler.post {
                Log.d(TAG, "$name -> $result")
                onComplete(result)
            }
        }
    }

    fun typeWithRetry(
        node: AccessibilityNodeInfo?,
        text: String,
        onComplete: (Boolean) -> Unit
    ) {
        val startTime = System.currentTimeMillis()
        val maxDuration = 15_000L + random.nextInt(10_000).toLong()
        var attempts = 0

        fun tryOnce() {
            if (closed.get()) {
                onComplete(false)
                return
            }
            if (System.currentTimeMillis() - startTime > maxDuration) {
                Log.w(TAG, "Time limit reached after $attempts attempts")
                onComplete(false)
                return
            }

            attempts++
            typeIntoNode(node, text) { success ->
                if (success) {
                    failHistory["type"] = 0
                    onComplete(true)
                } else {
                    failHistory["type"] = (failHistory["type"] ?: 0) + 1
                    val delay = (500 + random.nextInt(500)).toLong()
                    Log.w(TAG, "Retry #$attempts in ${delay}ms")
                    handler.postDelayed({ tryOnce() }, delay)
                }
            }
        }

        tryOnce()
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        Log.d(TAG, "KeyboardSimulator closed.")
    }
}
