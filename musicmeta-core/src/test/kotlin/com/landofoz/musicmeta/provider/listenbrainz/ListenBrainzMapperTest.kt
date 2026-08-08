package com.landofoz.musicmeta.provider.listenbrainz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListenBrainzMapperTest {

    @Test
    fun `toPopularity carries listenerCount, durationMs and album from the same DTO toTopTracks uses`() {
        // Given — the same ListenBrainzPopularTrack shape toTopTracks maps from
        val tracks = listOf(
            ListenBrainzPopularTrack(
                recordingMbid = "rec1",
                title = "Creep",
                artistName = "Radiohead",
                listenCount = 500L,
                listenerCount = 120L,
                durationMs = 238_000L,
                albumName = "Pablo Honey",
            ),
        )

        // When — mapping the tracks to popularity
        val popularity = ListenBrainzMapper.toPopularity(tracks)

        // Then — PopularTrack no longer drops the fields toTopTracks already keeps
        val track = popularity.topTracks!!.first()
        assertEquals(500L, track.listenCount)
        assertEquals(120L, track.listenerCount)
        assertEquals(238_000L, track.durationMs)
        assertEquals("Pablo Honey", track.album)
        assertEquals(1, track.rank)
    }

    @Test
    fun `toPopularity leaves the new fields null when the DTO doesn't carry them`() {
        // Given — a track DTO without listenerCount, durationMs or albumName
        val tracks = listOf(
            ListenBrainzPopularTrack(
                recordingMbid = "rec1",
                title = "Creep",
                artistName = "Radiohead",
                listenCount = 500L,
            ),
        )

        // When — mapping the tracks to popularity
        val popularity = ListenBrainzMapper.toPopularity(tracks)

        // Then — the missing fields stay null rather than being defaulted
        val track = popularity.topTracks!!.first()
        assertNull(track.listenerCount)
        assertNull(track.durationMs)
        assertNull(track.album)
    }
}
