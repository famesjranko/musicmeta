package com.landofoz.musicmeta.provider.listenbrainz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The three fields `popularity/top-recordings-for-artist` sends beyond a title and a listen count.
 * The DTOs below are synthetic — no captured ListenBrainz pool exists in this tree — but their
 * shape is `ListenBrainzApi.parseRecordings`' output for `total_user_count`, `length` and
 * `release_name`.
 */
class ListenBrainzMapperTest {

    @Test
    fun `toTopTracks carries listenerCount, durationMs and album from the DTO`() {
        // Given - a track DTO carrying all three
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

        // When - mapping the tracks to top tracks
        val topTracks = ListenBrainzMapper.toTopTracks(tracks)

        // Then - each field reaches the published TopTrack
        val track = topTracks.tracks.first()
        assertEquals(120L, track.listenerCount)
        assertEquals(238_000L, track.durationMs)
        assertEquals("Pablo Honey", track.album)
    }

    @Test
    fun `toTopTracks leaves them null when the DTO doesn't carry them`() {
        // Given - a track DTO without listenerCount, durationMs or albumName
        val tracks = listOf(
            ListenBrainzPopularTrack(
                recordingMbid = "rec1",
                title = "Creep",
                artistName = "Radiohead",
                listenCount = 500L,
            ),
        )

        // When - mapping the tracks to top tracks
        val topTracks = ListenBrainzMapper.toTopTracks(tracks)

        // Then - the missing fields stay null rather than being defaulted
        val track = topTracks.tracks.first()
        assertNull(track.listenerCount)
        assertNull(track.durationMs)
        assertNull(track.album)
    }
}
