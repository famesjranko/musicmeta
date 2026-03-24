package com.landofoz.musicmeta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrichmentResultsTest {

    private fun success(type: EnrichmentType, data: EnrichmentData, provider: String = "test") =
        EnrichmentResult.Success(type, data, provider, 0.9f)

    @Test fun `get returns typed data for matching type`() {
        // Given
        val artwork = EnrichmentData.Artwork(url = "https://example.com/art.jpg")
        val results = EnrichmentResults(
            raw = mapOf(EnrichmentType.ALBUM_ART to success(EnrichmentType.ALBUM_ART, artwork)),
            requestedTypes = setOf(EnrichmentType.ALBUM_ART),
            identity = null,
        )

        // When
        val retrieved = results.get<EnrichmentData.Artwork>(EnrichmentType.ALBUM_ART)

        // Then
        assertNotNull(retrieved)
        assertEquals("https://example.com/art.jpg", retrieved!!.url)
    }

    @Test fun `get returns null for wrong data type`() {
        // Given
        val metadata = EnrichmentData.Metadata(genres = listOf("rock"))
        val results = EnrichmentResults(
            raw = mapOf(EnrichmentType.GENRE to success(EnrichmentType.GENRE, metadata)),
            requestedTypes = setOf(EnrichmentType.GENRE),
            identity = null,
        )

        // When
        val retrieved = results.get<EnrichmentData.Artwork>(EnrichmentType.GENRE)

        // Then
        assertNull(retrieved)
    }

    @Test fun `get returns null for NotFound result`() {
        // Given
        val results = EnrichmentResults(
            raw = mapOf(EnrichmentType.ALBUM_ART to EnrichmentResult.NotFound(EnrichmentType.ALBUM_ART, "test")),
            requestedTypes = setOf(EnrichmentType.ALBUM_ART),
            identity = null,
        )

        // When
        val retrieved = results.get<EnrichmentData.Artwork>(EnrichmentType.ALBUM_ART)

        // Then
        assertNull(retrieved)
    }

    @Test fun `wasRequested distinguishes requested from unrequested types`() {
        val results = EnrichmentResults(
            raw = emptyMap(),
            requestedTypes = setOf(EnrichmentType.GENRE, EnrichmentType.ALBUM_ART),
            identity = null,
        )

        assertTrue(results.wasRequested(EnrichmentType.GENRE))
        assertTrue(results.wasRequested(EnrichmentType.ALBUM_ART))
        assertFalse(results.wasRequested(EnrichmentType.ARTIST_BIO))
    }

    @Test fun `named accessors return typed data`() {
        // Given
        val artwork = EnrichmentData.Artwork(url = "https://example.com/photo.jpg")
        val bio = EnrichmentData.Biography(text = "A band", source = "wikipedia", language = "en")
        val results = EnrichmentResults(
            raw = mapOf(
                EnrichmentType.ARTIST_PHOTO to success(EnrichmentType.ARTIST_PHOTO, artwork),
                EnrichmentType.ARTIST_BIO to success(EnrichmentType.ARTIST_BIO, bio),
            ),
            requestedTypes = setOf(EnrichmentType.ARTIST_PHOTO, EnrichmentType.ARTIST_BIO),
            identity = null,
        )

        // Then
        assertEquals("https://example.com/photo.jpg", results.artistPhoto()?.url)
        assertEquals("A band", results.biography()?.text)
    }

    @Test fun `lyrics accessor prefers synced over plain`() {
        // Given — both synced and plain available
        val synced = EnrichmentData.Lyrics(syncedLyrics = "[00:01]Hello", plainLyrics = "Hello")
        val plain = EnrichmentData.Lyrics(plainLyrics = "Hello plain")
        val results = EnrichmentResults(
            raw = mapOf(
                EnrichmentType.LYRICS_SYNCED to success(EnrichmentType.LYRICS_SYNCED, synced),
                EnrichmentType.LYRICS_PLAIN to success(EnrichmentType.LYRICS_PLAIN, plain),
            ),
            requestedTypes = setOf(EnrichmentType.LYRICS_SYNCED, EnrichmentType.LYRICS_PLAIN),
            identity = null,
        )

        // Then — synced wins
        assertEquals("[00:01]Hello", results.lyrics()?.syncedLyrics)
    }

    @Test fun `lyrics accessor falls back to plain when synced not found`() {
        // Given — only plain available
        val plain = EnrichmentData.Lyrics(plainLyrics = "Hello plain")
        val results = EnrichmentResults(
            raw = mapOf(
                EnrichmentType.LYRICS_SYNCED to EnrichmentResult.NotFound(EnrichmentType.LYRICS_SYNCED, "lrclib"),
                EnrichmentType.LYRICS_PLAIN to success(EnrichmentType.LYRICS_PLAIN, plain),
            ),
            requestedTypes = setOf(EnrichmentType.LYRICS_SYNCED, EnrichmentType.LYRICS_PLAIN),
            identity = null,
        )

        // Then — falls back to plain
        assertEquals("Hello plain", results.lyrics()?.plainLyrics)
    }

    @Test fun `genre accessor falls back to ALBUM_METADATA`() {
        // Given — GENRE not requested, but ALBUM_METADATA has genre data
        val metadata = EnrichmentData.Metadata(
            genres = listOf("rock", "alternative"),
            genreTags = listOf(GenreTag("rock", 0.9f), GenreTag("alternative", 0.7f)),
        )
        val results = EnrichmentResults(
            raw = mapOf(
                EnrichmentType.ALBUM_METADATA to success(EnrichmentType.ALBUM_METADATA, metadata),
            ),
            requestedTypes = setOf(EnrichmentType.ALBUM_METADATA),
            identity = null,
        )

        // Then — falls back to ALBUM_METADATA
        assertEquals(listOf("rock", "alternative"), results.genres())
        assertEquals(2, results.genreTags().size)
        assertEquals("rock", results.genreTags()[0].name)
    }

    @Test fun `genre accessor prefers dedicated GENRE over ALBUM_METADATA`() {
        // Given — both GENRE (merged, 4 tags) and ALBUM_METADATA (1 tag) present
        val genreResult = EnrichmentData.Metadata(
            genreTags = listOf(GenreTag("rock", 0.9f), GenreTag("art rock", 0.7f), GenreTag("alt", 0.6f)),
            genres = listOf("rock", "art rock", "alt"),
        )
        val albumMeta = EnrichmentData.Metadata(genreTags = listOf(GenreTag("rock", 0.4f)), genres = listOf("rock"))
        val results = EnrichmentResults(
            raw = mapOf(
                EnrichmentType.GENRE to success(EnrichmentType.GENRE, genreResult),
                EnrichmentType.ALBUM_METADATA to success(EnrichmentType.ALBUM_METADATA, albumMeta),
            ),
            requestedTypes = setOf(EnrichmentType.GENRE, EnrichmentType.ALBUM_METADATA),
            identity = null,
        )

        // Then — dedicated GENRE wins
        assertEquals(3, results.genreTags().size)
    }

    @Test fun `label accessor falls back to ALBUM_METADATA`() {
        val metadata = EnrichmentData.Metadata(label = "Island Records")
        val results = EnrichmentResults(
            raw = mapOf(EnrichmentType.ALBUM_METADATA to success(EnrichmentType.ALBUM_METADATA, metadata)),
            requestedTypes = setOf(EnrichmentType.ALBUM_METADATA),
            identity = null,
        )

        assertEquals("Island Records", results.label())
    }

    @Test fun `metadata accessors return null when no data`() {
        val results = EnrichmentResults(raw = emptyMap(), requestedTypes = emptySet(), identity = null)

        assertEquals(emptyList<String>(), results.genres())
        assertEquals(emptyList<GenreTag>(), results.genreTags())
        assertNull(results.label())
        assertNull(results.releaseDate())
        assertNull(results.releaseType())
        assertNull(results.country())
    }

    @Test fun `identity resolution is accessible`() {
        val ids = EnrichmentIdentifiers(musicBrainzId = "abc-123", wikidataId = "Q123")
        val identity = IdentityResolution(
            identifiers = ids,
            match = IdentityMatch.RESOLVED,
            matchScore = 95,
        )
        val results = EnrichmentResults(raw = emptyMap(), requestedTypes = emptySet(), identity = identity)

        assertEquals(IdentityMatch.RESOLVED, results.identity?.match)
        assertEquals(95, results.identity?.matchScore)
        assertEquals("abc-123", results.identity?.identifiers?.musicBrainzId)
    }

    @Test fun `similarTracks accessor returns typed data`() {
        // Given — results containing two similar tracks
        val tracks = EnrichmentData.SimilarTracks(listOf(
            SimilarTrack("Lucky", "Radiohead", 0.9f),
            SimilarTrack("Karma Police", "Radiohead", 0.85f),
        ))
        val results = EnrichmentResults(
            raw = mapOf(EnrichmentType.SIMILAR_TRACKS to success(EnrichmentType.SIMILAR_TRACKS, tracks)),
            requestedTypes = setOf(EnrichmentType.SIMILAR_TRACKS),
            identity = null,
        )

        // When — accessing via the named accessor
        val similar = results.similarTracks()

        // Then — returns the unwrapped SimilarTracks data
        assertEquals(2, similar?.tracks?.size)
        assertEquals("Lucky", similar?.tracks?.get(0)?.title)
    }

    @Test fun `result accessor returns raw EnrichmentResult for diagnostics`() {
        // Given — a rate-limited result
        val rateLimited = EnrichmentResult.RateLimited(EnrichmentType.ARTIST_BIO, "lastfm")
        val results = EnrichmentResults(
            raw = mapOf(EnrichmentType.ARTIST_BIO to rateLimited),
            requestedTypes = setOf(EnrichmentType.ARTIST_BIO),
            identity = null,
        )

        // Then — can check the raw result for error diagnostics
        val rawResult = results.result(EnrichmentType.ARTIST_BIO)
        assertTrue(rawResult is EnrichmentResult.RateLimited)
    }
}
