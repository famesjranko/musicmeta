package com.landofoz.musicmeta.e2e

import com.landofoz.musicmeta.ApiKeyConfig
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Whether `/1/explore/lb-radio` still answers with a payload [EnrichmentType.ARTIST_RADIO_DISCOVERY]
 * can be built from, asked of the live API through the engine rather than of the route directly.
 *
 * A status code says the route is reachable; it says nothing about whether the JSPF envelope the
 * parser walks — `payload.jspf.playlist.track`, and each track's `extension` block — is still the
 * shape ListenBrainz returns. Only a call that goes through `ListenBrainzProvider` and its mapper
 * distinguishes "the route answered" from "the type has data", and an empty playlist reads as
 * `NotFound`, so a silent shape change cannot pass as success here.
 *
 * It is not coverage for the parsing: `ListenBrainzRadioDiscoveryTest` pins that against a fixture
 * and gates a merge. Under `-Dinclude.e2e=true` and a token only, so neither an outage nor a keyless
 * machine fails a build — the type's behaviour during an outage is
 * [OutageReadsAsOutageE2ETest]'s subject instead.
 *
 * Run manually: ./gradlew :musicmeta-core:test -Dinclude.e2e=true --tests "*RadioDiscoveryLive*"
 */
class RadioDiscoveryLiveE2ETest {

    @Test
    fun `LB Radio answers a seed artist with a playable playlist`() = runBlocking {
        // Given - e2e enabled, a token, and an artist ListenBrainz has ample listen data for
        assumeTrue(E2ETestFixture.prop("include.e2e") == "true")
        val token = E2ETestFixture.prop("listenbrainz.token")
        assumeTrue("needs listenbrainz.token", token.isNotBlank())
        val engine = EnrichmentEngine.Builder()
            .config(EnrichmentConfig(enableIdentityResolution = false))
            .apiKeys(ApiKeyConfig(listenBrainzToken = token))
            .withDefaultProviders()
            .build()

        // When - asking the engine for the type, which only ListenBrainz can serve
        val type = EnrichmentType.ARTIST_RADIO_DISCOVERY
        val result = engine.enrich(EnrichmentRequest.forArtist("Radiohead"), setOf(type)).raw[type]

        // Then - a Success carrying tracks the parser built from today's payload, not an empty one
        assertTrue("expected Success for LB Radio, got $result", result is EnrichmentResult.Success)
        val tracks = ((result as EnrichmentResult.Success).data as EnrichmentData.RadioPlaylist).tracks
        assertTrue("a Success with no tracks would have been NotFound", tracks.isNotEmpty())
        tracks.forEach { track ->
            assertTrue("a track came back with a blank title", track.title.isNotBlank())
            assertTrue("${track.title} came back with a blank artist", track.artist.isNotBlank())
        }
        val withMbid = tracks.count { it.identifiers.musicBrainzId != null }
        assertTrue(
            "no track carried a recording MBID, so the JSPF identifier shape has moved",
            withMbid > 0,
        )
        println("LB Radio: ${tracks.size} tracks, $withMbid with a recording MBID, first = ${tracks.first()}")
    }
}
