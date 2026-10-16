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
import com.landofoz.musicmeta.IdentityResolution
import com.landofoz.musicmeta.ProviderInfo
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.cache.CacheMode
import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
 * The admission gate in front of both enrichment endpoints. What it owes the demo is two things
 * that pull against each other: a cap on how much fan-out one instance runs at once, and enough
 * room inside that cap for concurrent lookups of the same entity to overlap, since the engine's
 * run registry can only collapse requests that are in flight together. So the assertions here are
 * about how many get in *at the same time*, never about how many eventually complete — a gate that
 * admitted one at a time would satisfy the second and destroy the first.
 *
 * The gate is one process-wide bound, so these tests are also the only place that leaves it
 * measurably empty: every held request is released and drained before the class finishes.
 */
class AdmissionGateTest {

    private companion object {
        /** The one entity these tests look up, so [HeldEngine] holds only what a test asked for. */
        const val HELD_ARTIST = "Portishead"
        const val STREAM_PATH = "/api/enrich-stream?kind=artist&name=$HELD_ARTIST"
        const val ENRICH_PATH = "/api/enrich?kind=artist&name=$HELD_ARTIST"

        /** Long enough that a loaded CI machine is not the reason a poll gives up. */
        val WAIT: Long = TimeUnit.SECONDS.toNanos(20)
    }

    /**
     * Holds every enrichment of [HELD_ARTIST] until the test hands out a release, so a chosen
     * number of requests can be kept inside the gate at once. Anything else returns at once and is
     * not counted.
     */
    private class HeldEngine(failWith: Throwable? = null) : EnrichmentEngine {

        /** Spent on the first held request, so what follows it is an ordinary lookup again. */
        private val failNext = AtomicReference(failWith)

        override val cache: EnrichmentCache = InMemoryEnrichmentCache()

        /** Requests inside an enrichment right now, whichever endpoint let them in. */
        val inFlight = AtomicInteger(0)

        /** The most that were ever inside together: one if the gate serialises, the bound if it bounds. */
        val peakInFlight = AtomicInteger(0)

        private val releases = AtomicInteger(0)

        /** Lets [count] held requests leave. Each release is spent once, so the hold can be re-armed. */
        fun release(count: Int) {
            releases.addAndGet(count)
        }

        private fun isHeld(request: EnrichmentRequest) =
            (request as? EnrichmentRequest.ForArtist)?.name == HELD_ARTIST

        private suspend fun hold() {
            val depth = inFlight.incrementAndGet()
            peakInFlight.getAndUpdate { seen -> maxOf(seen, depth) }
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

        private fun artistResults() = EnrichmentResults(
            raw = mapOf(
                EnrichmentType.ARTIST_BIO to EnrichmentResult.Success(
                    type = EnrichmentType.ARTIST_BIO,
                    data = EnrichmentData.Biography(text = "Bristol.", source = "stub-bio"),
                    provider = "stub-bio",
                    confidence = 0.9f,
                ),
            ) + (EnrichmentRequest.DEFAULT_ARTIST_TYPES + EnrichmentType.COUNTRY)
                .associateWith { type -> EnrichmentResult.NotFound(type, "stub") },
            requestedTypes = EnrichmentRequest.DEFAULT_ARTIST_TYPES + EnrichmentType.COUNTRY,
            identity = IdentityResolution(EnrichmentIdentifiers(), CanonicalStatus.RESOLVED),
        )

        override suspend fun enrich(
            request: EnrichmentRequest,
            types: Set<EnrichmentType>,
            forceRefresh: Boolean,
        ): EnrichmentResults {
            if (isHeld(request)) {
                hold()
                failNext.getAndSet(null)?.let { throw it }
            }
            return artistResults()
        }

        override fun enrichProgressive(
            request: EnrichmentRequest,
            types: Set<EnrichmentType>,
            forceRefresh: Boolean,
        ): Flow<EnrichmentResults> = flow {
            if (isHeld(request)) {
                hold()
                failNext.getAndSet(null)?.let { throw it }
            }
            emit(artistResults())
        }

        override suspend fun search(request: EnrichmentRequest, limit: Int): List<SearchCandidate> = emptyList()
        override fun getProviders(): List<ProviderInfo> = emptyList()
        override suspend fun invalidate(request: EnrichmentRequest, type: EnrichmentType?) = Unit
        override suspend fun isManuallySelected(request: EnrichmentRequest, type: EnrichmentType) = false
        override suspend fun markManuallySelected(request: EnrichmentRequest, type: EnrichmentType) = Unit
    }

    private val http: HttpClient = HttpClient.newHttpClient()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val started = CopyOnWriteArrayList<HeldEngine>()
    private val outstanding = CopyOnWriteArrayList<CompletableFuture<HttpResponse<String>>>()

    /**
     * Empties the gate before the next test runs. The bound is one process-wide thing, so a test
     * that walked away from a held request would not fail — the test after it would, by finding
     * the gate already full. Waiting on the responses rather than only on the engine is what makes
     * that exact: a permit is handed back on the way out of the handler, before its caller sees
     * the end of the body.
     */
    @After
    fun drain() {
        started.forEach { it.release(MAX_IN_GATE * 4) }
        val all = CompletableFuture.allOf(*outstanding.toTypedArray())
        // One request in this class is answered by a Throwable that kills its connection instead of
        // its response, so waiting on every last one would mean waiting out that connection.
        runCatching { all.get(5, TimeUnit.SECONDS) }
        started.forEach { awaitDrained(it) }
    }

    private fun startWith(engine: HeldEngine): Int {
        val port = startServer(AtomicReference(engine), AtomicReference(CacheMode.NETWORK_FIRST), { engine }, ApiKeyConfig(), 0)
        started += engine
        return port
    }

    private fun get(port: Int, path: String): HttpResponse<String> =
        http.send(request(port, path).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun getAsync(port: Int, path: String): CompletableFuture<HttpResponse<String>> =
        http.sendAsync(request(port, path).GET().build(), HttpResponse.BodyHandlers.ofString())
            .also { outstanding += it }

    private fun request(port: Int, path: String) =
        HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))

    /**
     * Blocks until [target] requests are inside the engine together, and fails saying how many got
     * there if they never do. Polling for the state we want rather than sleeping for it is what
     * makes "the gate admits five" a claim about the bound and not about machine speed.
     */
    private fun awaitInFlight(engine: HeldEngine, target: Int) {
        val deadline = System.nanoTime() + WAIT
        while (engine.inFlight.get() < target && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals("requests admitted together", target, engine.inFlight.get())
    }

    /**
     * Blocks until [target] requests have been inside the engine together at some point.
     *
     * Polls the peak rather than reading it once, because the arrival count and the peak are
     * published a line apart: a watcher that sees the last arrival's increment can read the peak
     * before that arrival has recorded itself in it. That window is a race in the observation, not
     * in the bound, and reading once turns it into a test that fails on a slow machine and passes
     * on a fast one. Polling still fails when the gate serialises — the peak then sits at one until
     * the deadline.
     */
    private fun awaitPeak(engine: HeldEngine, target: Int) {
        val deadline = System.nanoTime() + WAIT
        while (engine.peakInFlight.get() < target && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals("most requests ever inside together", target, engine.peakInFlight.get())
    }

    /** Blocks until nothing is inside the engine, so a request's departure cannot race the next step. */
    private fun awaitDrained(engine: HeldEngine) {
        val deadline = System.nanoTime() + WAIT
        while (engine.inFlight.get() > 0 && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals("requests still inside the engine", 0, engine.inFlight.get())
    }

    private fun errorBody(response: HttpResponse<String>): String =
        json.decodeFromString(ApiError.serializer(), response.body()).error

    @Test fun `the gate admits its whole bound at once rather than one request at a time`() {
        // Given - an engine that holds every request it is given, so admissions accumulate
        val engine = HeldEngine()
        val port = startWith(engine)

        // When - the bound's worth of streaming lookups arrive together
        repeat(MAX_IN_GATE) { getAsync(port, STREAM_PATH) }

        // Then - all of them are inside the engine at the same moment, which is what leaves the run
        // registry able to collapse concurrent lookups of one entity into a single fan-out
        awaitInFlight(engine, MAX_IN_GATE)
        awaitPeak(engine, MAX_IN_GATE)
    }

    @Test fun `a streaming request over the bound is refused with a status the page can read`() {
        // Given - a full gate, with the bound's worth of streaming lookups held inside it
        val engine = HeldEngine()
        val port = startWith(engine)
        repeat(MAX_IN_GATE) { getAsync(port, STREAM_PATH) }
        awaitInFlight(engine, MAX_IN_GATE)

        // When - one more streaming lookup arrives
        val refused = get(port, STREAM_PATH)

        // Then - it is refused as a status, not as an event on an opened stream: the handler commits
        // 200 before it enriches, so a gate decided any later than this could only have said "busy"
        // inside a successful response, where no status code and no proxy can see it
        assertEquals(429, refused.statusCode())
        assertEquals("15", refused.headers().firstValue("Retry-After").orElse(""))
        assertTrue(errorBody(refused).isNotBlank())
    }

    @Test fun `a single-shot request over the bound is refused the same way`() {
        // Given - a full gate, filled through the single-shot endpoint this time
        val engine = HeldEngine()
        val port = startWith(engine)
        repeat(MAX_IN_GATE) { getAsync(port, ENRICH_PATH) }
        awaitInFlight(engine, MAX_IN_GATE)

        // When - one more lookup arrives
        val refused = get(port, ENRICH_PATH)

        // Then - both endpoints refuse in the same shape, so the page needs one busy path and not two
        assertEquals(429, refused.statusCode())
        assertEquals("15", refused.headers().firstValue("Retry-After").orElse(""))
        assertTrue(errorBody(refused).isNotBlank())
    }

    @Test fun `both enrichment endpoints draw on one bound`() {
        // Given - a gate filled entirely by streaming requests
        val engine = HeldEngine()
        val port = startWith(engine)
        repeat(MAX_IN_GATE) { getAsync(port, STREAM_PATH) }
        awaitInFlight(engine, MAX_IN_GATE)

        // When - a single-shot lookup arrives at the other endpoint
        val refused = get(port, ENRICH_PATH)

        // Then - it is refused, because the cost being bounded is provider fan-out and both
        // endpoints spend it; a per-endpoint bound would let a visitor have twice the share
        assertEquals(429, refused.statusCode())
    }

    @Test fun `a permit is given back when its request finishes`() {
        // Given - an engine holding nothing, so each request passes straight through
        val engine = HeldEngine()
        val port = startWith(engine)
        engine.release(MAX_IN_GATE * 4)

        // When - more lookups than the bound are served one after another
        val statuses = (1..MAX_IN_GATE + 2).map { get(port, ENRICH_PATH).statusCode() }

        // Then - every one is served, so the gate is a bound on concurrency and not a lifetime quota
        assertEquals(List(MAX_IN_GATE + 2) { 200 }, statuses)
    }

    @Test fun `an enrichment failure the handler does not catch still gives back its permit`() {
        // Given - an engine that fails with a Throwable the handler's catch of Exception cannot see
        val engine = HeldEngine(failWith = AssertionError("provider chain gave up"))
        val port = startWith(engine)

        // When - one lookup fails that way, and then the bound's worth arrive together
        getAsync(port, ENRICH_PATH)
        awaitInFlight(engine, 1)
        engine.release(1)
        awaitDrained(engine)
        repeat(MAX_IN_GATE) { getAsync(port, ENRICH_PATH) }

        // Then - the failed request stranded nothing: the full bound is admitted again, which it
        // could not be if a permit only came back on the paths that return normally
        awaitInFlight(engine, MAX_IN_GATE)
    }

    @Test fun `a malformed query is answered before the gate is consulted`() {
        // Given - a full gate
        val engine = HeldEngine()
        val port = startWith(engine)
        repeat(MAX_IN_GATE) { getAsync(port, STREAM_PATH) }
        awaitInFlight(engine, MAX_IN_GATE)

        // When - a request arrives that names no kind at all
        val rejected = get(port, "/api/enrich-stream?name=$HELD_ARTIST")

        // Then - it is told what is wrong with it rather than told the server is busy, because a
        // request that could never have enriched anything must not spend a permit to find out
        assertEquals(400, rejected.statusCode())
    }

    @Test fun `a wrong method is answered before the gate is consulted`() {
        // Given - a full gate
        val engine = HeldEngine()
        val port = startWith(engine)
        repeat(MAX_IN_GATE) { getAsync(port, STREAM_PATH) }
        awaitInFlight(engine, MAX_IN_GATE)

        // When - the endpoint is posted to instead of got
        val rejected = http.send(
            request(port, STREAM_PATH).POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        // Then - the method is the answer, not the load
        assertEquals(405, rejected.statusCode())
    }
}
