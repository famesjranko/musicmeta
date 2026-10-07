package com.landofoz.musicmeta.provider.wikipedia

import com.landofoz.musicmeta.ArtworkSize
import com.landofoz.musicmeta.ContentAttribution
import com.landofoz.musicmeta.ContentLicense
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.LicenseRelation
import com.landofoz.musicmeta.provider.encodePathSegment

/** Maps Wikipedia responses to EnrichmentData subclasses. */
internal object WikipediaMapper {

    fun toBiography(summary: WikipediaSummary): EnrichmentData.Biography =
        EnrichmentData.Biography(
            text = summary.extract,
            source = "Wikipedia",
            // A page-image thumbnail is a file route and has no file-specific attribution here.
            thumbnailUrl = null,
            attribution = ContentAttribution(
                resourceId = summary.title,
                sourceUrl = "https://en.wikipedia.org/wiki/" + encodeArticleTitle(summary.title),
                creator = "Wikipedia contributors",
                licenses = listOf(
                    ContentLicense("CC BY-SA 4.0", "https://creativecommons.org/licenses/by-sa/4.0/"),
                ),
            ),
        )

    /**
     * `height` stays null and `width` describes a rendering, not a file: Wikipedia's media-list
     * response carries neither the original image nor any height. `sizes` lists every rendering
     * the article offers, so a caller wanting a smaller one need not re-derive it from the URL.
     */
    fun toArtwork(media: WikipediaMediaItem): EnrichmentData.Artwork =
        EnrichmentData.Artwork(
            url = media.url,
            width = media.width,
            height = media.height,
            sizes = media.renderings
                .map { ArtworkSize(url = it.url, width = it.width, label = it.scale) }
                .takeIf { it.isNotEmpty() },
        )

    /**
     * Returns source-reported credit only for complete, consistent, explicitly unrestricted files.
     * Commons extmetadata can hide multi-licensing, so this is not sufficient to lift photo quarantine.
     */
    fun toFileAttribution(metadata: WikipediaFileMetadata): ContentAttribution? {
        if (metadata.attributionState == WikipediaAttributionState.PRESENT_REJECTED) return null
        val sourceUrl = metadata.descriptionPageUrl ?: return null
        val license = metadata.licenseShortName ?: return null
        if (!WikipediaMetadata.httpsUrl(sourceUrl)) return null
        val publicDomain = license == "Public domain" || license == "CC0"
        if (!hasReusableFlags(metadata, publicDomain)) return null
        if (!publicDomain && metadata.attribution == null && metadata.artist == null) return null
        if (!supportedLicense(metadata)) return null
        if (metadata.usageTerms != null && !supportedTerms(license, metadata.usageTerms)) return null
        return ContentAttribution(
            resourceId = metadata.title,
            sourceUrl = sourceUrl,
            creator = metadata.artist,
            credit = metadata.credit,
            attributionText = metadata.attribution,
            licenses = listOf(ContentLicense(license, metadata.licenseUrl)),
            licenseRelation = LicenseRelation.ANY_OF,
            copyrighted = metadata.copyrighted,
            attributionRequired = metadata.attributionRequired,
            nonFree = metadata.nonFree,
            usageTerms = metadata.usageTerms,
            restrictions = metadata.restrictions,
        )
    }

    private fun hasReusableFlags(metadata: WikipediaFileMetadata, publicDomain: Boolean): Boolean =
        metadata.nonFree == false && metadata.restrictions == emptyList<String>() &&
            metadata.copyrighted == !publicDomain && metadata.attributionRequired == !publicDomain

    private fun supportedLicense(metadata: WikipediaFileMetadata): Boolean {
        val license = metadata.licenseShortName ?: return false
        val expected = when (license) {
            "Public domain" -> "pd" to null
            "CC0" -> "cc-zero" to "https://creativecommons.org/publicdomain/zero/1.0"
            else -> {
                val match = Regex("CC (BY|BY-SA) (1.0|2.0|2.5|3.0|4.0)").matchEntire(license) ?: return false
                val kind = match.groupValues[1].lowercase()
                val version = match.groupValues[2]
                "cc-$kind-$version" to "https://creativecommons.org/licenses/$kind/$version"
            }
        }
        // A combined or contradictory template code cannot establish a single reusable licence.
        if (metadata.licenseCode != expected.first) return false
        return if (expected.second == null) metadata.licenseUrl == null
        else metadata.licenseUrl?.trimEnd('/') == expected.second
    }

    private fun encodeArticleTitle(title: String): String = encodePathSegment(title).replace("%20", "_")

    private fun supportedTerms(license: String, terms: String): Boolean {
        if (license == terms) return true
        val longName = when {
            license == "CC0" -> "Creative Commons CC0 1.0 Universal"
            license.startsWith("CC BY-SA ") ->
                "Creative Commons Attribution Share Alike " + license.substringAfterLast(' ')
            license.startsWith("CC BY ") -> "Creative Commons Attribution " + license.substringAfterLast(' ')
            else -> return false
        }
        fun normalized(value: String): String = value.lowercase().replace(Regex("[-\\s]"), "")
        return normalized(terms) == normalized(longName)
    }
}
