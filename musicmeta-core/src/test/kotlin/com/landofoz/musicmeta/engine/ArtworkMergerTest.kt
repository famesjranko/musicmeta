package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.ArtworkSize
import com.landofoz.musicmeta.Attribution
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierNamespace
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
        attribution: Attribution? = null,
    ) = EnrichmentResult.Success(
        type = EnrichmentType.ARTIST_PHOTO,
        data = EnrichmentData.Artwork(url = url, thumbnailUrl = thumbnailUrl, sizes = sizes, attribution = attribution),
        provider = provider,
        confidence = confidence,
        resolvedIdentifiers = identifiers,
    )

    @Test fun `empty input returns NotFound`() {
        // Given - no results
        val result = merger.merge(emptyList())

        // Then - NotFound is returned
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test fun `single provider returns result with no alternatives`() {
        // Given - one provider
        val result = merger.merge(listOf(
            artwork("wikidata", "https://commons.wikimedia.org/photo.jpg", confidence = 1.0f),
        ))

        // Then - success with no alternatives
        val success = result as EnrichmentResult.Success
        val art = success.data as EnrichmentData.Artwork
        assertEquals("https://commons.wikimedia.org/photo.jpg", art.url)
        assertEquals("wikidata", success.provider)
        assertNull(art.alternatives)
    }

    @Test fun `multiple providers merge into primary plus alternatives`() {
        // Given - three providers with different confidences
        val results = listOf(
            artwork("deezer", "https://deezer.com/artist.jpg", confidence = 0.8f,
                thumbnailUrl = "https://deezer.com/artist_thumb.jpg",
                sizes = listOf(ArtworkSize("https://deezer.com/artist.jpg", 1000, 1000, "xl"))),
            artwork("wikidata", "https://commons.wikimedia.org/photo.jpg", confidence = 1.0f,
                sizes = listOf(ArtworkSize("https://commons.wikimedia.org/photo.jpg", 1200, 800))),
            artwork("fanarttv", "https://fanart.tv/thumb.jpg", confidence = 0.9f),
        )

        // When - merging the three results
        val result = merger.merge(results)

        // Then - wikidata wins primary (highest confidence), others are alternatives
        val success = result as EnrichmentResult.Success
        assertEquals("wikidata", success.provider)
        assertEquals(1.0f, success.confidence)

        val art = success.data as EnrichmentData.Artwork
        assertEquals("https://commons.wikimedia.org/photo.jpg", art.url)

        val alts = art.alternatives!!
        assertEquals(2, alts.size)
        assertEquals("fanarttv", alts[0].provider) // 0.9 confidence
        assertEquals("deezer", alts[1].provider) // 0.8 confidence
        assertEquals("https://deezer.com/artist_thumb.jpg", alts[1].thumbnailUrl)
    }

    @Test fun `duplicate URLs are deduplicated in alternatives`() {
        // Given - two providers returning the same URL
        val results = listOf(
            artwork("providerA", "https://example.com/photo.jpg", confidence = 1.0f),
            artwork("providerB", "https://example.com/photo.jpg", confidence = 0.8f),
        )

        // When - merging the results with the duplicate URL
        val result = merger.merge(results)

        // Then - no alternatives since the duplicate URL is removed
        val art = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertNull(art.alternatives)
    }

    @Test fun `identifiers are merged from all providers`() {
        // Given - different providers contribute different identifiers
        val results = listOf(
            artwork("wikidata", "https://commons.wikimedia.org/photo.jpg", confidence = 1.0f,
                identifiers = EnrichmentIdentifiers(wikidataId = "Q123")),
            artwork("deezer", "https://deezer.com/artist.jpg", confidence = 0.8f,
                identifiers = EnrichmentIdentifiers().with(IdentifierNamespace.DEEZER, "456")),
        )

        // When - merging the results
        val result = merger.merge(results)

        // Then - merged identifiers include both
        val ids = (result as EnrichmentResult.Success).resolvedIdentifiers!!
        assertEquals("Q123", ids.wikidataId)
        assertEquals("456", ids.extra["deezerId"])
    }

    @Test fun `alternatives preserve sizes from each provider`() {
        // Given - providers with different size variants
        val deezerSizes = listOf(
            ArtworkSize("https://deezer.com/56.jpg", 56, 56, "small"),
            ArtworkSize("https://deezer.com/1000.jpg", 1000, 1000, "xl"),
        )
        val results = listOf(
            artwork("wikidata", "https://commons.wikimedia.org/photo.jpg", confidence = 1.0f),
            artwork("deezer", "https://deezer.com/1000.jpg", confidence = 0.8f, sizes = deezerSizes),
        )

        // When - merging the results
        val result = merger.merge(results)

        // Then - deezer's sizes are preserved in its alternative entry
        val alts = ((result as EnrichmentResult.Success).data as EnrichmentData.Artwork).alternatives!!
        assertEquals(1, alts.size)
        assertEquals(2, alts[0].sizes!!.size)
        assertEquals(56, alts[0].sizes!![0].width)
        assertEquals(1000, alts[0].sizes!![1].width)
    }

    @Test fun `works for ALBUM_ART type`() {
        // Given - merger parameterized for ALBUM_ART
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

        // When - merging the ALBUM_ART results
        val result = albumMerger.merge(results)

        // Then - works the same way
        val success = result as EnrichmentResult.Success
        assertEquals("coverartarchive", success.provider)
        val alts = (success.data as EnrichmentData.Artwork).alternatives!!
        assertEquals(1, alts.size)
        assertEquals("deezer", alts[0].provider)
    }

    @Test fun `an alternative keeps the attribution its provider returned`() {
        // Given - a losing provider whose artwork carries its own creator and licence
        val loserAttribution = Attribution(creator = "Raph_PH", licence = "CC BY 4.0")
        val results = listOf(
            artwork("wikidata", "https://commons.wikimedia.org/winner.jpg", confidence = 1.0f),
            artwork(
                "wikipedia", "https://upload.wikimedia.org/loser.jpg", confidence = 0.4f,
                attribution = loserAttribution,
            ),
        )

        // When - merging the two results
        val result = merger.merge(results)

        // Then - the alternative carries that attribution beside its url
        val alternatives = ((result as EnrichmentResult.Success).data as EnrichmentData.Artwork).alternatives!!
        assertEquals("https://upload.wikimedia.org/loser.jpg", alternatives.single().url)
        assertEquals(loserAttribution, alternatives.single().attribution)
    }

    @Test fun `the primary keeps the attribution its provider returned`() {
        // Given - a winning provider whose artwork carries its own creator and licence
        val winnerAttribution = Attribution(creator = "Raph_PH", licence = "CC BY 4.0")
        val results = listOf(
            artwork(
                "wikidata", "https://commons.wikimedia.org/winner.jpg", confidence = 1.0f,
                attribution = winnerAttribution,
            ),
            artwork("deezer", "https://deezer.com/loser.jpg", confidence = 0.4f),
        )

        // When - merging the two results
        val result = merger.merge(results)

        // Then - the primary artwork still carries that attribution
        val primary = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertEquals(winnerAttribution, primary.attribution)
    }

    @Test fun `attribution does not change which provider wins or which urls are listed`() {
        // Given - the same three results twice, once with attributions on the losers and once without
        val attributed = Attribution(creator = "Raph_PH", licence = "CC BY 4.0", licenceUrl = "http://example.com/l")
        fun results(attribution: Attribution?) = listOf(
            artwork("deezer", "https://deezer.com/a.jpg", confidence = 0.8f),
            artwork("wikidata", "https://commons.wikimedia.org/b.jpg", confidence = 1.0f),
            artwork("wikipedia", "https://upload.wikimedia.org/c.jpg", confidence = 0.8f, attribution = attribution),
            artwork("lastfm", "https://deezer.com/a.jpg", confidence = 0.5f, attribution = attribution),
        )

        // When - merging both sets
        val attributedMerge = merger.merge(results(attributed)) as EnrichmentResult.Success
        val plainMerge = merger.merge(results(null)) as EnrichmentResult.Success

        // Then - the winning provider, primary url and the alternatives' providers and urls are identical
        assertEquals(plainMerge.provider, attributedMerge.provider)
        val withArt = attributedMerge.data as EnrichmentData.Artwork
        val withoutArt = plainMerge.data as EnrichmentData.Artwork
        assertEquals(withoutArt.url, withArt.url)
        assertEquals(withoutArt.alternatives!!.map { it.provider to it.url }, withArt.alternatives!!.map { it.provider to it.url })
    }
}
