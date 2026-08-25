package com.landofoz.musicmeta.okhttp

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.http.BudgetedTransientRetry
import com.landofoz.musicmeta.http.HttpAttempt
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.HttpResult
import com.landofoz.musicmeta.http.asAttempt
import com.landofoz.musicmeta.http.enrichDeadlineRemainingMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OkHttp adapter for [HttpClient].
 *
 * - Transient retry comes from core's [BudgetedTransientRetry], the same ladder `DefaultHttpClient`
 *   runs on, so every sleep is charged against the enclosing `enrich()` deadline that only core can
 *   see. Retry configured on the [OkHttpClient] itself is still unbudgeted and still invisible to
 *   that deadline — leave `retryOnConnectionFailure` alone and add no retrying interceptor.
 * - Gzip decompression handled transparently by OkHttp. Do NOT add Accept-Encoding
 *   manually; setting it disables OkHttp's transparent decompression and delivers
 *   raw gzip bytes to the JSON parser.
 * - Timeouts inherited from the caller's [OkHttpClient] instance, which is also where the ladder
 *   gets what it charges an attempt — see [attemptCostMs].
 * - User-Agent set on every request via the [userAgent] constructor parameter.
 *
 * @param maxAttempts total attempts per request, not retries: pass 1 to opt out of retrying. What
 *   one attempt is charged against the enrich budget is not a knob — it comes from [client]'s own
 *   timeouts, because a figure a caller supplies is a figure that can be wrong in the one direction
 *   that silently disables the budget test.
 */
class OkHttpEnrichmentClient(
    private val client: OkHttpClient,
    private val userAgent: String = EnrichmentConfig.DEFAULT_USER_AGENT,
    maxAttempts: Int = 3,
) : HttpClient {

    private val noRedirectClient = client.newBuilder().followRedirects(false).build()

    /**
     * [base], with its call ceiling clamped to what is left of the enclosing enrich() deadline:
     * cancellation cannot interrupt a thread blocked in `execute()`, so the deadline only binds if
     * the call is told about it up front. `callTimeout`, not `connectTimeout` — OkHttp applies
     * connect/read per redirect hop, and only the call ceiling covers the whole chain. A consumer's
     * own tighter `callTimeout` stands; with no deadline installed, [base] is returned untouched.
     */
    private suspend fun budgeted(base: OkHttpClient): OkHttpClient {
        val ceilingMs = (enrichDeadlineRemainingMs() ?: return base).coerceAtLeast(1L)
        if (base.callTimeoutMillis in 1..ceilingMs.toInt()) return base
        return base.newBuilder().callTimeout(ceilingMs, TimeUnit.MILLISECONDS).build()
    }

    private val retry = BudgetedTransientRetry(client.attemptCostMs(), maxAttempts)

    override suspend fun fetchJsonResult(url: String): HttpResult<JSONObject> =
        fetchJsonResult(url, emptyMap())

    override suspend fun fetchJsonResult(
        url: String,
        headers: Map<String, String>,
    ): HttpResult<JSONObject> = retry.execute {
        withContext(Dispatchers.IO) {
            val requestBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
            headers.forEach { (k, v) -> requestBuilder.header(k, v) }
            budgeted(client).newCall(requestBuilder.build()).execute().use { response ->
                parseJsonResult(response) { JSONObject(it) }
            }
        }
    }

    override suspend fun fetchRedirectUrlResult(url: String): HttpResult<String> = retry.execute {
        withContext(Dispatchers.IO) {
            budgeted(noRedirectClient).newCall(buildGetRequest(url)).execute().use { response ->
                val code = response.code
                when {
                    code == 429 -> HttpResult.RateLimited(response.retryAfterMs())
                    code in 200..299 -> HttpResult.Ok(url, code)
                    code in 300..399 -> response.header("Location")
                        ?.let { HttpResult.Ok(it, code) }
                        ?: HttpResult.ClientError(code, "redirect without a Location header")
                    code in 500..599 -> HttpResult.ServerError(code, response.body?.string())
                    else -> HttpResult.ClientError(code, response.body?.string())
                }
                    .asAttempt(response.header(RETRY_AFTER))
            }
        }
    }

    override suspend fun fetchJsonArrayResult(url: String): HttpResult<JSONArray> = retry.execute {
        withContext(Dispatchers.IO) {
            budgeted(client).newCall(buildGetRequest(url)).execute().use { response ->
                parseJsonResult(response) { JSONArray(it) }
            }
        }
    }

    override suspend fun postJsonResult(url: String, body: String): HttpResult<JSONObject> =
        retry.execute {
            withContext(Dispatchers.IO) {
                budgeted(client).newCall(buildPostRequest(url, body)).execute().use { response ->
                    parseJsonResult(response) { JSONObject(it) }
                }
            }
        }

    override suspend fun postJsonArrayResult(url: String, body: String): HttpResult<JSONArray> =
        retry.execute {
            withContext(Dispatchers.IO) {
                budgeted(client).newCall(buildPostRequest(url, body)).execute().use { response ->
                    parseJsonResult(response) { JSONArray(it) }
                }
            }
        }

    private fun buildGetRequest(url: String): Request = Request.Builder()
        .url(url)
        .header("User-Agent", userAgent)
        .header("Accept", "application/json")
        .build()

    private fun buildPostRequest(url: String, body: String): Request = Request.Builder()
        .url(url)
        .header("User-Agent", userAgent)
        .header("Accept", "application/json")
        .post(body.toRequestBody("application/json".toMediaType()))
        .build()

    /**
     * Maps HTTP status code to [HttpResult] variants and parses the body using [parse].
     * 429 is checked before the 400–499 range (it falls in that range but has different semantics).
     *
     * A [java.io.IOException] out of the call this reads is not caught anywhere in this class: that
     * is how [BudgetedTransientRetry] is told the transport failed, and catching it here would
     * report a dropped connection as a response worth keeping.
     */
    private fun <T : Any> parseJsonResult(response: Response, parse: (String) -> T): HttpAttempt<T> {
        val code = response.code
        return when {
            code == 429 -> HttpResult.RateLimited(response.retryAfterMs())
            code in 400..499 -> HttpResult.ClientError(code, response.body?.string())
            code in 500..599 -> HttpResult.ServerError(code, response.body?.string())
            code in 200..299 -> {
                val text = response.body?.string().orEmpty()
                try {
                    HttpResult.Ok(parse(text), code)
                } catch (e: JSONException) {
                    HttpResult.NetworkError("JSON parse error: ${e.message}", e)
                }
            }
            else -> HttpResult.ClientError(code)
        }
            .asAttempt(response.header(RETRY_AFTER))
    }

    private fun Response.retryAfterMs(): Long? = header(RETRY_AFTER)?.toLongOrNull()?.let { it * 1000 }
}

private const val RETRY_AFTER = "Retry-After"

/**
 * What one attempt through this client may spend, for the retry budget: the per-call ceiling where
 * one is set, otherwise connect plus read.
 *
 * Never `callTimeoutMillis` alone — it is 0 (disabled) unless the caller sets it, and a 0 charge
 * silently reduces the budget test to the sleep-only one, which hands a hanging upstream a second
 * attempt inside a deadline that had room for one. A client with every timeout disabled has no
 * honest figure at all, so it gets the ladder's default; such a client can hang past any deadline
 * whatever the ladder does.
 */
internal fun OkHttpClient.attemptCostMs(): Int =
    callTimeoutMillis.takeIf { it > 0 }
        ?: (connectTimeoutMillis + readTimeoutMillis).takeIf { it > 0 }
        ?: BudgetedTransientRetry.DEFAULT_ATTEMPT_TIMEOUT_MS
