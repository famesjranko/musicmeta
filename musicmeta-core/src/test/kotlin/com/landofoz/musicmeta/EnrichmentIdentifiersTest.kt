package com.landofoz.musicmeta

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnrichmentIdentifiersTest {

    private val json = Json { encodeDefaults = true }

    @Test fun `withExtra stores key-value pair retrievable via get`() {
        // Given - empty identifiers
        val ids = EnrichmentIdentifiers()

        // When - adding an extra identifier
        val updated = ids.withExtra("deezerId", "123")

        // Then - value is retrievable
        assertEquals("123", updated.get("deezerId"))
    }

    @Test fun `get returns null for nonexistent key`() {
        // Given - empty identifiers
        val ids = EnrichmentIdentifiers()

        // When - a nonexistent key is looked up
        // Then - returns null
        assertNull(ids.get("nonexistent"))
    }

    @Test fun `withExtra preserves existing extra entries`() {
        // Given - identifiers with one extra entry
        val ids = EnrichmentIdentifiers().withExtra("deezerId", "123")

        // When - adding another extra entry
        val updated = ids.withExtra("spotifyId", "456")

        // Then - both entries present
        assertEquals("123", updated.get("deezerId"))
        assertEquals("456", updated.get("spotifyId"))
    }

    @Test fun `withExtra preserves existing typed fields`() {
        // Given - identifiers with typed fields set
        val ids = EnrichmentIdentifiers(
            musicBrainzId = "mbid-123",
            wikidataId = "Q456",
            isrc = "USRC123",
        )

        // When - adding extra entry
        val updated = ids.withExtra("deezerId", "789")

        // Then - typed fields unchanged
        assertEquals("mbid-123", updated.musicBrainzId)
        assertEquals("Q456", updated.wikidataId)
        assertEquals("USRC123", updated.isrc)
        assertEquals("789", updated.get("deezerId"))
    }

    @Test fun `SimilarArtist has identifiers field`() {
        // Given - an identifiers value
        // When - a SimilarArtist is created with it
        val artist = SimilarArtist(
            name = "Thom Yorke",
            identifiers = EnrichmentIdentifiers(musicBrainzId = "abc123"),
            matchScore = 0.8f,
        )

        // Then - identifiers accessible
        assertEquals("abc123", artist.identifiers.musicBrainzId)
    }

    @Test fun `PopularTrack has identifiers field`() {
        // Given - an identifiers value
        // When - a PopularTrack is created with it
        val track = PopularTrack(
            title = "Creep",
            identifiers = EnrichmentIdentifiers(musicBrainzId = "track-123"),
            listenCount = 50000L,
            rank = 1,
        )

        // Then - identifiers accessible
        assertEquals("track-123", track.identifiers.musicBrainzId)
    }

    @Test fun `with stores a namespaced identifier retrievable via get`() {
        // Given - empty identifiers
        val ids = EnrichmentIdentifiers()

        // When - adding an identifier through the namespace accessor
        val updated = ids.with(IdentifierNamespace.DEEZER, "123")

        // Then - value is retrievable through the same accessor
        assertEquals("123", updated.get(IdentifierNamespace.DEEZER))
    }

    @Test fun `get by namespace returns null for a namespace never written`() {
        // Given - identifiers carrying an unrelated namespace
        val ids = EnrichmentIdentifiers().with(IdentifierNamespace.DEEZER, "123")

        // When - a different namespace is looked up
        // Then - returns null
        assertNull(ids.get(IdentifierNamespace.DISCOGS_ARTIST))
    }

    @Test fun `namespace accessor and string accessor read the same underlying key`() {
        // Given - identifiers written through the string-keyed accessor
        val ids = EnrichmentIdentifiers().withExtra("deezerId", "789")

        // When - the same key is read through the namespace accessor
        // Then - the value matches, proving the enum key mapping is unchanged
        assertEquals("789", ids.get(IdentifierNamespace.DEEZER))
    }

    // synthetic — a JSON literal shaped like the pre-change wire form, not a live capture.
    private val preChangeJson = """
        {"musicBrainzId":null,"musicBrainzReleaseGroupId":null,"wikidataId":null,
        "isrc":null,"barcode":null,"wikipediaTitle":null,"extra":{"deezerId":"123"}}
    """.trimIndent()

    @Test fun `a pre-change serialized identifiers payload decodes unchanged`() {
        // Given - a pre-change serialized identifiers payload holding an extra deezerId entry
        // When - decoding it with the post-change model and re-encoding the result
        val decoded = json.decodeFromString<EnrichmentIdentifiers>(preChangeJson)
        val reEncoded = json.decodeFromString<EnrichmentIdentifiers>(json.encodeToString(decoded))

        // Then - the round trip is lossless and the enum accessor reads the same stored value
        assertEquals(decoded, reEncoded)
        assertEquals("123", decoded.get(IdentifierNamespace.DEEZER))
        assertEquals("123", decoded.get("deezerId"))
    }
}
