package com.landofoz.musicmeta.provider.wikipedia

import com.landofoz.musicmeta.ArtworkSize
import com.landofoz.musicmeta.Attribution
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.provider.encodePathSegment

/** Maps Wikipedia responses to EnrichmentData subclasses. */
internal object WikipediaMapper {

    fun toBiography(summary: WikipediaSummary): EnrichmentData.Biography =
        EnrichmentData.Biography(
            text = summary.extract,
            source = "Wikipedia",
            thumbnailUrl = summary.thumbnailUrl,
            attribution = textAttribution(summary.title),
        )

    /**
     * What Wikipedia states about its article text: CC BY-SA 4.0, and the article it came from.
     * Describes the text only; the thumbnail is a separate file with its own terms. A blank title
     * leaves the title and the article URL null and keeps the licence.
     */
    private fun textAttribution(title: String): Attribution {
        val articleTitle = title.takeIf { it.isNotBlank() }
        return Attribution(
            title = articleTitle,
            language = "en",
            sourceUrl = articleTitle?.let { "$ARTICLE_BASE_URL/${encodePathSegment(it.replace(' ', '_'))}" },
            licence = TEXT_LICENCE,
            licenceUrl = TEXT_LICENCE_URL,
        )
    }

    // Every request goes to en.wikipedia.org (WikipediaProvider), so the article URL names that host.
    private const val ARTICLE_BASE_URL = "https://en.wikipedia.org/wiki"
    private const val TEXT_LICENCE = "CC BY-SA 4.0"
    private const val TEXT_LICENCE_URL = "https://creativecommons.org/licenses/by-sa/4.0/"

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
}
