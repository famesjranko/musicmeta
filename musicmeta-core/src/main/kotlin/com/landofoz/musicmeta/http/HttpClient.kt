package com.landofoz.musicmeta.http

import org.json.JSONArray
import org.json.JSONObject

/** HTTP abstraction for enrichment providers. */
interface HttpClient {

    /** GET request, parse response as JSON object. Returns null on error. */
    suspend fun fetchJson(url: String): JSONObject?

    /** GET request, parse response as JSON array. Returns null on error. */
    suspend fun fetchJsonArray(url: String): JSONArray?

    /** GET request, return raw response body. Returns null on error. */
    suspend fun fetchBody(url: String): String?

    /**
     * GET request that follows redirects. Returns the final redirect URL.
     * Useful for Cover Art Archive which returns 307 redirects.
     * Returns null on error.
     */
    suspend fun fetchRedirectUrl(url: String): String?

    /**
     * [fetchRedirectUrl] returning a typed [HttpResult], so a 429, a 5xx or a dropped connection on
     * the redirect path is distinguishable from "no such artwork" instead of collapsing to `null`.
     *
     * `Ok` carries the resolved URL: the request URL itself on a 2xx, the `Location` header on a
     * 3xx. A 3xx with no `Location` is a [HttpResult.ClientError] — there is no URL to return.
     *
     * The default preserves the old semantics exactly (a `null` becomes a 404 `ClientError`), so
     * every existing implementor keeps compiling and behaving as before. Override it wherever the
     * status is actually available.
     */
    suspend fun fetchRedirectUrlResult(url: String): HttpResult<String> =
        fetchRedirectUrl(url)?.let { HttpResult.Ok(it) }
            ?: HttpResult.ClientError(404, "fetchRedirectUrl returned null")

    /**
     * GET request returning typed [HttpResult] with parsed JSON body.
     * Unlike [fetchJson] which returns null on any failure, this preserves
     * the specific HTTP status for precise error handling.
     */
    suspend fun fetchJsonResult(url: String): HttpResult<JSONObject>

    /**
     * GET request returning typed [HttpResult] with custom headers.
     * Default implementation ignores headers — override in implementations
     * that need to pass Authorization or other per-request headers.
     */
    suspend fun fetchJsonResult(url: String, headers: Map<String, String>): HttpResult<JSONObject> =
        fetchJsonResult(url)

    /**
     * GET request returning typed [HttpResult] with parsed JSON array body.
     * Unlike [fetchJsonArray] which returns null on any failure, this preserves
     * the specific HTTP status for precise error handling.
     */
    suspend fun fetchJsonArrayResult(url: String): HttpResult<JSONArray>

    /** POST request with JSON body, parse response as JSON object. Returns null on error. */
    suspend fun postJson(url: String, body: String): JSONObject?

    /** POST request with JSON body, parse response as JSON array. Returns null on error. */
    suspend fun postJsonArray(url: String, body: String): JSONArray?

    /**
     * POST request returning typed [HttpResult] with parsed JSON object body.
     * Unlike [postJson] which returns null on any failure, this preserves
     * the specific HTTP status for precise error handling.
     */
    suspend fun postJsonResult(url: String, body: String): HttpResult<JSONObject>

    /**
     * POST request returning typed [HttpResult] with parsed JSON array body.
     * Unlike [postJsonArray] which returns null on any failure, this preserves
     * the specific HTTP status for precise error handling.
     */
    suspend fun postJsonArrayResult(url: String, body: String): HttpResult<JSONArray>
}
