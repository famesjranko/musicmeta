package com.landofoz.musicmeta

import com.landofoz.musicmeta.provider.deezer.DeezerTrackSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackPreviewDataModelTest {

    @Test
    fun `TRACK_PREVIEW enum entry exists with 24-hour TTL`() {
        // Given — the EnrichmentType enum
        // When — accessing TRACK_PREVIEW
        val type = EnrichmentType.TRACK_PREVIEW

        // Then — TTL is exactly 24 hours in milliseconds
        assertEquals(86400000L, type.defaultTtlMs)
    }

    @Test
    fun `TrackPreview can be constructed with url durationMs and source`() {
        // Given — a fully specified TrackPreview
        // When — constructing with explicit fields
        val preview = EnrichmentData.TrackPreview(
            url = "https://cdns-preview.dzcdn.net/stream/preview.mp3",
            durationMs = 30000L,
            source = "deezer",
        )

        // Then — all fields are accessible
        assertEquals("https://cdns-preview.dzcdn.net/stream/preview.mp3", preview.url)
        assertEquals(30000L, preview.durationMs)
        assertEquals("deezer", preview.source)
    }

    @Test
    fun `TrackPreview durationMs defaults to 30000`() {
        // Given — a TrackPreview constructed without explicit durationMs
        // When — omitting the durationMs field
        val preview = EnrichmentData.TrackPreview(
            url = "https://cdns-preview.dzcdn.net/stream/preview.mp3",
            source = "deezer",
        )

        // Then — default durationMs is 30000 (30 seconds)
        assertEquals(30000L, preview.durationMs)
    }

    @Test
    fun `DeezerTrackSearchResult can be constructed with previewUrl durationSec albumTitle all null`() {
        // Given — a DeezerTrackSearchResult with only required fields
        // When — new optional fields default to null
        val result = DeezerTrackSearchResult(
            id = 123L,
            title = "Karma Police",
            artistName = "Radiohead",
        )

        // Then — new optional fields are null by default
        assertNull(result.previewUrl)
        assertNull(result.durationSec)
        assertNull(result.albumTitle)
    }
}
