package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.*
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeHttpClient
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * `enrich()` must not propagate a failure from a consumer-supplied [ResultMerger] or
 * [CompositeSynthesizer]. These are the third of three consumer-implementable extension points;
 * [EnrichmentProvider] is guarded in [ProviderChain] and [EnrichmentCache] in `CacheGuard`.
 */
class EnrichStrategyFailureTest {
    private lateinit var cache: FakeEnrichmentCache
    private val config = EnrichmentConfig(enableIdentityResolution = false)
    private val req = EnrichmentRequest.forArtist("Radiohead")
    private val genre = EnrichmentType.GENRE
    private val timeline = EnrichmentType.ARTIST_TIMELINE
    private val bio = EnrichmentType.ARTIST_BIO

    @Before fun setup() { cache = FakeEnrichmentCache() }

    private fun success(type: EnrichmentType, provider: String = "p") =
        EnrichmentResult.Success(type, EnrichmentData.Metadata(genres = listOf("rock")), provider, 0.95f)

    /** A merger that always throws — stands in for a consumer's broken implementation. */
    private class ThrowingMerger(override val type: EnrichmentType) : ResultMerger {
        override fun merge(results: List<EnrichmentResult.Success>): EnrichmentResult =
            throw IllegalStateException("merger boom")
    }

    /** A synthesizer that always throws, with no dependencies so nothing else must resolve first. */
    private class ThrowingSynthesizer(override val type: EnrichmentType) : CompositeSynthesizer {
        override val dependencies: Set<EnrichmentType> = emptySet()
        override fun synthesize(
            resolved: Map<EnrichmentType, EnrichmentResult>,
            identityResult: EnrichmentResult?,
            request: EnrichmentRequest,
        ): EnrichmentResult = throw IllegalStateException("synthesizer boom")
    }

    private fun engine(
        providers: List<EnrichmentProvider>,
        mergers: List<ResultMerger> = emptyList(),
        synthesizers: List<CompositeSynthesizer> = emptyList(),
    ) = DefaultEnrichmentEngine(
        ProviderRegistry(providers), cache, FakeHttpClient(), config,
        mergers = mergers, synthesizers = synthesizers,
    )

    private fun providerFor(vararg types: EnrichmentType, id: String = "p") =
        FakeProvider(id = id, capabilities = types.map { ProviderCapability(it, 100) })
            .also { p -> types.forEach { p.givenResult(it, success(it, id)) } }

    @Test fun `enrich reports a typed Error when the merger throws`() = runTest {
        // Given — a GENRE provider with data, and a merger for GENRE that throws
        val p = providerFor(genre)

        // When — enriching the mergeable type
        val results = engine(listOf(p), mergers = listOf(ThrowingMerger(genre))).enrich(req, setOf(genre))

        // Then — the failure comes back as an Error for that type rather than escaping enrich()
        val result = results.raw[genre]
        assertTrue("expected Error, got $result", result is EnrichmentResult.Error)
        assertEquals("merger boom", (result as EnrichmentResult.Error).message)
        assertEquals("merger", result.provider)
    }

    @Test fun `enrich reports a typed Error when the synthesizer throws`() = runTest {
        // Given — a composite type whose synthesizer throws
        val p = providerFor(bio)

        // When — enriching the composite type
        val results = engine(listOf(p), synthesizers = listOf(ThrowingSynthesizer(timeline)))
            .enrich(req, setOf(timeline))

        // Then — the failure comes back as an Error for that type rather than escaping enrich()
        val result = results.raw[timeline]
        assertTrue("expected Error, got $result", result is EnrichmentResult.Error)
        assertEquals("synthesizer boom", (result as EnrichmentResult.Error).message)
        assertEquals("synthesizer", result.provider)
    }

    @Test fun `a throwing merger does not discard an unrelated type resolved in the same call`() = runTest {
        // Given — one provider serving both a mergeable type whose merger throws and an
        // unrelated regular type; previously the throw unwound the whole call and both were lost
        val p = providerFor(genre, bio)

        // When — enriching both in one call
        val results = engine(listOf(p), mergers = listOf(ThrowingMerger(genre)))
            .enrich(req, setOf(genre, bio))

        // Then — only the merged type fails; the unrelated type's work survives
        assertTrue(results.raw[genre] is EnrichmentResult.Error)
        assertEquals("p", (results.raw[bio] as EnrichmentResult.Success).provider)
    }

    @Test fun `a throwing merger does not discard a cache hit collected in the same call`() = runTest {
        // Given — a cached result for an unrelated type, and a merger that throws
        val p = providerFor(genre)
        cache.put(DefaultEnrichmentEngine.entityKeyFor(req, bio), bio, success(bio, "cached"))

        // When — enriching the cached type alongside the failing merged type
        val results = engine(listOf(p), mergers = listOf(ThrowingMerger(genre)))
            .enrich(req, setOf(genre, bio))

        // Then — the cache hit is still returned, not thrown away with the merge failure
        assertEquals("cached", (results.raw[bio] as EnrichmentResult.Success).provider)
    }

    @Test fun `a type with no merger registered resolves through the chain untouched`() = runTest {
        // Given — GENRE requested with no merger registered, so it is not a mergeable type at all
        // and never reaches the guarded merge path
        val p = providerFor(genre)

        // When — enriching that type
        val results = engine(listOf(p)).enrich(req, setOf(genre))

        // Then — it resolves as an ordinary type; the guard only ever sees a merger that exists
        assertEquals("p", (results.raw[genre] as EnrichmentResult.Success).provider)
    }

    // --- the guard itself ---

    @Test fun `guarded strategy converts an ordinary failure into an Error for the type`() = runTest {
        // Given — a strategy that throws a plain exception
        // When — run through the guard
        val result = guardedStrategy(EnrichmentLogger.NoOp, genre, "merger") {
            throw IllegalStateException("boom")
        }

        // Then — it becomes a typed Error naming the type and the strategy
        assertTrue(result is EnrichmentResult.Error)
        assertEquals(genre, (result as EnrichmentResult.Error).type)
        assertEquals("boom", result.message)
    }

    // --- cancellation: the two directions must be told apart (#61) ---
    //
    // Same split as `CacheGuard`. `merge`/`synthesize` are not suspend, so a consumer reaching for
    // a deadline writes `runBlocking { withTimeout { … } }` and its expiry arrives here as a bare
    // CancellationException — which is what these tests throw.

    @Test fun `guarded strategy propagates a cancellation of our own job`() = runTest {
        // Given — our job genuinely cancelled. Both call sites sit inside enrich()'s withTimeout,
        // so this is also how enrichTimeoutMs must continue to be delivered.
        // Observed from inside the coroutine, and joined rather than awaited — `await()` on a
        // cancelled Deferred throws regardless of what the guard did, which makes the obvious
        // version of this test pass with the guard deleted.
        var rethrew = false
        var degraded = false
        val running = CoroutineScope(Job()).async {
            // Captured out here: the guarded block is not suspend, so it cannot reach the context
            // itself — which is precisely why the guard has to be told to look, and cannot infer
            // ownership from the exception type.
            val job = currentCoroutineContext()[Job]
            try {
                guardedStrategy(EnrichmentLogger.NoOp, genre, "merger") {
                    job?.cancel()
                    throw CancellationException("caller went away")
                }
                degraded = true
            } catch (_: CancellationException) {
                rethrew = true
            }
        }

        // When — letting the guarded merge finish
        running.join()

        // Then — it stops, rather than reporting a type that nobody is waiting for
        assertTrue("a cancelled enrichment must not be converted into a per-type Error", rethrew)
        assertFalse("our own cancellation must not become that type's Error", degraded)
    }

    @Test fun `guarded strategy reports an Error when the strategy's own timeout expires`() = runTest {
        // Given — a consumer merger whose own deadline expired, our job healthy
        val result = guardedStrategy(EnrichmentLogger.NoOp, genre, "merger") {
            throw CancellationException("the merger's own withTimeout expired")
        }

        // Then — contained as that type's failure. Escaping here cancels the sibling types resolving
        // alongside it and is reported to the caller as enrichTimeoutMs expiring.
        assertTrue("a merger's own timeout is that merger's failure", result is EnrichmentResult.Error)
        assertEquals(genre, (result as EnrichmentResult.Error).type)
    }

    @Test fun `enrich reports a typed Error when the merger's own timeout expires`() = runTest {
        // Given — the same at the public API, on the async fan-out path where an escaping
        // cancellation takes the sibling types down with it
        val cancellingMerger = object : ResultMerger {
            override val type = genre
            override fun merge(results: List<EnrichmentResult.Success>): EnrichmentResult =
                throw CancellationException("the merger's own withTimeout expired")
        }

        // When — enriching both a merged type and an ordinary one
        val results = engine(
            listOf(providerFor(genre, bio)),
            mergers = listOf(cancellingMerger),
        ).enrich(req, setOf(genre, bio))

        // Then — enrich() returns, GENRE carries the failure, and ARTIST_BIO is untouched by it
        assertTrue(results.raw[genre] is EnrichmentResult.Error)
        assertEquals("p", (results.raw[bio] as EnrichmentResult.Success).provider)
    }
}
