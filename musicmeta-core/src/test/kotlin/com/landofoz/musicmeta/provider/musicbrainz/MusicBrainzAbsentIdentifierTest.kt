package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.engine.ConfidenceCalculator
import com.landofoz.musicmeta.engine.DefaultEnrichmentEngine
import com.landofoz.musicmeta.engine.ProviderRegistry
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * An MBID MusicBrainz holds nothing under names no entity, so a request carrying one resolves by
 * name — the same rule for albums and artists that `MusicBrainzTrackMbidLookupTest` pins for tracks.
 *
 * The line these tests draw is [MusicBrainzLookup]'s: **absent** falls back, **unreadable** does not.
 * MusicBrainz answering for an entity and the body failing to parse still means it holds that
 * entity, and a search hit standing in for it would be a different album or artist than the caller
 * named. Only "no such entity" leaves nothing for an answer to be unfaithful to.
 */
class MusicBrainzAbsentIdentifierTest {

    private lateinit var httpClient: FakeHttpClient
    private lateinit var provider: MusicBrainzProvider

    @Before
    fun setUp() {
        httpClient = FakeHttpClient()
        provider = MusicBrainzProvider(httpClient, RateLimiter(0))
    }

    private fun engine() = DefaultEnrichmentEngine(
        ProviderRegistry(listOf(provider)),
        FakeEnrichmentCache(),
        EnrichmentConfig(),
        mergers = emptyList(),
    )

    private fun successOf(result: EnrichmentResult?): EnrichmentResult.Success {
        assertTrue("expected Success, got $result", result is EnrichmentResult.Success)
        return result as EnrichmentResult.Success
    }

    private fun releaseLookups() = httpClient.requestedUrls.count { it.contains("/release/") }

    private fun artistLookups() = httpClient.requestedUrls.count { it.contains("/artist/") }

    @Test
    fun `a release mbid MusicBrainz does not hold resolves by name, as a request carrying none does`() = runTest {
        // Given - a release pool that resolves happily, and an MBID whose lookup answers 404
        httpClient.givenJsonResponse(RELEASE_SEARCH, RELEASE_POOL)

        // When - the album is enriched with that MBID
        val result = provider.enrich(albumWithMbid(DEAD_MBID), EnrichmentType.LABEL)

        // Then - the name search answers, at its own confidence
        val success = successOf(result)
        assertEquals(FOUND_RELEASE, success.resolvedIdentifiers?.musicBrainzId)
        assertEquals(ConfidenceCalculator.searchScore(POOL_SCORE), success.confidence, TOLERANCE)
    }

    @Test
    fun `a release body MusicBrainz answers but the parser cannot read is not traded for a search hit`() = runTest {
        // Given - a release lookup the parser rejects, and a pool that would resolve happily
        httpClient.givenJsonResponse(DEAD_RELEASE_LOOKUP, UNPARSEABLE)
        httpClient.givenJsonResponse(RELEASE_SEARCH, RELEASE_POOL)

        // When - the album is enriched with that MBID
        val result = provider.enrich(albumWithMbid(DEAD_MBID), EnrichmentType.LABEL)

        // Then - MusicBrainz holds the release the caller named, so no other may stand in for it
        assertTrue("expected NotFound, got $result", result is EnrichmentResult.NotFound)
    }

    @Test
    fun `an absent release mbid is looked up once for the call, not once per type`() = runTest {
        // Given - the same dead release MBID and a pool that resolves
        httpClient.givenJsonResponse(RELEASE_SEARCH, RELEASE_POOL)

        // When - two album types are enriched in one call
        val results = engine().enrich(albumWithMbid(DEAD_MBID), setOf(EnrichmentType.LABEL, EnrichmentType.COUNTRY))

        // Then - both answer, and the absence was established once rather than re-asked per type
        assertTrue(results.raw[EnrichmentType.LABEL] is EnrichmentResult.Success)
        assertTrue(results.raw[EnrichmentType.COUNTRY] is EnrichmentResult.Success)
        assertEquals(1, releaseLookups())
    }

    @Test
    fun `album tracks for an absent release mbid come from the release the name search picks`() = runTest {
        // Given - a dead release MBID, and a searched release whose lookup carries a tracklist
        httpClient.givenJsonResponse(RELEASE_SEARCH, RELEASE_POOL)
        httpClient.givenJsonResponse(FOUND_RELEASE_LOOKUP, RELEASE_WITH_TRACKS)

        // When - the tracklist is enriched for that MBID
        val result = provider.enrich(albumWithMbid(DEAD_MBID), EnrichmentType.ALBUM_TRACKS)

        // Then - the tracklist is the searched release's, where the dead identifier answered nothing
        val tracks = successOf(result).data as EnrichmentData.Tracklist
        assertEquals(listOf("Airbag"), tracks.tracks.map { it.title })
    }

    @Test
    fun `an artist mbid MusicBrainz does not hold resolves by name, as a request carrying none does`() = runTest {
        // Given - an artist pool that resolves happily, and an MBID whose lookup answers 404
        httpClient.givenJsonResponse(ARTIST_SEARCH, ARTIST_POOL)

        // When - the artist is enriched with that MBID
        val result = provider.enrich(EnrichmentRequest.forArtist(ARTIST, mbid = DEAD_MBID), EnrichmentType.GENRE)

        // Then - the name search answers, at its own confidence
        val success = successOf(result)
        assertEquals(FOUND_ARTIST, success.resolvedIdentifiers?.musicBrainzId)
        assertEquals(ConfidenceCalculator.searchScore(POOL_SCORE), success.confidence, TOLERANCE)
    }

    @Test
    fun `an artist body MusicBrainz answers but the parser cannot read is not traded for a search hit`() = runTest {
        // Given - an artist lookup the parser rejects, and a pool that would resolve happily
        httpClient.givenJsonResponse(DEAD_ARTIST_LOOKUP, UNPARSEABLE)
        httpClient.givenJsonResponse(ARTIST_SEARCH, ARTIST_POOL)

        // When - the artist is enriched with that MBID
        val result = provider.enrich(EnrichmentRequest.forArtist(ARTIST, mbid = DEAD_MBID), EnrichmentType.GENRE)

        // Then - MusicBrainz holds the artist the caller named, so no other may stand in for it
        assertTrue("expected NotFound, got $result", result is EnrichmentResult.NotFound)
    }

    @Test
    fun `artist links for an absent mbid come from the artist the name search picks`() = runTest {
        // Given - a dead artist MBID, and a searched artist whose lookup carries url relations
        httpClient.givenJsonResponse(ARTIST_SEARCH, ARTIST_POOL)
        httpClient.givenJsonResponse(FOUND_ARTIST_LOOKUP, ARTIST_WITH_LINKS)

        // When - the artist's links are enriched for that MBID
        val result = provider.enrich(
            EnrichmentRequest.forArtist(ARTIST, mbid = DEAD_MBID),
            EnrichmentType.ARTIST_LINKS,
        )

        // Then - the searched artist's links answer, where the dead identifier answered nothing.
        // Two lookups: the one that established the absence, and the one that replaced it.
        assertTrue("expected Success, got $result", result is EnrichmentResult.Success)
        assertEquals(2, artistLookups())
    }

    @Test
    fun `a name MusicBrainz knows no artist for is a miss for a lookup-only type, not a failure`() = runTest {
        // Given - a request naming no MBID, and a search MusicBrainz answers with no artists at all
        httpClient.givenJsonResponse(ARTIST_SEARCH, NO_ARTISTS)

        // When - a type that can only be resolved from an artist id is enriched
        val result = provider.enrich(EnrichmentRequest.forArtist(ARTIST), EnrichmentType.ARTIST_LINKS)

        // Then - the empty pool is an answer, not an error. Ranking it used to take the first of no
        // candidates, and the throw reached the consumer as a provider failure they would retry.
        assertTrue("expected NotFound, got $result", result is EnrichmentResult.NotFound)
    }

    private companion object {
        const val ALBUM = "OK Computer"
        const val ARTIST = "Radiohead"
        const val DEAD_MBID = "00000000-0000-4000-8000-000000000000"
        const val FOUND_RELEASE = "rel-found"
        const val FOUND_ARTIST = "art-found"
        const val POOL_SCORE = 98
        const val RELEASE_SEARCH = "release?query"
        const val ARTIST_SEARCH = "artist?query"
        const val DEAD_RELEASE_LOOKUP = "/release/00000000"
        const val DEAD_ARTIST_LOOKUP = "/artist/00000000"
        const val FOUND_RELEASE_LOOKUP = "/release/rel-found"
        const val FOUND_ARTIST_LOOKUP = "/artist/art-found"
        const val TOLERANCE = 0.0001f

        fun albumWithMbid(mbid: String) = EnrichmentRequest.forAlbum(ALBUM, ARTIST, mbid = mbid)

        /** A 200 both lookup parsers reject: each requires an `id`, and this body carries none. */
        const val UNPARSEABLE = """{"title": "OK Computer"}"""

        /** What MusicBrainz answers a search for a name it holds no artist under with. */
        const val NO_ARTISTS = """{"count": 0, "offset": 0, "artists": []}"""

        val RELEASE_POOL = """
            {
              "releases": [{
                "id": "rel-found",
                "score": 98,
                "title": "OK Computer",
                "artist-credit": [{"artist": {"id": "art-found", "name": "Radiohead"}}],
                "date": "1997-06-16",
                "country": "GB",
                "label-info": [{"label": {"name": "Parlophone"}}],
                "release-group": {"id": "rg-found", "primary-type": "Album"}
              }]
            }
        """.trimIndent()

        val RELEASE_WITH_TRACKS = """
            {
              "id": "rel-found",
              "title": "OK Computer",
              "artist-credit": [{"artist": {"id": "art-found", "name": "Radiohead"}}],
              "media": [{"tracks": [{"title": "Airbag", "position": 1, "length": 284000}]}]
            }
        """.trimIndent()

        val ARTIST_POOL = """
            {
              "artists": [{
                "id": "art-found",
                "score": 98,
                "name": "Radiohead",
                "country": "GB",
                "tags": [{"name": "alternative rock", "count": 5}]
              }]
            }
        """.trimIndent()

        val ARTIST_WITH_LINKS = """
            {
              "id": "art-found",
              "name": "Radiohead",
              "relations": [
                {
                  "type": "official homepage",
                  "target-type": "url",
                  "url": {"resource": "https://radiohead.com"}
                }
              ]
            }
        """.trimIndent()
    }
}
