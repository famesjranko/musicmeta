package com.landofoz.musicmeta.provider.deezer

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.RadioTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    // TRACK_PREVIEW mapper tests

    @Test
    fun `toTrackPreview returns TrackPreview with url and durationMs`() {
        // Given — a track search result with preview URL and duration
        val result = DeezerTrackSearchResult(
            id = 789L,
            title = "Karma Police",
            artistName = "Radiohead",
            previewUrl = "https://cdns-preview.dzcdn.net/stream/abc123.mp3",
            durationSec = 30,
            albumTitle = "OK Computer",
        )

        // When
        val preview = DeezerMapper.toTrackPreview(result)

        // Then — TrackPreview has correct fields
        assertNotNull(preview)
        assertEquals("https://cdns-preview.dzcdn.net/stream/abc123.mp3", preview!!.url)
        assertEquals(30000L, preview.durationMs)
        assertEquals("deezer", preview.source)
    }

    @Test
    fun `toTrackPreview returns null when previewUrl is null`() {
        // Given — a track search result without preview URL
        val result = DeezerTrackSearchResult(id = 1L, title = "T", artistName = "A", previewUrl = null)

        // When
        val preview = DeezerMapper.toTrackPreview(result)

        // Then — null because no preview available
        assertNull(preview)
    }

    @Test
    fun `toTrackPreview always returns 30000ms regardless of track duration`() {
        // Given — a track with a 45-second full duration (preview is always 30s)
        val result = DeezerTrackSearchResult(
            id = 3L, title = "T", artistName = "A",
            previewUrl = "https://example.com/preview.mp3", durationSec = 45,
        )

        // When
        val preview = DeezerMapper.toTrackPreview(result)

        // Then — preview duration is always 30000ms (Deezer previews are 30 seconds)
        assertEquals(30000L, preview!!.durationMs)
    }
}
