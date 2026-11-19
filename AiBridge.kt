package com.stealth.bot.core

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AiBridge(
    private val networkHandler: NetworkHandler = NetworkHandler(),
    private val endpointUrl: String = "",
    private val apiKey: String = ""
) {

    private val TAG = "StealthAiBridge"
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val closed = AtomicBoolean(false)

    data class AiResponse(
        val success: Boolean,
        val reply: String,
        val rawBody: String = "",
        val errorMessage: String? = null
    )

    fun ask(
        prompt: String,
        systemInstruction: String = "You are a concise, accurate assistant.",
        onResult: (AiResponse) -> Unit
    ) {
        if (closed.get()) {
            onResult(AiResponse(false, "", errorMessage = "AiBridge is closed"))
            return
        }

        if (prompt.isBlank()) {
            onResult(AiResponse(false, "", errorMessage = "Empty prompt"))
            return
        }

        if (endpointUrl.isBlank()) {
            Log.w(TAG, "Endpoint URL not configured; returning local fallback.")
            onResult(
                AiResponse(
                    success = false,
                    reply = "",
                    errorMessage = "Endpoint URL not configured"
                )
            )
            return
        }

        worker.execute {
            if (closed.get()) {
                handler.post { onResult(AiResponse(false, "", errorMessage = "Cancelled")) }
                return@execute
            }

            try {
                val payload = JSONObject().apply {
                    put("system", systemInstruction)
                    put("prompt", prompt)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        })
                    })
                }

                val headers = mutableMapOf(
                    "Content-Type" to "application/json"
                )
                if (apiKey.isNotBlank()) {
                    headers["Authorization"] = "Bearer $apiKey"
                }

                networkHandler.post(
                    url = endpointUrl,
                    body = payload.toString(),
                    headers = headers
                ) { netResult ->
                    if (closed.get()) {
                        onResult(AiResponse(false, "", netResult.responseBody, "Cancelled"))
                        return@post
                    }

                    if (!netResult.success) {
                        onResult(
                            AiResponse(
                                success = false,
                                reply = "",
                                rawBody = netResult.responseBody,
                                errorMessage = netResult.errorMessage ?: "HTTP ${netResult.statusCode}"
                            )
                        )
                        return@post
                    }

                    val parsedReply = extractTextFromJson(netResult.responseBody)
                    onResult(
                        AiResponse(
                            success = true,
                            reply = parsedReply,
                            rawBody = netResult.responseBody
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "AiBridge ask failed: ${e.message}")
                handler.post {
                    onResult(AiResponse(false, "", errorMessage = e.message))
                }
            }
        }
    }

    fun analyzeScreenText(
        screenText: String,
        goal: String,
        onResult: (AiResponse) -> Unit
    ) {
        val prompt = """
            Goal: $goal
            Screen Content:
            $screenText
            Respond concisely with the next action or answer.
        """.trimIndent()
        ask(prompt, "You analyze Android UI text and decide exact next steps.", onResult)
    }

    private fun extractTextFromJson(raw: String): String {
        if (raw.isBlank()) return ""
        return try {
            val obj = JSONObject(raw)
            when {
                obj.has("reply") -> obj.optString("reply", raw)
                obj.has("text") -> obj.optString("text", raw)
                obj.has("response") -> obj.optString("response", raw)
                obj.has("output") -> obj.optString("output", raw)
                obj.has("choices") -> {
                    val choices = obj.optJSONArray("choices")
                    val first = choices?.optJSONObject(0)
                    first?.optJSONObject("message")?.optString("content", raw)
                        ?: first?.optString("text", raw)
                        ?: raw
                }
                else -> raw
            }
        } catch (_: Exception) {
            raw
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        networkHandler.close()
        Log.d(TAG, "AiBridge closed.")
    }
}
