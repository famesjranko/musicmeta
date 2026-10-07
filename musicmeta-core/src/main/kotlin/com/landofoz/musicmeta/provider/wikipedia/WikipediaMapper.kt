package com.landofoz.musicmeta.provider.wikipedia

import com.landofoz.musicmeta.ArtworkSize
import com.landofoz.musicmeta.ContentAttribution
import com.landofoz.musicmeta.ContentLicense
import com.landofoz.musicmeta.EnrichmentData
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
     * Represents only source-reported file facts. A caller must still fail closed before serving
     * artwork when required facts are absent, restricted, non-free or multi-licence ambiguous.
     */
    fun toFileAttribution(metadata: WikipediaFileMetadata): ContentAttribution? {
        val sourceUrl = metadata.descriptionPageUrl ?: return null
        val license = metadata.licenseShortName ?: return null
        return ContentAttribution(
            resourceId = metadata.title,
            sourceUrl = sourceUrl,
            creator = metadata.artist,
            credit = metadata.credit,
            attributionText = metadata.attribution,
            licenses = listOf(ContentLicense(license, metadata.licenseUrl)),
            copyrighted = metadata.copyrighted,
            attributionRequired = metadata.attribution != null,
            nonFree = metadata.nonFree,
            usageTerms = metadata.usageTerms,
            restrictions = metadata.restrictions,
        )
    }

    private fun encodeArticleTitle(title: String): String = encodePathSegment(title).replace("%20", "_")
}
