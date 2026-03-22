package com.landofoz.musicmeta.provider.deezer

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.RadioTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeezerMapperTest {

    @Test
    fun `toRadioPlaylist converts tracks with duration and deezerId`() {
        // Given — a list of radio tracks with full data
        val tracks = listOf(
            DeezerRadioTrack(
                id = 123L,
                title = "Creep",
                artistName = "Radiohead",
                albumTitle = "Pablo Honey",
                durationSec = 238,
            ),
        )

        // When
        val result = DeezerMapper.toRadioPlaylist(tracks)

        // Then — track fields mapped correctly
        assertEquals(1, result.tracks.size)
        val track = result.tracks.first()
        assertEquals("Creep", track.title)
        assertEquals("Radiohead", track.artist)
        assertEquals("Pablo Honey", track.album)
        assertEquals(238000L, track.durationMs)
        assertEquals("123", track.identifiers.extra["deezerId"])
    }

    @Test
    fun `toRadioPlaylist converts durationSec to durationMs correctly`() {
        // Given — a track with known duration in seconds
        val tracks = listOf(
            DeezerRadioTrack(id = 1L, title = "T", artistName = "A", durationSec = 180),
        )

        // When
        val result = DeezerMapper.toRadioPlaylist(tracks)

        // Then — duration is 180 * 1000 = 180000 ms
        assertEquals(180000L, result.tracks.first().durationMs)
    }

    @Test
    fun `toRadioPlaylist sets durationMs to null when durationSec is zero`() {
        // Given — a track with zero duration (unknown)
        val tracks = listOf(
            DeezerRadioTrack(id = 2L, title = "T", artistName = "A", durationSec = 0),
        )

        // When
        val result = DeezerMapper.toRadioPlaylist(tracks)

        // Then — durationMs is null for unknown duration
        assertNull(result.tracks.first().durationMs)
    }

    @Test
    fun `toRadioPlaylist sets album to null when albumTitle is null`() {
        // Given — a track without album info
        val tracks = listOf(
            DeezerRadioTrack(id = 3L, title = "T", artistName = "A", albumTitle = null),
        )

        // When
        val result = DeezerMapper.toRadioPlaylist(tracks)

        // Then — album is null
        assertNull(result.tracks.first().album)
    }

    @Test
    fun `toRadioPlaylist returns empty RadioPlaylist for empty list`() {
        // Given — empty track list
        val tracks = emptyList<DeezerRadioTrack>()

        // When
        val result = DeezerMapper.toRadioPlaylist(tracks)

        // Then — empty playlist
        assertEquals(EnrichmentData.RadioPlaylist(tracks = emptyList()), result)
    }

    @Test
    fun `toRadioPlaylist stores track id as deezerId in identifiers`() {
        // Given — a track with a specific id
        val tracks = listOf(
            DeezerRadioTrack(id = 999L, title = "T", artistName = "A"),
        )

        // When
        val result = DeezerMapper.toRadioPlaylist(tracks)

        // Then — deezerId is stored as string
        assertEquals("999", result.tracks.first().identifiers.extra["deezerId"])
    }
}
