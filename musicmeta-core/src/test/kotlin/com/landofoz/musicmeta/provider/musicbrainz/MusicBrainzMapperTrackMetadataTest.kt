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
        // Given - a recording carrying length, an art release-group title, and a disambiguation
        val recording = makeRecording(
            lengthMs = 245_000L,
            artReleaseGroupTitle = "OK Computer",
            disambiguation = "live, 1992-04-20: Wembley Arena, London, England",
        )

        // When - mapping to track metadata details
        val metadata = MusicBrainzMapper.toTrackMetadataDetails(recording)

        // Then - duration, album title, and disambiguation are all copied across
        assertEquals(245_000L, metadata.durationMs)
        assertEquals("OK Computer", metadata.albumTitle)
        assertEquals("live, 1992-04-20: Wembley Arena, London, England", metadata.disambiguation)
    }

    @Test
    fun `toTrackMetadataDetails returns all-null fields when the recording carries none of them`() {
        // Given - a recording with no length, art release-group title, or disambiguation
        val recording = makeRecording()

        // When - mapping to track metadata details
        val metadata = MusicBrainzMapper.toTrackMetadataDetails(recording)

        // Then - all three fields come back null
        assertNull(metadata.durationMs)
        assertNull(metadata.albumTitle)
        assertNull(metadata.disambiguation)
    }

    @Test
    fun `toTrackMetadata still drops duration, album title and disambiguation for GENRE`() {
        // Given - the mapper used by GENRE/CREDITS-adjacent lookups
        val recording = makeRecording(
            lengthMs = 245_000L,
            artReleaseGroupTitle = "OK Computer",
            disambiguation = "live take",
        )

        // When - mapping to the legacy metadata shape
        val metadata = MusicBrainzMapper.toTrackMetadata(recording)

        // Then - unchanged behaviour, this mapper answers EnrichmentData.Metadata only
        assertNull(metadata.disambiguation)
    }
}
