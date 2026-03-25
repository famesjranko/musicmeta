package com.landofoz.musicmeta.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.GZIPInputStream
import kotlin.random.Random

class DefaultHttpClient(
    private val userAgent: String,
    private val timeoutMs: Int = 10_000,
    private val maxRetries: Int = 3,
) : HttpClient {

    override suspend fun fetchJson(url: String): JSONObject? = withContext(Dispatchers.IO) {
        try { get(url)?.let { JSONObject(it) } } catch (_: JSONException) { null }
    }

    override suspend fun fetchJsonArray(url: String): JSONArray? = withContext(Dispatchers.IO) {
        try { get(url)?.let { JSONArray(it) } } catch (_: JSONException) { null }
    }

    override suspend fun fetchBody(url: String): String? = withContext(Dispatchers.IO) {
        try { get(url) } catch (_: IOException) { null }
    }

    override suspend fun fetchRedirectUrl(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val conn = openConnection(url).apply { instanceFollowRedirects = false; connect() }
            try {
                when { conn.responseCode in 200..299 -> url; conn.responseCode in 300..399 -> conn.getHeaderField("Location"); else -> null }
            } finally { conn.disconnect() }
        } catch (_: IOException) { null }
    }

    override suspend fun fetchJsonResult(url: String): HttpResult<JSONObject> =
        fetchJsonResult(url, emptyMap())

    override suspend fun fetchJsonResult(
        url: String,
        headers: Map<String, String>,
    ): HttpResult<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val conn = openConnection(url).apply {
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                connect()
            }
            try {
                val code = conn.responseCode
                when {
                    code == 429 -> {
                        val retryMs = conn.getHeaderField("Retry-After")
                            ?.toLongOrNull()?.let { it * 1000 }
                        HttpResult.RateLimited(retryMs)
                    }
                    code in 400..499 -> {
                        val body = readErrorBody(conn)
                        HttpResult.ClientError(code, body)
                    }
                    code in 500..599 -> {
                        val body = readErrorBody(conn)
                        HttpResult.ServerError(code, body)
                    }
                    code in 200..299 -> {
                        val text = conn.responseStream().bufferedReader()
                            .use { it.readText() }
                        try {
                            HttpResult.Ok(JSONObject(text), code)
                        } catch (e: JSONException) {
                            HttpResult.NetworkError(
                                "JSON parse error: ${e.message}", e,
                            )
                        }
                    }
                    else -> HttpResult.ClientError(code)
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            HttpResult.NetworkError(e.message ?: "Network error", e)
        }
    }

    override suspend fun fetchJsonArrayResult(url: String): HttpResult<JSONArray> =
        withContext(Dispatchers.IO) {
            try {
                val conn = openConnection(url).apply { connect() }
                try {
                    val code = conn.responseCode
                    when {
                        code == 429 -> {
                            val retryMs = conn.getHeaderField("Retry-After")
                                ?.toLongOrNull()?.let { it * 1000 }
                            HttpResult.RateLimited(retryMs)
                        }
                        code in 400..499 -> {
                            val body = readErrorBody(conn)
                            HttpResult.ClientError(code, body)
                        }
                        code in 500..599 -> {
                            val body = readErrorBody(conn)
                            HttpResult.ServerError(code, body)
                        }
                        code in 200..299 -> {
                            val text = conn.responseStream().bufferedReader()
                                .use { it.readText() }
                            try {
                                HttpResult.Ok(JSONArray(text), code)
                            } catch (e: JSONException) {
                                HttpResult.NetworkError(
                                    "JSON parse error: ${e.message}", e,
                                )
                            }
                        }
                        else -> HttpResult.ClientError(code)
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: IOException) {
                HttpResult.NetworkError(e.message ?: "Network error", e)
            }
        }

    override suspend fun postJson(url: String, body: String): JSONObject? =
        withContext(Dispatchers.IO) {
            try {
                val conn = openConnection(url).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                try {
                    if (conn.responseCode !in 200..299) return@withContext null
                    val text = conn.responseStream().bufferedReader().use { it.readText() }
                    JSONObject(text)
                } finally {
                    conn.disconnect()
                }
            } catch (_: IOException) { null } catch (_: JSONException) { null }
        }

    override suspend fun postJsonArray(url: String, body: String): JSONArray? =
        withContext(Dispatchers.IO) {
            try {
                val conn = openConnection(url).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                try {
                    if (conn.responseCode !in 200..299) return@withContext null
                    val text = conn.responseStream().bufferedReader().use { it.readText() }
                    JSONArray(text)
                } finally {
                    conn.disconnect()
                }
            } catch (_: IOException) { null } catch (_: JSONException) { null }
        }

    override suspend fun postJsonResult(url: String, body: String): HttpResult<JSONObject> =
        withContext(Dispatchers.IO) {
            try {
                val conn = openConnection(url).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                try {
                    val code = conn.responseCode
                    when {
                        code == 429 -> {
                            val retryMs = conn.getHeaderField("Retry-After")
                                ?.toLongOrNull()?.let { it * 1000 }
                            HttpResult.RateLimited(retryMs)
                        }
                        code in 400..499 -> HttpResult.ClientError(code, readErrorBody(conn))
                        code in 500..599 -> HttpResult.ServerError(code, readErrorBody(conn))
                        code in 200..299 -> {
                            val text = conn.responseStream().bufferedReader().use { it.readText() }
                            try {
                                HttpResult.Ok(JSONObject(text), code)
                            } catch (e: JSONException) {
                                HttpResult.NetworkError("JSON parse error: ${e.message}", e)
                            }
                        }
                        else -> HttpResult.ClientError(code)
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: IOException) {
                HttpResult.NetworkError(e.message ?: "Network error", e)
            }
        }

    override suspend fun postJsonArrayResult(url: String, body: String): HttpResult<JSONArray> =
        withContext(Dispatchers.IO) {
            try {
                val conn = openConnection(url).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                try {
                    val code = conn.responseCode
                    when {
                        code == 429 -> {
                            val retryMs = conn.getHeaderField("Retry-After")
                                ?.toLongOrNull()?.let { it * 1000 }
                            HttpResult.RateLimited(retryMs)
                        }
                        code in 400..499 -> HttpResult.ClientError(code, readErrorBody(conn))
                        code in 500..599 -> HttpResult.ServerError(code, readErrorBody(conn))
                        code in 200..299 -> {
                            val text = conn.responseStream().bufferedReader().use { it.readText() }
                            try {
                                HttpResult.Ok(JSONArray(text), code)
                            } catch (e: JSONException) {
                                HttpResult.NetworkError("JSON parse error: ${e.message}", e)
                            }
                        }
                        else -> HttpResult.ClientError(code)
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: IOException) {
                HttpResult.NetworkError(e.message ?: "Network error", e)
            }
        }

    private fun readErrorBody(conn: HttpURLConnection): String? =
        try { conn.errorStream?.bufferedReader()?.use { it.readText() } } catch (_: IOException) { null }

    private suspend fun get(url: String): String? {
        repeat(maxRetries) { attempt ->
            try {
                val conn = openConnection(url).apply { connect() }
                try {
                    if (conn.responseCode == 429 || conn.responseCode == 503) {
                        val wait = retryDelay(conn, attempt)
                        if (wait < 0) return null // Server wants us to wait too long — bail out
                        delay(wait); return@repeat
                    }
                    if (conn.responseCode !in 200..299) return null
                    return conn.responseStream().bufferedReader().use { it.readText() }
                } finally { conn.disconnect() }
            } catch (_: IOException) { if (attempt == maxRetries - 1) return null; delay(retryDelay(null, attempt)) }
        }
        return null // All retries exhausted (429/503 on every attempt)
    }

    private fun openConnection(url: String): HttpURLConnection {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", userAgent)
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Accept-Encoding", "gzip")
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        return conn
    }

    private fun HttpURLConnection.responseStream(): InputStream {
        val stream = inputStream
        return if (contentEncoding.equals("gzip", ignoreCase = true)) GZIPInputStream(stream) else stream
    }

    /**
     * Returns delay in ms, or -1 if Retry-After exceeds max wait
     * (caller should abort rather than wait an unreasonable time).
     */
    private fun retryDelay(conn: HttpURLConnection?, attempt: Int): Long {
        val retryAfterSec = conn?.getHeaderField("Retry-After")?.toLongOrNull()
        if (retryAfterSec != null && retryAfterSec > MAX_RETRY_AFTER_SEC) return -1L
        val base = if (retryAfterSec != null) retryAfterSec * 1000 else 2000L * (1L shl attempt)
        return (base + (base * 0.25 * (Random.nextDouble() * 2 - 1)).toLong()).coerceAtLeast(1000L)
    }

    private companion object {
        const val MAX_RETRY_AFTER_SEC = 120L
    }
}
