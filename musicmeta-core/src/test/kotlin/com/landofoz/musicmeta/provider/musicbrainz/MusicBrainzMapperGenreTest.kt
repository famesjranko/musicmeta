package com.landofoz.musicmeta.provider.musicbrainz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicBrainzMapperGenreTest {

    private fun makeRelease(tagCounts: List<TagCount> = emptyList()): MusicBrainzRelease =
        MusicBrainzRelease(
            id = "test-id",
            title = "Test Album",
            artistCredit = "Test Artist",
            date = "2020-01-01",
            country = "US",
            barcode = null,
            tags = tagCounts.map { it.name },
            tagCounts = tagCounts,
            label = null,
            releaseType = "Album",
            releaseGroupId = null,
            disambiguation = null,
            score = 100,
        )

    private fun makeArtist(tagCounts: List<TagCount> = emptyList()): MusicBrainzArtist =
        MusicBrainzArtist(
            id = "artist-id",
            name = "Test Artist",
            type = "Group",
            country = "US",
            beginDate = null,
            endDate = null,
            tags = tagCounts.map { it.name },
            tagCounts = tagCounts,
            disambiguation = null,
            wikidataId = null,
            wikipediaTitle = null,
            score = 100,
        )

    private fun makeRecording(tagCounts: List<TagCount> = emptyList()): MusicBrainzRecording =
        MusicBrainzRecording(
            id = "recording-id",
            title = "Test Track",
            isrcs = emptyList(),
            tags = tagCounts.map { it.name },
            tagCounts = tagCounts,
            score = 100,
        )

    @Test
    fun `toAlbumMetadata populates genreTags with 0_4f confidence and musicbrainz source`() {
        // Given
        val tagCounts = listOf(TagCount("rock", 10), TagCount("pop", 5))
        val release = makeRelease(tagCounts)

        // When
        val metadata = MusicBrainzMapper.toAlbumMetadata(release)

        // Then
        val genreTags = metadata.genreTags
        assertTrue(genreTags != null)
        assertEquals(2, genreTags!!.size)
        assertEquals("rock", genreTags[0].name)
        assertEquals(0.4f, genreTags[0].confidence)
        assertEquals(listOf("musicbrainz"), genreTags[0].sources)
        assertEquals("pop", genreTags[1].name)
        assertEquals(0.4f, genreTags[1].confidence)
    }

    @Test
    fun `toAlbumMetadata still populates genres for backward compatibility`() {
        // Given
        val tagCounts = listOf(TagCount("jazz", 8), TagCount("blues", 3))
        val release = makeRelease(tagCounts)

        // When
        val metadata = MusicBrainzMapper.toAlbumMetadata(release)

        // Then
        assertEquals(listOf("jazz", "blues"), metadata.genres)
    }

    @Test
    fun `toAlbumMetadata returns null genreTags when tagCounts is empty`() {
        // Given
        val release = makeRelease(emptyList())

        // When
        val metadata = MusicBrainzMapper.toAlbumMetadata(release)

        // Then
        assertNull(metadata.genreTags)
    }

    @Test
    fun `toArtistMetadata populates genreTags with 0_4f confidence and musicbrainz source`() {
        // Given
        val tagCounts = listOf(TagCount("metal", 15))
        val artist = makeArtist(tagCounts)

        // When
        val metadata = MusicBrainzMapper.toArtistMetadata(artist)

        // Then
        val genreTags = metadata.genreTags
        assertTrue(genreTags != null)
        assertEquals(1, genreTags!!.size)
        assertEquals("metal", genreTags[0].name)
        assertEquals(0.4f, genreTags[0].confidence)
        assertEquals(listOf("musicbrainz"), genreTags[0].sources)
    }

    @Test
    fun `toArtistMetadata returns null genreTags when tagCounts is empty`() {
        // Given
        val artist = makeArtist(emptyList())

        // When
        val metadata = MusicBrainzMapper.toArtistMetadata(artist)

        // Then
        assertNull(metadata.genreTags)
    }

    @Test
    fun `toTrackMetadata populates genreTags with 0_4f confidence and musicbrainz source`() {
        // Given
        val tagCounts = listOf(TagCount("electronic", 7))
        val recording = makeRecording(tagCounts)

        // When
        val metadata = MusicBrainzMapper.toTrackMetadata(recording)

        // Then
        val genreTags = metadata.genreTags
        assertTrue(genreTags != null)
        assertEquals(1, genreTags!!.size)
        assertEquals("electronic", genreTags[0].name)
        assertEquals(0.4f, genreTags[0].confidence)
        assertEquals(listOf("musicbrainz"), genreTags[0].sources)
    }

    @Test
    fun `toTrackMetadata returns null genreTags when tagCounts is empty`() {
        // Given
        val recording = makeRecording(emptyList())

        // When
        val metadata = MusicBrainzMapper.toTrackMetadata(recording)

        // Then
        assertNull(metadata.genreTags)
    }

    // --- Band member date normalization ---

    @Test
    fun `toBandMembers normalizes full dates to year-only in activePeriod`() {
        // Given — member with YYYY-MM-DD dates from MusicBrainz
        val members = listOf(
            MusicBrainzBandMember(
                name = "Dave Grohl",
                id = "member-1",
                role = "drums",
                beginDate = "1990-03-15",
                endDate = "2003-07-10",
                ended = true,
            ),
        )

        // When
        val result = MusicBrainzMapper.toBandMembers(members)

        // Then — activePeriod uses year-only format
        assertEquals("1990-2003", result.members[0].activePeriod)
    }

    @Test
    fun `toBandMembers normalizes YYYY-MM dates to year-only`() {
        // Given — member with YYYY-MM dates
        val members = listOf(
            MusicBrainzBandMember(
                name = "Pat Smear",
                id = "member-2",
                role = "guitar",
                beginDate = "1994-10",
                endDate = null,
                ended = false,
            ),
        )

        // When
        val result = MusicBrainzMapper.toBandMembers(members)

        // Then
        assertEquals("1994-present", result.members[0].activePeriod)
    }

    @Test
    fun `toBandMembers handles year-only dates without change`() {
        // Given — member with plain YYYY dates
        val members = listOf(
            MusicBrainzBandMember(
                name = "Nate Mendel",
                id = "member-3",
                role = "bass",
                beginDate = "1995",
                endDate = "2010",
                ended = true,
            ),
        )

        // When
        val result = MusicBrainzMapper.toBandMembers(members)

        // Then
        assertEquals("1995-2010", result.members[0].activePeriod)
    }
}
