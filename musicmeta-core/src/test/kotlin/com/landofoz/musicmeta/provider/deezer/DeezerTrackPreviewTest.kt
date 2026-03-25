package com.landofoz.musicmeta.provider.deezer

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeezerTrackPreviewTest {

    private val httpClient = FakeHttpClient()
    private val provider = DeezerProvider(httpClient, RateLimiter(0))

    // --- DeezerMapper.toTrackPreview tests ---

    @Test
    fun `toTrackPreview returns TrackPreview with url durationMs and source when previewUrl is present`() {
        // Given — a DeezerTrackSearchResult with all preview fields populated
        val result = DeezerTrackSearchResult(
            id = 789L,
            title = "Karma Police",
            artistName = "Radiohead",
            previewUrl = "https://cdns-preview.dzcdn.net/stream/c-abc123-1.mp3",
            durationSec = 30,
            albumTitle = "OK Computer",
        )

        // When — mapping to TrackPreview
        val preview = DeezerMapper.toTrackPreview(result)

        // Then — all fields are correctly mapped
        assertNotNull(preview)
        assertEquals("https://cdns-preview.dzcdn.net/stream/c-abc123-1.mp3", preview!!.url)
        assertEquals(30000L, preview.durationMs)
        assertEquals("deezer", preview.source)
    }

    @Test
    fun `toTrackPreview returns null when previewUrl is null`() {
        // Given — a DeezerTrackSearchResult with no preview URL
        val result = DeezerTrackSearchResult(
            id = 789L,
            title = "Karma Police",
            artistName = "Radiohead",
            previewUrl = null,
        )

        // When — attempting to map
        val preview = DeezerMapper.toTrackPreview(result)

        // Then — null because no preview URL available
        assertNull(preview)
    }

    @Test
    fun `toTrackPreview returns TrackPreview with durationMs 30000 when durationSec is null`() {
        // Given — a DeezerTrackSearchResult with preview URL but no duration
        val result = DeezerTrackSearchResult(
            id = 789L,
            title = "Karma Police",
            artistName = "Radiohead",
            previewUrl = "https://cdns-preview.dzcdn.net/stream/c-abc123-1.mp3",
            durationSec = null,
        )

        // When — mapping to TrackPreview
        val preview = DeezerMapper.toTrackPreview(result)

        // Then — default durationMs of 30000 is used (Deezer previews are always 30 seconds)
        assertNotNull(preview)
        assertEquals(30000L, preview!!.durationMs)
    }

    // --- DeezerProvider.enrichTrackPreview tests ---

    @Test
    fun `enrich returns Success with TrackPreview when Deezer has matching track with preview`() = runTest {
        // Given — Deezer returns a matching track search result with a preview URL
        httpClient.givenJsonResponse("search/track", TRACK_SEARCH_WITH_PREVIEW_RESPONSE)
        val request = EnrichmentRequest.forTrack("Karma Police", "Radiohead")

        // When — enriching for track preview
        val result = provider.enrich(request, EnrichmentType.TRACK_PREVIEW)

        // Then — success with TrackPreview data
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.TrackPreview
        assertEquals("https://cdns-preview.dzcdn.net/stream/c-abc123-1.mp3", data.url)
        assertEquals(30000L, data.durationMs)
        assertEquals("deezer", data.source)
    }

    @Test
    fun `enrich returns NotFound for TRACK_PREVIEW when no search result`() = runTest {
        // Given — Deezer returns no track search results
        httpClient.givenJsonResponse("search/track", """{"data":[]}""")
        val request = EnrichmentRequest.forTrack("Nonexistent", "Nobody")

        // When — enriching for track preview
        val result = provider.enrich(request, EnrichmentType.TRACK_PREVIEW)

        // Then — NotFound because no track matched
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound for TRACK_PREVIEW when artist does not match`() = runTest {
        // Given — Deezer returns a track from a different artist
        httpClient.givenJsonResponse("search/track", """{"data":[{
            "id":999,"title":"Karma Police",
            "artist":{"name":"Completely Different Band"},
            "preview":"https://cdns-preview.dzcdn.net/stream/preview.mp3",
            "duration":30
        }]}""")
        val request = EnrichmentRequest.forTrack("Karma Police", "Radiohead")

        // When — enriching for track preview
        val result = provider.enrich(request, EnrichmentType.TRACK_PREVIEW)

        // Then — NotFound because ArtistMatcher.isMatch() rejects the result
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound for TRACK_PREVIEW when previewUrl is blank or null`() = runTest {
        // Given — Deezer returns a matching track but with no preview URL
        httpClient.givenJsonResponse("search/track", """{"data":[{
            "id":789,"title":"Karma Police",
            "artist":{"name":"Radiohead"},
            "preview":"",
            "duration":0
        }]}""")
        val request = EnrichmentRequest.forTrack("Karma Police", "Radiohead")

        // When — enriching for track preview
        val result = provider.enrich(request, EnrichmentType.TRACK_PREVIEW)

        // Then — NotFound because previewUrl is blank
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound for TRACK_PREVIEW on ForArtist request`() = runTest {
        // Given — a ForArtist request (TRACK_PREVIEW requires ForTrack)
        val request = EnrichmentRequest.forArtist("Radiohead")

        // When — enriching for track preview
        val result = provider.enrich(request, EnrichmentType.TRACK_PREVIEW)

        // Then — NotFound because TRACK_PREVIEW requires ForTrack
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `capabilities includes TRACK_PREVIEW with priority 100`() {
        // Given — the DeezerProvider
        // When — checking capabilities
        val capability = provider.capabilities.find { it.type == EnrichmentType.TRACK_PREVIEW }

        // Then — TRACK_PREVIEW capability exists with priority 100
        assertNotNull(capability)
        assertEquals(100, capability!!.priority)
    }

    companion object {
        val TRACK_SEARCH_WITH_PREVIEW_RESPONSE = """
            {"data":[{
                "id":789,
                "title":"Karma Police",
                "artist":{"name":"Radiohead"},
                "album":{"title":"OK Computer"},
                "preview":"https://cdns-preview.dzcdn.net/stream/c-abc123-1.mp3",
                "duration":30
            }]}
        """.trimIndent()
    }
}
