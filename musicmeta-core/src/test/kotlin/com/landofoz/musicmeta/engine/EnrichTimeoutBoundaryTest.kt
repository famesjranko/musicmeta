package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.*
import com.landofoz.musicmeta.http.EnrichDeadline
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/**
 * The two halves of `enrich()`'s deadline: what a timed-out run may persist (#56), and whose
 * deadline `ErrorKind.TIMEOUT` is allowed to mean (#55).
 *
 * Catalog filtering is the shape both hang on — it rewrites `results` one type at a time inside the
 * timed block, so an expiry mid-loop leaves a mix of filtered and unfiltered entries, and it is the
 * one consumer-implementable call site with no guard between it and `enrich()`'s own timeout catch.
 */
class EnrichTimeoutBoundaryTest {

    private val req = EnrichmentRequest.forArtist("Radiohead")
    private val cache = FakeEnrichmentCache()

    private fun similarArtists(vararg names: String) = EnrichmentResult.Success(
        type = EnrichmentType.SIMILAR_ARTISTS,
        data = EnrichmentData.SimilarArtists(names.map { SimilarArtist(name = it, matchScore = 0.9f) }),
        provider = "fake",
        confidence = 0.9f,
    )

    private fun similarTracks(vararg titles: String) = EnrichmentResult.Success(
        type = EnrichmentType.SIMILAR_TRACKS,
        data = EnrichmentData.SimilarTracks(titles.map { SimilarTrack(title = it, artist = "Various", matchScore = 0.8f) }),
        provider = "fake",
        confidence = 0.9f,
    )

    /** Resolves an MBID, so the write-back would also reach the name-alias key. */
    private fun identityProvider() = FakeProvider(id = "mb", isIdentityProvider = true).also {
        it.givenIdentityResult(
            EnrichmentResult.Success(
                EnrichmentType.GENRE,
                EnrichmentData.Metadata(genres = listOf("rock")),
                "mb",
                0.95f,
                resolvedIdentifiers = EnrichmentIdentifiers(musicBrainzId = "mbid-123"),
            ),
        )
    }

    private fun recommendationProvider() = FakeProvider(
        id = "fake",
        capabilities = listOf(
            ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, 100),
            ProviderCapability(EnrichmentType.SIMILAR_TRACKS, 100),
        ),
    ).also {
        it.givenResult(EnrichmentType.SIMILAR_ARTISTS, similarArtists("Artist A", "Artist B"))
        it.givenResult(EnrichmentType.SIMILAR_TRACKS, similarTracks("Track 1", "Track 2"))
    }

    private fun engine(catalog: CatalogProvider) = DefaultEnrichmentEngine(
        ProviderRegistry(listOf(identityProvider(), recommendationProvider())),
        cache,
        EnrichmentConfig(
            enableIdentityResolution = true,
            enrichTimeoutMs = 100,
            catalogProvider = catalog,
            catalogFilterMode = CatalogFilterMode.AVAILABLE_ONLY,
        ),
    )

    private val types = setOf(EnrichmentType.SIMILAR_ARTISTS, EnrichmentType.SIMILAR_TRACKS)

    @Test fun `a timeout mid-filter persists nothing, under either key`() = runTest {
        // Given — a catalog that filters the first type and then blocks past the deadline. The
        // second type is left in `results` unfiltered, so caching the map would poison the entry.
        var calls = 0
        val catalog = CatalogProvider { queries ->
            if (calls++ > 0) delay(5_000)
            queries.mapIndexed { i, _ -> CatalogMatch(available = i == 0, source = "test") }
        }

        // When — enriching two recommendation types
        val results = engine(catalog).enrich(req, types)

        // Then — the alias key is genuinely in play: identity resolved an MBID the request lacked,
        // which is the condition for the second write. Without this the empty-cache assertion below
        // could pass for the wrong reason.
        assertEquals("mbid-123", results.identity?.identifiers?.musicBrainzId)

        // And — nothing written back at all: not the filtered type, not the unfiltered one, and
        // neither under the primary key nor under the MBID-resolved name alias.
        assertEquals("a timed-out run caches nothing", emptyMap<String, EnrichmentResult>(), cache.stored)

        // And — results already fetched are still returned, mix and all. That is the contract; only
        // the cache fill is lost. The mix is precisely why it may not be persisted: the first type
        // is filtered down to its available item, the second is the raw provider result.
        val filtered = results.raw[EnrichmentType.SIMILAR_ARTISTS] as EnrichmentResult.Success
        assertEquals(1, (filtered.data as EnrichmentData.SimilarArtists).artists.size)
        val unfiltered = results.raw[EnrichmentType.SIMILAR_TRACKS] as EnrichmentResult.Success
        assertEquals(2, (unfiltered.data as EnrichmentData.SimilarTracks).tracks.size)
    }

    @Test fun `the deadline is readable inside the timed block, so a 429 retry can respect it`() = runTest {
        // Given — a catalog standing in for anything running inside the fan-out; DefaultHttpClient
        // reads the same element to decide whether a Retry-After fits in what is left.
        var remaining: Long? = null
        val catalog = CatalogProvider { queries ->
            remaining = currentCoroutineContext()[EnrichDeadline]?.remainingMs
            queries.map { CatalogMatch(available = true, source = "test") }
        }

        // When
        engine(catalog).enrich(req, types)

        // Then — present, and no larger than the budget it was built from
        assertNotNull("enrich() must install EnrichDeadline", remaining)
        assertTrue("remaining $remaining should be within enrichTimeoutMs", remaining!! in 0..100)
    }

    @Test fun `search() installs the deadline too, having no timeout of its own`() = runTest {
        // Given — a search provider that reports what budget it was given. search() runs no
        // withTimeout, so without this its providers' 429s would retry against the standalone
        // 120s ceiling — minutes of stall on a call that used to fail fast.
        var remaining: Long? = null
        val searcher = object : FakeProvider(id = "search", isIdentityProvider = true) {
            override suspend fun searchCandidates(request: EnrichmentRequest, limit: Int): List<SearchCandidate> {
                remaining = currentCoroutineContext()[EnrichDeadline]?.remainingMs
                return emptyList()
            }
        }

        // When
        DefaultEnrichmentEngine(
            ProviderRegistry(listOf(searcher)), cache, EnrichmentConfig(enrichTimeoutMs = 100),
        ).search(req, limit = 5)

        // Then
        assertNotNull("search() must install EnrichDeadline", remaining)
        assertTrue("remaining $remaining should be within enrichTimeoutMs", remaining!! in 0..100)
    }

    @Test fun `a catalog's own timeout is not reported as the engine's deadline`() = runTest {
        // Given — a consumer catalog running its own withTimeout while our job is perfectly healthy.
        // `catch (_: TimeoutCancellationException)` could not tell this from enrichTimeoutMs expiring.
        val catalog = CatalogProvider {
            withTimeout(1) {
                delay(100)
                emptyList()
            }
        }

        // When — enriching
        val thrown = try {
            val results = engine(catalog).enrich(req, types)
            fail("expected the catalog's own timeout to surface, got $results")
            null
        } catch (e: TimeoutCancellationException) {
            e
        }

        // Then — it surfaces as the catalog's failure. What must not happen is the old behaviour:
        // every unfinished type stamped Error(TIMEOUT) by "engine", telling the consumer their
        // enrichTimeoutMs is too low when the deadline was their own.
        assertNotNull(thrown)
        assertTrue("nothing may be cached from a run that failed this way", cache.stored.isEmpty())
    }
}
