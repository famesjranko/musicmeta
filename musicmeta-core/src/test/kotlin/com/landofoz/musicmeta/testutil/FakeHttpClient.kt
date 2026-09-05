package com.landofoz.musicmeta.testutil

import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.HttpResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.util.Collections

class FakeHttpClient : HttpClient {
    private val jsonResponses = mutableMapOf<String, String>()
    private val sequencedResponses = mutableMapOf<String, MutableList<String>>()
    private val errors = mutableSetOf<String>()
    private val ioExceptions = mutableSetOf<String>()
    private val httpResultResponses = mutableMapOf<String, HttpResult<JSONObject>>()
    private val sequencedHttpResults = mutableMapOf<String, MutableList<HttpResult<JSONObject>>>()
    private val httpResultArrayResponses = mutableMapOf<String, HttpResult<JSONArray>>()
    private val redirectResults = mutableMapOf<String, HttpResult<String>>()
    val requestedUrls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val delaysMs = mutableMapOf<String, Long>()

    /**
     * How long a matching URL takes to answer. Empty by default, so a client nobody configured
     * behaves exactly as it did before this existed.
     *
     * A real `delay`, not a virtual one: whether a concurrent fan-out's failures cluster is a
     * question about the order real threads finish in, and a `runTest` scheduler would answer it by
     * fiat. A test that wants this to cost no wall clock should simply not call it.
     */
    fun givenDelay(urlContains: String, ms: Long) { delaysMs[urlContains] = ms }

    private suspend fun applyDelay(url: String) {
        if (delaysMs.isEmpty()) return
        val ms = delaysMs.entries.firstOrNull { url.contains(it.key) }?.value ?: return
        if (ms > 0) kotlinx.coroutines.delay(ms)
    }

    /**
     * The name of every thread a request was issued from. An engine's fan-out is detached, so this
     * is the only place a composed-stack test can see which dispatcher that fan-out actually ran
     * on. A set, and synchronized: a fan-out issues its requests from several threads at once.
     */
    val requestedThreads: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /**
     * Every recorded URL must be one `java.net.URI` accepts, because that is what
     * `DefaultHttpClient` parses with in production: a URL this fake accepted and the real client
     * rejects is a test that passes on a request that can never be made.
     *
     * `java.net.URI` accepts `#`, `?`, `=` and `&`, so it cannot see a value that was spliced in
     * unencoded and silently became structure — a `#` truncates the request at a fragment, the
     * others move or invent a parameter. [assertTemplateShape] rejects a URL whose shape no `*Api`
     * template in this module produces.
     */
    private fun record(url: String): String {
        requestedThreads.add(Thread.currentThread().name)
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            throw AssertionError("request URL is not a valid URI, DefaultHttpClient would throw: $url", e)
        }
        assertTemplateShape(uri, url)
        return url
    }

    /**
     * Fails when the URL carries a fragment, or a query pair that is not one `name=value` with a
     * bare-word name. A raw delimiter reaching the URL from an interpolated value lands in one of
     * those two, so the value goes out percent-encoded or the test that sent it goes red.
     *
     * The one raw `&` this cannot see is a value that is itself a whole `&name=value` pair: it is
     * indistinguishable from the template's own without knowing the template. Encoding at the call
     * site is what rules that out; this guard is the backstop for forgetting to.
     */
    private fun assertTemplateShape(uri: URI, url: String) {
        if (uri.rawFragment != null) {
            throw AssertionError("request URL carries a fragment, so both clients would truncate it there: $url")
        }
        val query = uri.rawQuery ?: return
        for (pair in query.split("&")) {
            val name = pair.substringBefore("=")
            if (pair.count { it == '=' } != 1 || '?' in pair || !name.matches(QUERY_NAME)) {
                throw AssertionError(
                    "request URL query pair \"$pair\" is not one name=value under a bare-word " +
                        "name: either a raw delimiter reached it from an unencoded interpolated " +
                        "value, or the template writes a parameter shape this fake does not " +
                        "model, in which case widen the guard: $url",
                )
            }
        }
    }
    val requestedHeaders: MutableList<Map<String, String>> = Collections.synchronizedList(mutableListOf())

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
        requestedUrls.add(record(url))
        applyDelay(url)
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
        requestedUrls.add(record(url))
        applyDelay(url)
        requestedHeaders.add(headers)
        if (ioExceptions.any { url.contains(it) }) throw IOException("Simulated network error: $url")
        if (errors.any { url.contains(it) }) return HttpResult.NetworkError("Simulated network error")
        httpResultFor(url)?.let { return it }
        val json = bodyFor(url)
        return if (json != null) HttpResult.Ok(JSONObject(json)) else UNSTUBBED
    }

    override suspend fun fetchJsonArrayResult(url: String): HttpResult<JSONArray> {
        requestedUrls.add(record(url))
        applyDelay(url)
        if (ioExceptions.any { url.contains(it) }) throw IOException("Simulated network error: $url")
        if (errors.any { url.contains(it) }) return HttpResult.NetworkError("Simulated network error")
        val configured = httpResultArrayResponses.entries.firstOrNull { url.contains(it.key) }
        if (configured != null) return configured.value
        val json = bodyFor(url)
        return if (json != null) HttpResult.Ok(JSONArray(json)) else UNSTUBBED
    }

    override suspend fun postJsonResult(url: String, body: String): HttpResult<JSONObject> {
        requestedUrls.add(record(url))
        applyDelay(url)
        if (ioExceptions.any { url.contains(it) }) throw IOException("Simulated network error: $url")
        if (errors.any { url.contains(it) }) return HttpResult.NetworkError("Simulated network error")
        httpResultFor(url)?.let { return it }
        val json = bodyFor(url)
        return if (json != null) HttpResult.Ok(JSONObject(json)) else UNSTUBBED
    }

    override suspend fun postJsonArrayResult(url: String, body: String): HttpResult<JSONArray> {
        requestedUrls.add(record(url))
        applyDelay(url)
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
        /** A query parameter's name, as every `*Api` template in this module writes one. */
        private val QUERY_NAME = Regex("[A-Za-z0-9_.-]+")

        /**
         * An unstubbed URL: the fake has nothing at that address, which is a 404, not a dropped
         * connection. It must not be a transient variant — providers treat 429/5xx/network as a
         * retryable `Error`, so a `NetworkError` here would make every test that simply omits a stub
         * assert the transient path instead of the empty-result path it means to test.
         */
        private val UNSTUBBED = HttpResult.ClientError(404, "No response configured")
    }
}
