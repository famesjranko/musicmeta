package com.landofoz.musicmeta.testutil

import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.HttpResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class FakeHttpClient : HttpClient {
    private val jsonResponses = mutableMapOf<String, String>()
    private val sequencedResponses = mutableMapOf<String, MutableList<String>>()
    private val errors = mutableSetOf<String>()
    private val ioExceptions = mutableSetOf<String>()
    private val httpResultResponses = mutableMapOf<String, HttpResult<JSONObject>>()
    private val sequencedHttpResults = mutableMapOf<String, MutableList<HttpResult<JSONObject>>>()
    private val httpResultArrayResponses = mutableMapOf<String, HttpResult<JSONArray>>()
    private val redirectResults = mutableMapOf<String, HttpResult<String>>()
    val requestedUrls = mutableListOf<String>()
    val requestedHeaders = mutableListOf<Map<String, String>>()

    fun givenJsonResponse(urlContains: String, json: String) { jsonResponses[urlContains] = json }

    /**
     * Successive answers to the *same* URL, in order, the last one repeating — for an upstream
     * whose ordering is not stable between identical calls. MusicBrainz's recording search is one:
     * the same query returns the same recordings in a different order run to run, which is what
     * makes a repeated search a correctness question and not only a cost one.
     */
    fun givenJsonResponsesInTurn(urlContains: String, vararg json: String) {
        sequencedResponses[urlContains] = json.toMutableList()
    }

    fun givenJsonArrayResponse(urlContains: String, json: String) { jsonResponses[urlContains] = json }
    fun givenError(urlContains: String) { errors.add(urlContains) }

    /**
     * Causes fetchJsonResult (and related Result-returning methods) to throw an IOException
     * for URLs containing [urlContains]. Use this to test Provider-level error handling when
     * Api classes propagate the exception rather than returning null.
     */
    fun givenIoException(urlContains: String) { ioExceptions.add(urlContains) }

    /** One status for a URL, repeating for every call to it. */
    fun givenHttpResult(urlContains: String, result: HttpResult<JSONObject>) { httpResultResponses[urlContains] = result }
    fun givenHttpResultArray(urlContains: String, result: HttpResult<JSONArray>) { httpResultArrayResponses[urlContains] = result }

    /**
     * Successive statuses for the *same* URL, in order, the last one repeating — the shape
     * [givenJsonResponsesInTurn] uses for bodies. A recovery is only expressible this way: a single
     * [givenHttpResult] repeats, so *429 then 200* — the sequence that separates a ladder that
     * retried from one that gave up — needs the list.
     *
     * The retry ladder lives in the [HttpClient] implementation, not in the provider, and this fake
     * has none. Driving a provider through a recovery means wrapping this in a client that composes
     * `BudgetedTransientRetry`; a provider handed this fake directly sees the first entry and stops.
     */
    fun givenHttpResultsInTurn(urlContains: String, vararg results: HttpResult<JSONObject>) {
        sequencedHttpResults[urlContains] = results.toMutableList()
    }

    /**
     * The status channel for [fetchRedirectUrlResult] — how that method is told "429" or "no
     * artwork (404)". [givenError] cannot say either: it means a dropped connection, which is a
     * transient the provider reports as `Error`, not the empty result most tests mean.
     *
     * The JSON path has its own status channel in [givenHttpResult] and [givenHttpResultsInTurn].
     */
    fun givenRedirectResult(urlContains: String, result: HttpResult<String>) { redirectResults[urlContains] = result }

    override suspend fun fetchRedirectUrlResult(url: String): HttpResult<String> {
        requestedUrls.add(url)
        if (ioExceptions.any { url.contains(it) }) throw IOException("Simulated network error: $url")
        if (errors.any { url.contains(it) }) return HttpResult.NetworkError("Simulated network error")
        redirectResults.entries.firstOrNull { url.contains(it.key) }?.let { return it.value }
        // Unstubbed resolves to the request URL itself, as a 2xx no-op redirect.
        return HttpResult.Ok(bodyFor(url) ?: url)
    }

    override suspend fun fetchJsonResult(url: String): HttpResult<JSONObject> =
        fetchJsonResult(url, emptyMap())

    override suspend fun fetchJsonResult(
        url: String,
        headers: Map<String, String>,
    ): HttpResult<JSONObject> {
        requestedUrls.add(url)
        requestedHeaders.add(headers)
        if (ioExceptions.any { url.contains(it) }) throw IOException("Simulated network error: $url")
        if (errors.any { url.contains(it) }) return HttpResult.NetworkError("Simulated network error")
        httpResultFor(url)?.let { return it }
        val json = bodyFor(url)
        return if (json != null) HttpResult.Ok(JSONObject(json)) else UNSTUBBED
    }

    override suspend fun fetchJsonArrayResult(url: String): HttpResult<JSONArray> {
        requestedUrls.add(url)
        if (ioExceptions.any { url.contains(it) }) throw IOException("Simulated network error: $url")
        if (errors.any { url.contains(it) }) return HttpResult.NetworkError("Simulated network error")
        val configured = httpResultArrayResponses.entries.firstOrNull { url.contains(it.key) }
        if (configured != null) return configured.value
        val json = bodyFor(url)
        return if (json != null) HttpResult.Ok(JSONArray(json)) else UNSTUBBED
    }

    override suspend fun postJsonResult(url: String, body: String): HttpResult<JSONObject> {
        requestedUrls.add(url)
        if (ioExceptions.any { url.contains(it) }) throw IOException("Simulated network error: $url")
        if (errors.any { url.contains(it) }) return HttpResult.NetworkError("Simulated network error")
        httpResultFor(url)?.let { return it }
        val json = bodyFor(url)
        return if (json != null) HttpResult.Ok(JSONObject(json)) else UNSTUBBED
    }

    override suspend fun postJsonArrayResult(url: String, body: String): HttpResult<JSONArray> {
        requestedUrls.add(url)
        if (ioExceptions.any { url.contains(it) }) throw IOException("Simulated network error: $url")
        if (errors.any { url.contains(it) }) return HttpResult.NetworkError("Simulated network error")
        val configured = httpResultArrayResponses.entries.firstOrNull { url.contains(it.key) }
        if (configured != null) return configured.value
        val json = bodyFor(url)
        return if (json != null) HttpResult.Ok(JSONArray(json)) else UNSTUBBED
    }

    /** A sequenced stub answers first, so a URL can be given an order as well as a body. */
    private fun bodyFor(url: String): String? {
        val queued = sequencedResponses.entries.firstOrNull { url.contains(it.key) }?.value
        if (queued != null) return if (queued.size > 1) queued.removeAt(0) else queued.first()
        return jsonResponses.entries.firstOrNull { url.contains(it.key) }?.value
    }

    /**
     * The configured status for a JSON call, or `null` to fall through to the body map. Sequenced
     * first, so a URL can be given an order of statuses as well as a single one — [bodyFor]'s
     * arrangement, one channel over. The queue is shared by every JSON method, as the single-status
     * map already is.
     */
    private fun httpResultFor(url: String): HttpResult<JSONObject>? {
        val queued = sequencedHttpResults.entries.firstOrNull { url.contains(it.key) }?.value
        if (queued != null) return if (queued.size > 1) queued.removeAt(0) else queued.first()
        return httpResultResponses.entries.firstOrNull { url.contains(it.key) }?.value
    }

    companion object {
        /**
         * An unstubbed URL: the fake has nothing at that address, which is a 404, not a dropped
         * connection. It must not be a transient variant — providers treat 429/5xx/network as a
         * retryable `Error`, so a `NetworkError` here would make every test that simply omits a stub
         * assert the transient path instead of the empty-result path it means to test.
         */
        private val UNSTUBBED = HttpResult.ClientError(404, "No response configured")
    }
}
