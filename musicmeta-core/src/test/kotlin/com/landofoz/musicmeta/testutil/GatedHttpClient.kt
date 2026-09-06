package com.landofoz.musicmeta.testutil

import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.HttpResult
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Holds every request whose URL contains [urlContains] inside the fetch until [release], so that a
 * memo's *coalescing* is observable rather than only its caching: a memo whose lock spans the fetch
 * admits one caller and parks the rest, while one that guards only its map admits every concurrent
 * caller at once. [arrivals] counts the callers that got inside — one, for a memo that coalesces.
 */
class GatedHttpClient(
    private val delegate: HttpClient,
    private val urlContains: String,
) : HttpClient by delegate {

    private val gate = CompletableDeferred<Unit>()

    private val arrived = AtomicInteger()

    /** How many callers got inside the gate. Atomic: the callers it counts are the ones racing. */
    val arrivals: Int get() = arrived.get()

    /** Lets every parked caller, and every later one, through. */
    fun release() {
        gate.complete(Unit)
    }

    override suspend fun fetchJsonResult(url: String): HttpResult<JSONObject> {
        awaitGate(url)
        return delegate.fetchJsonResult(url)
    }

    override suspend fun fetchJsonResult(url: String, headers: Map<String, String>): HttpResult<JSONObject> {
        awaitGate(url)
        return delegate.fetchJsonResult(url, headers)
    }

    override suspend fun fetchJsonArrayResult(url: String): HttpResult<JSONArray> {
        awaitGate(url)
        return delegate.fetchJsonArrayResult(url)
    }

    private suspend fun awaitGate(url: String) {
        if (!url.contains(urlContains)) return
        arrived.incrementAndGet()
        gate.await()
    }
}
