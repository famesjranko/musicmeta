package com.landofoz.musicmeta

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class EnrichmentDataSerializationTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun `BandMembers survives round-trip serialization`() {
        // Given - a BandMembers with members, one carrying identifiers
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

        // When - encoding then decoding
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<EnrichmentData.BandMembers>(encoded)

        // Then - the decoded value equals the original
        assertEquals(original, decoded)
    }

    @Test
    fun `Discography survives round-trip serialization`() {
        // Given - a Discography with albums, one carrying identifiers
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

        // When - encoding then decoding
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<EnrichmentData.Discography>(encoded)

        // Then - the decoded value equals the original
        assertEquals(original, decoded)
    }

    @Test
    fun `Tracklist survives round-trip serialization`() {
        // Given - a Tracklist with several tracks
        val original = EnrichmentData.Tracklist(
            tracks = listOf(
                TrackInfo(title = "Come Together", position = 1, durationMs = 259000),
                TrackInfo(title = "Something", position = 2, durationMs = 182000),
                TrackInfo(title = "Maxwell's Silver Hammer", position = 3),
            ),
        )

        // When - encoding then decoding
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<EnrichmentData.Tracklist>(encoded)

        // Then - the decoded value equals the original
        assertEquals(original, decoded)
    }

    @Test
    fun `SimilarTracks survives round-trip serialization`() {
        // Given - a SimilarTracks with tracks, one carrying identifiers
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

        // When - encoding then decoding
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<EnrichmentData.SimilarTracks>(encoded)

        // Then - the decoded value equals the original
        assertEquals(original, decoded)
    }

    @Test
    fun `ArtistLinks survives round-trip serialization`() {
        // Given - an ArtistLinks with several external links
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

        // When - encoding then decoding
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<EnrichmentData.ArtistLinks>(encoded)

        // Then - the decoded value equals the original
        assertEquals(original, decoded)
    }

    @Test
    fun `ALBUM_ART_BACK is a valid EnrichmentType with 90-day TTL`() {
        // Given - the new ALBUM_ART_BACK type
        val type = EnrichmentType.ALBUM_ART_BACK

        // Then - it exists and has 90-day TTL
        assertEquals(90L * 24 * 60 * 60 * 1000, type.defaultTtlMs)
    }

    @Test
    fun `ALBUM_BOOKLET is a valid EnrichmentType with 90-day TTL`() {
        // Given - the new ALBUM_BOOKLET type
        val type = EnrichmentType.ALBUM_BOOKLET

        // Then - it exists and has 90-day TTL
        assertEquals(90L * 24 * 60 * 60 * 1000, type.defaultTtlMs)
    }

    @Test
    fun `GenreTag round-trip serialization works`() {
        // Given - a GenreTag with confidence and sources
        val tag = GenreTag(
            name = "Alternative Rock",
            confidence = 0.85f,
            sources = listOf("musicbrainz", "lastfm"),
        )

        // When - encoding then decoding
        val encoded = json.encodeToString(tag)
        val decoded = json.decodeFromString<GenreTag>(encoded)

        // Then - the decoded value equals the original
        assertEquals(tag, decoded)
    }

    @Test
    fun `Metadata with genreTags survives round-trip serialization`() {
        // Given - a Metadata with genres and genreTags
        val original = EnrichmentData.Metadata(
            genres = listOf("rock", "alternative"),
            genreTags = listOf(
                GenreTag(name = "Rock", confidence = 0.9f, sources = listOf("musicbrainz")),
                GenreTag(name = "Alternative", confidence = 0.7f),
            ),
        )

        // When - encoding then decoding
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<EnrichmentData.Metadata>(encoded)

        // Then - the decoded value equals the original
        assertEquals(original, decoded)
    }

    @Test
    fun `Artwork with sizes survives round-trip serialization`() {
        // Given - an Artwork with a list of alternate sizes
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

        // When - encoding then decoding
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<EnrichmentData.Artwork>(encoded)

        // Then - the decoded value equals the original
        assertEquals(original, decoded)
    }

    @Test
    fun `ARTIST_RADIO is a valid EnrichmentType with 7-day TTL`() {
        // Given - the ARTIST_RADIO type
        val type = EnrichmentType.ARTIST_RADIO

        // Then - it exists and has 7-day TTL
        assertEquals(7L * 24 * 60 * 60 * 1000, type.defaultTtlMs)
    }

    @Test
    fun `RadioPlaylist survives round-trip serialization`() {
        // Given - a RadioPlaylist with tracks, one carrying extra identifiers
        val original = EnrichmentData.RadioPlaylist(
            tracks = listOf(
                RadioTrack(
                    title = "Creep",
                    artist = "Radiohead",
                    album = "Pablo Honey",
                    durationMs = 238000L,
                    identifiers = EnrichmentIdentifiers().with(IdentifierNamespace.DEEZER, "123"),
                ),
                RadioTrack(
                    title = "Karma Police",
                    artist = "Radiohead",
                ),
            ),
        )

        // When - encoding then decoding
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<EnrichmentData.RadioPlaylist>(encoded)

        // Then - the decoded value equals the original
        assertEquals(original, decoded)
    }

    // synthetic - a JSON literal shaped like the pre-signals wire form, not a live capture.
    private val preSignalsPopularityJson = """
        {"listenCount":900,"listenerCount":42,"rank":3}
    """.trimIndent()

    @Test
    fun `a popularity payload cached before signals existed still decodes`() {
        // Given - a serialized Popularity written by a build with no signals field
        val encoded = preSignalsPopularityJson

        // When - decoding it with the post-change model
        val decoded = json.decodeFromString<EnrichmentData.Popularity>(encoded)

        // Then - the flat fields survive and signals defaults to empty rather than failing to parse
        assertEquals(900L, decoded.listenCount)
        assertEquals(42L, decoded.listenerCount)
        assertEquals(3, decoded.rank)
        assertEquals(emptyList<PopularitySignal>(), decoded.signals)
    }

    @Test
    fun `Popularity with signals survives round-trip serialization`() {
        // Given - a Popularity carrying one signal per source, in three different units
        val original = EnrichmentData.Popularity(
            listenCount = 900,
            signals = listOf(
                PopularitySignal("listenbrainz", PopularitySignalKind.LISTEN_COUNT, 900.0),
                PopularitySignal("musicbrainz", PopularitySignalKind.RATING, 4.05, 0.7625f, 49),
            ),
        )

        // When - encoding then decoding
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<EnrichmentData.Popularity>(encoded)

        // Then - the decoded value equals the original
        assertEquals(original, decoded)
    }
}
