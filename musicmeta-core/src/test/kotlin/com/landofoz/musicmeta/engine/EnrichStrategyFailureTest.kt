package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.*
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeHttpClient
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.CancellationException
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

    @Test fun `guarded strategy converts an ordinary failure into an Error for the type`() {
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

    @Test fun `guarded strategy rethrows CancellationException`() {
        // Given — a strategy that throws CancellationException, as a consumer's own cancelled work can
        var propagated = false

        // When — run through the guard
        try {
            guardedStrategy(EnrichmentLogger.NoOp, genre, "merger") {
                throw CancellationException("cancelled")
            }
        } catch (_: CancellationException) {
            propagated = true
        }

        // Then — it is rethrown rather than converted to an Error, as structured concurrency requires
        assertTrue("cancellation must not be swallowed by the strategy guard", propagated)
    }
}
