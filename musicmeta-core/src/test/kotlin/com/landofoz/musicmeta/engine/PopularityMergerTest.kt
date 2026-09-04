package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.PopularitySignal
import com.landofoz.musicmeta.PopularitySignalKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * First-wins discarded complementary fields, not duplicate numbers: Last.fm carries listeners and
 * plays, ListenBrainz carries listen and user counts, MusicBrainz carries a rating and no counts.
 * Whichever answered first left the rest null, which is what the demos rendered as
 * "Listener count unavailable".
 */
class PopularityMergerTest {

    private val merger = PopularityMerger(EnrichmentType.ARTIST_POPULARITY)

    private fun success(provider: String, data: EnrichmentData.Popularity, confidence: Float = 0.9f) =
        EnrichmentResult.Success(EnrichmentType.ARTIST_POPULARITY, data, provider, confidence)

    private fun merged(vararg results: EnrichmentResult.Success): EnrichmentData.Popularity =
        (merger.merge(results.toList()) as EnrichmentResult.Success).data as EnrichmentData.Popularity

    @Test
    fun `a field the winning provider lacks is filled from the losing one`() {
        // Given - a leading source with plays but no listeners, and a second carrying listeners
        val winner = success("listenbrainz", EnrichmentData.Popularity(listenCount = 900))
        val loser = success("lastfm", EnrichmentData.Popularity(listenerCount = 42))

        // When - the chain's results are merged rather than resolved first-wins
        val result = merged(winner, loser)

        // Then - both fields are present, where first-wins would have left listenerCount null
        assertEquals(900L, result.listenCount)
        assertEquals(42L, result.listenerCount)
    }

    @Test
    fun `the winning provider's value is kept where both sources have one`() {
        // Given - two sources that both report a listen count, in chain priority order
        val winner = success("listenbrainz", EnrichmentData.Popularity(listenCount = 900))
        val loser = success("lastfm", EnrichmentData.Popularity(listenCount = 5))

        // When - they are merged
        val result = merged(winner, loser)

        // Then - the first source's number stands; the two units are not summed or averaged
        assertEquals(900L, result.listenCount)
    }

    @Test
    fun `every source's signal survives, unblended`() {
        // Given - three sources measuring three different things
        val lb = success(
            "listenbrainz",
            EnrichmentData.Popularity(
                listenCount = 900,
                signals = listOf(
                    PopularitySignal("listenbrainz", PopularitySignalKind.LISTEN_COUNT, 900.0),
                ),
            ),
        )
        val lastfm = success(
            "lastfm",
            EnrichmentData.Popularity(
                listenerCount = 42,
                signals = listOf(
                    PopularitySignal("lastfm", PopularitySignalKind.LISTENER_COUNT, 42.0),
                ),
            ),
        )
        val mb = success(
            "musicbrainz",
            EnrichmentData.Popularity(
                signals = listOf(
                    PopularitySignal(
                        "musicbrainz", PopularitySignalKind.RATING, 4.0,
                        normalized = 0.75f, sampleSize = 12,
                    ),
                ),
            ),
        )

        // When - all three are merged
        val result = merged(lb, lastfm, mb)

        // Then - three signals, each still naming its own source and unit
        assertEquals(
            listOf("listenbrainz", "lastfm", "musicbrainz"),
            result.signals.map { it.source },
        )
        assertEquals(
            listOf(
                PopularitySignalKind.LISTEN_COUNT,
                PopularitySignalKind.LISTENER_COUNT,
                PopularitySignalKind.RATING,
            ),
            result.signals.map { it.kind },
        )
    }

    @Test
    fun `a rating-only source contributes its signal without touching the counts`() {
        // Given - MusicBrainz's community rating alongside a source carrying real counts
        val counts = success("listenbrainz", EnrichmentData.Popularity(listenCount = 900))
        val rating = success(
            "musicbrainz",
            EnrichmentData.Popularity(
                signals = listOf(
                    PopularitySignal(
                        "musicbrainz", PopularitySignalKind.RATING, 4.0,
                        normalized = 0.75f, sampleSize = 12,
                    ),
                ),
            ),
        )

        // When - they are merged
        val result = merged(counts, rating)

        // Then - a 1-5 rating never becomes a listen count, and the vote count rides with it
        assertEquals(900L, result.listenCount)
        assertNull(result.listenerCount)
        assertEquals(12, result.signals.single().sampleSize)
    }

    @Test
    fun `merging nothing is NotFound rather than an empty Success`() {
        // Given - a chain where every provider failed, so no successes reached the merger
        val results = emptyList<EnrichmentResult.Success>()

        // When - the merger runs anyway, as the engine calls it unconditionally
        val result = merger.merge(results)

        // Then - NotFound, matching every other merger's answer to an empty list
        assertTrue("expected NotFound, got $result", result is EnrichmentResult.NotFound)
    }
}
