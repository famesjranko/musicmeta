package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.LookupProvenance
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A MusicBrainz search hit reports [LookupProvenance.EXACT_NAME] only when the title it came back
 * with is the title that was asked for. MusicBrainz scores a truncated title as a full phrase
 * match — `release:"Hail to the"` returns "Hail to the Thief" at score 100 — so the score floor
 * accepts a hit no one compared to the request, and only a title comparison can tell the two apart.
 */
class MusicBrainzSearchTitleProvenanceTest {

    private lateinit var httpClient: FakeHttpClient
    private lateinit var provider: MusicBrainzProvider

    @Before
    fun setUp() {
        httpClient = FakeHttpClient()
        provider = MusicBrainzProvider(httpClient, RateLimiter(0))
    }

    private suspend fun resolveAlbum(title: String, type: EnrichmentType): EnrichmentResult.Success {
        val result = provider.enrich(EnrichmentRequest.forAlbum(title, ARTIST_ALBUM), type)
        assertTrue("expected Success, got $result", result is EnrichmentResult.Success)
        return result as EnrichmentResult.Success
    }

    private suspend fun resolveTrack(title: String): EnrichmentResult.Success {
        val result = provider.enrich(
            EnrichmentRequest.forTrack(title, ARTIST_TRACK),
            EnrichmentType.TRACK_METADATA,
        )
        assertTrue("expected Success, got $result", result is EnrichmentResult.Success)
        return result as EnrichmentResult.Success
    }

    @Test
    fun `an album hit whose title is not the requested one reports FUZZY_NAME`() = runTest {
        // Given - a request for "Hail to the", which MusicBrainz answers with the full album at 100
        httpClient.givenJsonResponse("release?query", RELEASE_POOL)

        // When - resolving an album type off that search
        val success = resolveAlbum("Hail to the", EnrichmentType.GENRE)

        // Then - the accepted release is a different album from the one named, so the route is fuzzy
        assertEquals(RELEASE_MBID, success.resolvedIdentifiers?.musicBrainzId)
        assertEquals(LookupProvenance.FUZZY_NAME, success.provenance)
    }

    @Test
    fun `an album hit whose title is the requested one reports EXACT_NAME`() = runTest {
        // Given - the same pool, asked for under the album's own title
        httpClient.givenJsonResponse("release?query", RELEASE_POOL)

        // When - resolving an album type off that search
        val success = resolveAlbum("Hail to the Thief", EnrichmentType.GENRE)

        // Then - the title that came back is the title that was asked for
        assertEquals(RELEASE_MBID, success.resolvedIdentifiers?.musicBrainzId)
        assertEquals(LookupProvenance.EXACT_NAME, success.provenance)
    }

    @Test
    fun `an album tracklist from a loosely matched search reports FUZZY_NAME`() = runTest {
        // Given - the truncated title resolves the same release, whose tracklist is then looked up
        httpClient.givenJsonResponse("release?query", RELEASE_POOL)
        httpClient.givenJsonResponse("release/$RELEASE_MBID", RELEASE_LOOKUP)

        // When - resolving the tracklist, which reaches its release by the same search
        val success = resolveAlbum("Hail to the", EnrichmentType.ALBUM_TRACKS)

        // Then - the tracklist route is no more verified than the search that found it
        assertEquals(LookupProvenance.FUZZY_NAME, success.provenance)
    }

    @Test
    fun `a track hit whose title is not the requested one reports FUZZY_NAME`() = runTest {
        // Given - a request for "Enter", which MusicBrainz answers with "Enter Sandman" at 100
        httpClient.givenJsonResponse("recording?query", RECORDING_POOL)

        // When - resolving track metadata off that search
        val success = resolveTrack("Enter")

        // Then - the accepted recording is a different song from the one named
        assertEquals(RECORDING_MBID, success.resolvedIdentifiers?.musicBrainzId)
        assertEquals(LookupProvenance.FUZZY_NAME, success.provenance)
    }

    @Test
    fun `a track hit whose title is the requested one reports EXACT_NAME`() = runTest {
        // Given - the same pool, asked for under the recording's own title
        httpClient.givenJsonResponse("recording?query", RECORDING_POOL)

        // When - resolving track metadata off that search
        val success = resolveTrack("Enter Sandman")

        // Then - the title that came back is the title that was asked for
        assertEquals(RECORDING_MBID, success.resolvedIdentifiers?.musicBrainzId)
        assertEquals(LookupProvenance.EXACT_NAME, success.provenance)
    }

    private companion object {
        const val ARTIST_ALBUM = "Radiohead"
        const val ARTIST_TRACK = "Metallica"
        const val RELEASE_MBID = "release-hail-to-the-thief"
        const val RECORDING_MBID = "recording-enter-sandman"

        val RELEASE_POOL = """
            {"releases": [{
              "id": "$RELEASE_MBID",
              "score": 100,
              "title": "Hail to the Thief",
              "date": "2003-06-09",
              "country": "GB",
              "artist-credit": [{"artist": {"id": "art-radiohead", "name": "Radiohead"}}],
              "release-group": {"id": "rg-hail-to-the-thief", "primary-type": "Album",
                "tags": [{"name": "alternative rock", "count": 5}]}
            }]}
        """.trimIndent()

        val RELEASE_LOOKUP = """
            {"id": "$RELEASE_MBID", "title": "Hail to the Thief",
             "media": [{"tracks": [{"title": "2 + 2 = 5", "position": 1}]}]}
        """.trimIndent()

        val RECORDING_POOL = """
            {"recordings": [{
              "id": "$RECORDING_MBID",
              "score": 100,
              "title": "Enter Sandman",
              "length": 331560,
              "artist-credit": [{"artist": {"id": "art-metallica", "name": "Metallica"}}],
              "releases": [{"id": "rel-metallica", "status": "Official",
                "release-group": {"id": "rg-metallica", "title": "Metallica", "primary-type": "Album"}}]
            }]}
        """.trimIndent()
    }
}
