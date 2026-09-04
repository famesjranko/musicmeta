package com.landofoz.musicmeta

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins each namespace's wire key. `extra` is serialized into consumers' caches under these
 * literals, so changing one makes a stored payload unreadable through its namespace.
 */
class IdentifierNamespaceTest {

    private val json = Json

    // synthetic — JSON literals shaped like a stored payload, not live captures.
    private fun storedPayload(key: String, value: String) = """
        {"musicBrainzId":null,"musicBrainzReleaseGroupId":null,"wikidataId":null,
        "isrc":null,"barcode":null,"wikipediaTitle":null,"extra":{"$key":"$value"}}
    """.trimIndent()

    @Test fun `DISCOGS_RELEASE keeps the wire key the Discogs mapper writes`() {
        // Given - the literal key stored in cached payloads
        val stored = "discogsReleaseId"

        // When - the namespace's key is read
        // Then - it is that literal
        assertEquals(stored, IdentifierNamespace.DISCOGS_RELEASE.key)
    }

    @Test fun `DISCOGS_MASTER keeps the wire key the Discogs provider writes`() {
        // Given - the literal key stored in cached payloads
        val stored = "discogsMasterId"

        // When - the namespace's key is read
        // Then - it is that literal
        assertEquals(stored, IdentifierNamespace.DISCOGS_MASTER.key)
    }

    @Test fun `ITUNES_COLLECTION keeps the wire key the iTunes mapper writes`() {
        // Given - the literal key stored in cached payloads
        val stored = "itunesCollectionId"

        // When - the namespace's key is read
        // Then - it is that literal
        assertEquals(stored, IdentifierNamespace.ITUNES_COLLECTION.key)
    }

    @Test fun `a stored payload keyed discogsReleaseId reads back through DISCOGS_RELEASE`() {
        // Given - a serialized payload written before the namespace existed
        val payload = storedPayload("discogsReleaseId", "99001")

        // When - it is decoded and read through the namespace
        val decoded = json.decodeFromString<EnrichmentIdentifiers>(payload)

        // Then - the stored value is still reachable
        assertEquals("99001", decoded.get(IdentifierNamespace.DISCOGS_RELEASE))
    }

    @Test fun `a stored payload keyed discogsMasterId reads back through DISCOGS_MASTER`() {
        // Given - a serialized payload written before the namespace existed
        val payload = storedPayload("discogsMasterId", "55002")

        // When - it is decoded and read through the namespace
        val decoded = json.decodeFromString<EnrichmentIdentifiers>(payload)

        // Then - the stored value is still reachable
        assertEquals("55002", decoded.get(IdentifierNamespace.DISCOGS_MASTER))
    }

    @Test fun `a stored payload keyed itunesCollectionId reads back through ITUNES_COLLECTION`() {
        // Given - a serialized payload written before the namespace existed
        val payload = storedPayload("itunesCollectionId", "203558498")

        // When - it is decoded and read through the namespace
        val decoded = json.decodeFromString<EnrichmentIdentifiers>(payload)

        // Then - the stored value is still reachable
        assertEquals("203558498", decoded.get(IdentifierNamespace.ITUNES_COLLECTION))
    }
}
