package com.landofoz.musicmeta.demo

import com.landofoz.musicmeta.ContentAttribution
import com.landofoz.musicmeta.ContentLicense
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ArtworkSource
import com.landofoz.musicmeta.LicenseRelation
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class AttributionRenderingTest {
    @Test
    fun `result output credits the article and each artwork alternative`() {
        // Given - biography text and artwork alternatives with independent attribution
        val article = ContentAttribution("Article", "https://example.test/article", creator = "Contributors",
            licenses = listOf(ContentLicense("CC BY-SA 4.0")))
        val alternative = article.copy(resourceId = "File:Alternative", sourceUrl = "https://example.test/alternative", creator = "Alternative author")
        val biography = EnrichmentData.Biography("Biography", "Wikipedia", attribution = article)
        val artwork = EnrichmentData.Artwork("https://example.test/primary", alternatives = listOf(
            ArtworkSource("other", "https://example.test/alternative-image", attribution = alternative)))
        val results = resultsOf(mapOf(
            EnrichmentType.ARTIST_BIO to EnrichmentResult.Success(EnrichmentType.ARTIST_BIO, biography, "wikipedia", 1f),
            EnrichmentType.ARTIST_PHOTO to EnrichmentResult.Success(EnrichmentType.ARTIST_PHOTO, artwork, "other", 1f)))

        // When - rendering the result rows to the terminal
        val text = captureOutput { Formatter.printResults(results, it) }

        // Then - text and alternative artwork use their own source and author credit
        assertTrue(text.contains("https://example.test/article"))
        assertTrue(text.contains("Contributors"))
        assertTrue(text.contains("https://example.test/alternative"))
        assertTrue(text.contains("Alternative author"))
        assertTrue(text.contains("CC BY-SA 4.0"))
    }
    @Test
    fun `custom terminal credit preserves licence links and removes active control characters`() {
        // Given - a file with an overriding custom credit and hostile terminal and URL input
        val artwork = EnrichmentData.Artwork("https://example.test/image.jpg", attribution = ContentAttribution(
            "File:Image.jpg", "javascript:alert(1)", creator = "Hidden creator", attributionText = "Credit\u001b[31m" + "a".repeat(5000),
            licenses = listOf(ContentLicense("Custom", "https://example.test/license"), ContentLicense("CC0")),
            licenseRelation = LicenseRelation.ALL_OF, usageTerms = "Both apply", restrictions = listOf("Keep notice"),
            modificationNote = "Cropped"))

        // When - formatting its terminal attribution
        val text = Formatter.attributionText(artwork)

        // Then - complete credit and valid licence references survive without active controls or unsafe links
        assertTrue(text.contains("a".repeat(5000)))
        assertTrue(text.contains("https://example.test/license"))
        assertTrue(text.contains("Cropped"))
        assertTrue(text.contains("All licences apply"))
        assertTrue(text.contains("Keep notice"))
        assertFalse(text.contains("\u001b"))
        assertFalse(text.contains("javascript:"))
        assertFalse(text.contains("Hidden creator"))
    }
    @Test
    fun `artwork detail renders the file creator source and licence`() {
        // Given - a file with source-specific attribution
        val artwork = EnrichmentData.Artwork(
            url = "https://example.com/image.jpg",
            attribution = ContentAttribution(
                resourceId = "File:image.jpg",
                sourceUrl = "https://commons.wikimedia.org/wiki/File:image.jpg",
                creator = "Ada Lovelace",
                licenses = listOf(ContentLicense("CC0", "https://creativecommons.org/publicdomain/zero/1.0/")),
            ),
        )

        // When - formatting the artwork result
        val text = Formatter.attributionText(artwork)

        // Then - it keeps the creator, file source, and licence as ordinary terminal text
        assertTrue(text.contains("Ada Lovelace"))
        assertTrue(text.contains("https://commons.wikimedia.org/wiki/File:image.jpg"))
        assertTrue(text.contains("CC0"))
    }
}
