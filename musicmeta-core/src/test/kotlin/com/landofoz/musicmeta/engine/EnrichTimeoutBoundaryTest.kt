package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.*
import com.landofoz.musicmeta.http.EnrichDeadline
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeProvider
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
 * Catalog filtering is the shape both hang on — it runs per type inside one settle-and-finalize
 * region, so a deadline that fires mid-filter for one type never leaks that type's unfiltered raw
 * result; it settles as a clean `Error(TIMEOUT)` instead, and its guard has to separate the
 * consumer's own deadline from `enrich()`'s.
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

    @Test fun `a timeout mid-filter persists nothing, and never leaks an unfiltered type`() = runTest {
        // Given - a catalog that filters one type normally and blocks past the deadline on the
        // other. Each type's whole settle-and-filter step is one atomic region, so the one caught
        // mid-filter never reaches the terminal result unfiltered — it settles as Error(TIMEOUT),
        // same as a type whose chain walk never returned at all.
        var calls = 0
        val catalog = CatalogProvider { queries ->
            if (calls++ > 0) delay(5_000)
            queries.mapIndexed { i, _ -> CatalogMatch(available = i == 0, source = "test") }
        }

        // When - enriching two recommendation types
        val results = engine(catalog).enrich(req, types)

        // Then - the alias key is genuinely in play: identity resolved an MBID the request lacked,
        // which is the condition for the second write. Without this the empty-cache assertion below
        // could pass for the wrong reason.
        assertEquals("mbid-123", results.identity.identifiers.musicBrainzId)

        // And — nothing written back at all: not the filtered type, not the timed-out one, and
        // neither under the primary key nor under the MBID-resolved name alias.
        assertEquals("a timed-out run caches nothing", emptyMap<String, EnrichmentResult>(), cache.stored)

        // And — real concurrency does not promise which of the two types wins the race against the
        // deadline, so this asserts the shape rather than which type lands on which side: exactly
        // one settled and filtered down to its available item, and the other never leaks the raw,
        // unfiltered provider result — it is a clean Error(TIMEOUT) instead.
        val filteredCount = types.count { type ->
            val result = results.raw[type]
            result is EnrichmentResult.Success && itemCount(result) == 1
        }
        val timedOutCount = types.count { type ->
            val result = results.raw[type]
            result is EnrichmentResult.Error && result.errorKind == ErrorKind.TIMEOUT
        }
        assertEquals("exactly one type should have settled and been filtered", 1, filteredCount)
        assertEquals("exactly one type should have timed out mid-filter", 1, timedOutCount)
    }

    private fun itemCount(result: EnrichmentResult.Success): Int = when (val data = result.data) {
        is EnrichmentData.SimilarArtists -> data.artists.size
        is EnrichmentData.SimilarTracks -> data.tracks.size
        else -> error("unexpected data type in this test: $data")
    }

    @Test fun `the deadline is readable inside the timed block, so a 429 retry can respect it`() = runTest {
        // Given - a catalog standing in for anything running inside the fan-out; DefaultHttpClient
        // reads the same element to decide whether a Retry-After fits in what is left.
        var remaining: Long? = null
        val catalog = CatalogProvider { queries ->
            remaining = currentCoroutineContext()[EnrichDeadline]?.remainingMs
            queries.map { CatalogMatch(available = true, source = "test") }
        }

        // When - enriching through the catalog
        engine(catalog).enrich(req, types)

        // Then - present, and no larger than the budget it was built from
        assertNotNull("enrich() must install EnrichDeadline", remaining)
        assertTrue("remaining $remaining should be within enrichTimeoutMs", remaining!! in 0..100)
    }

    @Test fun `search() installs the deadline too, having no timeout of its own`() = runTest {
        // Given - a search provider that reports what budget it was given. search() runs no
        // withTimeout, so without this its providers' 429s would retry against the standalone
        // 120s ceiling — minutes of stall on a call that used to fail fast.
        var remaining: Long? = null
        val searcher = object : FakeProvider(id = "search", isIdentityProvider = true) {
            override suspend fun searchCandidates(request: EnrichmentRequest, limit: Int): List<SearchCandidate> {
                remaining = currentCoroutineContext()[EnrichDeadline]?.remainingMs
                return emptyList()
            }
        }

        // When - calling search()
        DefaultEnrichmentEngine(
            ProviderRegistry(listOf(searcher)), cache, EnrichmentConfig(enrichTimeoutMs = 100),
        ).search(req, limit = 5)

        // Then - the deadline is installed even though search() has no withTimeout of its own
        assertNotNull("search() must install EnrichDeadline", remaining)
        assertTrue("remaining $remaining should be within enrichTimeoutMs", remaining!! in 0..100)
    }

    @Test fun `a catalog's own timeout is not reported as the engine's deadline`() = runTest {
        // Given - a consumer catalog running its own withTimeout while our job is perfectly healthy.
        // `catch (_: TimeoutCancellationException)` could not tell this from enrichTimeoutMs expiring.
        val catalog = CatalogProvider {
            withTimeout(1) {
                delay(100)
                emptyList()
            }
        }

        // When - enriching
        val results = engine(catalog).enrich(req, types)

        // Then - it costs the catalog's filtering and nothing else: both types come back as the
        // provider returned them. What must not happen is the old behaviour: every unfinished type
        // stamped Error(TIMEOUT) by "engine", telling the consumer their enrichTimeoutMs is too low
        // when the deadline was their own.
        val artists = results.raw[EnrichmentType.SIMILAR_ARTISTS] as EnrichmentResult.Success
        assertEquals(2, (artists.data as EnrichmentData.SimilarArtists).artists.size)
        val tracks = results.raw[EnrichmentType.SIMILAR_TRACKS] as EnrichmentResult.Success
        assertEquals(2, (tracks.data as EnrichmentData.SimilarTracks).tracks.size)

        // And - the run completed, so what the providers fetched is cached rather than discarded.
        assertTrue("a completed run caches its results", cache.stored.isNotEmpty())
    }
}
