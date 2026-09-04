package com.landofoz.musicmeta.e2e

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.provider.listenbrainz.ListenBrainzProvider
import com.landofoz.musicmeta.testutil.assertNotDrift
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Whether the Labs `similar-artists` route still accepts the pinned `algorithm` and answers with
 * rows the mapper can build `SIMILAR_ARTISTS` from.
 *
 * The provider is called directly rather than through the engine: `SIMILAR_ARTISTS` is a merged
 * type, and Deezer answers it without a key, so an engine result cannot say which provider spoke.
 *
 * A retired algorithm arrives here as an `Error` that is neither network, rate limit nor timeout,
 * which is the one thing `assertNotDrift` refuses to treat as a shed — a member leaving the enum
 * fails this test rather than passing as an empty answer.
 *
 * It is not coverage for the parsing or the classification: `ListenBrainzSimilarArtistsTest` pins
 * both against captured bodies and gates a merge. This runs only under `-Dinclude.e2e=true`, so
 * neither an outage nor an offline machine fails a build.
 *
 * Run manually: ./gradlew :musicmeta-core:test -Dinclude.e2e=true --tests "*LabsSimilarArtistsLive*"
 */
class LabsSimilarArtistsLiveE2ETest {

    @Test
    fun `Labs answers a seeded artist MBID with scored neighbours`() = runBlocking {
        // Given - e2e enabled and an artist ListenBrainz has ample session data for
        assumeTrue(E2ETestFixture.prop("include.e2e") == "true")
        val provider = ListenBrainzProvider(E2ETestFixture.httpClient, E2ETestFixture.listenBrainzRateLimiter)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "a74b1b7f-71a5-4011-9441-d0b5e4122711"),
            name = "Radiohead",
        )

        // When - asking the provider for similar artists, which only the Labs route can serve
        val result = provider.enrich(request, EnrichmentType.SIMILAR_ARTISTS)

        // Then - neighbours the mapper built from today's rows, each named and MBID-carrying
        val success = assertNotDrift("Labs similar artists for Radiohead", result) ?: return@runBlocking
        val artists = (success.data as EnrichmentData.SimilarArtists).artists
        assertTrue("a Success with no artists would have been NotFound", artists.isNotEmpty())
        artists.forEach { artist ->
            assertTrue("an artist came back with a blank name", artist.name.isNotBlank())
            assertTrue("${artist.name} carried no MBID, so the row shape has moved", artist.identifiers.musicBrainzId != null)
        }
        assertTrue("the nearest neighbour should score 0.5, the top row at half scale", artists.first().matchScore == 0.5f)
        println("Labs similar artists: ${artists.size} artists, first = ${artists.first()}")
    }
}
