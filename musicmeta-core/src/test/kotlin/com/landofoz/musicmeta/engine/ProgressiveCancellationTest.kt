package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.CacheEnvelope
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.cache.CacheMode
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * `enrichProgressive`'s complete-and-cache contract: a cancelled collector detaches from the
 * fan-out already in flight, which finishes and writes back in [DefaultEnrichmentEngine]'s own
 * `detachedScope` regardless. Every test here runs under a real `Dispatchers.Default` — never
 * `runTest` — because the bug class this contract targets (real work outliving a cancelled
 * caller, real timing races between concurrent calls) is invisible under virtual time.
 */
// InjectDispatcher: a real dispatcher, not runTest virtual time, is the point everywhere in this
// file — see the class KDoc.
@Suppress("InjectDispatcher")
class ProgressiveCancellationTest {
    private val req = EnrichmentRequest.forArtist("Radiohead")

    /** One type, answered after [delayMs] of real suspension — never runTest virtual time. */
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

    private fun engineFor(vararg providers: FakeProvider, cache: FakeEnrichmentCache = FakeEnrichmentCache()) =
        DefaultEnrichmentEngine(
            ProviderRegistry(providers.toList()), cache, EnrichmentConfig(enableIdentityResolution = false),
        )

    // --- Dedupe eviction (condition 2) ---

    @Test fun `a completed detached run's dedupe-map entry is gone afterward`() = runBlocking(Dispatchers.Default) {
        // Given - one provider, one type, no identity resolution needed
        val provider = DelayingProvider(EnrichmentType.ALBUM_ART, delayMs = 20)
        val engine = engineFor(provider)

        // When - a full progressive collection completes
        engine.enrichProgressive(req, setOf(EnrichmentType.ALBUM_ART)).last()

        // Then - the dedupe map is empty: nothing this call started is still registered
        assertEquals(0, engine.inFlightDetachedRunCount())
    }

    @Test fun `N sequential forceRefresh runs for the same key never grow the dedupe map`() =
        runBlocking(Dispatchers.Default) {
            // Given - a provider fast enough that each of 6 sequential forceRefresh calls
            // completes before the next starts
            val provider = DelayingProvider(EnrichmentType.ALBUM_ART, delayMs = 5)
            val engine = engineFor(provider)
            var maxObservedSize = 0

            // When - running the same forceRefresh call 6 times in a row, each awaited to
            // completion before the next starts
            repeat(6) {
                engine.enrichProgressive(req, setOf(EnrichmentType.ALBUM_ART), forceRefresh = true).last()
                maxObservedSize = maxOf(maxObservedSize, engine.inFlightDetachedRunCount())
            }

            // Then - the map never grew past one live entry at a time, and ends empty — bounded,
            // not O(N) in the number of calls
            assertEquals(0, engine.inFlightDetachedRunCount())
            assertTrue("dedupe map size must stay bounded, saw $maxObservedSize", maxObservedSize <= 1)
        }

    // --- forceRefresh coalescing race (condition 3) ---

    @Test fun `a forceRefresh call never attaches to an unforced in-flight run`() = runBlocking(Dispatchers.Default) {
        // Given - one slow provider, so the unforced run is still in flight when the forced one arrives
        val provider = DelayingProvider(EnrichmentType.ALBUM_ART, delayMs = 150)
        val engine = engineFor(provider)

        // When - an unforced call starts a detached run, and a forced call for the same type
        // follows while it is still running
        val unforced = async { engine.enrichProgressive(req, setOf(EnrichmentType.ALBUM_ART)).last() }
        delay(30)
        val forced = async {
            engine.enrichProgressive(req, setOf(EnrichmentType.ALBUM_ART), forceRefresh = true).last()
        }
        unforced.await(); forced.await()

        // Then - the provider was asked twice: the forced call started its own fresh run rather
        // than attaching to the unforced one's in-flight data
        assertEquals(2, provider.callCount.get())
    }

    @Test fun `attaching to an in-flight forced run does not re-run its invalidate pass`() =
        runBlocking(Dispatchers.Default) {
            // Given - a cache that counts invalidate() calls, and a provider slow enough that the
            // second forced call arrives while the first is still running
            val provider = DelayingProvider(EnrichmentType.ALBUM_ART, delayMs = 120)
            var invalidateCalls = 0
            val cache = object : FakeEnrichmentCache() {
                override suspend fun invalidate(entityKey: String, type: EnrichmentType?) {
                    invalidateCalls++
                    super.invalidate(entityKey, type)
                }
            }
            val engine = engineFor(provider, cache = cache)

            // When - two forceRefresh calls for the same key race each other
            val first = async {
                engine.enrichProgressive(req, setOf(EnrichmentType.ALBUM_ART), forceRefresh = true).last()
            }
            delay(15)
            val second = async {
                engine.enrichProgressive(req, setOf(EnrichmentType.ALBUM_ART), forceRefresh = true).last()
            }
            first.await(); second.await()

            // Then - one fan-out (the second call attached, it did not start its own), one
            // invalidate pass, and the cache still ends up written — a second, redundant
            // invalidate racing the one run's write-back would have been able to wipe it
            assertEquals(1, provider.callCount.get())
            assertEquals(1, invalidateCalls)
            assertTrue("the coalesced run's write-back must survive", cache.stored.isNotEmpty())
        }

    // --- Invariants that must survive detachment ---

    @Test fun `a detached run that times out with nobody watching still caches nothing`() =
        runBlocking(Dispatchers.Default) {
            // Given - a provider slower than the run's own deadline
            val provider = DelayingProvider(EnrichmentType.ALBUM_ART, delayMs = 5_000)
            val cache = FakeEnrichmentCache()
            val engine = DefaultEnrichmentEngine(
                ProviderRegistry(listOf(provider)), cache,
                EnrichmentConfig(enableIdentityResolution = false, enrichTimeoutMs = 50),
            )

            // When - the only collector cancels almost immediately, well before the run's own
            // 50ms deadline, leaving the detached run to time out with nobody attached
            val job = launch { engine.enrichProgressive(req, setOf(EnrichmentType.ALBUM_ART)).collect {} }
            delay(5)
            job.cancelAndJoin()
            delay(250) // past the detached run's own deadline, with nobody watching

            // Then - the timed-out detached run wrote nothing back, reachable via detachment same as
            // any timed-out call, and its dedupe entry evicted like any other completed run
            assertTrue("a timed-out detached run must cache nothing", cache.stored.isEmpty())
            assertEquals(0, engine.inFlightDetachedRunCount())
        }

    @Test fun `detached completion after a cancel writes the same cache entries an uncancelled run writes`() =
        runBlocking(Dispatchers.Default) {
            // Given - an uncancelled run against one cache
            val uncancelledCache = FakeEnrichmentCache()
            val uncancelledEngine = engineFor(
                DelayingProvider(EnrichmentType.ALBUM_ART, delayMs = 60),
                cache = uncancelledCache,
            )
            uncancelledEngine.enrichProgressive(req, setOf(EnrichmentType.ALBUM_ART)).last()

            // And - a separate engine/cache whose only collector cancels almost immediately
            val detachedCache = FakeEnrichmentCache()
            val detachedEngine = engineFor(
                DelayingProvider(EnrichmentType.ALBUM_ART, delayMs = 60),
                cache = detachedCache,
            )
            val job = launch { detachedEngine.enrichProgressive(req, setOf(EnrichmentType.ALBUM_ART)).collect {} }
            delay(1)
            job.cancelAndJoin()

            // When - waiting (bounded) for the detached run's own write-back
            withTimeout(3_000) {
                while (detachedCache.stored.isEmpty()) delay(5)
            }

            // Then - identical cache key sets and identical cached payloads
            assertEquals(uncancelledCache.stored.keys, detachedCache.stored.keys)
            assertEquals(uncancelledCache.stored, detachedCache.stored)
        }

    @Test fun `a second call attaching mid-run receives the current cumulative snapshot immediately`() =
        runBlocking(Dispatchers.Default) {
            // Given - one fast type and one slow type on the same request
            val fast = DelayingProvider(EnrichmentType.SIMILAR_ARTISTS, delayMs = 10, id = "fast")
            val slow = DelayingProvider(EnrichmentType.SIMILAR_TRACKS, delayMs = 400, id = "slow")
            val engine = engineFor(fast, slow)
            val types = setOf(EnrichmentType.SIMILAR_ARTISTS, EnrichmentType.SIMILAR_TRACKS)

            // When - the first call starts the fan-out and stays attached until the fast type has
            // settled but the slow one has not
            val collected = mutableListOf<EnrichmentType>()
            val firstJob = launch {
                engine.enrichProgressive(req, types).collect { snapshot -> collected += snapshot.raw.keys }
            }
            withTimeout(3_000) {
                while (EnrichmentType.SIMILAR_ARTISTS !in collected) delay(5)
            }

            // Then - a second, freshly-attaching call's very first observed snapshot already
            // carries the fast type via the shared run's replay=1 buffer, not an empty one
            val secondFirstSnapshot = engine.enrichProgressive(req, types).first()
            assertTrue(EnrichmentType.SIMILAR_ARTISTS in secondFirstSnapshot.raw)

            firstJob.cancelAndJoin()
        }

    // --- Engine lifecycle (condition 1) ---

    @Test fun `close abandons an in-flight detached run, which writes nothing and releases its collector`() =
        runBlocking(Dispatchers.Default) {
            // Given - a provider slower than the test is willing to wait for a real answer
            val provider = DelayingProvider(EnrichmentType.ALBUM_ART, delayMs = 10_000)
            val cache = FakeEnrichmentCache()
            val engine = DefaultEnrichmentEngine(
                ProviderRegistry(listOf(provider)), cache,
                EnrichmentConfig(enableIdentityResolution = false, enrichTimeoutMs = 60_000),
            )

            // When - a collector stays attached (never cancels) while the engine itself closes
            val resultDeferred = async {
                withTimeout(3_000) { engine.enrichProgressive(req, setOf(EnrichmentType.ALBUM_ART)).last() }
            }
            delay(30)
            engine.close()
            val result = resultDeferred.await()

            // Then - the attached collector was released with an abandoned snapshot (not the
            // 10-second answer, and not a hang), with an honest Error stamp — not just missing —
            // for the type that never settled, and nothing was written back
            val albumArt = result.raw[EnrichmentType.ALBUM_ART]
            assertTrue("an abandoned run must stamp every unsettled type as Error, not omit it", albumArt is EnrichmentResult.Error)
            assertEquals(ErrorKind.ENGINE_CLOSED, (albumArt as EnrichmentResult.Error).errorKind)
            assertTrue("close() must abandon without writing back", cache.stored.isEmpty())
        }

    @Test fun `enrich() itself is released, not hung, when the engine closes mid-call`() =
        runBlocking(Dispatchers.Default) {
            // Given - as above, but through enrich() (= enrichProgressive(...).last()), whose own
            // collector never cancels on its own
            val provider = DelayingProvider(EnrichmentType.ALBUM_ART, delayMs = 10_000)
            val cache = FakeEnrichmentCache()
            val engine = DefaultEnrichmentEngine(
                ProviderRegistry(listOf(provider)), cache,
                EnrichmentConfig(enableIdentityResolution = false, enrichTimeoutMs = 60_000),
            )

            // When - closing the engine while enrich() is still suspended
            val resultDeferred = async { withTimeout(3_000) { engine.enrich(req, setOf(EnrichmentType.ALBUM_ART)) } }
            delay(30)
            engine.close()
            val result = resultDeferred.await()

            // Then - released rather than hung (withTimeout above would have thrown otherwise),
            // every requested type is present as an honest Error(ENGINE_CLOSED) rather than
            // silently missing (this is enrich()'s own documented per-type completeness contract),
            // and consistent with the same abandon-on-close rule enrichProgressive follows
            val albumArt = result.raw[EnrichmentType.ALBUM_ART]
            assertTrue("enrich() must stamp every unsettled type as Error, not omit it", albumArt is EnrichmentResult.Error)
            assertEquals(ErrorKind.ENGINE_CLOSED, (albumArt as EnrichmentResult.Error).errorKind)
            assertTrue("close() must abandon without writing back, even through enrich()", cache.stored.isEmpty())
        }

    @Test fun `enrichProgressive on a brand-new key after close() must not hang`() = runBlocking(Dispatchers.Default) {
        // Given - an engine closed before any run for this request/types key was ever registered
        val provider = DelayingProvider(EnrichmentType.ALBUM_ART, delayMs = 5_000)
        val engine = engineFor(provider)
        engine.close()

        // When - a call for that never-seen key is made after close()
        // Then - it must be released with an abandoned/error snapshot, not hang forever; 8s is well
        // past the provider's own 5s delay, which never actually runs because a scope already
        // cancelled at launch time never starts the fan-out's body at all
        val result = withTimeout(8_000) {
            engine.enrichProgressive(req, setOf(EnrichmentType.ALBUM_ART)).last()
        }
        val albumArt = result.raw[EnrichmentType.ALBUM_ART]
        assertTrue("a call arriving after close() must stamp every type as Error, not omit it", albumArt is EnrichmentResult.Error)
        assertEquals(ErrorKind.ENGINE_CLOSED, (albumArt as EnrichmentResult.Error).errorKind)
    }

    // --- close()'s runBlocking bridge must never deadlock a limited-parallelism dispatcher ---

    /** Suspends via `delay`, so a caller of it is a real mutex-adjacent suspension. */
    private class SlowStaleCache : FakeEnrichmentCache() {
        override suspend fun getIncludingExpired(
            entityKey: String,
            type: EnrichmentType,
        ): CacheEnvelope<EnrichmentResult.Success>? {
            delay(2_000)
            return super.getIncludingExpired(entityKey, type)
        }
    }

    @Test fun `a second close() on a single-thread dispatcher must not deadlock against a slow STALE_IF_ERROR cache read`() {
        val executor = Executors.newSingleThreadExecutor()
        val singleThread = executor.asCoroutineDispatcher()
        try {
            runBlocking {
                withTimeout(10_000) {
                    // Given - STALE_IF_ERROR (so an ENGINE_CLOSED Error triggers a stale-cache
                    // read), a cache whose getIncludingExpired suspends for 2s, and everything
                    // pinned to one single-thread dispatcher
                    val provider = FakeProvider(
                        id = "p",
                        capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)),
                    )
                    val engine = DefaultEnrichmentEngine(
                        ProviderRegistry(listOf(provider)),
                        SlowStaleCache(),
                        EnrichmentConfig(enableIdentityResolution = false, cacheMode = CacheMode.STALE_IF_ERROR),
                        detachedDispatcher = singleThread,
                    )

                    withContext(singleThread) {
                        // When - engine closed once, then a call for a never-seen key enters the
                        // closed branch and suspends on the slow stale-cache read, and a second
                        // close() call on the same single-thread dispatcher follows
                        engine.close()
                        val stuckInClosedBranch = launch {
                            engine.enrichProgressive(req, setOf(EnrichmentType.ALBUM_ART)).last()
                        }
                        delay(200) // let it start the slow cache read
                        val secondClose = async { engine.close() }

                        // Then - both finish; a hang here means close()'s runBlocking deadlocked
                        // the single thread against work its own mutex critical section triggered
                        secondClose.await()
                        stuckInClosedBranch.join()
                    }
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun `the same single-thread close() race never hangs under NETWORK_FIRST, where the closed branch never suspends`() {
        val executor = Executors.newSingleThreadExecutor()
        val singleThread = executor.asCoroutineDispatcher()
        try {
            runBlocking {
                withTimeout(10_000) {
                    // Given - the same setup as the STALE_IF_ERROR probe, but NETWORK_FIRST, whose
                    // closed-branch abandonment never reaches a suspending cache call
                    val provider = FakeProvider(
                        id = "p",
                        capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_ART, 100)),
                    )
                    val engine = DefaultEnrichmentEngine(
                        ProviderRegistry(listOf(provider)),
                        SlowStaleCache(),
                        EnrichmentConfig(enableIdentityResolution = false, cacheMode = CacheMode.NETWORK_FIRST),
                        detachedDispatcher = singleThread,
                    )

                    withContext(singleThread) {
                        // When - the identical close(), in-flight call, second-close() sequence
                        engine.close()
                        val stuckInClosedBranch = launch {
                            engine.enrichProgressive(req, setOf(EnrichmentType.ALBUM_ART)).last()
                        }
                        delay(200)
                        val secondClose = async { engine.close() }

                        // Then - this configuration was never the trigger, only the isolating
                        // control for it, so it must pass regardless of the fix above
                        secondClose.await()
                        stuckInClosedBranch.join()
                    }
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
