package com.landofoz.musicmeta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnrichmentIdentifiersTest {

    @Test fun `withExtra stores key-value pair retrievable via get`() {
        // Given — empty identifiers
        val ids = EnrichmentIdentifiers()

        // When — adding an extra identifier
        val updated = ids.withExtra("deezerId", "123")

        // Then — value is retrievable
        assertEquals("123", updated.get("deezerId"))
    }

    @Test fun `get returns null for nonexistent key`() {
        // Given — empty identifiers
        val ids = EnrichmentIdentifiers()

        // When / Then — nonexistent key returns null
        assertNull(ids.get("nonexistent"))
    }

    @Test fun `withExtra preserves existing extra entries`() {
        // Given — identifiers with one extra entry
        val ids = EnrichmentIdentifiers().withExtra("deezerId", "123")

        // When — adding another extra entry
        val updated = ids.withExtra("spotifyId", "456")

        // Then — both entries present
        assertEquals("123", updated.get("deezerId"))
        assertEquals("456", updated.get("spotifyId"))
    }

    @Test fun `withExtra preserves existing typed fields`() {
        // Given — identifiers with typed fields set
        val ids = EnrichmentIdentifiers(
            musicBrainzId = "mbid-123",
            wikidataId = "Q456",
            isrc = "USRC123",
        )

        // When — adding extra entry
        val updated = ids.withExtra("deezerId", "789")

        // Then — typed fields unchanged
        assertEquals("mbid-123", updated.musicBrainzId)
        assertEquals("Q456", updated.wikidataId)
        assertEquals("USRC123", updated.isrc)
        assertEquals("789", updated.get("deezerId"))
    }

    @Test fun `SimilarArtist has identifiers field`() {
        // Given / When — creating a SimilarArtist with identifiers
        val artist = SimilarArtist(
            name = "Thom Yorke",
            identifiers = EnrichmentIdentifiers(musicBrainzId = "abc123"),
            matchScore = 0.8f,
        )

        // Then — identifiers accessible
        assertEquals("abc123", artist.identifiers.musicBrainzId)
    }

    @Test fun `PopularTrack has identifiers field`() {
        // Given / When — creating a PopularTrack with identifiers
        val track = PopularTrack(
            title = "Creep",
            identifiers = EnrichmentIdentifiers(musicBrainzId = "track-123"),
            listenCount = 50000L,
            rank = 1,
        )

        // Then — identifiers accessible
        assertEquals("track-123", track.identifiers.musicBrainzId)
    }
}
