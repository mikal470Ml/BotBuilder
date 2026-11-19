package com.stealth.bot.core

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class NetworkHandler {

    private val TAG = "StealthNetworkHandler"
    private val worker = Executors.newFixedThreadPool(4)
    private val handler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)

    private val cache = ConcurrentHashMap<String, CachedResponse>()
    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()

    data class NetworkResult(
        val success: Boolean,
        val statusCode: Int,
        val responseBody: String,
        val errorMessage: String? = null,
        val headers: Map<String, List<String>> = emptyMap(),
        val durationMs: Long = 0,
        val url: String = "",
        val cached: Boolean = false
    )

    data class CachedResponse(
        val result: NetworkResult,
        val timestamp: Long
    )

    data class Request(
        val id: String = UUID.randomUUID().toString(),
        val url: String,
        val method: String = "GET",
        val headers: Map<String, String> = emptyMap(),
        val body: String? = null,
        val timeoutMs: Int = 15_000,
        val followRedirects: Boolean = true,
        val useCache: Boolean = false,
        val cacheTtlMs: Long = 60_000L
    )

    fun execute(
        request: Request,
        onResult: (NetworkResult) -> Unit
    ): String {
        val requestId = request.id

        if (closed.get()) {
            onResult(NetworkResult(false, 0, "", "Closed"))
            return requestId
        }

        cancelFlags[requestId] = AtomicBoolean(false)

        if (request.useCache) {
            val key = "${request.method}:${request.url}"
            val cached = cache[key]
            if (cached != null && System.currentTimeMillis() - cached.timestamp < request.cacheTtlMs) {
                onResult(cached.result.copy(cached = true))
                return requestId
            }
        }

        worker.execute {
            val startTime = System.currentTimeMillis()
            var connection: HttpURLConnection? = null

            try {
                if (closed.get() || cancelFlags[requestId]?.get() == true) {
                    handler.post { onResult(NetworkResult(false, 0, "", "Cancelled")) }
                    return@execute
                }

                val url = URL(request.url)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = request.method
                    connectTimeout = request.timeoutMs
                    readTimeout = request.timeoutMs
                    doInput = true
                    instanceFollowRedirects = request.followRedirects

                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "StealthBot/1.0")

                    request.headers.forEach { (k, v) -> setRequestProperty(k, v) }

                    if (request.body != null &&
                        (request.method == "POST" || request.method == "PUT" ||
                            request.method == "PATCH" || request.method == "DELETE")) {
                        doOutput = true
                        outputStream.use { os ->
                            os.write(request.body.toByteArray(Charsets.UTF_8))
                            os.flush()
                        }
                    }
                }

                val responseCode = connection.responseCode
                val inputStream = if (responseCode in 200..299) connection.inputStream
                else connection.errorStream

                val responseString = inputStream?.let { stream ->
                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                } ?: ""

                val headers = connection.headerFields ?: emptyMap()
                val duration = System.currentTimeMillis() - startTime

                val result = NetworkResult(
                    success = responseCode in 200..299,
                    statusCode = responseCode,
                    responseBody = responseString,
                    headers = headers,
                    durationMs = duration,
                    url = request.url
                )

                if (request.useCache && result.success) {
                    cache["${request.method}:${request.url}"] = CachedResponse(result, System.currentTimeMillis())
                }

                handler.post { onResult(result) }

            } catch (e: Exception) {
                Log.e(TAG, "Request exception: ${e.message}")
                val duration = System.currentTimeMillis() - startTime
                val result = NetworkResult(
                    success = false,
                    statusCode = 0,
                    responseBody = "",
                    errorMessage = e.message,
                    durationMs = duration,
                    url = request.url
                )
                handler.post { onResult(result) }
            } finally {
                connection?.disconnect()
                cancelFlags.remove(requestId)
            }
        }

        return requestId
    }

    fun get(url: String, headers: Map<String, String> = emptyMap(), onResult: (NetworkResult) -> Unit): String =
        execute(Request(url = url, method = "GET", headers = headers), onResult)

    fun post(url: String, body: String, headers: Map<String, String> = emptyMap(), onResult: (NetworkResult) -> Unit): String =
        execute(Request(url = url, method = "POST", headers = headers, body = body), onResult)

    fun put(url: String, body: String, headers: Map<String, String> = emptyMap(), onResult: (NetworkResult) -> Unit): String =
        execute(Request(url = url, method = "PUT", headers = headers, body = body), onResult)

    fun patch(url: String, body: String, headers: Map<String, String> = emptyMap(), onResult: (NetworkResult) -> Unit): String =
        execute(Request(url = url, method = "PATCH", headers = headers, body = body), onResult)

    fun delete(url: String, headers: Map<String, String> = emptyMap(), onResult: (NetworkResult) -> Unit): String =
        execute(Request(url = url, method = "DELETE", headers = headers), onResult)

    fun head(url: String, headers: Map<String, String> = emptyMap(), onResult: (NetworkResult) -> Unit): String =
        execute(Request(url = url, method = "HEAD", headers = headers), onResult)

    fun options(url: String, headers: Map<String, String> = emptyMap(), onResult: (NetworkResult) -> Unit): String =
        execute(Request(url = url, method = "OPTIONS", headers = headers), onResult)

    fun postJson(url: String, json: JSONObject, onResult: (NetworkResult) -> Unit): String =
        post(url, json.toString(), emptyMap(), onResult)

    fun executeWithInfiniteRetry(
        request: Request,
        onResult: (NetworkResult) -> Unit
    ): String {
        val requestId = request.id
        var attempt = 0

        fun tryOnce() {
            if (closed.get() || cancelFlags[requestId]?.get() == true) {
                onResult(NetworkResult(false, 0, "", "Cancelled"))
                return
            }

            attempt++
            execute(request) { result ->
                if (result.success) {
                    onResult(result)
                } else if (closed.get() || cancelFlags[requestId]?.get() == true) {
                    onResult(NetworkResult(false, 0, "", "Cancelled"))
                } else {
                    val delay = (500L * attempt).coerceAtMost(5000L)
                    Log.w(TAG, "Retry #$attempt in ${delay}ms")
                    handler.postDelayed({ tryOnce() }, delay)
                }
            }
        }

        tryOnce()
        return requestId
    }

    fun downloadFile(
        url: String,
        destPath: String,
        onProgress: ((Long, Long) -> Unit)? = null,
        onResult: (NetworkResult) -> Unit
    ): String {
        val request = Request(url = url, method = "GET", timeoutMs = 60_000)
        val requestId = request.id
        cancelFlags[requestId] = AtomicBoolean(false)

        worker.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    doInput = true
                }

                val totalSize = connection.contentLengthLong
                var downloaded: Long = 0

                connection.inputStream.use { input ->
                    FileOutputStream(destPath).use { output ->
                        val buffer = ByteArray(8192)
                        var bytes: Int
                        while (input.read(buffer).also { bytes = it } != -1) {
                            if (closed.get() || cancelFlags[requestId]?.get() == true) {
                                handler.post { onResult(NetworkResult(false, 0, "", "Cancelled")) }
                                return@execute
                            }
                            output.write(buffer, 0, bytes)
                            downloaded += bytes
                            if (totalSize > 0) {
                                handler.post { onProgress?.invoke(downloaded, totalSize) }
                            }
                        }
                    }
                }

                val result = NetworkResult(
                    success = true,
                    statusCode = connection.responseCode,
                    responseBody = destPath,
                    url = url
                )
                handler.post { onResult(result) }
            } catch (e: Exception) {
                handler.post { onResult(NetworkResult(false, 0, "", e.message)) }
            } finally {
                connection?.disconnect()
                cancelFlags.remove(requestId)
            }
        }
        return requestId
    }

    fun uploadFile(
        url: String,
        filePath: String,
        fieldName: String = "file",
        extraFields: Map<String, String> = emptyMap(),
        onProgress: ((Long, Long) -> Unit)? = null,
        onResult: (NetworkResult) -> Unit
    ): String {
        val requestId = UUID.randomUUID().toString()
        cancelFlags[requestId] = AtomicBoolean(false)

        worker.execute {
            var connection: HttpURLConnection? = null
            val boundary = "----StealthBoundary${System.currentTimeMillis()}"
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    doOutput = true
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                }

                val file = File(filePath)
                val totalSize = file.length()

                DataOutputStream(connection.outputStream).use { dos ->
                    extraFields.forEach { (k, v) ->
                        dos.writeBytes("--$boundary\r\n")
                        dos.writeBytes("Content-Disposition: form-data; name=\"$k\"\r\n\r\n")
                        dos.writeBytes("$v\r\n")
                    }

                    dos.writeBytes("--$boundary\r\n")
                    dos.writeBytes("Content-Disposition: form-data; name=\"$fieldName\"; filename=\"${file.name}\"\r\n")
                    dos.writeBytes("Content-Type: application/octet-stream\r\n\r\n")

                    file.inputStream().use { fis ->
                        val buffer = ByteArray(8192)
                        var bytes: Int
                        var uploaded: Long = 0
                        while (fis.read(buffer).also { bytes = it } != -1) {
                            if (closed.get() || cancelFlags[requestId]?.get() == true) {
                                handler.post { onResult(NetworkResult(false, 0, "", "Cancelled")) }
                                return@execute
                            }
                            dos.write(buffer, 0, bytes)
                            uploaded += bytes
                            handler.post { onProgress?.invoke(uploaded, totalSize) }
                        }
                    }

                    dos.writeBytes("\r\n--$boundary--\r\n")
                    dos.flush()
                }

                val code = connection.responseCode
                val body = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = body?.let {
                    BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
                } ?: ""

                val result = NetworkResult(code in 200..299, code, response, url = url)
                handler.post { onResult(result) }
            } catch (e: Exception) {
                handler.post { onResult(NetworkResult(false, 0, "", e.message)) }
            } finally {
                connection?.disconnect()
                cancelFlags.remove(requestId)
            }
        }
        return requestId
    }

    fun cancel(requestId: String) {
        cancelFlags[requestId]?.set(true)
    }

    fun cancelAll() {
        cancelFlags.values.forEach { it.set(true) }
        cancelFlags.clear()
    }

    fun clearCache() {
        cache.clear()
        Log.d(TAG, "Cache cleared.")
    }

    fun getCacheSize(): Int = cache.size

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancelAll()
        worker.shutdownNow()
        cache.clear()
        Log.d(TAG, "NetworkHandler closed.")
    }
}
