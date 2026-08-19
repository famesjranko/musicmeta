package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.LookupProvenance
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * `enrichBatchProgressive` composed from `enrichProgressive`: one cumulative snapshot stream per
 * request, sequenced the way `enrichBatch` sequences its own `enrich()` calls. Every test here
 * pins a property the composition must preserve rather than re-testing `enrichProgressive` itself
 * (see `EnrichProgressiveTest`/`ProgressiveCancellationTest` for that). The concurrency-bound and
 * registry-coalescing tests run under a real `Dispatchers.Default` rather than `runTest`, for the
 * same reason `ProgressiveCancellationTest` does: the races they pin are invisible under virtual
 * time.
 */
// InjectDispatcher: a real dispatcher is the point for the tests that use one — see the KDoc above.
@Suppress("InjectDispatcher")
class EnrichBatchProgressiveTest {

    private lateinit var cache: FakeEnrichmentCache
    private val config = EnrichmentConfig(enableIdentityResolution = false)
    private val types = setOf(EnrichmentType.ALBUM_ART)

    @Before fun setup() {
        cache = FakeEnrichmentCache()
    }

    private fun artSuccess(provider: String) = EnrichmentResult.Success(
        type = EnrichmentType.ALBUM_ART,
        data = EnrichmentData.Artwork("https://example.com/art.jpg"),
        provider = provider,
        confidence = 0.9f,
    )

    /**
     * [artSuccess] as the engine actually settles it for every live (non-cache-hit) fetch in this
     * file: `config.enableIdentityResolution == false` and a [FakeProvider] declares no identifier
     * requirement, so [com.landofoz.musicmeta.engine.observedProvenance] always lands on
     * [LookupProvenance.FUZZY_NAME] here. [artSuccess] itself stays provenance-`null` — it is also
     * used as a raw [FakeProvider.givenResult] value and a raw cache-`put` literal, both pre-stamp.
     */
    private fun settledArtSuccess(provider: String) = artSuccess(provider).copy(provenance = LookupProvenance.FUZZY_NAME)

    private fun engine(vararg providers: FakeProvider) =
        DefaultEnrichmentEngine(ProviderRegistry(providers.toList()), cache, config)

    /** One type, answered after [delayMs] of real suspension — never `runTest` virtual time. */
    private class DelayingProvider(
        type: EnrichmentType,
        private val delayMs: Long,
        id: String = "slow",
    ) : FakeProvider(id = id, capabilities = listOf(ProviderCapability(type, 100))) {
        val callCount = AtomicInteger(0)
        override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult {
            callCount.incrementAndGet()
            delay(delayMs)
            return EnrichmentResult.Success(type, EnrichmentData.Artwork("https://x/${type.name}"), id, 0.9f)
        }
    }

    /** Tracks how many [enrich] calls this provider has in flight at once, real dispatcher only. */
    private class ConcurrencyTrackingProvider(
        type: EnrichmentType,
        private val delayMs: Long,
        private val maxObserved: AtomicInteger,
        id: String = "slow",
    ) : FakeProvider(id = id, capabilities = listOf(ProviderCapability(type, 100))) {
        private val inFlight = AtomicInteger(0)
        override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult {
            val now = inFlight.incrementAndGet()
            maxObserved.updateAndGet { current -> maxOf(current, now) }
            delay(delayMs)
            inFlight.decrementAndGet()
            return EnrichmentResult.Success(type, EnrichmentData.Artwork("https://x/${type.name}"), id, 0.9f)
        }
    }

    @Test fun `enrichBatchProgressive emits a terminal snapshot per request, filtered to types`() = runTest {
        // Given - two distinct album requests and a provider that answers both
        val req1 = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")
        val req2 = EnrichmentRequest.forAlbum("Kid A", "Radiohead")
        val provider = FakeProvider(
            id = "art-provider", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)),
        ).also { it.givenResult(EnrichmentType.ALBUM_ART, artSuccess("art-provider")) }

        // When - collecting every emission from enrichBatchProgressive
        val emissions = engine(provider).enrichBatchProgressive(listOf(req1, req2), types).toList()

        // Then - each request's own last emission derives complete: nothing of its requested types
        // is still pending
        val last1 = emissions.last { (request, _) -> request == req1 }.second
        val last2 = emissions.last { (request, _) -> request == req2 }.second
        assertTrue("req1's terminal emission must derive complete", (types - last1.raw.keys).isEmpty())
        assertTrue("req2's terminal emission must derive complete", (types - last2.raw.keys).isEmpty())
        assertEquals(settledArtSuccess("art-provider"), last1.raw[EnrichmentType.ALBUM_ART])
        assertEquals(settledArtSuccess("art-provider"), last2.raw[EnrichmentType.ALBUM_ART])
    }

    @Test fun `enrichBatchProgressive's terminal emission for a request equals enrichBatch's entry for it`() =
        runTest {
            // Given - one request, a provider answering it, and two freshly-built engines with
            // their own independent caches. engine(...) closes over this class's single `cache`
            // field (shared on purpose by the other tests here), so it cannot be reused for this
            // one: it would turn the second call into a full cache hit off the first call's
            // write-back rather than a genuine second live fetch, which is what this test claims
            // to compare.
            val req = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")
            fun freshEngine() = DefaultEnrichmentEngine(
                ProviderRegistry(
                    listOf(
                        FakeProvider(
                            id = "art-provider",
                            capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)),
                        ).also { it.givenResult(EnrichmentType.ALBUM_ART, artSuccess("art-provider")) },
                    ),
                ),
                FakeEnrichmentCache(),
                config,
            )

            // When - enriching through enrichBatch and through enrichBatchProgressive's terminal
            val viaBatch = freshEngine().enrichBatch(listOf(req), types).toList().single().second
            val viaBatchProgressive = freshEngine().enrichBatchProgressive(listOf(req), types).toList().last().second

            // Then - both are genuine live fetches (independent caches, so neither can be served
            // by the other's write-back) and report the identical settled snapshot for this
            // request, full object equality: enrich() is enrichProgressive(...).last() against one
            // shared resolution path, so enrichBatch's per-item enrich() call and
            // enrichBatchProgressive's per-item terminal snapshot never had a reason to diverge
            // once the fixture actually isolates them
            assertEquals(viaBatch, viaBatchProgressive)
        }

    @Test fun `enrichBatchProgressive processes requests one at a time, like enrichBatch`() =
        runBlocking(Dispatchers.Default) {
            // Given - a provider that records how many of its own calls are in flight at once, and
            // two distinct entities
            val maxObserved = AtomicInteger(0)
            val provider = ConcurrencyTrackingProvider(EnrichmentType.ALBUM_ART, delayMs = 30, maxObserved)
            val req1 = EnrichmentRequest.forArtist("Artist One")
            val req2 = EnrichmentRequest.forArtist("Artist Two")

            // When - both requests are enriched through one enrichBatchProgressive call
            engine(provider).enrichBatchProgressive(listOf(req1, req2), types).toList()

            // Then - the two entities' fan-outs never overlapped: enrichBatch's existing sequential
            // bound is reused rather than replaced by concurrent per-entity fan-out
            assertEquals(
                "requests must not fan out concurrently across the batch",
                1,
                maxObserved.get(),
            )
        }

    @Test fun `enrichBatchProgressive cancellation stops processing remaining requests`() = runTest {
        // Given - two requests, but the collector only wants the first one's settled answer
        val req1 = EnrichmentRequest.forArtist("Nirvana")
        val req2 = EnrichmentRequest.forArtist("Foo Fighters")
        val provider = FakeProvider(
            id = "art-provider", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)),
        ).also { it.givenResult(EnrichmentType.ALBUM_ART, artSuccess("art-provider")) }

        // When - collection stops as soon as req1's own snapshot derives complete
        engine(provider).enrichBatchProgressive(listOf(req1, req2), types)
            .first { (request, results) -> request == req1 && (types - results.raw.keys).isEmpty() }

        // Then - req2 was never reached: cancellation stopped the batch cooperatively before its
        // fan-out started, the same way enrichBatch's own take(1) test proves for req3
        assertTrue(
            "req2 must never have been asked",
            provider.enrichCalls.none { (request, _) -> request == req2 },
        )
    }

    @Test fun `enrichBatchProgressive propagates forceRefresh to enrichProgressive`() = runTest {
        // Given - a request with a cached result and a provider that returns fresh data
        val req = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")
        val cacheKey = DefaultEnrichmentEngine.entityKeyFor(req, EnrichmentType.ALBUM_ART)
        cache.put(cacheKey, EnrichmentType.ALBUM_ART, artSuccess("cached-provider"), CanonicalStatus.RESOLVED)
        val provider = FakeProvider(
            id = "fresh-provider", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)),
        ).also { it.givenResult(EnrichmentType.ALBUM_ART, artSuccess("fresh-provider")) }

        // When - calling enrichBatchProgressive with forceRefresh=true
        val last = engine(provider).enrichBatchProgressive(listOf(req), types, forceRefresh = true)
            .toList().last().second

        // Then - the cache was bypassed: the provider was asked and its fresh data won, full
        // equality including the pipeline's own provenance stamp
        assertEquals(settledArtSuccess("fresh-provider"), last.raw[EnrichmentType.ALBUM_ART])
        assertTrue(provider.enrichCalls.isNotEmpty())
    }

    @Test fun `enrichBatchProgressive gives each item its own run, so a repeated request asks upstream again`() =
        runTest {
            // Given - one provider and the same request listed twice in one batch, forceRefresh so
            // EnrichmentCache's own per-request memoization cannot explain the call count. Batch
            // items are still processed one at a time (this file's own sequencing test), so the
            // first occurrence's run has already completed and evicted from the registry before the
            // second starts — there is nothing live left to attach to. This mirrors enrichBatch's
            // own documented behaviour for a repeated item (see EnrichBatchTest's identically-named
            // scenario): only a *concurrent* duplicate coalesces (see the next test).
            val req = EnrichmentRequest.forArtist("Radiohead")
            val provider = FakeProvider(
                id = "art-provider", capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)),
            ).also { it.givenResult(EnrichmentType.ALBUM_ART, artSuccess("art-provider")) }

            // When - the identical request is enriched twice in one batch
            engine(provider).enrichBatchProgressive(listOf(req, req), types, forceRefresh = true).toList()

            // Then - upstream was asked once per item, not deduped within the batch itself
            assertEquals(
                "expected one upstream fetch per batch item, not a coalesced single fetch",
                2,
                provider.enrichCalls.size,
            )
        }

    @Test fun `enrichBatchProgressive coalesces one entity shared across two concurrent batches`() =
        runBlocking(Dispatchers.Default) {
            // Given - one slow provider, so both batches' shared request overlaps in flight
            val provider = DelayingProvider(EnrichmentType.ALBUM_ART, delayMs = 50)
            val shared = EnrichmentRequest.forArtist("Shared Artist")
            val theEngine = engine(provider)

            // When - two enrichBatchProgressive calls, each carrying the same entity, run
            // concurrently
            val first = async { theEngine.enrichBatchProgressive(listOf(shared), types).toList() }
            delay(10) // let the first call's fan-out start before the second arrives
            val second = async { theEngine.enrichBatchProgressive(listOf(shared), types).toList() }
            awaitAll(first, second)

            // Then - the provider was asked once, not twice: the run registry [ProgressiveRunRegistry]
            // that dedupes within one enrichProgressive call also dedupes across two batches
            assertEquals(
                "the shared entity must not have fanned out twice",
                1,
                provider.callCount.get(),
            )
        }

    @Test fun `enrichBatchProgressive stamps ENGINE_CLOSED for a request not yet reached at close()`() =
        runBlocking(Dispatchers.Default) {
            // Given - two requests and a provider fast enough that req1 settles well before req2
            // would otherwise start
            val provider = DelayingProvider(EnrichmentType.ALBUM_ART, delayMs = 5)
            val req1 = EnrichmentRequest.forArtist("Artist One")
            val req2 = EnrichmentRequest.forArtist("Artist Two")
            val theEngine = engine(provider)

            // When - the engine is closed right after req1's own terminal emission, before req2's
            // fan-out has started
            val emissions = mutableListOf<Pair<EnrichmentRequest, EnrichmentResult?>>()
            theEngine.enrichBatchProgressive(listOf(req1, req2), types).take(2).let { flow ->
                var closed = false
                flow.collect { (request, results) ->
                    emissions.add(request to results.raw[EnrichmentType.ALBUM_ART])
                    if (!closed && request == req1) {
                        theEngine.close()
                        closed = true
                    }
                }
            }

            // Then - req2's snapshot reports every requested type present as an honest
            // Error(ENGINE_CLOSED), the same per-type completeness a timeout or close() already
            // documents for enrich()/enrichProgressive
            val req2Result = emissions.last { (request, _) -> request == req2 }.second
            assertTrue(
                "req2 must stamp ENGINE_CLOSED rather than hang or omit the type",
                req2Result is EnrichmentResult.Error,
            )
            assertEquals(ErrorKind.ENGINE_CLOSED, (req2Result as EnrichmentResult.Error).errorKind)
        }
}
