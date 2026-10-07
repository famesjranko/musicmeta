package com.landofoz.musicmeta.demo

import com.landofoz.musicmeta.ContentAttribution
import com.landofoz.musicmeta.ContentLicense
import com.landofoz.musicmeta.EnrichmentData
import org.junit.Assert.assertTrue
import org.junit.Test

class AttributionRenderingTest {
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
