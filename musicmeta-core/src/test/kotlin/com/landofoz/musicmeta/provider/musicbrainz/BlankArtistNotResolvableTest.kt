package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testkit.UpstreamPools
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An album or track request carrying no artist cannot identify an entity by name, and MusicBrainz
 * widens rather than refuses such a search — so the guard is what stops a candidate out of
 * thousands being reported as the caller's album.
 */
class BlankArtistNotResolvableTest {

    @Test
    fun `an album with no artist offers its candidates instead of resolving one`() = runTest {
        // Given - a title shared by thousands of releases and no artist to narrow it
        val http = UpstreamPools.load("blank-artist-album")
        val provider = MusicBrainzProvider(http, RateLimiter(0))

        // When - enriching the album
        val result = provider.enrich(EnrichmentRequest.forAlbum("Greatest Hits", ""), EnrichmentType.GENRE)

        // Then - nothing is resolved, and the pool comes back as candidates to choose between
        val notFound = result as EnrichmentResult.NotFound
        val suggestions = notFound.suggestions.orEmpty()
        assertTrue("expected candidates to choose between, got none", suggestions.isNotEmpty())
        assertTrue(
            "expected candidates by different artists, got ${suggestions.map { it.artist }}",
            suggestions.map { it.artist }.distinct().size > 1,
        )
    }

    @Test
    fun `an album with no artist searches once and does not retry the blank`() = runTest {
        // Given - the same unidentifiable album request
        val http = UpstreamPools.load("blank-artist-album")
        val provider = MusicBrainzProvider(http, RateLimiter(0))

        // When - enriching the album
        provider.enrich(EnrichmentRequest.forAlbum("Greatest Hits", ""), EnrichmentType.GENRE)

        // Then - the qualifier and symbol fallbacks are not spent re-searching the same blank artist
        assertEquals("requested: ${http.requestedUrls}", 1, http.requestedUrls.size)
    }

    @Test
    fun `a track with no artist asks MusicBrainz nothing at all`() = runTest {
        // Given - a recording title with no artist, and every upstream route stubbed
        val http = UpstreamPools.load("blank-artist-album")
        val provider = MusicBrainzProvider(http, RateLimiter(0))

        // When - enriching the track
        val result = provider.enrich(
            EnrichmentRequest.forTrack("Yesterday", ""),
            EnrichmentType.TRACK_METADATA,
        )

        // Then - no request is spent: a recording title alone rarely holds the answer in its pool
        assertTrue(result is EnrichmentResult.NotFound)
        assertEquals("requested: ${http.requestedUrls}", emptyList<String>(), http.requestedUrls)
    }

    @Test
    fun `a track whose artist is only whitespace asks MusicBrainz nothing at all`() = runTest {
        // Given - a recording title whose artist is whitespace, and every upstream route stubbed
        val http = UpstreamPools.load("blank-artist-album")
        val provider = MusicBrainzProvider(http, RateLimiter(0))

        // When - enriching the track
        val result = provider.enrich(
            EnrichmentRequest.forTrack("Yesterday", "   "),
            EnrichmentType.TRACK_METADATA,
        )

        // Then - whitespace is as blank as empty: no request is spent
        assertTrue(result is EnrichmentResult.NotFound)
        assertEquals("requested: ${http.requestedUrls}", emptyList<String>(), http.requestedUrls)
    }

    @Test
    fun `an album naming its artist still resolves`() = runTest {
        // Given - the same pool, reached by a request that does name an artist
        val http = UpstreamPools.load("blank-artist-album")
        val provider = MusicBrainzProvider(http, RateLimiter(0))

        // When - enriching an album whose artist is one the pool credits
        val result = provider.enrich(
            EnrichmentRequest.forAlbum("Greatest Hits", "The Offspring"),
            EnrichmentType.GENRE,
        )

        // Then - the guard is inert: this is the ordinary search path, not a refusal
        assertTrue(
            "a named artist must still reach the ranking, got $result",
            result !is EnrichmentResult.NotFound || result.suggestions == null,
        )
    }
}
