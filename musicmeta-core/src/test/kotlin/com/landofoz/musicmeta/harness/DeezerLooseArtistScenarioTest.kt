package com.landofoz.musicmeta.harness

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.testkit.EntityIdentity
import com.landofoz.musicmeta.testkit.TestStack
import com.landofoz.musicmeta.testkit.UpstreamPools
import com.landofoz.musicmeta.testkit.assertNoUrlRequestedTwice
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composed stack over the `deezer-loose-artist` pool: the only right-artist hit for `Song` by
 * `David Bowie` is a wholly unrelated recording that merely shares the artist and a remaster
 * qualifier. The #210 defect this reproduces is Deezer accepting on artist match alone; the fix
 * added a title-equivalence filter (`DeezerApi.kt:173`), so `TRACK_METADATA` must decline rather
 * than answer about the wrong song. MusicBrainz (unstubbed here) and LRCLIB (also unstubbed) both
 * decline the same request ahead of and behind Deezer in priority order, so only Deezer's own
 * acceptance decides this pool's outcome.
 */
class DeezerLooseArtistScenarioTest {

    @Test
    fun `Deezer declines the loose-artist wrong-title hit, rather than answering about it`() = runTest {
        // Given - the real stack, offline, over a pool whose only Deezer hit shares the artist but
        // names an unrelated recording
        val http = UpstreamPools.load(SCENARIO)
        val engine = TestStack.build(http)
        val request = EnrichmentRequest.forTrack(TITLE, ARTIST)

        // When - enriching for the type Deezer, MusicBrainz and LRCLIB all declare
        val results = engine.enrich(request, setOf(EnrichmentType.TRACK_METADATA))

        // Then - NotFound, not a confident answer about the wrong song
        val result = results.raw[EnrichmentType.TRACK_METADATA]
        assertTrue("$result should decline a loose-artist wrong-title hit", result !is EnrichmentResult.Success)
    }

    @Test
    fun `the identity rule holds over the composed stack`() = runTest {
        // Given - the same scenario and stack
        val http = UpstreamPools.load(SCENARIO)
        val engine = TestStack.build(http)
        val request = EnrichmentRequest.forTrack(TITLE, ARTIST)

        // When - enriching for TRACK_METADATA, the type Deezer, MusicBrainz and LRCLIB all declare
        val results = engine.enrich(request, setOf(EnrichmentType.TRACK_METADATA))

        // Then - every result is filed under the type it actually answers about
        EntityIdentity.assertAnswersTheRequestWithoutCanonicalIdentity(request, results)
    }

    @Test
    fun `Deezer's own search URL was requested, proving it was asked and not short-circuited`() = runTest {
        // Given - the same scenario and stack
        val http = UpstreamPools.load(SCENARIO)
        val engine = TestStack.build(http)
        val request = EnrichmentRequest.forTrack(TITLE, ARTIST)

        // When - enriching for TRACK_METADATA, the type Deezer, MusicBrainz and LRCLIB all declare
        engine.enrich(request, setOf(EnrichmentType.TRACK_METADATA))

        // Then - Deezer's track-search endpoint was actually hit
        assertTrue(
            "expected Deezer's search/track endpoint to have been requested, urls=${http.requestedUrls}",
            http.requestedUrls.any { it.contains("search/track") },
        )
    }

    @Test
    fun `no upstream URL is requested twice in one enrich`() = runTest {
        // Given - the same scenario and stack
        val http = UpstreamPools.load(SCENARIO)
        val engine = TestStack.build(http)
        val request = EnrichmentRequest.forTrack(TITLE, ARTIST)

        // When - enriching for TRACK_METADATA, the type Deezer, MusicBrainz and LRCLIB all declare
        engine.enrich(request, setOf(EnrichmentType.TRACK_METADATA))

        // Then - every upstream URL was fetched at most once
        http.assertNoUrlRequestedTwice()
    }

    private companion object {
        const val SCENARIO = "deezer-loose-artist"
        const val TITLE = "Song"
        const val ARTIST = "David Bowie"
    }
}
