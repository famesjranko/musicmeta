package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.SimilarArtist
import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.HttpResult
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzProvider
import com.landofoz.musicmeta.testkit.UpstreamPools
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine step that labels a merged similar-artist list, and every reason it declines to run.
 *
 * The step calls MusicBrainz directly rather than through a `ProviderChain`, which is exactly why
 * the declining cases are pinned as hard as the working one: it is the single call in the engine
 * that no chain is gating, so the gates a chain would have applied have to be here instead.
 */
class SimilarArtistDisambiguationEngineTest {

    /** A contributor answering with the split pair the whole feature exists for: one side described. */
    private class SplitPairProvider : EnrichmentProvider {
        override val id: String = "fake_similar"
        override val displayName: String = "Fake"
        override val requiresApiKey: Boolean = false
        override val isAvailable: Boolean = true
        override val capabilities: List<ProviderCapability> =
            listOf(ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, priority = 100))

        override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult =
            EnrichmentResult.Success(
                type = EnrichmentType.SIMILAR_ARTISTS,
                data = EnrichmentData.SimilarArtists(
                    artists = listOf(
                        SimilarArtist(
                            name = "Loathe",
                            identifiers = EnrichmentIdentifiers(musicBrainzId = LOATHE_UK),
                            matchScore = 1.0f,
                            sources = listOf("listenbrainz"),
                            disambiguation = "UK experimental metal",
                        ),
                        SimilarArtist(
                            name = "Loathe",
                            identifiers = EnrichmentIdentifiers(musicBrainzId = LOATHE_MT),
                            matchScore = 0.4f,
                            sources = listOf("lastfm"),
                        ),
                    ),
                ),
                provider = id,
                confidence = 1.0f,
            )
    }

    /** A contributor for an unrelated type, so a run's other answers can be checked for collateral. */
    private class GenreProvider : EnrichmentProvider {
        override val id: String = "fake_genre"
        override val displayName: String = "Fake genre"
        override val requiresApiKey: Boolean = false
        override val isAvailable: Boolean = true
        override val capabilities: List<ProviderCapability> =
            listOf(ProviderCapability(EnrichmentType.GENRE, priority = 100))

        override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult =
            EnrichmentResult.Success(
                type = EnrichmentType.GENRE,
                data = EnrichmentData.Metadata(genres = listOf("metal")),
                provider = id,
                confidence = 1.0f,
            )
    }

    /**
     * [delegate], with the batch search made slower than any budget it may be given.
     *
     * `delay` rather than a blocking sleep on purpose: the point under test is that the step's own
     * ceiling *cancels* the wait, and a wait no cancellation can reach would prove nothing.
     */
    private class SlowAridHttpClient(
        private val delegate: FakeHttpClient,
        private val delayMs: Long,
    ) : HttpClient {
        override suspend fun fetchJsonResult(url: String): HttpResult<JSONObject> {
            if (url.contains("arid")) delay(delayMs)
            return delegate.fetchJsonResult(url)
        }

        override suspend fun fetchJsonResult(url: String, headers: Map<String, String>): HttpResult<JSONObject> =
            delegate.fetchJsonResult(url, headers)

        override suspend fun fetchJsonArrayResult(url: String): HttpResult<JSONArray> =
            delegate.fetchJsonArrayResult(url)

        override suspend fun fetchRedirectUrlResult(url: String): HttpResult<String> =
            delegate.fetchRedirectUrlResult(url)

        override suspend fun postJsonResult(url: String, body: String): HttpResult<JSONObject> =
            delegate.postJsonResult(url, body)

        override suspend fun postJsonArrayResult(url: String, body: String): HttpResult<JSONArray> =
            delegate.postJsonArrayResult(url, body)
    }

    private fun http(): FakeHttpClient = FakeHttpClient().apply {
        givenJsonResponse(
            "artist?query=arid",
            UpstreamPools.body("similar-artist-disambiguation", "musicbrainz-arid-batch.json"),
        )
        // The name search identity resolution makes: without it nothing resolves, and an unresolved
        // request is never written back, which would make the warm-cache case below prove nothing.
        givenJsonResponse("artist%3A%22", SLEEP_TOKEN_SEARCH)
    }

    private fun engineOver(
        http: FakeHttpClient,
        withMusicBrainz: Boolean = true,
        cache: InMemoryEnrichmentCache? = null,
        config: EnrichmentConfig = EnrichmentConfig(),
    ): EnrichmentEngine = EnrichmentEngine.Builder()
        .httpClient(http)
        .config(config)
        .addProvider(SplitPairProvider())
        .apply { if (withMusicBrainz) addProvider(MusicBrainzProvider(http, RateLimiter(0L))) }
        .apply { cache?.let { cache(it) } }
        .build()

    private suspend fun similarArtists(engine: EnrichmentEngine): List<SimilarArtist> {
        val results = engine.enrich(EnrichmentRequest.forArtist("Sleep Token"), setOf(EnrichmentType.SIMILAR_ARTISTS))
        val result = results.raw[EnrichmentType.SIMILAR_ARTISTS]
        assertTrue("expected a Success, got $result", result is EnrichmentResult.Success)
        return ((result as EnrichmentResult.Success).data as EnrichmentData.SimilarArtists).artists
    }

    private fun FakeHttpClient.aridRequests() = requestedUrls.filter { it.contains("arid") }

    @Test
    fun `the undescribed half of a same-name pair is labelled from MusicBrainz`() = runTest {
        // Given - a contributor answering with a split pair, and MusicBrainz able to describe it
        val http = http()

        // When - the merged list is asked for
        val artists = similarArtists(engineOver(http))

        // Then - both sides are readable now, each from its own MusicBrainz record, at one request
        assertEquals("UK experimental metal", artists.single { it.identifiers.musicBrainzId == LOATHE_UK }.disambiguation)
        assertEquals("Maltese death metal band", artists.single { it.identifiers.musicBrainzId == LOATHE_MT }.disambiguation)
        assertEquals(1, http.aridRequests().size)
    }

    @Test
    fun `labelling does not add MusicBrainz to the sources that recommended the artist`() = runTest {
        // Given - the same stack
        val http = http()

        // When - the merged list is asked for
        val artists = similarArtists(engineOver(http))

        // Then - sources still names the contributor that put the artist in the list, and only it
        assertEquals(listOf("lastfm"), artists.single { it.identifiers.musicBrainzId == LOATHE_MT }.sources)
    }

    @Test
    fun `an engine with no MusicBrainz provider returns the merged list unlabelled`() = runTest {
        // Given - an engine a consumer built without MusicBrainz, which is theirs to do
        val http = http()

        // When - the merged list is asked for
        val artists = similarArtists(engineOver(http, withMusicBrainz = false))

        // Then - a Success with the free half only, no request, and nothing thrown
        assertEquals("UK experimental metal", artists.single { it.identifiers.musicBrainzId == LOATHE_UK }.disambiguation)
        assertNull(artists.single { it.identifiers.musicBrainzId == LOATHE_MT }.disambiguation)
        assertEquals(0, http.aridRequests().size)
    }

    @Test
    fun `a MusicBrainz whose breaker has tripped is not asked`() = runTest {
        // Given - a MusicBrainz that has failed enough consecutive calls to open its breaker
        val http = FakeHttpClient().apply { givenError("musicbrainz.org") }
        val musicBrainz = MusicBrainzProvider(http, RateLimiter(0L))
        val engine = EnrichmentEngine.Builder()
            .httpClient(http)
            .addProvider(SplitPairProvider())
            .addProvider(musicBrainz)
            .build()
        repeat(BREAKER_THRESHOLD) {
            engine.enrich(EnrichmentRequest.forArtist("Sleep Token"), setOf(EnrichmentType.GENRE))
        }
        val before = http.aridRequests().size

        // When - a similar-artist list is merged while that breaker is open
        val results = engine.enrich(EnrichmentRequest.forArtist("Sleep Token"), setOf(EnrichmentType.SIMILAR_ARTISTS))

        // Then - the one engine call that bypasses ProviderChain still honours the gate the chain
        // applies, so a shed MusicBrainz is not asked one more question per merge
        val result = results.raw[EnrichmentType.SIMILAR_ARTISTS]
        assertTrue("expected a Success, got $result", result is EnrichmentResult.Success)
        assertEquals(before, http.aridRequests().size)
    }

    @Test
    fun `a warm cache read asks MusicBrainz nothing`() = runTest {
        // Given - a cache, and one cold call that has already labelled and stored the list
        val http = http()
        val cache = InMemoryEnrichmentCache()
        val engine = engineOver(http, cache = cache)
        val cold = similarArtists(engine)
        assertEquals("Maltese death metal band", cold.single { it.identifiers.musicBrainzId == LOATHE_MT }.disambiguation)
        val afterCold = http.aridRequests().size

        // When - the same request is served again, this time from the cache
        val warm = similarArtists(engine)

        // Then - the text came back with the payload and cost nothing. Labelling on the serve path
        // instead would re-ask MusicBrainz on every warm read for the entry's whole TTL
        assertEquals("Maltese death metal band", warm.single { it.identifiers.musicBrainzId == LOATHE_MT }.disambiguation)
        assertEquals(afterCold, http.aridRequests().size)
    }

    @Test
    fun `a run with no deadline left asks MusicBrainz nothing`() = runTest {
        // Given - a budget the fan-out has already spent by the time anything merges
        val http = http()
        val engine = engineOver(http, config = EnrichmentConfig(enrichTimeoutMs = 1))

        // When - a similar-artist list is asked for
        engine.enrich(EnrichmentRequest.forArtist("Sleep Token"), setOf(EnrichmentType.SIMILAR_ARTISTS))

        // Then - the label is skipped rather than spending the last of a budget the run still owes
        // its cache write-back. Two mechanisms hold this today: the explicit check, and
        // withTimeoutOrNull declining to run a block at a non-positive timeout at all
        assertEquals(0, http.aridRequests().size)
    }

    @Test
    fun `a slow batch gives up on its own ceiling rather than spending the run's whole budget`() = runTest {
        // Given - a MusicBrainz that hangs on the batch for far longer than the run's own deadline,
        // and a run whose deadline is itself far longer than the label's ceiling. On the shared
        // 1 req/s limiter this is an ordinary shape, not a pathological one.
        val slow = SlowAridHttpClient(http(), delayMs = SLOW_BATCH_MS)
        val engine = EnrichmentEngine.Builder()
            .httpClient(slow)
            .config(EnrichmentConfig(enrichTimeoutMs = RUN_DEADLINE_MS))
            .addProvider(SplitPairProvider())
            .addProvider(GenreProvider())
            .addProvider(MusicBrainzProvider(slow, RateLimiter(0L)))
            .build()

        // When - a run asks for the merged list and one unrelated type
        val results = engine.enrich(
            EnrichmentRequest.forArtist("Sleep Token"),
            setOf(EnrichmentType.SIMILAR_ARTISTS, EnrichmentType.GENRE),
        )

        // Then - the label is abandoned at its own ceiling, so the merged list still arrives with the
        // free half intact and the paid half blank...
        val similar = results.raw[EnrichmentType.SIMILAR_ARTISTS]
        assertTrue("expected a Success, got $similar", similar is EnrichmentResult.Success)
        val artists = ((similar as EnrichmentResult.Success).data as EnrichmentData.SimilarArtists).artists
        assertEquals("UK experimental metal", artists.single { it.identifiers.musicBrainzId == LOATHE_UK }.disambiguation)
        assertNull(artists.single { it.identifiers.musicBrainzId == LOATHE_MT }.disambiguation)

        // ...and every other type of the same run is untouched. Bounded by the run's own deadline
        // instead, a cosmetic label would hold the fan-out until that deadline fired and take the
        // unrelated answers — and the cache write-back — down with it
        val genre = results.raw[EnrichmentType.GENRE]
        assertTrue("a label must not cost an unrelated type its answer, got $genre", genre is EnrichmentResult.Success)
    }

    @Test
    fun `a failing MusicBrainz leaves the entry unlabelled rather than failing the type`() = runTest {
        // Given - a MusicBrainz that sheds the batch search
        val http = FakeHttpClient().apply { givenError("artist?query=arid") }

        // When - the merged list is asked for
        val artists = similarArtists(engineOver(http))

        // Then - still a Success carrying the free half: this labels an answer that already merged,
        // so its failure may not turn a good answer into an error
        assertEquals("UK experimental metal", artists.single { it.identifiers.musicBrainzId == LOATHE_UK }.disambiguation)
        assertNull(artists.single { it.identifiers.musicBrainzId == LOATHE_MT }.disambiguation)
    }

    private companion object {
        const val LOATHE_UK = "56eb02c4-1f16-4613-8bb3-b4a752283fc3"
        const val LOATHE_MT = "e9ea0fbc-ccc7-4e98-9290-0a41aa848fa2"

        /** `CircuitBreaker.DEFAULT_FAILURE_THRESHOLD` — restated so tripping it is deliberate here. */
        const val BREAKER_THRESHOLD = 5

        /** Longer than the run's deadline, so the label cannot be the thing that finishes first. */
        const val SLOW_BATCH_MS = 30_000L

        /** Longer than the label's own ceiling, so the ceiling is what the test observes. */
        const val RUN_DEADLINE_MS = 10_000L

        /** A one-hit MusicBrainz artist search, enough for identity to resolve and the run to cache. */
        val SLEEP_TOKEN_SEARCH = """
            {"artists":[{"id":"a2fe0c0d-9c1d-4ec1-9e0e-1b3f5b1b3d47","name":"Sleep Token",
              "sort-name":"Sleep Token","score":100}]}
        """.trimIndent()
    }
}
