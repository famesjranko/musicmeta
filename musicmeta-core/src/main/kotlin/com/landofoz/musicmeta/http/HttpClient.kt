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
