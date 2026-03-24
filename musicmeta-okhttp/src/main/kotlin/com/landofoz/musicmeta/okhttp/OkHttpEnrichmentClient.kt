package com.landofoz.musicmeta.okhttp

import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.HttpResult
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
import java.io.IOException

/**
 * OkHttp adapter for [HttpClient].
 *
 * - No built-in retry logic (unlike DefaultHttpClient). OkHttp users add retry
 *   via interceptors on the provided [OkHttpClient] instance.
 * - Gzip decompression handled transparently by OkHttp. Do NOT add Accept-Encoding
 *   manually; setting it disables OkHttp's transparent decompression and delivers
 *   raw gzip bytes to the JSON parser.
 * - Timeouts inherited from the caller's [OkHttpClient] instance.
 * - User-Agent set on every request via the [userAgent] constructor parameter.
 */
class OkHttpEnrichmentClient(
    private val client: OkHttpClient,
    private val userAgent: String = "MusicEnrichmentEngine/1.0",
) : HttpClient {

    private val noRedirectClient = client.newBuilder().followRedirects(false).build()

    // region Nullable GET methods

    override suspend fun fetchJson(url: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            client.newCall(buildGetRequest(url)).execute().use { response ->
                if (response.code !in 200..299) return@withContext null
                val text = response.body?.string() ?: return@withContext null
                try { JSONObject(text) } catch (_: JSONException) { null }
            }
        } catch (_: IOException) { null }
    }

    override suspend fun fetchJsonArray(url: String): JSONArray? = withContext(Dispatchers.IO) {
        try {
            client.newCall(buildGetRequest(url)).execute().use { response ->
                if (response.code !in 200..299) return@withContext null
                val text = response.body?.string() ?: return@withContext null
                try { JSONArray(text) } catch (_: JSONException) { null }
            }
        } catch (_: IOException) { null }
    }

    override suspend fun fetchBody(url: String): String? = withContext(Dispatchers.IO) {
        try {
            client.newCall(buildGetRequest(url)).execute().use { response ->
                if (response.code !in 200..299) return@withContext null
                response.body?.string()
            }
        } catch (_: IOException) { null }
    }

    override suspend fun fetchRedirectUrl(url: String): String? = withContext(Dispatchers.IO) {
        try {
            noRedirectClient.newCall(buildGetRequest(url)).execute().use { response ->
                when {
                    response.code in 300..399 -> response.header("Location")
                    response.code in 200..299 -> url
                    else -> null
                }
            }
        } catch (_: IOException) { null }
    }

    // endregion

    // region Nullable POST methods

    override suspend fun postJson(url: String, body: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            client.newCall(buildPostRequest(url, body)).execute().use { response ->
                if (response.code !in 200..299) return@withContext null
                val text = response.body?.string() ?: return@withContext null
                try { JSONObject(text) } catch (_: JSONException) { null }
            }
        } catch (_: IOException) { null }
    }

    override suspend fun postJsonArray(url: String, body: String): JSONArray? = withContext(Dispatchers.IO) {
        try {
            client.newCall(buildPostRequest(url, body)).execute().use { response ->
                if (response.code !in 200..299) return@withContext null
                val text = response.body?.string() ?: return@withContext null
                try { JSONArray(text) } catch (_: JSONException) { null }
            }
        } catch (_: IOException) { null }
    }

    // endregion

    // region HttpResult GET methods

    override suspend fun fetchJsonResult(url: String): HttpResult<JSONObject> =
        withContext(Dispatchers.IO) {
            try {
                client.newCall(buildGetRequest(url)).execute().use { response ->
                    parseJsonResult(response) { JSONObject(it) }
                }
            } catch (e: IOException) {
                HttpResult.NetworkError(e.message ?: "Network error", e)
            }
        }

    override suspend fun fetchJsonArrayResult(url: String): HttpResult<JSONArray> =
        withContext(Dispatchers.IO) {
            try {
                client.newCall(buildGetRequest(url)).execute().use { response ->
                    parseJsonResult(response) { JSONArray(it) }
                }
            } catch (e: IOException) {
                HttpResult.NetworkError(e.message ?: "Network error", e)
            }
        }

    // endregion

    // region HttpResult POST methods

    override suspend fun postJsonResult(url: String, body: String): HttpResult<JSONObject> =
        withContext(Dispatchers.IO) {
            try {
                client.newCall(buildPostRequest(url, body)).execute().use { response ->
                    parseJsonResult(response) { JSONObject(it) }
                }
            } catch (e: IOException) {
                HttpResult.NetworkError(e.message ?: "Network error", e)
            }
        }

    override suspend fun postJsonArrayResult(url: String, body: String): HttpResult<JSONArray> =
        withContext(Dispatchers.IO) {
            try {
                client.newCall(buildPostRequest(url, body)).execute().use { response ->
                    parseJsonResult(response) { JSONArray(it) }
                }
            } catch (e: IOException) {
                HttpResult.NetworkError(e.message ?: "Network error", e)
            }
        }

    // endregion

    // region Private helpers

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
     */
    private fun <T : Any> parseJsonResult(response: Response, parse: (String) -> T): HttpResult<T> {
        val code = response.code
        return when {
            code == 429 -> {
                val retryAfterMs = response.header("Retry-After")?.toLongOrNull()?.let { it * 1000 }
                HttpResult.RateLimited(retryAfterMs)
            }
            code in 400..499 -> HttpResult.ClientError(code, response.body?.string())
            code in 500..599 -> HttpResult.ServerError(code, response.body?.string())
            code in 200..299 -> {
                val text = response.body?.string() ?: ""
                try {
                    HttpResult.Ok(parse(text), code)
                } catch (e: JSONException) {
                    HttpResult.NetworkError("JSON parse error: ${e.message}", e)
                }
            }
            else -> HttpResult.ClientError(code)
        }
    }

    // endregion
}
