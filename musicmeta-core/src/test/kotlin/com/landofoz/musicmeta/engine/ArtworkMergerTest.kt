package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.ArtworkSize
import com.landofoz.musicmeta.ArtworkSource
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import org.junit.Assert.*
import org.junit.Test

class ArtworkMergerTest {

    private val merger = ArtworkMerger(EnrichmentType.ARTIST_PHOTO)

    private fun artwork(
        provider: String,
        url: String,
        confidence: Float = 0.9f,
        thumbnailUrl: String? = null,
        sizes: List<ArtworkSize>? = null,
        identifiers: EnrichmentIdentifiers? = null,
    ) = EnrichmentResult.Success(
        type = EnrichmentType.ARTIST_PHOTO,
        data = EnrichmentData.Artwork(url = url, thumbnailUrl = thumbnailUrl, sizes = sizes),
        provider = provider,
        confidence = confidence,
        resolvedIdentifiers = identifiers,
    )

    @Test fun `empty input returns NotFound`() {
        // Given — no results
        val result = merger.merge(emptyList())

        // Then
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test fun `single provider returns result with no alternatives`() {
        // Given — one provider
        val result = merger.merge(listOf(
            artwork("wikidata", "https://commons.wikimedia.org/photo.jpg", confidence = 1.0f),
        ))

        // Then — success with no alternatives
        val success = result as EnrichmentResult.Success
        val art = success.data as EnrichmentData.Artwork
        assertEquals("https://commons.wikimedia.org/photo.jpg", art.url)
        assertEquals("wikidata", success.provider)
        assertNull(art.alternatives)
    }

    @Test fun `multiple providers merge into primary plus alternatives`() {
        // Given — three providers with different confidences
        val results = listOf(
            artwork("deezer", "https://deezer.com/artist.jpg", confidence = 0.8f,
                thumbnailUrl = "https://deezer.com/artist_thumb.jpg",
                sizes = listOf(ArtworkSize("https://deezer.com/artist.jpg", 1000, 1000, "xl"))),
            artwork("wikidata", "https://commons.wikimedia.org/photo.jpg", confidence = 1.0f,
                sizes = listOf(ArtworkSize("https://commons.wikimedia.org/photo.jpg", 1200, 800))),
            artwork("fanarttv", "https://fanart.tv/thumb.jpg", confidence = 0.9f),
        )

        // When
        val result = merger.merge(results)

        // Then — wikidata wins primary (highest confidence), others are alternatives
        val success = result as EnrichmentResult.Success
        assertEquals("wikidata", success.provider)
        assertEquals(1.0f, success.confidence)

        val art = success.data as EnrichmentData.Artwork
        assertEquals("https://commons.wikimedia.org/photo.jpg", art.url)

        val alts = art.alternatives!!
        assertEquals(2, alts.size)
        assertEquals("fanarttv", alts[0].provider) // 0.9 confidence
        assertEquals("deezer", alts[1].provider)   // 0.8 confidence
        assertEquals("https://deezer.com/artist_thumb.jpg", alts[1].thumbnailUrl)
    }

    @Test fun `duplicate URLs are deduplicated in alternatives`() {
        // Given — two providers returning the same URL
        val results = listOf(
            artwork("providerA", "https://example.com/photo.jpg", confidence = 1.0f),
            artwork("providerB", "https://example.com/photo.jpg", confidence = 0.8f),
        )

        // When
        val result = merger.merge(results)

        // Then — no alternatives since the duplicate URL is removed
        val art = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertNull(art.alternatives)
    }

    @Test fun `identifiers are merged from all providers`() {
        // Given — different providers contribute different identifiers
        val results = listOf(
            artwork("wikidata", "https://commons.wikimedia.org/photo.jpg", confidence = 1.0f,
                identifiers = EnrichmentIdentifiers(wikidataId = "Q123")),
            artwork("deezer", "https://deezer.com/artist.jpg", confidence = 0.8f,
                identifiers = EnrichmentIdentifiers().withExtra("deezerId", "456")),
        )

        // When
        val result = merger.merge(results)

        // Then — merged identifiers include both
        val ids = (result as EnrichmentResult.Success).resolvedIdentifiers!!
        assertEquals("Q123", ids.wikidataId)
        assertEquals("456", ids.extra["deezerId"])
    }

    @Test fun `alternatives preserve sizes from each provider`() {
        // Given — providers with different size variants
        val deezerSizes = listOf(
            ArtworkSize("https://deezer.com/56.jpg", 56, 56, "small"),
            ArtworkSize("https://deezer.com/1000.jpg", 1000, 1000, "xl"),
        )
        val results = listOf(
            artwork("wikidata", "https://commons.wikimedia.org/photo.jpg", confidence = 1.0f),
            artwork("deezer", "https://deezer.com/1000.jpg", confidence = 0.8f, sizes = deezerSizes),
        )

        // When
        val result = merger.merge(results)

        // Then — deezer's sizes are preserved in its alternative entry
        val alts = ((result as EnrichmentResult.Success).data as EnrichmentData.Artwork).alternatives!!
        assertEquals(1, alts.size)
        assertEquals(2, alts[0].sizes!!.size)
        assertEquals(56, alts[0].sizes!![0].width)
        assertEquals(1000, alts[0].sizes!![1].width)
    }

    @Test fun `works for ALBUM_ART type`() {
        // Given — merger parameterized for ALBUM_ART
        val albumMerger = ArtworkMerger(EnrichmentType.ALBUM_ART)
        val results = listOf(
            EnrichmentResult.Success(
                type = EnrichmentType.ALBUM_ART,
                data = EnrichmentData.Artwork(url = "https://caa.org/front.jpg"),
                provider = "coverartarchive", confidence = 1.0f,
            ),
            EnrichmentResult.Success(
                type = EnrichmentType.ALBUM_ART,
                data = EnrichmentData.Artwork(url = "https://deezer.com/cover.jpg"),
                provider = "deezer", confidence = 0.8f,
            ),
        )

        // When
        val result = albumMerger.merge(results)

        // Then — works the same way
        val success = result as EnrichmentResult.Success
        assertEquals("coverartarchive", success.provider)
        val alts = (success.data as EnrichmentData.Artwork).alternatives!!
        assertEquals(1, alts.size)
        assertEquals("deezer", alts[0].provider)
    }
}
