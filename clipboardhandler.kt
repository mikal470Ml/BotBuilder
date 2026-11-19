package com.stealth.bot.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Random

class ClipboardHandler(private val context: Context) {

    private val TAG = "StealthClipboard"
    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    private val handler = Handler(Looper.getMainLooper())
    private val random = Random()

    // History (पुरानी clips)
    private val history = mutableListOf<ClipHistory>()
    private val maxHistory = 50

    // Cache (तेज़ के लिए)
    private var cachedText: String? = null
    private var lastCacheTime: Long = 0L

    // Listener
    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null

    // =========================================================
    // 1. नतीजा (Data class)
    // =========================================================
    data class ClipHistory(
        val text: String,
        val label: String,
        val timestamp: Long,
        val type: ClipType
    )

    enum class ClipType {
        TEXT, HTML, URI, INTENT, UNKNOWN
    }

    // =========================================================
    // 2. मुख्य: कॉपी करना (Text, HTML, URI)
    // =========================================================
    fun copyText(
        text: String,
        label: String = "StealthBotData",
        onComplete: (Boolean) -> Unit = {}
    ) {
        if (text.isEmpty()) {
            Log.w(TAG, "Empty text, nothing to copy.")
            onComplete(false)
            return
        }

        try {
            if (clipboardManager == null) {
                Log.e(TAG, "ClipboardManager is null.")
                onComplete(false)
                return
            }

            val clip = ClipData.newPlainText(label, text)
            clipboardManager.setPrimaryClip(clip)

            // History में जोड़ो
            addToHistory(text, label, ClipType.TEXT)

            // Cache update
            cachedText = text
            lastCacheTime = System.currentTimeMillis()

            Log.d(TAG, "Copied: '$text'")
            onComplete(true)
        } catch (e: Exception) {
            Log.e(TAG, "Copy failed: ${e.message}")
            retryCopy(text, label, 0, onComplete)
        }
    }

    // =========================================================
    // 3. HTML कॉपी (रिच टेक्स्ट)
    // =========================================================
    fun copyHtml(
        html: String,
        label: String = "StealthBotHtml",
        onComplete: (Boolean) -> Unit = {}
    ) {
        try {
            val clip = ClipData.newHtmlText(label, html, html)
            clipboardManager?.setPrimaryClip(clip)
            addToHistory(html, label, ClipType.HTML)
            Log.d(TAG, "HTML copied")
            onComplete(true)
        } catch (e: Exception) {
            Log.e(TAG, "HTML copy failed: ${e.message}")
            onComplete(false)
        }
    }

    // =========================================================
    // 4. URI कॉपी (लिंक)
    // =========================================================
    fun copyUri(
        uri: Uri,
        label: String = "StealthBotUri",
        onComplete: (Boolean) -> Unit = {}
    ) {
        try {
            val clip = ClipData.newRawUri(label, uri)
            clipboardManager?.setPrimaryClip(clip)
            addToHistory(uri.toString(), label, ClipType.URI)
            Log.d(TAG, "URI copied: $uri")
            onComplete(true)
        } catch (e: Exception) {
            Log.e(TAG, "URI copy failed: ${e.message}")
            onComplete(false)
        }
    }

    // =========================================================
    // 5. कई items एक साथ कॉपी (Multi-item)
    // =========================================================
    fun copyMultiple(
        items: List<Pair<String, String>>,  // (label, text)
        onComplete: (Boolean) -> Unit = {}
    ) {
        if (items.isEmpty()) {
            onComplete(false)
            return
        }

        try {
            val clipData = ClipData.newPlainText(items[0].first, items[0].second)
            for (i in 1 until items.size) {
                clipData.addItem(ClipData.Item(items[i].second))
            }
            clipboardManager?.setPrimaryClip(clipData)

            items.forEach { addToHistory(it.second, it.first, ClipType.TEXT) }
            Log.d(TAG, "Multi-item copied: ${items.size} items")
            onComplete(true)
        } catch (e: Exception) {
            Log.e(TAG, "Multi-copy failed: ${e.message}")
            onComplete(false)
        }
    }

    // =========================================================
    // 6. पढ़ना (Read) — पूरा
    // =========================================================
    fun getText(onResult: (String?) -> Unit) {
        // Cache जाँच (500ms)
        val now = System.currentTimeMillis()
        if (cachedText != null && (now - lastCacheTime) < 500) {
            onResult(cachedText)
            return
        }

        try {
            if (clipboardManager == null || !clipboardManager.hasPrimaryClip()) {
                onResult(null)
                return
            }

            val clip = clipboardManager.primaryClip ?: run {
                onResult(null)
                return
            }

            val item = clip.getItemAt(0)
            val text = item.coerceToText(context)?.toString()

            cachedText = text
            lastCacheTime = now

            Log.d(TAG, "Read: '$text'")
            onResult(text)
        } catch (e: Exception) {
            Log.e(TAG, "Read failed: ${e.message}")
            onResult(null)
        }
    }

    // =========================================================
    // 7. पूरा clip पढ़ो (सारे items)
    // =========================================================
    fun getAllItems(onResult: (List<String>) -> Unit) {
        try {
            val clip = clipboardManager?.primaryClip
            if (clip == null) {
                onResult(emptyList())
                return
            }

            val items = mutableListOf<String>()
            for (i in 0 until clip.itemCount) {
                val text = clip.getItemAt(i).coerceToText(context)?.toString()
                if (!text.isNullOrBlank()) items.add(text)
            }
            onResult(items)
        } catch (e: Exception) {
            Log.e(TAG, "Get all items failed: ${e.message}")
            onResult(emptyList())
        }
    }

    // =========================================================
    // 8. Type पहचानो
    // =========================================================
    fun getClipType(onResult: (ClipType) -> Unit) {
        try {
            val clip = clipboardManager?.primaryClip
            if (clip == null || clip.itemCount == 0) {
                onResult(ClipType.UNKNOWN)
                return
            }

            val item = clip.getItemAt(0)
            val type = when {
                item.htmlText != null -> ClipType.HTML
                item.uri != null -> ClipType.URI
                item.intent != null -> ClipType.INTENT
                item.text != null -> ClipType.TEXT
                else -> ClipType.UNKNOWN
            }
            onResult(type)
        } catch (e: Exception) {
            onResult(ClipType.UNKNOWN)
        }
    }

    // =========================================================
    // 9. History
    // =========================================================
    private fun addToHistory(text: String, label: String, type: ClipType) {
        history.add(0, ClipHistory(text, label, System.currentTimeMillis(), type))
        while (history.size > maxHistory) history.removeAt(history.size - 1)
    }

    fun getHistory(): List<ClipHistory> = history.toList()

    fun getLastClip(): ClipHistory? = history.firstOrNull()

    fun clearHistory() {
        history.clear()
        Log.d(TAG, "History cleared.")
    }

    // =========================================================
    // 10. Listener (बदलाव पर पता)
    // =========================================================
    fun startListening(onChange: (String?) -> Unit) {
        stopListening()

        listener = ClipboardManager.OnPrimaryClipChangedListener {
            try {
                val clip = clipboardManager?.primaryClip
                val text = clip?.getItemAt(0)?.coerceToText(context)?.toString()
                cachedText = text
                lastCacheTime = System.currentTimeMillis()
                if (!text.isNullOrBlank()) {
                    addToHistory(text, "Listener", ClipType.TEXT)
                }
                onClipChangeInternal(text, onChange)
            } catch (e: Exception) {
                Log.e(TAG, "Listener error: ${e.message}")
            }
        }

        clipboardManager?.addPrimaryClipChangedListener(listener)
        Log.d(TAG, "Listener started.")
    }

    private fun onClipChangeInternal(text: String?, onChange: (String?) -> Unit) {
        handler.post { onChange(text) }
    }

    fun stopListening() {
        listener?.let {
            clipboardManager?.removePrimaryClipChangedListener(it)
        }
        listener = null
        Log.d(TAG, "Listener stopped.")
    }

    // =========================================================
    // 11. साफ करना (Clear)
    // =========================================================
    fun clearClipboard(onComplete: (Boolean) -> Unit = {}) {
        try {
            if (clipboardManager == null) {
                onComplete(false)
                return
            }

            // Android 10+: empty clip
            val emptyClip = ClipData.newPlainText("", "")
            clipboardManager.setPrimaryClip(emptyClip)

            // अगर समर्थित हो (Android 13+), तो clearPrimaryClip
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    clipboardManager.clearPrimaryClip()
                } catch (_: Exception) {}
            }

            cachedText = null
            lastCacheTime = 0L
            Log.d(TAG, "Clipboard cleared.")
            onComplete(true)
        } catch (e: Exception) {
            Log.e(TAG, "Clear failed: ${e.message}")
            onComplete(false)
        }
    }

    // =========================================================
    // 12. Retry (खुद तय)
    // =========================================================
    private fun retryCopy(
        text: String,
        label: String,
        attempt: Int,
        onComplete: (Boolean) -> Unit
    ) {
        // कोई तय सीमा नहीं — समय के हिसाब से
        if (attempt > 5) {
            Log.w(TAG, "Retry limit reached.")
            onComplete(false)
            return
        }

        val delay = (200 + random.nextInt(300)).toLong()
        Log.w(TAG, "Retry #${attempt + 1} in ${delay}ms")

        handler.postDelayed({
            try {
                val clip = ClipData.newPlainText(label, text)
                clipboardManager?.setPrimaryClip(clip)
                addToHistory(text, label, ClipType.TEXT)
                onComplete(true)
            } catch (e: Exception) {
                retryCopy(text, label, attempt + 1, onComplete)
            }
        }, delay)
    }

    // =========================================================
    // 13. बंद करना
    // =========================================================
    fun close() {
        stopListening()
        history.clear()
        cachedText = null
        Log.d(TAG, "ClipboardHandler closed.")
    }
}