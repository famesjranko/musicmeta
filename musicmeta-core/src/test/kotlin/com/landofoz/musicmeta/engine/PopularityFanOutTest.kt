package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.PopularitySignal
import com.landofoz.musicmeta.PopularitySignalKind
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That [EnrichmentEngine.Builder] registers a [PopularityMerger] for both popularity types.
 *
 * The merger existing is not the same as the engine using it: an unregistered type falls back to
 * `ProviderChain.resolve`, which returns the first `Success` and discards the rest. The demos'
 * "Listener count unavailable" was exactly that — the winning provider lacking a field the losing
 * one had filled. These build through the real `Builder` and pass it no merger of their own, so
 * dropping either registration from its default list fails them.
 */
class PopularityFanOutTest {

    private val artist = EnrichmentRequest.forArtist("Radiohead")
    private val track = EnrichmentRequest.forTrack("Karma Police", "Radiohead")

    private fun provider(
        id: String,
        type: EnrichmentType,
        priority: Int,
        data: EnrichmentData.Popularity,
    ) = FakeProvider(
        id = id,
        capabilities = listOf(ProviderCapability(type, priority = priority)),
    ).also {
        it.givenResult(type, EnrichmentResult.Success(type, data, id, 0.9f))
    }

    /** Deliberately no `addMerger`: the merger under test must come from the Builder's defaults. */
    private suspend fun popularityFrom(
        type: EnrichmentType,
        request: EnrichmentRequest,
        vararg providers: FakeProvider,
    ): EnrichmentData.Popularity {
        val builder = EnrichmentEngine.Builder()
            .config(EnrichmentConfig(enableIdentityResolution = false))
        providers.forEach { builder.addProvider(it) }
        val result = builder.build().enrich(request, setOf(type)).raw[type]
        assertTrue("expected Success, got $result", result is EnrichmentResult.Success)
        return (result as EnrichmentResult.Success).data as EnrichmentData.Popularity
    }

    @Test
    fun `the Builder's defaults merge ARTIST_POPULARITY, keeping the losing provider's listener count`() = runTest {
        // Given - a leading source with plays only, and a lower-priority one carrying listeners
        val leader = provider(
            "listenbrainz", EnrichmentType.ARTIST_POPULARITY, 100,
            EnrichmentData.Popularity(listenCount = 900),
        )
        val follower = provider(
            "lastfm", EnrichmentType.ARTIST_POPULARITY, 50,
            EnrichmentData.Popularity(listenerCount = 42),
        )

        // When - the engine enriches the type both providers claim
        val popularity = popularityFrom(EnrichmentType.ARTIST_POPULARITY, artist, leader, follower)

        // Then - both fields reach the consumer, where first-wins reported no listener count
        assertEquals(900L, popularity.listenCount)
        assertEquals(42L, popularity.listenerCount)
    }

    @Test
    fun `TRACK_POPULARITY is merged too, not only the artist type`() = runTest {
        // Given - the same split across the track type, one source rating and one counting
        val counter = provider(
            "listenbrainz", EnrichmentType.TRACK_POPULARITY, 100,
            EnrichmentData.Popularity(listenCount = 12),
        )
        val rater = provider(
            "musicbrainz", EnrichmentType.TRACK_POPULARITY, 20,
            EnrichmentData.Popularity(
                signals = listOf(
                    PopularitySignal("musicbrainz", PopularitySignalKind.RATING, 4.4, 0.85f, 41),
                ),
            ),
        )

        // When - the engine enriches the track type
        val popularity = popularityFrom(EnrichmentType.TRACK_POPULARITY, track, counter, rater)

        // Then - the count stands and the rating arrives beside it as its own signal
        assertEquals(12L, popularity.listenCount)
        assertEquals(PopularitySignalKind.RATING, popularity.signals.single().kind)
    }
}
