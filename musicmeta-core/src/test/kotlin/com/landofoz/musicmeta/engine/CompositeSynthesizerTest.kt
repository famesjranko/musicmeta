package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.BandMember
import com.landofoz.musicmeta.DiscographyAlbum
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositeSynthesizerTest {

    @Test
    fun `TimelineSynthesizer type is ARTIST_TIMELINE`() {
        // Given / When / Then
        assertEquals(EnrichmentType.ARTIST_TIMELINE, TimelineSynthesizer.type)
    }

    @Test
    fun `TimelineSynthesizer dependencies contains ARTIST_DISCOGRAPHY and BAND_MEMBERS`() {
        // Given / When
        val deps = TimelineSynthesizer.dependencies

        // Then
        assertTrue(EnrichmentType.ARTIST_DISCOGRAPHY in deps)
        assertTrue(EnrichmentType.BAND_MEMBERS in deps)
    }

    @Test
    fun `TimelineSynthesizer synthesize with ForArtist request returns Success with ArtistTimeline`() {
        // Given
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(),
            name = "Radiohead",
        )
        val discography = EnrichmentResult.Success(
            type = EnrichmentType.ARTIST_DISCOGRAPHY,
            data = EnrichmentData.Discography(
                albums = listOf(DiscographyAlbum(title = "Pablo Honey", year = "1993")),
            ),
            provider = "musicbrainz",
            confidence = 1.0f,
        )
        val resolved = mapOf(
            EnrichmentType.ARTIST_DISCOGRAPHY to discography,
        )

        // When
        val result = TimelineSynthesizer.synthesize(resolved, null, request)

        // Then
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertEquals(EnrichmentType.ARTIST_TIMELINE, success.type)
        assertTrue(success.data is EnrichmentData.ArtistTimeline)
    }

    @Test
    fun `TimelineSynthesizer synthesize with ForAlbum request returns NotFound artist_only`() {
        // Given
        val request = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers(),
            title = "OK Computer",
            artist = "Radiohead",
        )

        // When
        val result = TimelineSynthesizer.synthesize(emptyMap(), null, request)

        // Then
        assertTrue(result is EnrichmentResult.NotFound)
        val notFound = result as EnrichmentResult.NotFound
        assertEquals("artist_only", notFound.provider)
    }

    @Test
    fun `TimelineSynthesizer synthesize with ForArtist and band members produces member events`() {
        // Given
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(),
            name = "Radiohead",
        )
        val members = EnrichmentResult.Success(
            type = EnrichmentType.BAND_MEMBERS,
            data = EnrichmentData.BandMembers(
                members = listOf(BandMember(name = "Thom Yorke", activePeriod = "1985-present")),
            ),
            provider = "musicbrainz",
            confidence = 1.0f,
        )
        val resolved = mapOf(EnrichmentType.BAND_MEMBERS to members)

        // When
        val result = TimelineSynthesizer.synthesize(resolved, null, request) as EnrichmentResult.Success
        val timeline = result.data as EnrichmentData.ArtistTimeline

        // Then
        assertTrue(timeline.events.any { it.type == "member_joined" && it.relatedEntity == "Thom Yorke" })
    }
}
