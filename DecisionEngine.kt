package com.stealth.bot.core

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Random

class DecisionEngine(
    private val gestureHandler: GestureHandler? = null
) {

    private val TAG = "StealthDecisionEngine"
    private val random = Random()
    private val handler = Handler(Looper.getMainLooper())

    fun processTarget(node: AccessibilityNodeInfo, text: String) {
        val bounds = Rect()
        val hasBounds = try {
            node.getBoundsInScreen(bounds) && !bounds.isEmpty
        } catch (_: Exception) {
            false
        }
        val centerX = if (hasBounds) bounds.exactCenterX() else 0f
        val centerY = if (hasBounds) bounds.exactCenterY() else 0f

        val patternType = selectDynamicPattern()
        executeActionWithPattern(node, hasBounds, centerX, centerY, patternType, text)
    }

    private fun selectDynamicPattern(): Int {
        val choice = random.nextInt(100)
        return when {
            choice < 30 -> 1 // Fast
            choice < 70 -> 2 // Medium
            choice < 95 -> 3 // Slow
            else -> 4        // Miss / Skip
        }
    }

    private fun executeActionWithPattern(
        node: AccessibilityNodeInfo,
        hasBounds: Boolean,
        centerX: Float,
        centerY: Float,
        pattern: Int,
        text: String
    ) {
        when (pattern) {
            1 -> {
                val delay = 2000 + random.nextInt(2000) // 2 to 4 seconds
                delayedAction(node, hasBounds, centerX, centerY, delay, "Fast Pattern")
            }
            2 -> {
                val delay = 5000 + random.nextInt(3000) // 5 to 8 seconds
                delayedAction(node, hasBounds, centerX, centerY, delay, "Medium Pattern")
            }
            3 -> {
                val delay = 9000 + random.nextInt(3000) // 9 to 12 seconds
                delayedAction(node, hasBounds, centerX, centerY, delay, "Slow Pattern")
            }
            4 -> {
                Log.d(TAG, "Human Error Simulated: Skipped action naturally for target -> $text")
                try {
                    node.recycle()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun delayedAction(
        node: AccessibilityNodeInfo,
        hasBounds: Boolean,
        centerX: Float,
        centerY: Float,
        delayMillis: Int,
        patternName: String
    ) {
        handler.postDelayed({
            try {
                if (gestureHandler != null && hasBounds) {
                    gestureHandler.performTap(centerX, centerY)
                    Log.d(TAG, "Executed tap via GestureHandler ($patternName) at ($centerX, $centerY) after ${delayMillis}ms")
                } else {
                    try {
                        node.refresh()
                    } catch (_: Exception) {
                    }
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "Executed click ($patternName) after ${delayMillis}ms")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Execution error: ${e.message}")
            } finally {
                try {
                    node.recycle()
                } catch (_: Exception) {
                }
            }
        }, delayMillis.toLong())
    }
}
