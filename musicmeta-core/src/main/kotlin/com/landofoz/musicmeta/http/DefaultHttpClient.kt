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
public class DefaultHttpClient(
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
        val conn = connectFollowingRedirects(url, configureGet = {
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        })
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
            val conn = connectFollowingRedirects(url)
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
            val conn = connectFollowingRedirects(url, firstHop = { u ->
                val first = openConnection(u).apply {
                    requestMethod = "POST"
                    instanceFollowRedirects = false
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }
                try {
                    // Inside a try that disconnects: a write that fails out here leaks the socket.
                    first.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                } catch (e: IOException) {
                    first.disconnect()
                    throw e
                }
                first
            })
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
                    .asAttempt(conn.retryAfterHeader())
            } finally {
                conn.disconnect()
            }
        }

    override suspend fun postJsonArrayResult(url: String, body: String): HttpResult<JSONArray> =
        retry.execute { postJsonArrayOnce(url, body) }

    private suspend fun postJsonArrayOnce(url: String, body: String): HttpAttempt<JSONArray> =
        withContext(Dispatchers.IO) {
            val conn = connectFollowingRedirects(url, firstHop = { u ->
                val first = openConnection(u).apply {
                    requestMethod = "POST"
                    instanceFollowRedirects = false
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }
                try {
                    // Inside a try that disconnects: a write that fails out here leaks the socket.
                    first.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                } catch (e: IOException) {
                    first.disconnect()
                    throw e
                }
                first
            })
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
                    .asAttempt(conn.retryAfterHeader())
            } finally {
                conn.disconnect()
            }
        }

    private fun readErrorBody(conn: HttpURLConnection): String? =
        try { conn.errorStream?.bufferedReader()?.use { it.readText() } } catch (_: IOException) { null }

    private suspend fun openConnection(url: String): HttpURLConnection {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", userAgent)
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Accept-Encoding", "gzip")
        // Clamped to the enclosing enrich() deadline: cancellation cannot interrupt a thread
        // already blocked in connect or read, so the deadline only binds if the socket is told
        // about it before the blocking call starts.
        val perLegMs = legBudgetMs(timeoutMs)
        conn.connectTimeout = perLegMs
        conn.readTimeout = perLegMs
        return conn
    }

    /**
     * Opens [url] and follows redirects one hop at a time, so every hop's timeouts are re-clamped
     * to what is left of the enclosing deadline. The JDK's own following opens each hop inside one
     * uninterruptible `getResponseCode` with the first leg's timeouts, so a chain of individually
     * fast hops can outlive any deadline — measured in
     * `.scratch/enrich-deadline-not-a-bound/prototypes/`.
     *
     * Follows what the JDK follows: 301/302/303 re-issued as GET, 307/308 only for a request that
     * was GET to begin with, never across a protocol change, and never past [MAX_REDIRECTS] hops.
     * Anything else — a 3xx with no `Location` included — is returned for the caller to map.
     */
    private suspend fun connectFollowingRedirects(
        url: String,
        configureGet: HttpURLConnection.() -> Unit = {},
        firstHop: (suspend (String) -> HttpURLConnection)? = null,
    ): HttpURLConnection {
        var isGet = firstHop == null
        var conn = firstHop?.invoke(url) ?: openGetConnection(url).apply(configureGet)
        repeat(MAX_REDIRECTS) {
            val code = try {
                conn.responseCode
            } catch (e: IOException) {
                conn.disconnect()
                throw e
            }
            // 301/302/303 are re-issued as GET whatever the method was; 307/308 keep the method,
            // which for a POST would mean re-sending the body — the JDK does not, and neither do
            // we, so those surface to the caller instead.
            val followable = code in PERMANENT_REDIRECTS || (isGet && code in TEMPORARY_REDIRECTS)
            if (!followable) return conn
            val location = conn.getHeaderField("Location") ?: return conn
            val target = conn.url.toURI().resolve(location)
            if (!target.scheme.equals(conn.url.protocol, ignoreCase = true)) return conn
            conn.disconnect()
            isGet = true
            conn = openGetConnection(target.toString()).apply(configureGet)
        }
        conn.disconnect()
        throw IOException("more than $MAX_REDIRECTS redirects from $url")
    }

    private suspend fun openGetConnection(url: String): HttpURLConnection =
        openConnection(url).apply { instanceFollowRedirects = false }

    private fun HttpURLConnection.responseStream(): InputStream {
        val stream = inputStream
        return if (contentEncoding.equals("gzip", ignoreCase = true)) GZIPInputStream(stream) else stream
    }

    /** Must be read before the connection is disconnected, which is why every call site is inline. */
    private fun HttpURLConnection.retryAfterHeader(): String? = getHeaderField("Retry-After")

    private fun HttpURLConnection.retryAfterMs(): Long? =
        retryAfterHeader()?.toLongOrNull()?.let { it * 1000 }
}

/** The JDK's own `http.maxRedirects` default, kept for parity. */
private const val MAX_REDIRECTS = 20

private val PERMANENT_REDIRECTS = setOf(301, 302, 303)
private val TEMPORARY_REDIRECTS = setOf(307, 308)
