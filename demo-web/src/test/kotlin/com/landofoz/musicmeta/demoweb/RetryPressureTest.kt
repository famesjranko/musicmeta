package com.landofoz.musicmeta.demoweb

import com.landofoz.musicmeta.ApiKeyConfig
import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentCache
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentResults
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.IdentityResolution
import com.landofoz.musicmeta.ProviderInfo
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.cache.CacheMode
import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The bounded retry `/api/enrich` runs over a finished result, and the one condition under which it
 * must not run.
 *
 * A retry pass costs a second provider fan-out on top of the one that just failed. When the
 * admission gate has nothing left to hand out, `NETWORK` and `TIMEOUT` are as likely to be the
 * instance's own starvation as a blip on the wire — so a retry then spends the contended resource
 * on the symptom of the contention. The assertions here are about how many fan-outs a single
 * lookup costs, under pressure and on a quiet instance.
 *
 * The gate is one process-wide bound, so every held request is released and drained before the
 * class finishes.
 */
class RetryPressureTest {

    private companion object {
        /** Enrichments of this artist block until released, and are what fills the gate. */
        const val FILLER_ARTIST = "Portishead"

        /** Enrichments of this artist fail once with a retryable kind, and are what the tests measure. */
        const val PROBE_ARTIST = "Stereolab"

        /** Long enough that a loaded CI machine is not the reason a poll gives up. */
        val WAIT: Long = TimeUnit.SECONDS.toNanos(20)
    }

    /**
     * Fails the probe's first fan-out with [ErrorKind.NETWORK] — the kind a retry exists for — and
     * counts every fan-out the probe costs, so a suppressed retry and a fired one differ by one.
     */
    private class ProbeEngine : EnrichmentEngine {

        override val cache: EnrichmentCache = InMemoryEnrichmentCache()

        /** Fan-outs spent on [PROBE_ARTIST], which is one per `enrich` call the handler makes. */
        val probeEnrichments = AtomicInteger(0)

        /** Requests inside a held enrichment right now. */
        val inFlight = AtomicInteger(0)

        private val releases = AtomicInteger(0)

        fun release(count: Int) {
            releases.addAndGet(count)
        }

        private fun artistOf(request: EnrichmentRequest) =
            (request as? EnrichmentRequest.ForArtist)?.name

        private suspend fun hold() {
            inFlight.incrementAndGet()
            try {
                val deadline = System.nanoTime() + WAIT
                while (System.nanoTime() < deadline) {
                    val available = releases.get()
                    if (available > 0 && releases.compareAndSet(available, available - 1)) return
                    delay(5)
                }
            } finally {
                inFlight.decrementAndGet()
            }
        }

        private fun results(types: Set<EnrichmentType>, failBio: Boolean) = EnrichmentResults(
            raw = types.associateWith { type -> EnrichmentResult.NotFound(type, "stub") } + (
                EnrichmentType.ARTIST_BIO to if (failBio) {
                    EnrichmentResult.Error(
                        type = EnrichmentType.ARTIST_BIO,
                        provider = "stub-bio",
                        message = "connection reset",
                        errorKind = ErrorKind.NETWORK,
                    )
                } else {
                    EnrichmentResult.Success(
                        type = EnrichmentType.ARTIST_BIO,
                        data = EnrichmentData.Biography(text = "Groop played space age bachelor pad music.", source = "stub-bio"),
                        provider = "stub-bio",
                        confidence = 0.9f,
                    )
                }
                ),
            requestedTypes = types,
            identity = IdentityResolution(EnrichmentIdentifiers(), CanonicalStatus.RESOLVED),
        )

        override suspend fun enrich(
            request: EnrichmentRequest,
            types: Set<EnrichmentType>,
            forceRefresh: Boolean,
        ): EnrichmentResults {
            when (artistOf(request)) {
                FILLER_ARTIST -> hold()
                PROBE_ARTIST -> {
                    // The first fan-out fails; a second one is the retry, and it succeeds.
                    val attempt = probeEnrichments.incrementAndGet()
                    return results(types, failBio = attempt == 1)
                }
            }
            return results(types, failBio = false)
        }

        override fun enrichProgressive(
            request: EnrichmentRequest,
            types: Set<EnrichmentType>,
            forceRefresh: Boolean,
        ): Flow<EnrichmentResults> = flow {
            if (artistOf(request) == FILLER_ARTIST) hold()
            emit(results(types, failBio = false))
        }

        override suspend fun search(request: EnrichmentRequest, limit: Int): List<SearchCandidate> = emptyList()
        override fun getProviders(): List<ProviderInfo> = emptyList()
        override suspend fun invalidate(request: EnrichmentRequest, type: EnrichmentType?) = Unit
        override suspend fun isManuallySelected(request: EnrichmentRequest, type: EnrichmentType) = false
        override suspend fun markManuallySelected(request: EnrichmentRequest, type: EnrichmentType) = Unit
    }

    private val http: HttpClient = HttpClient.newHttpClient()
    private val started = CopyOnWriteArrayList<ProbeEngine>()
    private val outstanding = CopyOnWriteArrayList<CompletableFuture<HttpResponse<String>>>()

    /** Empties the process-wide gate, so a test that filled it cannot fail the test after it. */
    @After
    fun drain() {
        started.forEach { it.release(MAX_IN_GATE * 4) }
        runCatching { CompletableFuture.allOf(*outstanding.toTypedArray()).get(5, TimeUnit.SECONDS) }
        started.forEach { engine ->
            val deadline = System.nanoTime() + WAIT
            while (engine.inFlight.get() > 0 && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals("requests still inside the engine", 0, engine.inFlight.get())
        }
    }

    private fun startWith(engine: ProbeEngine): Int {
        val port = startServer(AtomicReference(engine), AtomicReference(CacheMode.NETWORK_FIRST), { engine }, ApiKeyConfig(), 0)
        started += engine
        return port
    }

    private fun request(port: Int, path: String) =
        HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))

    private fun get(port: Int, path: String): HttpResponse<String> =
        http.send(request(port, path).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun getAsync(port: Int, path: String) {
        outstanding += http.sendAsync(request(port, path).GET().build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun awaitInFlight(engine: ProbeEngine, target: Int) {
        val deadline = System.nanoTime() + WAIT
        while (engine.inFlight.get() < target && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals("requests holding a permit together", target, engine.inFlight.get())
    }

    @Test fun `a transient failure on a quiet instance is retried once`() {
        // Given - an instance with nothing else inside the gate
        val engine = ProbeEngine()
        val port = startWith(engine)

        // When - one lookup fails with a retryable kind
        val response = get(port, "/api/enrich?kind=artist&name=$PROBE_ARTIST")

        // Then - a second fan-out is spent on the types that failed, which is the retry doing its job
        assertEquals(200, response.statusCode())
        assertEquals("fan-outs spent on the probe", 2, engine.probeEnrichments.get())
    }

    @Test fun `a transient failure under gate pressure is not retried`() {
        // Given - every permit but one held, so the lookup under test takes the last of them
        val engine = ProbeEngine()
        val port = startWith(engine)
        repeat(MAX_IN_GATE - 1) { getAsync(port, "/api/enrich-stream?kind=artist&name=$FILLER_ARTIST") }
        awaitInFlight(engine, MAX_IN_GATE - 1)

        // When - a lookup fails with the same retryable kind while the gate has nothing left to give
        val response = get(port, "/api/enrich?kind=artist&name=$PROBE_ARTIST")

        // Then - the failure is reported rather than retried into: a second fan-out here would spend
        // the contended resource on the symptom of the contention
        assertEquals(200, response.statusCode())
        assertEquals("fan-outs spent on the probe", 1, engine.probeEnrichments.get())
    }
}
