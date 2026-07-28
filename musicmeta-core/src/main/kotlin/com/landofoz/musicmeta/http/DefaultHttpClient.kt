package com.landofoz.musicmeta.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
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
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

/**
 * When the enclosing `enrich()` deadline expires, installed by the engine inside its
 * `withTimeoutOrNull`. A retry reads it to refuse a sleep that would run past the deadline, since
 * a cancelled fan-out loses every provider's in-flight work.
 *
 * Absent when a consumer drives [HttpClient] directly; [DefaultHttpClient] then falls back to its
 * own `MAX_RETRY_AFTER_SEC`, which is what that constant is for.
 */
internal class EnrichDeadline(budgetMs: Long) : AbstractCoroutineContextElement(EnrichDeadline) {
    private val atNanos = System.nanoTime() + budgetMs * 1_000_000
    val remainingMs: Long get() = (atNanos - System.nanoTime()) / 1_000_000

    companion object Key : CoroutineContext.Key<EnrichDeadline>
}

class DefaultHttpClient(
    private val userAgent: String,
    private val timeoutMs: Int = 10_000,
    private val maxRetries: Int = 3,
) : HttpClient {

    override suspend fun fetchRedirectUrlResult(url: String): HttpResult<String> =
        retryingRateLimited { fetchRedirectUrlOnce(url) }

    private suspend fun fetchRedirectUrlOnce(url: String): HttpResult<String> =
        withContext(Dispatchers.IO) {
            try {
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
                } finally {
                    conn.disconnect()
                }
            } catch (e: IOException) {
                HttpResult.NetworkError(e.message ?: "Network error", e)
            }
        }

    override suspend fun fetchJsonResult(url: String): HttpResult<JSONObject> =
        fetchJsonResult(url, emptyMap())

    override suspend fun fetchJsonResult(
        url: String,
        headers: Map<String, String>,
    ): HttpResult<JSONObject> = retryingRateLimited { fetchJsonOnce(url, headers) }

    private suspend fun fetchJsonOnce(
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
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            HttpResult.NetworkError(e.message ?: "Network error", e)
        }
    }

    override suspend fun fetchJsonArrayResult(url: String): HttpResult<JSONArray> =
        retryingRateLimited { fetchJsonArrayOnce(url) }

    private suspend fun fetchJsonArrayOnce(url: String): HttpResult<JSONArray> =
        withContext(Dispatchers.IO) {
            try {
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
                } finally {
                    conn.disconnect()
                }
            } catch (e: IOException) {
                HttpResult.NetworkError(e.message ?: "Network error", e)
            }
        }

    override suspend fun postJsonResult(url: String, body: String): HttpResult<JSONObject> =
        retryingRateLimited { postJsonOnce(url, body) }

    private suspend fun postJsonOnce(url: String, body: String): HttpResult<JSONObject> =
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
                } finally {
                    conn.disconnect()
                }
            } catch (e: IOException) {
                HttpResult.NetworkError(e.message ?: "Network error", e)
            }
        }

    override suspend fun postJsonArrayResult(url: String, body: String): HttpResult<JSONArray> =
        retryingRateLimited { postJsonArrayOnce(url, body) }

    private suspend fun postJsonArrayOnce(url: String, body: String): HttpResult<JSONArray> =
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
                } finally {
                    conn.disconnect()
                }
            } catch (e: IOException) {
                HttpResult.NetworkError(e.message ?: "Network error", e)
            }
        }

    private fun readErrorBody(conn: HttpURLConnection): String? =
        try { conn.errorStream?.bufferedReader()?.use { it.readText() } } catch (_: IOException) { null }

    /**
     * Retries a [HttpResult.RateLimited] — up to [maxRetries] attempts, honouring `Retry-After` —
     * so a 429 does not depend on which method the caller reached for.
     * Returns the `RateLimited` unretried when the wait does not fit in the time left.
     */
    private suspend fun <T> retryingRateLimited(request: suspend () -> HttpResult<T>): HttpResult<T> {
        repeat(maxRetries - 1) { attempt ->
            val result = request()
            if (result !is HttpResult.RateLimited) return result
            delay(retryWaitMs(result.retryAfterMs, attempt) ?: return result)
        }
        return request()
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

    private fun HttpURLConnection.retryAfterMs(): Long? =
        getHeaderField("Retry-After")?.toLongOrNull()?.let { it * 1000 }

    /**
     * How long to wait before retrying, or `null` when that wait does not fit in the time left —
     * sleeping past the enclosing [EnrichDeadline] guarantees a timeout that loses every other
     * provider's in-flight work, so returning what we already have beats waiting for it. Standalone,
     * with no deadline installed, the budget is [MAX_RETRY_AFTER_SEC].
     */
    private suspend fun retryWaitMs(retryAfterMs: Long?, attempt: Int): Long? {
        val base = retryAfterMs ?: 2000L * (1L shl attempt)
        val budgetMs = currentCoroutineContext()[EnrichDeadline]?.remainingMs
            ?: (MAX_RETRY_AFTER_SEC * 1000)
        // Compared before jitter, so the ceiling is a hard figure rather than a coin flip near it.
        if (base > budgetMs) return null
        return (base + (base * 0.25 * (Random.nextDouble() * 2 - 1)).toLong()).coerceAtLeast(1000L)
    }

    private companion object {
        const val MAX_RETRY_AFTER_SEC = 120L
    }
}
