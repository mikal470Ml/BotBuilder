package com.stealth.bot.core

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SelectionHandler {

    private val TAG = "StealthSelectionHandler"
    private val handler = Handler(Looper.getMainLooper())
    private val random = Random()
    private val worker = Executors.newSingleThreadExecutor()
    private val closed = AtomicBoolean(false)

    private val failHistory = mutableMapOf<String, Int>()

    fun selectTextRange(
        node: AccessibilityNodeInfo?,
        start: Int,
        end: Int,
        onComplete: (Boolean) -> Unit
    ) {
        performAction(node, "SelectRange($start,$end)", onComplete) { target ->
            val args = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
            }
            target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
        }
    }

    fun selectAllText(node: AccessibilityNodeInfo?, onComplete: (Boolean) -> Unit) {
        if (node == null) {
            onComplete(false)
            return
        }
        val textLen = node.text?.length ?: 0
        selectTextRange(node, 0, textLen, onComplete)
    }

    fun copySelectedText(node: AccessibilityNodeInfo?, onComplete: (Boolean) -> Unit) {
        performAction(node, "Copy", onComplete) { target ->
            target.performAction(AccessibilityNodeInfo.ACTION_COPY)
        }
    }

    fun cutSelectedText(node: AccessibilityNodeInfo?, onComplete: (Boolean) -> Unit) {
        performAction(node, "Cut", onComplete) { target ->
            target.performAction(AccessibilityNodeInfo.ACTION_CUT)
        }
    }

    fun pasteText(node: AccessibilityNodeInfo?, onComplete: (Boolean) -> Unit) {
        performAction(node, "Paste", onComplete) { target ->
            target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
    }

    fun clearText(node: AccessibilityNodeInfo?, onComplete: (Boolean) -> Unit) {
        selectAllText(node) { selected ->
            if (!selected) {
                onComplete(false)
                return@selectAllText
            }
            performAction(node, "ClearText", onComplete) { target ->
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                }
                target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
        }
    }

    fun selectTextWithRetry(
        node: AccessibilityNodeInfo?,
        start: Int,
        end: Int,
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
            selectTextRange(node, start, end) { success ->
                if (success) {
                    failHistory["select"] = 0
                    onComplete(true)
                } else {
                    failHistory["select"] = (failHistory["select"] ?: 0) + 1
                    val delay = (500 + random.nextInt(500)).toLong()
                    Log.w(TAG, "Retry #$attempts in ${delay}ms")
                    handler.postDelayed({ tryOnce() }, delay)
                }
            }
        }

        tryOnce()
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
            } catch (e: Exception) {
                Log.e(TAG, "$name exception: ${e.message}")
                false
            }

            handler.post {
                Log.d(TAG, "$name -> $result")
                onComplete(result)
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        Log.d(TAG, "SelectionHandler closed.")
    }
}
