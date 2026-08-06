package com.landofoz.musicmeta.provider.musicbrainz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicBrainzMapperTrackMetadataTest {

    private fun makeRecording(
        lengthMs: Long? = null,
        artReleaseGroupTitle: String? = null,
        disambiguation: String? = null,
    ): MusicBrainzRecording =
        MusicBrainzRecording(
            id = "recording-id",
            title = "Test Track",
            isrcs = emptyList(),
            tags = emptyList(),
            tagCounts = emptyList(),
            score = 100,
            disambiguation = disambiguation,
            lengthMs = lengthMs,
            artReleaseGroupTitle = artReleaseGroupTitle,
        )

    @Test
    fun `toTrackMetadataDetails copies duration, album title and disambiguation`() {
        // Given
        val recording = makeRecording(
            lengthMs = 245_000L,
            artReleaseGroupTitle = "OK Computer",
            disambiguation = "live, 1992-04-20: Wembley Arena, London, England",
        )

        // When
        val metadata = MusicBrainzMapper.toTrackMetadataDetails(recording)

        // Then
        assertEquals(245_000L, metadata.durationMs)
        assertEquals("OK Computer", metadata.albumTitle)
        assertEquals("live, 1992-04-20: Wembley Arena, London, England", metadata.disambiguation)
    }

    @Test
    fun `toTrackMetadataDetails returns all-null fields when the recording carries none of them`() {
        // Given
        val recording = makeRecording()

        // When
        val metadata = MusicBrainzMapper.toTrackMetadataDetails(recording)

        // Then
        assertNull(metadata.durationMs)
        assertNull(metadata.albumTitle)
        assertNull(metadata.disambiguation)
    }

    @Test
    fun `toTrackMetadata still drops duration, album title and disambiguation for GENRE`() {
        // Given — the pre-existing mapper used by GENRE/CREDITS-adjacent lookups
        val recording = makeRecording(
            lengthMs = 245_000L,
            artReleaseGroupTitle = "OK Computer",
            disambiguation = "live take",
        )

        // When
        val metadata = MusicBrainzMapper.toTrackMetadata(recording)

        // Then — unchanged behaviour, this mapper answers EnrichmentData.Metadata only
        assertNull(metadata.disambiguation)
    }
}
