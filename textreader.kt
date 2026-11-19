package com.stealth.bot.core

import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log

class TextReader {

    private val TAG = "StealthTextReader"

    // =========================================================
    // 1. स्क्रीन के रूट नोड से सारा टेक्स्ट ढूँढकर बाहर निकालना
    // =========================================================
    fun extractAllText(rootNode: AccessibilityNodeInfo?): List<String> {
        val textList = mutableListOf<String>()
        if (rootNode == null) return textList

        traverseAndExtract(rootNode, textList)
        Log.d(TAG, "Extracted ${textList.size} text elements from screen.")
        return textList
    }

    // =========================================================
    // 2. रिकर्सिव ट्री पार्सर (Recursively traverse child nodes)
    // =========================================================
    private fun traverseAndExtract(node: AccessibilityNodeInfo, textList: MutableList<String>) {
        try {
            val nodeText = node.text?.toString()
            if (!nodeText.isNullOrBlank()) {
                textList.add(nodeText.trim())
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    traverseAndExtract(child, textList)
                    child.recycle() // मेमोरी सेफ रखने के लिए रिसाइकिल करना
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error traversing node for text: ${e.message}")
        }
    }

    // =========================================================
    // 3. किसी खास कीवर्ड (सवाल या निर्देश) को स्क्रीन पर खोजना
    // =========================================================
    fun findTextOnScreen(rootNode: AccessibilityNodeInfo?, keyword: String): Boolean {
        val allTexts = extractAllText(rootNode)
        for (text in allTexts) {
            if (text.contains(keyword, ignoreCase = true)) {
                Log.d(TAG, "Found keyword: '$keyword' in text: '$text'")
                return true
            }
        }
        return false
    }
}
