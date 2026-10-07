package com.landofoz.musicmeta.provider.wikipedia

import com.landofoz.musicmeta.ArtworkSize
import com.landofoz.musicmeta.ContentAttribution
import com.landofoz.musicmeta.ContentLicense
import com.landofoz.musicmeta.EnrichmentData

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

    private fun encodeArticleTitle(title: String): String = title.replace(" ", "_")
}
