package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.TimelineEvent
import com.landofoz.musicmeta.EnrichmentData
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineSynthesizerTest {

    // --- Task 1: Data model + serialization tests ---

    @Test
    fun `ArtistTimeline round-trip serialization works`() {
        // Given
        val timeline = EnrichmentData.ArtistTimeline(
            events = listOf(
                TimelineEvent(
                    date = "1985",
                    type = "formed",
                    description = "Band formed",
                    relatedEntity = null,
                    identifiers = EnrichmentIdentifiers(),
                ),
            ),
        )

        // When
        val json = Json.encodeToString(EnrichmentData.ArtistTimeline.serializer(), timeline)
        val decoded = Json.decodeFromString(EnrichmentData.ArtistTimeline.serializer(), json)

        // Then
        assertEquals(timeline, decoded)
    }

    @Test
    fun `TimelineEvent round-trip serialization works`() {
        // Given
        val event = TimelineEvent(
            date = "1985",
            type = "formed",
            description = "Band formed",
            relatedEntity = null,
            identifiers = EnrichmentIdentifiers(),
        )

        // When
        val json = Json.encodeToString(TimelineEvent.serializer(), event)
        val decoded = Json.decodeFromString(TimelineEvent.serializer(), json)

        // Then
        assertEquals(event, decoded)
    }

    @Test
    fun `ARTIST_TIMELINE has 30-day TTL`() {
        assertEquals(2_592_000_000L, EnrichmentType.ARTIST_TIMELINE.defaultTtlMs)
    }
}
