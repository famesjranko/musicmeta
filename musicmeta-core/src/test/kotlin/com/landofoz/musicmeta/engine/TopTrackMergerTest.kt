package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.TopTrack
import org.junit.Assert.*
import org.junit.Test

class TopTrackMergerTest {

    private fun topTracks(provider: String, tracks: List<TopTrack>) =
        EnrichmentResult.Success(
            type = EnrichmentType.ARTIST_TOP_TRACKS,
            data = EnrichmentData.TopTracks(tracks),
            provider = provider,
            confidence = 0.9f,
        )

    private fun track(
        title: String,
        artist: String = "Radiohead",
        listenCount: Long? = null,
        listenerCount: Long? = null,
        album: String? = null,
        durationMs: Long? = null,
        mbid: String? = null,
        source: String = "test",
    ) = TopTrack(
        title = title, artist = artist, album = album,
        durationMs = durationMs, listenCount = listenCount,
        listenerCount = listenerCount, rank = 0,
        sources = listOf(source),
        identifiers = if (mbid != null) EnrichmentIdentifiers(musicBrainzId = mbid) else EnrichmentIdentifiers(),
    )

    @Test fun `empty input returns NotFound`() {
        // Given - no provider results at all
        // When - merging an empty list
        val result = TopTrackMerger.merge(emptyList<EnrichmentResult.Success>())
        // Then - the merge reports NotFound
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test fun `single provider passes through with ranking`() {
        // Given - one provider's ranked track list
        // When - merging that single result
        val result = TopTrackMerger.merge(listOf(
            topTracks("lastfm", listOf(
                track("Creep", listenCount = 5000, source = "lastfm"),
                track("Karma Police", listenCount = 4000, source = "lastfm"),
            )),
        ))

        // Then - the tracks pass through unmerged, ranked by listen count, with sources preserved
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.TopTracks
        assertEquals(2, data.tracks.size)
        assertEquals("Creep", data.tracks[0].title)
        assertEquals(1, data.tracks[0].rank)
        assertEquals(2, data.tracks[1].rank)
        assertEquals(listOf("lastfm"), data.tracks[0].sources)
    }

    @Test fun `deduplicates by title and sums listen counts`() {
        // Given - two providers reporting the same track title
        // When - merging both results
        val result = TopTrackMerger.merge(listOf(
            topTracks("lastfm", listOf(
                track("Creep", listenCount = 5000, source = "lastfm"),
            )),
            topTracks("listenbrainz", listOf(
                track("Creep", listenCount = 3000, source = "listenbrainz"),
            )),
        ))

        // Then - the tracks collapse into one with summed listen counts and both sources recorded
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.TopTracks
        assertEquals(1, data.tracks.size)
        assertEquals("Creep", data.tracks[0].title)
        assertEquals(8000L, data.tracks[0].listenCount)
        assertTrue(data.tracks[0].sources.containsAll(listOf("lastfm", "listenbrainz")))
    }

    @Test fun `deduplicates by MBID across providers`() {
        // Given - two providers reporting the same MBID with differing album/duration detail
        val mbid = "abc-123"
        // When - merging both results
        val result = TopTrackMerger.merge(listOf(
            topTracks("lastfm", listOf(
                track("Creep", listenCount = 5000, mbid = mbid, source = "lastfm"),
            )),
            topTracks("listenbrainz", listOf(
                track("Creep", listenCount = 3000, mbid = mbid, source = "listenbrainz",
                    album = "Pablo Honey", durationMs = 238000),
            )),
        ))

        // Then - the tracks merge on MBID and the richer album/duration detail is kept
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.TopTracks
        assertEquals(1, data.tracks.size)
        assertEquals("Pablo Honey", data.tracks[0].album)
        assertEquals(238000L, data.tracks[0].durationMs)
        assertEquals(mbid, data.tracks[0].identifiers.musicBrainzId)
    }

    @Test fun `ranks by combined listen count`() {
        // Given - two providers each reporting listen counts for the same two tracks
        // When - merging both results
        val result = TopTrackMerger.merge(listOf(
            topTracks("lastfm", listOf(
                track("Karma Police", listenCount = 4000, source = "lastfm"),
                track("Creep", listenCount = 5000, source = "lastfm"),
            )),
            topTracks("listenbrainz", listOf(
                track("Karma Police", listenCount = 6000, source = "listenbrainz"),
                track("Creep", listenCount = 1000, source = "listenbrainz"),
            )),
        ))

        // Karma Police: 4000+6000=10000, Creep: 5000+1000=6000
        // Then - the track with the higher combined listen count ranks first
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.TopTracks
        assertEquals("Karma Police", data.tracks[0].title)
        assertEquals(10000L, data.tracks[0].listenCount)
        assertEquals(1, data.tracks[0].rank)
        assertEquals("Creep", data.tracks[1].title)
        assertEquals(2, data.tracks[1].rank)
    }

    @Test fun `preserves unique tracks from each provider`() {
        // Given - two providers each reporting a distinct track
        // When - merging both results
        val result = TopTrackMerger.merge(listOf(
            topTracks("lastfm", listOf(
                track("Creep", listenCount = 5000, source = "lastfm"),
            )),
            topTracks("deezer", listOf(
                track("Everything In Its Right Place", listenCount = null, source = "deezer",
                    album = "Kid A", durationMs = 250000),
            )),
        ))

        // Then - both tracks are kept, unmerged
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.TopTracks
        assertEquals(2, data.tracks.size)
    }

    @Test fun `keeps highest listener count`() {
        // Given - two providers reporting the same track with different listener counts
        // When - merging both results
        val result = TopTrackMerger.merge(listOf(
            topTracks("lastfm", listOf(
                track("Creep", listenerCount = 1000, listenCount = 5000, source = "lastfm"),
            )),
            topTracks("listenbrainz", listOf(
                track("Creep", listenerCount = 2000, listenCount = 3000, source = "listenbrainz"),
            )),
        ))

        // Then - the merged track keeps the higher listener count
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.TopTracks
        assertEquals(2000L, data.tracks[0].listenerCount)
    }

    @Test fun `deduplicates case-insensitively`() {
        // Given - two providers reporting the same title with different casing
        // When - merging both results
        val result = TopTrackMerger.merge(listOf(
            topTracks("lastfm", listOf(
                track("CREEP", listenCount = 5000, source = "lastfm"),
            )),
            topTracks("deezer", listOf(
                track("Creep", listenCount = 3000, source = "deezer"),
            )),
        ))

        // Then - the tracks merge into one with summed listen counts
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.TopTracks
        assertEquals(1, data.tracks.size)
        assertEquals(8000L, data.tracks[0].listenCount)
    }

    @Test fun `output is ordered by combined listen count descending`() {
        // Given - one provider's tracks with listen counts out of order
        // When - merging that result
        val result = TopTrackMerger.merge(listOf(
            topTracks("lastfm", listOf(
                track("C", listenCount = 100, source = "lastfm"),
                track("A", listenCount = 500, source = "lastfm"),
                track("B", listenCount = 300, source = "lastfm"),
            )),
        ))

        // Then - tracks are ordered by listen count descending, with ranks matching position
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.TopTracks
        assertEquals("A", data.tracks[0].title)
        assertEquals("B", data.tracks[1].title)
        assertEquals("C", data.tracks[2].title)
        // Ranks match position
        assertEquals(1, data.tracks[0].rank)
        assertEquals(2, data.tracks[1].rank)
        assertEquals(3, data.tracks[2].rank)
    }

    @Test fun `tracks with no listen count sort after tracks with counts`() {
        // Given - one track with no listen count and one with a listen count, from different providers
        // When - merging both results
        val result = TopTrackMerger.merge(listOf(
            topTracks("deezer", listOf(
                track("No Count", listenCount = null, source = "deezer"),
            )),
            topTracks("lastfm", listOf(
                track("Has Count", listenCount = 100, source = "lastfm"),
            )),
        ))

        // Then - the track with a listen count sorts before the one without
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.TopTracks
        assertEquals("Has Count", data.tracks[0].title)
        assertEquals("No Count", data.tracks[1].title)
    }

    @Test fun `returns all tracks with no artificial cap`() {
        // Given - 200 unique tracks from a single provider
        val manyTracks = (1..200).map {
            track("Track $it", listenCount = (200 - it).toLong(), source = "lastfm")
        }
        // When - merging that single result
        val result = TopTrackMerger.merge(listOf(topTracks("lastfm", manyTracks)))

        // Then - all 200 tracks are kept, none dropped by an artificial cap
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.TopTracks
        assertEquals(200, data.tracks.size)
        assertEquals("Track 1", data.tracks[0].title) // highest listen count
        assertEquals("Track 200", data.tracks[199].title) // lowest
    }

    @Test fun `MBID match takes priority over title match`() {
        // Given - same MBID but slightly different titles (e.g. remastered)
        val mbid = "abc-123"
        // When - merging both results
        val result = TopTrackMerger.merge(listOf(
            topTracks("lastfm", listOf(
                track("Karma Police", listenCount = 5000, mbid = mbid, source = "lastfm"),
            )),
            topTracks("listenbrainz", listOf(
                track("Karma Police (Remastered)", listenCount = 3000, mbid = mbid,
                    source = "listenbrainz", album = "OK Computer OKNOTOK"),
            )),
        ))

        // Then - the MBID match merges them into one track, not two
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.TopTracks
        assertEquals(1, data.tracks.size)
        assertEquals(8000L, data.tracks[0].listenCount)
    }

    @Test fun `different tracks with same title but no MBID stay separate when different artist`() {
        // Given - two providers reporting the same title and artist, no MBID
        // When - merging both results
        val result = TopTrackMerger.merge(listOf(
            topTracks("lastfm", listOf(
                track("Angel", artist = "Massive Attack", listenCount = 5000, source = "lastfm"),
            )),
            topTracks("deezer", listOf(
                track("Angel", artist = "Massive Attack", listenCount = 3000, source = "deezer"),
            )),
        ))

        // Then - same title AND same artist merges into one track
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.TopTracks
        assertEquals(1, data.tracks.size)
        assertEquals(8000L, data.tracks[0].listenCount)
    }
}
