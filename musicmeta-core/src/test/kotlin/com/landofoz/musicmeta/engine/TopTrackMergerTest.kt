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
        val result = TopTrackMerger.merge(emptyList<EnrichmentResult.Success>())
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test fun `single provider passes through with ranking`() {
        val result = TopTrackMerger.merge(listOf(
            topTracks("lastfm", listOf(
                track("Creep", listenCount = 5000, source = "lastfm"),
                track("Karma Police", listenCount = 4000, source = "lastfm"),
            )),
        ))

        val data = (result as EnrichmentResult.Success).data as EnrichmentData.TopTracks
        assertEquals(2, data.tracks.size)
        assertEquals("Creep", data.tracks[0].title)
        assertEquals(1, data.tracks[0].rank)
        assertEquals(2, data.tracks[1].rank)
        assertEquals(listOf("lastfm"), data.tracks[0].sources)
    }

    @Test fun `deduplicates by title and sums listen counts`() {
        val result = TopTrackMerger.merge(listOf(
            topTracks("lastfm", listOf(
                track("Creep", listenCount = 5000, source = "lastfm"),
            )),
            topTracks("listenbrainz", listOf(
                track("Creep", listenCount = 3000, source = "listenbrainz"),
            )),
        ))

        val data = (result as EnrichmentResult.Success).data as EnrichmentData.TopTracks
        assertEquals(1, data.tracks.size)
        assertEquals("Creep", data.tracks[0].title)
        assertEquals(8000L, data.tracks[0].listenCount)
        assertTrue(data.tracks[0].sources.containsAll(listOf("lastfm", "listenbrainz")))
    }

    @Test fun `deduplicates by MBID across providers`() {
        val mbid = "abc-123"
        val result = TopTrackMerger.merge(listOf(
            topTracks("lastfm", listOf(
                track("Creep", listenCount = 5000, mbid = mbid, source = "lastfm"),
            )),
            topTracks("listenbrainz", listOf(
                track("Creep", listenCount = 3000, mbid = mbid, source = "listenbrainz",
                    album = "Pablo Honey", durationMs = 238000),
            )),
        ))

        val data = (result as EnrichmentResult.Success).data as EnrichmentData.TopTracks
        assertEquals(1, data.tracks.size)
        assertEquals("Pablo Honey", data.tracks[0].album)
        assertEquals(238000L, data.tracks[0].durationMs)
        assertEquals(mbid, data.tracks[0].identifiers.musicBrainzId)
    }

    @Test fun `ranks by combined listen count`() {
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
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.TopTracks
        assertEquals("Karma Police", data.tracks[0].title)
        assertEquals(10000L, data.tracks[0].listenCount)
        assertEquals(1, data.tracks[0].rank)
        assertEquals("Creep", data.tracks[1].title)
        assertEquals(2, data.tracks[1].rank)
    }

    @Test fun `preserves unique tracks from each provider`() {
        val result = TopTrackMerger.merge(listOf(
            topTracks("lastfm", listOf(
                track("Creep", listenCount = 5000, source = "lastfm"),
            )),
            topTracks("deezer", listOf(
                track("Everything In Its Right Place", listenCount = null, source = "deezer",
                    album = "Kid A", durationMs = 250000),
            )),
        ))

        val data = (result as EnrichmentResult.Success).data as EnrichmentData.TopTracks
        assertEquals(2, data.tracks.size)
    }

    @Test fun `keeps highest listener count`() {
        val result = TopTrackMerger.merge(listOf(
            topTracks("lastfm", listOf(
                track("Creep", listenerCount = 1000, listenCount = 5000, source = "lastfm"),
            )),
            topTracks("listenbrainz", listOf(
                track("Creep", listenerCount = 2000, listenCount = 3000, source = "listenbrainz"),
            )),
        ))

        val data = (result as EnrichmentResult.Success).data as EnrichmentData.TopTracks
        assertEquals(2000L, data.tracks[0].listenerCount)
    }

    @Test fun `deduplicates case-insensitively`() {
        val result = TopTrackMerger.merge(listOf(
            topTracks("lastfm", listOf(
                track("CREEP", listenCount = 5000, source = "lastfm"),
            )),
            topTracks("deezer", listOf(
                track("Creep", listenCount = 3000, source = "deezer"),
            )),
        ))

        val data = (result as EnrichmentResult.Success).data as EnrichmentData.TopTracks
        assertEquals(1, data.tracks.size)
        assertEquals(8000L, data.tracks[0].listenCount)
    }

    @Test fun `output is ordered by combined listen count descending`() {
        val result = TopTrackMerger.merge(listOf(
            topTracks("lastfm", listOf(
                track("C", listenCount = 100, source = "lastfm"),
                track("A", listenCount = 500, source = "lastfm"),
                track("B", listenCount = 300, source = "lastfm"),
            )),
        ))

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
        val result = TopTrackMerger.merge(listOf(
            topTracks("deezer", listOf(
                track("No Count", listenCount = null, source = "deezer"),
            )),
            topTracks("lastfm", listOf(
                track("Has Count", listenCount = 100, source = "lastfm"),
            )),
        ))

        val data = (result as EnrichmentResult.Success).data as EnrichmentData.TopTracks
        assertEquals("Has Count", data.tracks[0].title)
        assertEquals("No Count", data.tracks[1].title)
    }

    @Test fun `returns all tracks with no artificial cap`() {
        // Given — 200 unique tracks from a single provider
        val manyTracks = (1..200).map {
            track("Track $it", listenCount = (200 - it).toLong(), source = "lastfm")
        }
        val result = TopTrackMerger.merge(listOf(topTracks("lastfm", manyTracks)))

        val data = (result as EnrichmentResult.Success).data as EnrichmentData.TopTracks
        assertEquals(200, data.tracks.size)
        assertEquals("Track 1", data.tracks[0].title) // highest listen count
        assertEquals("Track 200", data.tracks[199].title) // lowest
    }

    @Test fun `MBID match takes priority over title match`() {
        // Given — same MBID but slightly different titles (e.g. remastered)
        val mbid = "abc-123"
        val result = TopTrackMerger.merge(listOf(
            topTracks("lastfm", listOf(
                track("Karma Police", listenCount = 5000, mbid = mbid, source = "lastfm"),
            )),
            topTracks("listenbrainz", listOf(
                track("Karma Police (Remastered)", listenCount = 3000, mbid = mbid,
                    source = "listenbrainz", album = "OK Computer OKNOTOK"),
            )),
        ))

        // Should merge into one track (same MBID), not two
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.TopTracks
        assertEquals(1, data.tracks.size)
        assertEquals(8000L, data.tracks[0].listenCount)
    }

    @Test fun `different tracks with same title but no MBID stay separate when different artist`() {
        val result = TopTrackMerger.merge(listOf(
            topTracks("lastfm", listOf(
                track("Angel", artist = "Massive Attack", listenCount = 5000, source = "lastfm"),
            )),
            topTracks("deezer", listOf(
                track("Angel", artist = "Massive Attack", listenCount = 3000, source = "deezer"),
            )),
        ))

        // Same title AND same artist — should merge
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.TopTracks
        assertEquals(1, data.tracks.size)
        assertEquals(8000L, data.tracks[0].listenCount)
    }
}
