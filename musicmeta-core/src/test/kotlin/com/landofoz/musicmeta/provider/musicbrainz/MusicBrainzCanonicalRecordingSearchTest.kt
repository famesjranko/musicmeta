package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A name-only track request resolves out of a pool MusicBrainz has already filtered.
 *
 * `pickBestRecording`'s blank-disambiguation tier can only choose among candidates it is given, and
 * for a heavily-covered track the top of the unfiltered pool is entirely live, demo and bootleg
 * takes — measured 2026-08-10, zero of the 25 "Enter Sandman" recordings the shipped query returns
 * carry a blank disambiguation. The tier is dead on exactly the requests that need it. `-comment:*`
 * is that same tier expressed in MusicBrainz's own query language, which moves it upstream of the
 * limit rather than downstream of it.
 */
class MusicBrainzCanonicalRecordingSearchTest {

    private lateinit var httpClient: FakeHttpClient
    private lateinit var provider: MusicBrainzProvider

    @Before
    fun setUp() {
        httpClient = FakeHttpClient()
        provider = MusicBrainzProvider(httpClient, RateLimiter(0))
    }

    /** Stubbed before the unfiltered key, so the more specific URL wins the fake's first match. */
    private fun givenCanonicalPool(json: String) = httpClient.givenJsonResponse(CANONICAL_QUERY, json)

    private fun urlsMatching(fragment: String) = httpClient.requestedUrls.filter { it.contains(fragment) }

    private fun resolvedIdOf(result: EnrichmentResult): String? {
        assertTrue("expected Success, got $result", result is EnrichmentResult.Success)
        return (result as EnrichmentResult.Success).resolvedIdentifiers?.musicBrainzId
    }

    @Test
    fun `a pool with no blank-disambiguation candidate resolves to the studio cut, not a live take`() = runTest {
        // Given - the unfiltered pool holds only marked takes, and the studio cut is deeper than it reaches
        givenCanonicalPool(CANONICAL_POOL)
        httpClient.givenJsonResponse(RECORDING_SEARCH, ALL_VARIANTS_POOL)

        // When - the track is enriched by name alone
        val result = provider.enrich(EnrichmentRequest.forTrack(TITLE, ARTIST), EnrichmentType.TRACK_METADATA)

        // Then - the answer is right in kind — unmarked, on an Official Album release-group — which
        // no ranking of the unfiltered pool could have reached. Asserted by kind and not by id
        // alone: MusicBrainz holds several such recordings and may reorder them between calls, so
        // pinning one mastering would pin something upstream is free to move.
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.TrackMetadata
        assertNull("expected an unmarked recording, got ${data.disambiguation}", data.disambiguation)
        assertEquals("Metallica", data.albumTitle)
        assertEquals(STUDIO_MBID, result.resolvedIdentifiers?.musicBrainzId)
    }

    @Test
    fun `the resolution search asks MusicBrainz for the filter, at its maximum page`() = runTest {
        // Given - a filtered pool that resolves, so no fallback is reached
        givenCanonicalPool(CANONICAL_POOL)
        httpClient.givenJsonResponse(RECORDING_SEARCH, ALL_VARIANTS_POOL)

        // When - the track is enriched by name alone
        provider.enrich(EnrichmentRequest.forTrack(TITLE, ARTIST), EnrichmentType.GENRE)

        // Then - one request, carrying the filter and MusicBrainz's own maximum limit. Above 100 it
        // does not clamp — it silently serves 25 (measured 2026-08-10), so 100 is a ceiling, not a
        // number to raise later.
        val searches = urlsMatching(RECORDING_SEARCH)
        assertEquals(1, searches.size)
        assertTrue("expected the -comment:* filter in $searches", searches.single().contains(CANONICAL_QUERY))
        assertTrue("expected limit=100 in $searches", searches.single().contains("limit=100"))
    }

    @Test
    fun `a track whose every recording is marked falls back to the unfiltered pool`() = runTest {
        // Given - a filter that empties the pool, which is what a genuinely variant-only track looks like
        givenCanonicalPool(EMPTY_POOL)
        httpClient.givenJsonResponse(RECORDING_SEARCH, ALL_VARIANTS_POOL)

        // When - the track is enriched by name alone
        val result = provider.enrich(EnrichmentRequest.forTrack(TITLE, ARTIST), EnrichmentType.TRACK_METADATA)

        // Then - the unfiltered ladder answered rather than the request reporting nothing
        assertEquals(LIVE_MBID, resolvedIdOf(result))
        assertEquals(2, urlsMatching(RECORDING_SEARCH).size)
    }

    @Test
    fun `searchCandidates still offers the marked takes, because picking one is the point`() = runTest {
        // Given - the same two pools, and a consumer asking for candidates rather than an answer
        givenCanonicalPool(CANONICAL_POOL)
        httpClient.givenJsonResponse(RECORDING_SEARCH, ALL_VARIANTS_POOL)

        // When - candidates are searched for the same track
        val candidates = provider.searchCandidates(EnrichmentRequest.forTrack(TITLE, ARTIST), 10)

        // Then - the live takes are still offered: a "did you mean?" list narrowed to canonical
        // recordings cannot answer "I want the Moscow one"
        assertEquals(listOf(LIVE_MBID, "rec-demo"), candidates.map { it.identifiers.musicBrainzId })
        assertTrue(urlsMatching(CANONICAL_QUERY).isEmpty())
    }

    @Test
    fun `an album hint keeps the filter out of the way, so a marked album take can still win`() = runTest {
        // Given - a request naming the album, whose only album-matching take carries a disambiguation
        givenCanonicalPool(CANONICAL_POOL)
        httpClient.givenJsonResponse(RECORDING_SEARCH, ALL_VARIANTS_POOL)
        val request = EnrichmentRequest.forTrack(TITLE, ARTIST, album = "Metallica")

        // When - the track's metadata is enriched
        val result = provider.enrich(request, EnrichmentType.TRACK_METADATA)

        // Then - the album-match tier reaches it, which it could not have done from a filtered pool:
        // the filter deletes marked candidates, and an explicit album outranks the mark either way
        assertEquals("rec-demo", resolvedIdOf(result))
        assertEquals("Metallica", ((result as EnrichmentResult.Success).data as EnrichmentData.TrackMetadata).albumTitle)
        assertTrue("expected no filter on a hinted request", urlsMatching(CANONICAL_QUERY).isEmpty())
    }

    private companion object {
        const val TITLE = "Enter Sandman"
        const val ARTIST = "Metallica"
        const val STUDIO_MBID = "rec-studio"
        const val LIVE_MBID = "rec-live-1"
        const val RECORDING_SEARCH = "recording?query"

        /** `-comment:*` as `URLEncoder` writes it — `:` escapes, `*` and `-` do not. */
        const val CANONICAL_QUERY = "-comment%3A*"

        const val EMPTY_POOL = """{"count": 0, "recordings": []}"""

        /**
         * What the shipped query returns for a heavily-covered track: every candidate marked, so
         * `pickBestRecording`'s blank-disambiguation tier has nothing to choose and falls through.
         */
        val ALL_VARIANTS_POOL = """
            {
              "count": 757,
              "recordings": [
                {
                  "id": "rec-live-1", "score": 100, "title": "Enter Sandman",
                  "disambiguation": "live, 1992-01-11: Arco Arena, Sacramento, CA, US",
                  "releases": [{
                    "status": "Official",
                    "release-group": {"id": "rg-live", "title": "Live Shit", "primary-type": "Album"}
                  }]
                },
                {
                  "id": "rec-demo", "score": 100, "title": "Enter Sandman",
                  "disambiguation": "demo: 1990-08-13",
                  "releases": [{
                    "status": "Official",
                    "release-group": {"id": "rg-studio", "title": "Metallica", "primary-type": "Album"}
                  }]
                }
              ]
            }
        """.trimIndent()

        /** The same search with `-comment:*`: 757 ties become a pool the ranking can work on. */
        val CANONICAL_POOL = """
            {
              "count": 95,
              "recordings": [
                {
                  "id": "rec-sampler", "score": 100, "title": "Enter Sandman",
                  "releases": [{
                    "status": "Official",
                    "release-group": {"id": "rg-sampler", "title": "Metal Sampler", "primary-type": "Other"}
                  }]
                },
                {
                  "id": "rec-studio", "score": 100, "title": "Enter Sandman", "length": 331560,
                  "tags": [{"name": "heavy metal", "count": 7}],
                  "releases": [{
                    "status": "Official",
                    "release-group": {"id": "rg-studio", "title": "Metallica", "primary-type": "Album"}
                  }]
                }
              ]
            }
        """.trimIndent()
    }
}
