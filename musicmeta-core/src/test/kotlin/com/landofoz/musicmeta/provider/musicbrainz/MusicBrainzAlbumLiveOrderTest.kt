package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentLogger
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicBrainzAlbumLiveOrderTest {
    @Test
    fun `Encore albums precede the guest-credit single for the supplied artist`() = runTest {
        // Given - the unchanged Encore capture includes Eminem albums and a single with two guest credits.
        val provider = provider("encore")

        // When - the caller supplies Eminem and requests the complete visible pool.
        val found = provider.searchCandidates(EnrichmentRequest.forAlbum("Encore", "Eminem"), 100)

        // Then - the single follows all ten complete official albums.
        assertEncoreAlbumsLead(found)
    }

    @Test
    fun `Encore albums precede the guest-credit single for title only`() = runTest {
        // Given - the same unchanged capture supplies a title-only search without artist hints.
        val provider = provider("encore")

        // When - the caller requests every visible Encore candidate.
        val found = provider.searchCandidates(EnrichmentRequest.forAlbum("Encore", ""), 100)

        // Then - the single follows all ten complete official albums.
        assertEncoreAlbumsLead(found)
    }

    @Test
    fun `Encore albums precede the guest-credit single for a hinted artist`() = runTest {
        // Given - the unchanged capture receives Eminem as its only stubbed artist hint.
        val provider = provider("encore")

        // When - the caller searches by title with the hint.
        val found = provider.searchCandidates(
            EnrichmentRequest.forAlbum("Encore", ""), 100, listOf("Eminem"), EnrichmentLogger.NoOp,
        )

        // Then - the single follows all ten complete official albums.
        assertEncoreAlbumsLead(found)
    }

    @Test
    fun `Ella and Louis preserves the supplied full-credit artist tier`() = runTest {
        // Given - unchanged live credits use both an ampersand and a hyphen for the same artist ids.
        val provider = provider("ellalouis")

        // When - the caller supplies the full ampersand credit and requests one result.
        val found = provider.searchCandidates(EnrichmentRequest.forAlbum("Ella and Louis", "Ella Fitzgerald & Louis Armstrong"), 1)

        // Then - the stronger ampersand match keeps its slot ahead of the lower-tier hyphen credit.
        assertEquals("52486b56-5406-4e57-8a63-ec5b15b8f10b", found.single().identifiers.musicBrainzId)
    }

    @Test
    fun `Ella and Louis preserves the hinted full-credit artist tier`() = runTest {
        // Given - the unchanged live pool receives the full ampersand credit as a stubbed hint.
        val provider = provider("ellalouis")

        // When - the title-only caller requests one result with that hint.
        val found = provider.searchCandidates(
            EnrichmentRequest.forAlbum("Ella and Louis", ""), 1,
            listOf("Ella Fitzgerald & Louis Armstrong"), EnrichmentLogger.NoOp,
        )

        // Then - the stronger ampersand match keeps its slot ahead of the lower-tier hyphen credit.
        assertEquals("52486b56-5406-4e57-8a63-ec5b15b8f10b", found.single().identifiers.musicBrainzId)
    }

    private fun assertEncoreAlbumsLead(found: List<SearchCandidate>) {
        assertEquals(10, found.indexOfFirst { it.identifiers.musicBrainzId == "e8d6a3b0-1378-44f3-a6fa-18fc6a06d661" })
        assertTrue(found.take(10).all { it.releaseType == "Album" })
    }

    private fun provider(name: String): MusicBrainzProvider {
        val raw = requireNotNull(javaClass.getResource("/pools/musicbrainz-album-order/$name.json")).readText()
        return MusicBrainzProvider(FakeHttpClient().apply { givenJsonResponse("release?query", raw) }, RateLimiter(0))
    }
}
