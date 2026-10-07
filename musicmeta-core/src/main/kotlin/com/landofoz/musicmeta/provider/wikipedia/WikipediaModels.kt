package com.landofoz.musicmeta.provider.wikipedia

/**
 * Lead text and page properties from the Wikipedia Action API.
 *
 * [wikibaseItem] is the article's Wikidata Q-id (`pageprops.wikibase_item`), the identifier a
 * caller would otherwise resolve in a second request.
 */
internal data class WikipediaSummary(
    val title: String,
    val extract: String,
    val description: String?,
    val thumbnailUrl: String?,
    val wikibaseItem: String?,
)

/**
 * One rendering of a media-list image, from a `srcset` entry.
 *
 * [width] is read from the URL's `NNNpx-` segment, the only place the response states a size, and
 * is null for a URL without one. [scale] is the entry's own `1x`/`2x` label.
 */
internal data class WikipediaRendering(
    val url: String,
    val width: Int?,
    val scale: String?,
)

/**
 * Image item from the Wikipedia REST API page/media-list endpoint.
 *
 * [url] and [width] describe the largest rendering offered; [renderings] holds every one of them,
 * largest first. [height] is always null: media-list states no height and no original-file size.
 * [isLeadImage] marks the article's infobox image, which is the photograph a caller means by
 * "the artist's picture".
 */
internal data class WikipediaMediaItem(
    val title: String,
    val url: String,
    val width: Int?,
    val height: Int?,
    val renderings: List<WikipediaRendering>,
    val isLeadImage: Boolean,
)

/** File-specific facts from `imageinfo.extmetadata`; never inferred from an article's text licence. */
internal enum class WikipediaAttributionState {
    ABSENT,
    PRESENT_VALID,
    PRESENT_REJECTED,
}

internal data class WikipediaFileMetadata(
    val title: String,
    val descriptionPageUrl: String?,
    val attribution: String?,
    val attributionState: WikipediaAttributionState = if (attribution == null) {
        WikipediaAttributionState.ABSENT
    } else {
        WikipediaAttributionState.PRESENT_VALID
    },
    val artist: String?,
    val credit: String?,
    val licenseShortName: String?,
    val licenseUrl: String?,
    val usageTerms: String?,
    val restrictions: List<String>?,
    val copyrighted: Boolean?,
    val nonFree: Boolean?,
    val attributionRequired: Boolean? = null,
    val licenseCode: String? = null,
)
