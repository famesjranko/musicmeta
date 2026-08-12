package com.landofoz.musicmeta.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.GZIPInputStream

/**
 * The `HttpURLConnection` client, retrying through [BudgetedTransientRetry] on every method.
 *
 * Each `…Once` function maps one response and lets an [IOException] fly, which is how the ladder
 * hears a transport failure; nothing here decides what retries.
 */
class DefaultHttpClient(
    private val userAgent: String,
    private val timeoutMs: Int = 10_000,
    private val maxRetries: Int = 3,
) : HttpClient {

    private val retry = BudgetedTransientRetry(attemptTimeoutMs = timeoutMs, maxAttempts = maxRetries)

    override suspend fun fetchRedirectUrlResult(url: String): HttpResult<String> =
        retry.execute { fetchRedirectUrlOnce(url) }

    private suspend fun fetchRedirectUrlOnce(url: String): HttpAttempt<String> =
        withContext(Dispatchers.IO) {
            val conn = openConnection(url).apply { instanceFollowRedirects = false; connect() }
            try {
                val code = conn.responseCode
                when {
                    code == 429 -> HttpResult.RateLimited(conn.retryAfterMs())
                    code in 200..299 -> HttpResult.Ok(url, code)
                    code in 300..399 -> conn.getHeaderField("Location")
                        ?.let { HttpResult.Ok(it, code) }
                        ?: HttpResult.ClientError(code, "redirect without a Location header")
                    code in 500..599 -> HttpResult.ServerError(code, readErrorBody(conn))
                    else -> HttpResult.ClientError(code, readErrorBody(conn))
                }
                    .asAttempt(conn.retryAfterHeader())
            } finally {
                conn.disconnect()
            }
        }

    override suspend fun fetchJsonResult(url: String): HttpResult<JSONObject> =
        fetchJsonResult(url, emptyMap())

    override suspend fun fetchJsonResult(
        url: String,
        headers: Map<String, String>,
    ): HttpResult<JSONObject> = retry.execute { fetchJsonOnce(url, headers) }

    private suspend fun fetchJsonOnce(
        url: String,
        headers: Map<String, String>,
    ): HttpAttempt<JSONObject> = withContext(Dispatchers.IO) {
        val conn = openConnection(url).apply {
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            connect()
        }
        try {
            val code = conn.responseCode
            when {
                code == 429 -> HttpResult.RateLimited(conn.retryAfterMs())
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
                .asAttempt(conn.retryAfterHeader())
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun fetchJsonArrayResult(url: String): HttpResult<JSONArray> =
        retry.execute { fetchJsonArrayOnce(url) }

    private suspend fun fetchJsonArrayOnce(url: String): HttpAttempt<JSONArray> =
        withContext(Dispatchers.IO) {
            val conn = openConnection(url).apply { connect() }
            try {
                val code = conn.responseCode
                when {
                    code == 429 -> HttpResult.RateLimited(conn.retryAfterMs())
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
                    .asAttempt(conn.retryAfterHeader())
            } finally {
                conn.disconnect()
            }
        }

    override suspend fun postJsonResult(url: String, body: String): HttpResult<JSONObject> =
        retry.execute { postJsonOnce(url, body) }

    private suspend fun postJsonOnce(url: String, body: String): HttpAttempt<JSONObject> =
        withContext(Dispatchers.IO) {
            val conn = openConnection(url).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
            try {
                // Inside the try that disconnects: a write that fails out here leaks the socket.
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                when {
                    code == 429 -> HttpResult.RateLimited(conn.retryAfterMs())
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
                    .asAttempt(conn.retryAfterHeader())
            } finally {
                conn.disconnect()
            }
        }

    override suspend fun postJsonArrayResult(url: String, body: String): HttpResult<JSONArray> =
        retry.execute { postJsonArrayOnce(url, body) }

    private suspend fun postJsonArrayOnce(url: String, body: String): HttpAttempt<JSONArray> =
        withContext(Dispatchers.IO) {
            val conn = openConnection(url).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
            try {
                // Inside the try that disconnects: a write that fails out here leaks the socket.
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                when {
                    code == 429 -> HttpResult.RateLimited(conn.retryAfterMs())
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
                    .asAttempt(conn.retryAfterHeader())
            } finally {
                conn.disconnect()
            }
        }

    private fun readErrorBody(conn: HttpURLConnection): String? =
        try { conn.errorStream?.bufferedReader()?.use { it.readText() } } catch (_: IOException) { null }

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

    /** Must be read before the connection is disconnected, which is why every call site is inline. */
    private fun HttpURLConnection.retryAfterHeader(): String? = getHeaderField("Retry-After")

    private fun HttpURLConnection.retryAfterMs(): Long? =
        retryAfterHeader()?.toLongOrNull()?.let { it * 1000 }
}
