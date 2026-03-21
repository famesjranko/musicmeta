package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.BandMember
import com.landofoz.musicmeta.DiscographyAlbum
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.TimelineEvent
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // --- Task 2: Synthesis tests ---

    @Test
    fun `synthesize with Group beginDate produces formed event`() {
        // Given
        val meta = metadata(beginDate = "1985", artistType = "Group")

        // When
        val result = TimelineSynthesizer.synthesize(meta, null, null)

        // Then
        val event = result.events.single { it.type == "formed" }
        assertEquals("1985", event.date)
    }

    @Test
    fun `synthesize with Person beginDate produces born event`() {
        // Given
        val meta = metadata(beginDate = "1960", artistType = "Person")

        // When
        val result = TimelineSynthesizer.synthesize(meta, null, null)

        // Then
        val event = result.events.single { it.type == "born" }
        assertEquals("1960", event.date)
    }

    @Test
    fun `synthesize with Group endDate produces disbanded event`() {
        // Given
        val meta = metadata(endDate = "2020", artistType = "Group")

        // When
        val result = TimelineSynthesizer.synthesize(meta, null, null)

        // Then
        val event = result.events.single { it.type == "disbanded" }
        assertEquals("2020", event.date)
    }

    @Test
    fun `synthesize with Person endDate produces died event`() {
        // Given
        val meta = metadata(endDate = "2016", artistType = "Person")

        // When
        val result = TimelineSynthesizer.synthesize(meta, null, null)

        // Then
        val event = result.events.single { it.type == "died" }
        assertEquals("2016", event.date)
    }

    @Test
    fun `synthesize with discography produces album_release events`() {
        // Given
        val disco = discography("OK Computer" to "1997", "Pablo Honey" to "1993")

        // When
        val result = TimelineSynthesizer.synthesize(null, disco, null)

        // Then
        val albumReleases = result.events.filter { it.type == "album_release" }
        val titles = albumReleases.map { it.relatedEntity }
        assertTrue("OK Computer" in titles)
    }

    @Test
    fun `synthesize marks earliest album as first_album type`() {
        // Given
        val disco = discography("OK Computer" to "1997", "Pablo Honey" to "1993", "The Bends" to "1995")

        // When
        val result = TimelineSynthesizer.synthesize(null, disco, null)

        // Then
        val firstAlbum = result.events.single { it.type == "first_album" }
        assertEquals("Pablo Honey", firstAlbum.relatedEntity)
    }

    @Test
    fun `synthesize with band members with present activePeriod produces member_joined event`() {
        // Given
        val members = bandMembers("Thom Yorke" to "1985-present")

        // When
        val result = TimelineSynthesizer.synthesize(null, null, members)

        // Then
        val joined = result.events.single { it.type == "member_joined" }
        assertEquals("1985", joined.date)
        assertEquals("Thom Yorke", joined.relatedEntity)
    }

    @Test
    fun `synthesize with band members with year range activePeriod produces joined and left events`() {
        // Given
        val members = bandMembers("Colin Greenwood" to "1985-2009")

        // When
        val result = TimelineSynthesizer.synthesize(null, null, members)

        // Then
        val joined = result.events.single { it.type == "member_joined" }
        val left = result.events.single { it.type == "member_left" }
        assertEquals("1985", joined.date)
        assertEquals("2009", left.date)
        assertEquals("Colin Greenwood", left.relatedEntity)
    }

    @Test
    fun `synthesize events are sorted chronologically by date`() {
        // Given
        val meta = metadata(beginDate = "1990")
        val disco = discography("Album A" to "1995", "Album B" to "1992")

        // When
        val result = TimelineSynthesizer.synthesize(meta, disco, null)

        // Then
        val dates = result.events.map { it.date }
        assertEquals(dates.sorted(), dates)
    }

    @Test
    fun `synthesize deduplicates events with same date and type`() {
        // Given - same member from both metadata and band members producing same date+type
        val members = bandMembers("Alice" to "1990-2000", "Alice" to "1990-2000")

        // When
        val result = TimelineSynthesizer.synthesize(null, null, members)

        // Then
        val joined = result.events.filter { it.type == "member_joined" && it.date == "1990" }
        assertEquals(1, joined.size)
    }

    @Test
    fun `synthesize with only metadata returns partial timeline`() {
        // Given
        val meta = metadata(beginDate = "1985", artistType = "Group")

        // When
        val result = TimelineSynthesizer.synthesize(meta, null, null)

        // Then
        assertTrue(result.events.isNotEmpty())
        assertEquals(1, result.events.size)
    }

    @Test
    fun `synthesize with all NotFound returns empty events list`() {
        // Given
        val notFound = EnrichmentResult.NotFound(EnrichmentType.ARTIST_TIMELINE, "test")

        // When
        val result = TimelineSynthesizer.synthesize(notFound, notFound, notFound)

        // Then
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun `synthesize with null artistType defaults to Group behavior`() {
        // Given - null artistType should default to Group (formed/disbanded)
        val meta = metadata(beginDate = "1985", artistType = null)

        // When
        val result = TimelineSynthesizer.synthesize(meta, null, null)

        // Then
        val event = result.events.single()
        assertEquals("formed", event.type)
    }

    // --- Helpers ---

    private fun metadata(
        beginDate: String? = null,
        endDate: String? = null,
        artistType: String? = null,
    ): EnrichmentResult = EnrichmentResult.Success(
        type = EnrichmentType.ALBUM_METADATA,
        data = EnrichmentData.Metadata(
            beginDate = beginDate,
            endDate = endDate,
            artistType = artistType,
        ),
        provider = "test",
        confidence = 1.0f,
    )

    private fun discography(vararg albums: Pair<String, String?>): EnrichmentResult =
        EnrichmentResult.Success(
            type = EnrichmentType.ARTIST_DISCOGRAPHY,
            data = EnrichmentData.Discography(
                albums = albums.map { (title, year) ->
                    DiscographyAlbum(title = title, year = year)
                },
            ),
            provider = "test",
            confidence = 1.0f,
        )

    private fun bandMembers(vararg members: Pair<String, String?>): EnrichmentResult =
        EnrichmentResult.Success(
            type = EnrichmentType.BAND_MEMBERS,
            data = EnrichmentData.BandMembers(
                members = members.map { (name, period) ->
                    BandMember(name = name, activePeriod = period)
                },
            ),
            provider = "test",
            confidence = 1.0f,
        )
}
