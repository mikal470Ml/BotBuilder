package com.stealth.bot.core

import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

class TextTyper {

    private val TAG = "StealthTextTyper"

    fun typeTextIntoNode(node: AccessibilityNodeInfo?, textToType: String): Boolean {
        if (node == null) {
            Log.w(TAG, "Target node is null, cannot type text.")
            return false
        }

        val className = node.className?.toString() ?: ""
        if (!node.isEditable && !className.contains("EditText", ignoreCase = true)) {
            Log.w(TAG, "Target node is not an editable text field.")
            return false
        }

        return try {
            val arguments = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    textToType
                )
            }

            val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            if (success) {
                Log.d(TAG, "Successfully typed text: '$textToType'")
            } else {
                Log.w(TAG, "Failed to type text into node.")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Exception while typing text: ${e.message}")
            false
        }
    }

    fun focusAndType(node: AccessibilityNodeInfo?, textToType: String): Boolean {
        if (node == null) return false

        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return typeTextIntoNode(node, textToType)
    }
}
