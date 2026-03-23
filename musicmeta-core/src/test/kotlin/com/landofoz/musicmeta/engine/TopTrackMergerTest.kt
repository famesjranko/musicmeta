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
}
