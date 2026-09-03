package com.landofoz.musicmeta.http

import org.json.JSONArray
import org.json.JSONObject

/**
 * HTTP abstraction for enrichment providers.
 *
 * Every method returns a typed [HttpResult], so a 429, a 5xx or a dropped connection stays
 * distinguishable from an empty result all the way to the provider — which is what decides between
 * `Error` (retryable, opens the breaker, engages `STALE_IF_ERROR`) and `NotFound`. Nothing here is
 * defaulted: an implementor writes six methods, and the library calls all six.
 */
public interface HttpClient {

    /** GET request, response parsed as a JSON object. */
    public suspend fun fetchJsonResult(url: String): HttpResult<JSONObject>

    /**
     * GET request carrying per-request headers, response parsed as a JSON object.
     *
     * Make this the real request and let [fetchJsonResult] delegate to it with an empty map — the
     * headers are where `Authorization` arrives, so an implementation that drops them authenticates
     * nothing and every keyed provider reads as unauthorised.
     */
    public suspend fun fetchJsonResult(url: String, headers: Map<String, String>): HttpResult<JSONObject>

    /** GET request, response parsed as a JSON array. */
    public suspend fun fetchJsonArrayResult(url: String): HttpResult<JSONArray>

    /**
     * GET request that resolves a redirect instead of following it — Cover Art Archive answers 307.
     *
     * `Ok` carries the resolved URL: the request URL itself on a 2xx, the `Location` header on a
     * 3xx. A 3xx with no `Location` is a [HttpResult.ClientError] — there is no URL to return.
     *
     * Report the real status here. Collapsing a 429, a 5xx or a dropped connection into a 404 turns
     * every transient failure on the artwork path into "no artwork", which the providers hand back
     * as `NotFound` and the breaker never sees.
     */
    public suspend fun fetchRedirectUrlResult(url: String): HttpResult<String>

    /**
     * POST request with a JSON body, response parsed as a JSON object.
     *
     * **Send only what is safe to send twice.** [DefaultHttpClient] retries a POST on a dropped
     * connection as well as on a 429 or a 5xx, and those are not the same guarantee: a shed status
     * says the server rejected the request, while a dropped connection leaves no way to know
     * whether it was processed. The library's own POSTs are bulk *reads* — ListenBrainz takes a
     * list of MBIDs this way — so a repeat costs a request and nothing else. A caller reaching for
     * this method with something that mutates state needs its own idempotency key, or its own
     * client.
     */
    public suspend fun postJsonResult(url: String, body: String): HttpResult<JSONObject>

    /**
     * POST request with a JSON body, response parsed as a JSON array.
     *
     * Retried on a dropped connection, same as [postJsonResult] — send only what is safe to send
     * twice.
     */
    public suspend fun postJsonArrayResult(url: String, body: String): HttpResult<JSONArray>
}
