package com.stealth.bot.core

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class CoreAutomationService : AccessibilityService() {

    private val TAG = "StealthCoreService"
    private val mainHandler = Handler(Looper.getMainLooper())

    private val gestureHandler by lazy { GestureHandler(this) }
    private val decisionEngine by lazy { DecisionEngine() }

    private var lastScanTime = 0L
    private val scanCooldownMs = 350L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Stealth Core Service Connected Successfully.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastScanTime < scanCooldownMs) return
        lastScanTime = now

        val rootNode: AccessibilityNodeInfo = rootInActiveWindow ?: return
        try {
            scanAndProcessNodes(rootNode)
        } finally {
            try {
                rootNode.recycle()
            } catch (_: Exception) {
            }
        }
    }

    private fun scanAndProcessNodes(node: AccessibilityNodeInfo) {
        for (i in 0 until node.childCount) {
            val childNode = node.getChild(i) ?: continue
            try {
                val nodeText = childNode.text?.toString()
                    ?: childNode.contentDescription?.toString()
                    ?: ""
                val className = childNode.className?.toString() ?: ""

                if (className.contains("EditText", ignoreCase = true) ||
                    className.contains("Button", ignoreCase = true) ||
                    childNode.isClickable
                ) {
                    val bounds = Rect()
                    childNode.getBoundsInScreen(bounds)
                    if (!bounds.isEmpty) {
                        triggerDecisionEngine(childNode, nodeText, bounds)
                    }
                }

                scanAndProcessNodes(childNode)
            } finally {
                try {
                    childNode.recycle()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun triggerDecisionEngine(
        node: AccessibilityNodeInfo,
        text: String,
        bounds: Rect
    ) {
        val centerX = bounds.exactCenterX()
        val centerY = bounds.exactCenterY()
        Log.d(TAG, "Target Found: '$text' at ($centerX, $centerY)")

        // Safe copy or direct tap fallback via GestureHandler
        val nodeCopy = try {
            AccessibilityNodeInfo.obtain(node)
        } catch (_: Exception) {
            null
        }

        if (nodeCopy != null) {
            decisionEngine.processTarget(nodeCopy, text)
        } else {
            mainHandler.post {
                gestureHandler.performTap(centerX, centerY)
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service Interrupted Safely.")
    }
}

