package com.landofoz.musicmeta.provider.lrclib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LrcLibMapperTest {

    private fun makeResult(
        duration: Double? = null,
        albumName: String? = null,
    ): LrcLibResult = LrcLibResult(
        id = 1L,
        trackName = "Creep",
        artistName = "Radiohead",
        albumName = albumName,
        duration = duration,
        instrumental = false,
        syncedLyrics = null,
        plainLyrics = null,
    )

    @Test
    fun `toTrackMetadata converts duration seconds to durationMs and copies albumName`() {
        // Given — a result with a duration in seconds and an album name
        val result = makeResult(duration = 238.0, albumName = "Pablo Honey")

        // When — mapping the result to track metadata
        val metadata = LrcLibMapper.toTrackMetadata(result)

        // Then — duration is converted to milliseconds and albumName is copied
        assertEquals(238000L, metadata.durationMs)
        assertEquals("Pablo Honey", metadata.albumTitle)
    }

    @Test
    fun `toTrackMetadata leaves durationMs null when duration is absent or zero`() {
        // Given / When / Then
        assertNull(LrcLibMapper.toTrackMetadata(makeResult(duration = null)).durationMs)
        assertNull(LrcLibMapper.toTrackMetadata(makeResult(duration = 0.0)).durationMs)
    }

    @Test
    fun `toTrackMetadata leaves albumTitle null when albumName is null or blank`() {
        // Given / When / Then
        assertNull(LrcLibMapper.toTrackMetadata(makeResult(albumName = null)).albumTitle)
        assertNull(LrcLibMapper.toTrackMetadata(makeResult(albumName = "  ")).albumTitle)
    }
}
