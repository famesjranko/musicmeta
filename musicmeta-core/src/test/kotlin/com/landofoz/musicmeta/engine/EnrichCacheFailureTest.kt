package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.*
import com.landofoz.musicmeta.cache.CacheMode
import com.landofoz.musicmeta.testutil.CacheOp
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeHttpClient
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * `enrich()` must not propagate a failure from the [EnrichmentCache] — the cache is an optimisation,
 * and README.md's "Partial failure resilience" section tells consumers they need no try/catch.
 * Each cache interaction inside `enrich()` gets a test here.
 */
class EnrichCacheFailureTest {
    private lateinit var cache: FakeEnrichmentCache
    private val config = EnrichmentConfig(enableIdentityResolution = false)
    private val req = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")
    private val artType = EnrichmentType.ALBUM_ART

    @Before fun setup() { cache = FakeEnrichmentCache() }

    private fun art(p: String) =
        EnrichmentResult.Success(artType, EnrichmentData.Artwork("https://x.com/art.jpg"), p, 0.95f)

    private fun engine(provider: FakeProvider, cfg: EnrichmentConfig = config) =
        DefaultEnrichmentEngine(ProviderRegistry(listOf(provider)), cache, FakeHttpClient(), cfg)

    private fun provider(result: EnrichmentResult) =
        FakeProvider(id = "p", capabilities = listOf(ProviderCapability(artType, 100)))
            .also { it.givenResult(artType, result) }

    @Test fun `enrich falls through to the provider when the cache read throws`() = runTest {
        // Given — a cache holding a result, but whose get() throws
        val p = provider(art("fresh"))
        cache.put(DefaultEnrichmentEngine.entityKeyFor(req, artType), artType, art("cached"))
        cache.failing = setOf(CacheOp.GET)

        // When — enriching that type
        val results = engine(p).enrich(req, setOf(artType))

        // Then — the failure is treated as a miss and the provider answers, rather than throwing
        assertEquals("fresh", (results.raw[artType] as EnrichmentResult.Success).provider)
        assertEquals(1, p.enrichCalls.size)
    }

    @Test fun `enrich still returns provider results when the cache write throws`() = runTest {
        // Given — a provider with data and a cache whose put() throws
        val p = provider(art("fresh"))
        cache.failing = setOf(CacheOp.PUT)

        // When — enriching that type
        val results = engine(p).enrich(req, setOf(artType))

        // Then — the caller gets its result; only the caching side effect is lost
        assertEquals("fresh", (results.raw[artType] as EnrichmentResult.Success).provider)
    }

    @Test fun `forceRefresh returns fresh data rather than stale when invalidate throws`() = runTest {
        // Given — a stale cache entry, fresh provider data, and a cache whose invalidate() throws
        val p = provider(art("fresh"))
        cache.put(DefaultEnrichmentEngine.entityKeyFor(req, artType), artType, art("stale"))
        cache.failing = setOf(CacheOp.INVALIDATE)

        // When — forcing a refresh
        val results = engine(p).enrich(req, setOf(artType), forceRefresh = true)

        // Then — the failed invalidation must not resurrect the entry it was meant to drop
        assertEquals("fresh", (results.raw[artType] as EnrichmentResult.Success).provider)
    }

    @Test fun `STALE_IF_ERROR surfaces the provider error when the stale read throws`() = runTest {
        // Given — STALE_IF_ERROR mode, a failing provider, and a cache whose stale read throws
        val p = provider(EnrichmentResult.Error(artType, "p", "boom", errorKind = ErrorKind.NETWORK))
        cache.expiredStore["${DefaultEnrichmentEngine.entityKeyFor(req, artType)}:$artType"] = art("stale")
        cache.failing = setOf(CacheOp.GET_INCLUDING_EXPIRED)
        val cfg = EnrichmentConfig(enableIdentityResolution = false, cacheMode = CacheMode.STALE_IF_ERROR)

        // When — enriching that type
        val results = engine(p, cfg).enrich(req, setOf(artType))

        // Then — the typed Error comes back instead of the stale-lookup exception escaping
        assertTrue(results.raw[artType] is EnrichmentResult.Error)
    }

    @Test fun `a failed primary invalidation still invalidates the name-alias key`() = runTest {
        // Given — an MBID-carrying request, so forceRefresh invalidates a primary and an alias key,
        // with both keys populated and only the primary key's invalidate() throwing
        val mbidReq = EnrichmentRequest.ForAlbum(
            EnrichmentIdentifiers(musicBrainzId = "mbid-123"), "OK Computer", "Radiohead",
        )
        val p = provider(art("fresh"))
        val primaryKey = DefaultEnrichmentEngine.entityKeyFor(mbidReq, artType)
        val aliasKey = DefaultEnrichmentEngine.entityKeyForName(mbidReq, artType)
        cache.put(primaryKey, artType, art("stale"))
        cache.put(aliasKey, artType, art("stale-alias"))
        cache.failing = setOf(CacheOp.INVALIDATE)
        cache.failingKey = primaryKey

        // When — forcing a refresh
        engine(p).enrich(mbidReq, setOf(artType), forceRefresh = true)

        // Then — the alias invalidation still ran, so a later name-only lookup cannot be
        // served the stale alias entry that the failed primary key aborted early
        assertFalse("alias key should have been invalidated", "$aliasKey:$artType" in cache.stored)
    }

    // --- the guard itself ---

    @Test fun `guarded cache read degrades to a miss on an ordinary failure`() = runTest {
        // Given — a read that throws a plain exception
        // When — run through the guard
        val value = guardedCacheRead<String>(EnrichmentLogger.NoOp, "get") {
            throw IllegalStateException("disk gone")
        }

        // Then — it degrades to null rather than propagating
        assertNull(value)
    }

    // --- cancellation: the two directions must be told apart (#61) ---
    //
    // The guard cannot ask "was this a CancellationException" — it must ask "is *our* job
    // cancelled". `ensureActive()` answers the second question; a blanket rethrow answers the
    // first, and gets the foreign case wrong. Both tests below are needed: the propagate case
    // alone is satisfied by the blanket rethrow this issue removes.

    @Test fun `guarded cache read propagates a cancellation of our own job`() = runTest {
        // Given — the caller genuinely went away, so the cache call is cancelled with it. The
        // cancel() matters: throwing while the job is still active is a foreign cancellation, not
        // ours, and is the case immediately below.
        //
        // Both flags are observed from *inside* the coroutine deliberately. Reading the outcome
        // through `await()` proves nothing: a Deferred whose job was cancelled throws
        // CancellationException from await() whether the guard rethrew or swallowed and returned,
        // so that version of this test stayed green with the guard deleted entirely.
        var rethrew = false
        var degraded = false
        val running = CoroutineScope(Job()).async {
            val job = currentCoroutineContext()[Job]
            try {
                guardedCacheRead<String>(EnrichmentLogger.NoOp, "get") {
                    job?.cancel()
                    throw CancellationException("caller went away")
                }
                // Reached only if the guard degraded. There is no suspension point between the
                // cancel() and here, so the cancellation does *not* re-assert on its own — the
                // assumption CLAUDE.md pitfall 2 warns about.
                degraded = true
            } catch (_: CancellationException) {
                rethrew = true
            }
        }

        // When — letting it finish. join(), not await(): join does not throw on cancellation, so
        // the assertions below can only be satisfied by the guard's own behaviour.
        running.join()

        // Then — enrichment stops, rather than working on behalf of a caller that has gone
        assertTrue("a cancelled caller must not be left waiting on a cache round-trip", rethrew)
        assertFalse("our own cancellation must not degrade to a cache miss", degraded)
    }

    @Test fun `guarded cache read degrades when the cache's own timeout expires`() = runTest {
        // Given — a consumer cache running its own withTimeout while our job is perfectly healthy.
        // EnrichmentCache is consumer-implementable and the shipped one does disk I/O, so this is
        // the ordinary case, not a hypothetical. TimeoutCancellationException IS a
        // CancellationException, so the blanket rethrow escaped enrich() here.
        val value = guardedCacheRead<String>(EnrichmentLogger.NoOp, "get") {
            withTimeout(1) {
                delay(1_000)
                "never reached"
            }
        }

        // Then — a slow cache is a cache miss, exactly like a throwing one. It is not the engine's
        // deadline, and it must not be reported to the caller as one.
        assertNull("a cache that timed itself out is a miss, not a cancelled enrichment", value)
    }

    @Test fun `enrich survives a cache whose own timeout expires mid-read`() = runTest {
        // Given — the same thing at the public API: the guard's degradation has to actually reach
        // the caller, not just the unit test. This is the failure #61 describes end to end.
        val p = provider(art("fresh"))
        cache = object : FakeEnrichmentCache() {
            override suspend fun get(entityKey: String, type: EnrichmentType): EnrichmentResult.Success? =
                withTimeout(1) {
                    delay(1_000)
                    null
                }
        }

        // When — enriching, with our own job healthy throughout
        val results = engine(p).enrich(req, setOf(artType))

        // Then — enrich() completes and the provider answers. Before the fix this threw a bare
        // CancellationException out of enrich(), which the caller reads as the engine's deadline.
        assertEquals("fresh", (results.raw[artType] as EnrichmentResult.Success).provider)
    }
}
