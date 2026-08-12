package com.landofoz.musicmeta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the identifier-only factories build, for a caller who has an MBID and no name. */
class EnrichmentRequestMbidFactoryTest {

    @Test
    fun `a track by mbid carries the identifier and leaves the names for resolution to fill`() {
        // Given - a recording identifier and no title or artist
        val mbid = "6f903161-8757-4363-a6ab-54bfe1149bb8"

        // When - the request is built from it alone
        val request = EnrichmentRequest.forTrackByMbid(mbid)

        // Then - the identifier is the typed MusicBrainz one, and both names are blank
        assertEquals(mbid, request.identifiers.musicBrainzId)
        assertTrue(request.title.isBlank())
        assertTrue(request.artist.isBlank())
    }

    @Test
    fun `an album by mbid keeps the other identifiers it was handed`() {
        // Given - identifiers a caller already holds for the same album
        val existing = EnrichmentIdentifiers(barcode = "4988006846395")

        // When - the request is built from a release identifier and those
        val request = EnrichmentRequest.forAlbumByMbid("4ebc64a2-e9cc-43f8-96ac-536212c4c8d4", existing)

        // Then - neither displaces the other
        assertEquals("4ebc64a2-e9cc-43f8-96ac-536212c4c8d4", request.identifiers.musicBrainzId)
        assertEquals("4988006846395", request.identifiers.barcode)
    }

    @Test
    fun `an artist by mbid is an artist request with no name`() {
        // Given - an artist identifier
        val mbid = "0383dadf-2a4e-4d10-a46a-e9e041da8eb3"

        // When - the request is built from it alone
        val request = EnrichmentRequest.forArtistByMbid(mbid)

        // Then - it names the identifier and nothing else
        assertEquals(mbid, request.identifiers.musicBrainzId)
        assertTrue(request.name.isBlank())
    }
}
