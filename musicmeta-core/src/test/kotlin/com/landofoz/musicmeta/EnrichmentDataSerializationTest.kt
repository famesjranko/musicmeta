package com.landofoz.musicmeta

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class EnrichmentDataSerializationTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun `BandMembers survives round-trip serialization`() {
        // Given
        val original = EnrichmentData.BandMembers(
            members = listOf(
                BandMember(
                    name = "John Lennon",
                    role = "vocals, guitar",
                    activePeriod = "1960-1970",
                ),
                BandMember(
                    name = "Paul McCartney",
                    identifiers = EnrichmentIdentifiers(
                        musicBrainzId = "ba550d0e-adac-4864-b441-d9ricky",
                    ),
                ),
            ),
        )

        // When
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<EnrichmentData.BandMembers>(encoded)

        // Then
        assertEquals(original, decoded)
    }

    @Test
    fun `Discography survives round-trip serialization`() {
        // Given
        val original = EnrichmentData.Discography(
            albums = listOf(
                DiscographyAlbum(
                    title = "Abbey Road",
                    year = "1969",
                    type = "Album",
                    thumbnailUrl = "https://example.com/abbey-road.jpg",
                ),
                DiscographyAlbum(
                    title = "Let It Be",
                    year = "1970",
                    identifiers = EnrichmentIdentifiers(
                        musicBrainzId = "12345",
                    ),
                ),
            ),
        )

        // When
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<EnrichmentData.Discography>(encoded)

        // Then
        assertEquals(original, decoded)
    }

    @Test
    fun `Tracklist survives round-trip serialization`() {
        // Given
        val original = EnrichmentData.Tracklist(
            tracks = listOf(
                TrackInfo(title = "Come Together", position = 1, durationMs = 259000),
                TrackInfo(title = "Something", position = 2, durationMs = 182000),
                TrackInfo(title = "Maxwell's Silver Hammer", position = 3),
            ),
        )

        // When
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<EnrichmentData.Tracklist>(encoded)

        // Then
        assertEquals(original, decoded)
    }

    @Test
    fun `SimilarTracks survives round-trip serialization`() {
        // Given
        val original = EnrichmentData.SimilarTracks(
            tracks = listOf(
                SimilarTrack(
                    title = "Imagine",
                    artist = "John Lennon",
                    matchScore = 0.95f,
                ),
                SimilarTrack(
                    title = "Hey Jude",
                    artist = "The Beatles",
                    matchScore = 0.88f,
                    identifiers = EnrichmentIdentifiers(
                        musicBrainzId = "abc-123",
                    ),
                ),
            ),
        )

        // When
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<EnrichmentData.SimilarTracks>(encoded)

        // Then
        assertEquals(original, decoded)
    }

    @Test
    fun `ArtistLinks survives round-trip serialization`() {
        // Given
        val original = EnrichmentData.ArtistLinks(
            links = listOf(
                ExternalLink(type = "official", url = "https://thebeatles.com"),
                ExternalLink(
                    type = "social",
                    url = "https://twitter.com/thebeatles",
                    label = "Twitter",
                ),
                ExternalLink(type = "streaming", url = "https://spotify.com/artist/123"),
            ),
        )

        // When
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<EnrichmentData.ArtistLinks>(encoded)

        // Then
        assertEquals(original, decoded)
    }

    @Test
    fun `ALBUM_ART_BACK is a valid EnrichmentType with 90-day TTL`() {
        // Given -- the new ALBUM_ART_BACK type
        val type = EnrichmentType.ALBUM_ART_BACK

        // Then -- it exists and has 90-day TTL
        assertEquals(90L * 24 * 60 * 60 * 1000, type.defaultTtlMs)
    }

    @Test
    fun `ALBUM_BOOKLET is a valid EnrichmentType with 90-day TTL`() {
        // Given -- the new ALBUM_BOOKLET type
        val type = EnrichmentType.ALBUM_BOOKLET

        // Then -- it exists and has 90-day TTL
        assertEquals(90L * 24 * 60 * 60 * 1000, type.defaultTtlMs)
    }

    @Test
    fun `GenreTag round-trip serialization works`() {
        // Given
        val tag = GenreTag(
            name = "Alternative Rock",
            confidence = 0.85f,
            sources = listOf("musicbrainz", "lastfm"),
        )

        // When
        val encoded = json.encodeToString(tag)
        val decoded = json.decodeFromString<GenreTag>(encoded)

        // Then
        assertEquals(tag, decoded)
    }

    @Test
    fun `Metadata with genreTags survives round-trip serialization`() {
        // Given
        val original = EnrichmentData.Metadata(
            genres = listOf("rock", "alternative"),
            genreTags = listOf(
                GenreTag(name = "Rock", confidence = 0.9f, sources = listOf("musicbrainz")),
                GenreTag(name = "Alternative", confidence = 0.7f),
            ),
        )

        // When
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<EnrichmentData.Metadata>(encoded)

        // Then
        assertEquals(original, decoded)
    }

    @Test
    fun `Artwork with sizes survives round-trip serialization`() {
        // Given
        val original = EnrichmentData.Artwork(
            url = "https://example.com/art.jpg",
            width = 500,
            height = 500,
            thumbnailUrl = "https://example.com/art-thumb.jpg",
            sizes = listOf(
                ArtworkSize(
                    url = "https://example.com/art-250.jpg",
                    width = 250,
                    height = 250,
                    label = "small",
                ),
                ArtworkSize(
                    url = "https://example.com/art-1200.jpg",
                    width = 1200,
                    height = 1200,
                    label = "large",
                ),
            ),
        )

        // When
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<EnrichmentData.Artwork>(encoded)

        // Then
        assertEquals(original, decoded)
    }

    @Test
    fun `ARTIST_RADIO is a valid EnrichmentType with 7-day TTL`() {
        // Given -- the ARTIST_RADIO type
        val type = EnrichmentType.ARTIST_RADIO

        // Then -- it exists and has 7-day TTL
        assertEquals(7L * 24 * 60 * 60 * 1000, type.defaultTtlMs)
    }

    @Test
    fun `RadioPlaylist survives round-trip serialization`() {
        // Given
        val original = EnrichmentData.RadioPlaylist(
            tracks = listOf(
                RadioTrack(
                    title = "Creep",
                    artist = "Radiohead",
                    album = "Pablo Honey",
                    durationMs = 238000L,
                    identifiers = EnrichmentIdentifiers().withExtra("deezerId", "123"),
                ),
                RadioTrack(
                    title = "Karma Police",
                    artist = "Radiohead",
                ),
            ),
        )

        // When
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<EnrichmentData.RadioPlaylist>(encoded)

        // Then
        assertEquals(original, decoded)
    }
}
