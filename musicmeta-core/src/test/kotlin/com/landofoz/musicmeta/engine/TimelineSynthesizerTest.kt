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
        // Given - an ArtistTimeline with one formed event
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

        // When - encoding to JSON then decoding back
        val json = Json.encodeToString(EnrichmentData.ArtistTimeline.serializer(), timeline)
        val decoded = Json.decodeFromString(EnrichmentData.ArtistTimeline.serializer(), json)

        // Then - the decoded timeline equals the original
        assertEquals(timeline, decoded)
    }

    @Test
    fun `TimelineEvent round-trip serialization works`() {
        // Given - a single formed TimelineEvent
        val event = TimelineEvent(
            date = "1985",
            type = "formed",
            description = "Band formed",
            relatedEntity = null,
            identifiers = EnrichmentIdentifiers(),
        )

        // When - encoding to JSON then decoding back
        val json = Json.encodeToString(TimelineEvent.serializer(), event)
        val decoded = Json.decodeFromString(TimelineEvent.serializer(), json)

        // Then - the decoded event equals the original
        assertEquals(event, decoded)
    }

    @Test
    fun `ARTIST_TIMELINE has 30-day TTL`() {
        // Given - the ARTIST_TIMELINE enrichment type
        // When - reading its default TTL
        // Then - the TTL is 30 days in milliseconds
        assertEquals(2_592_000_000L, EnrichmentType.ARTIST_TIMELINE.defaultTtlMs)
    }

    // --- Task 2: Synthesis tests ---

    @Test
    fun `synthesize with Group beginDate produces formed event`() {
        // Given - a Group with a beginDate
        val meta = metadata(beginDate = "1985", artistType = "Group")

        // When - synthesizing the timeline from metadata alone
        val result = TimelineSynthesizer.synthesize(meta, null, null)

        // Then - a formed event carries the beginDate
        val event = result.events.single { it.type == "formed" }
        assertEquals("1985", event.date)
    }

    @Test
    fun `synthesize with Person beginDate produces born event`() {
        // Given - a Person with a beginDate
        val meta = metadata(beginDate = "1960", artistType = "Person")

        // When - synthesizing the timeline from metadata alone
        val result = TimelineSynthesizer.synthesize(meta, null, null)

        // Then - a born event carries the beginDate
        val event = result.events.single { it.type == "born" }
        assertEquals("1960", event.date)
    }

    @Test
    fun `synthesize with Group endDate produces disbanded event`() {
        // Given - a Group with an endDate
        val meta = metadata(endDate = "2020", artistType = "Group")

        // When - synthesizing the timeline from metadata alone
        val result = TimelineSynthesizer.synthesize(meta, null, null)

        // Then - a disbanded event carries the endDate
        val event = result.events.single { it.type == "disbanded" }
        assertEquals("2020", event.date)
    }

    @Test
    fun `synthesize with Person endDate produces died event`() {
        // Given - a Person with an endDate
        val meta = metadata(endDate = "2016", artistType = "Person")

        // When - synthesizing the timeline from metadata alone
        val result = TimelineSynthesizer.synthesize(meta, null, null)

        // Then - a died event carries the endDate
        val event = result.events.single { it.type == "died" }
        assertEquals("2016", event.date)
    }

    @Test
    fun `synthesize with discography produces album_release events`() {
        // Given - a discography with two albums
        val disco = discography("OK Computer" to 1997, "Pablo Honey" to 1993)

        // When - synthesizing the timeline from discography alone
        val result = TimelineSynthesizer.synthesize(null, disco, null)

        // Then - an album_release event is produced for each album
        val albumReleases = result.events.filter { it.type == "album_release" }
        val titles = albumReleases.map { it.relatedEntity }
        assertTrue("OK Computer" in titles)
    }

    @Test
    fun `synthesize marks earliest album as first_album type`() {
        // Given - a discography with three albums released in non-chronological order
        val disco = discography("OK Computer" to 1997, "Pablo Honey" to 1993, "The Bends" to 1995)

        // When - synthesizing the timeline from discography alone
        val result = TimelineSynthesizer.synthesize(null, disco, null)

        // Then - the earliest-released album is tagged first_album
        val firstAlbum = result.events.single { it.type == "first_album" }
        assertEquals("Pablo Honey", firstAlbum.relatedEntity)
    }

    @Test
    fun `synthesize with band members with present activePeriod produces member_joined event`() {
        // Given - a band member with an open-ended activePeriod
        val members = bandMembers("Thom Yorke" to "1985-present")

        // When - synthesizing the timeline from band members alone
        val result = TimelineSynthesizer.synthesize(null, null, members)

        // Then - a member_joined event carries the start year, with no member_left
        val joined = result.events.single { it.type == "member_joined" }
        assertEquals("1985", joined.date)
        assertEquals("Thom Yorke", joined.relatedEntity)
    }

    @Test
    fun `synthesize with band members with year range activePeriod produces joined and left events`() {
        // Given - a band member with a closed year-range activePeriod
        val members = bandMembers("Colin Greenwood" to "1985-2009")

        // When - synthesizing the timeline from band members alone
        val result = TimelineSynthesizer.synthesize(null, null, members)

        // Then - a member_joined event carries the start year and a member_left event carries the end year
        val joined = result.events.single { it.type == "member_joined" }
        val left = result.events.single { it.type == "member_left" }
        assertEquals("1985", joined.date)
        assertEquals("2009", left.date)
        assertEquals("Colin Greenwood", left.relatedEntity)
    }

    @Test
    fun `synthesize events are sorted chronologically by date`() {
        // Given - metadata and a discography whose album years are out of order
        val meta = metadata(beginDate = "1990")
        val disco = discography("Album A" to 1995, "Album B" to 1992)

        // When - synthesizing the timeline from both sources
        val result = TimelineSynthesizer.synthesize(meta, disco, null)

        // Then - the events are already in date-sorted order
        val dates = result.events.map { it.date }
        assertEquals(dates.sorted(), dates)
    }

    @Test
    fun `synthesize deduplicates events with same date and type`() {
        // Given - same member from both metadata and band members producing same date+type
        val members = bandMembers("Alice" to "1990-2000", "Alice" to "1990-2000")

        // When - synthesizing the timeline from the duplicated band member
        val result = TimelineSynthesizer.synthesize(null, null, members)

        // Then - the duplicate member_joined events are collapsed into one
        val joined = result.events.filter { it.type == "member_joined" && it.date == "1990" }
        assertEquals(1, joined.size)
    }

    @Test
    fun `synthesize with only metadata returns partial timeline`() {
        // Given - only metadata, no discography or band members
        val meta = metadata(beginDate = "1985", artistType = "Group")

        // When - synthesizing the timeline from metadata alone
        val result = TimelineSynthesizer.synthesize(meta, null, null)

        // Then - a single event is produced for the partial timeline
        assertTrue(result.events.isNotEmpty())
        assertEquals(1, result.events.size)
    }

    @Test
    fun `synthesize with all NotFound returns empty events list`() {
        // Given - all three enrichment inputs are NotFound
        val notFound = EnrichmentResult.NotFound(EnrichmentType.ARTIST_TIMELINE, "test")

        // When - synthesizing the timeline from all-NotFound inputs
        val result = TimelineSynthesizer.synthesize(notFound, notFound, notFound)

        // Then - no events are produced
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun `synthesize with null artistType defaults to Group behavior`() {
        // Given - null artistType should default to Group (formed/disbanded)
        val meta = metadata(beginDate = "1985", artistType = null)

        // When - synthesizing the timeline from metadata with no artistType
        val result = TimelineSynthesizer.synthesize(meta, null, null)

        // Then - the single event defaults to Group behavior (formed)
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

    private fun discography(vararg albums: Pair<String, Int?>): EnrichmentResult =
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
