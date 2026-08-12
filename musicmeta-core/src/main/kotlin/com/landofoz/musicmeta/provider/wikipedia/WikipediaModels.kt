package com.landofoz.musicmeta.provider.wikipedia

/**
 * Lead text and page properties from the Wikipedia Action API.
 *
 * [wikibaseItem] is the article's Wikidata Q-id (`pageprops.wikibase_item`); nothing surfaces it
 * yet, but it is the identifier a caller would otherwise resolve in a second request.
 */
internal data class WikipediaSummary(
    val title: String,
    val extract: String,
    val description: String?,
    val thumbnailUrl: String?,
    val wikibaseItem: String?,
)

/**
 * Image item from the Wikipedia REST API page/media-list endpoint.
 *
 * [url] and [width] describe the 1x rendered thumbnail, the only size the response carries;
 * [height] is always null because the response does not state it. [isLeadImage] marks the
 * article's infobox image, which is the photograph a caller means by "the artist's picture".
 */
internal data class WikipediaMediaItem(
    val title: String,
    val url: String,
    val width: Int?,
    val height: Int?,
    val isLeadImage: Boolean,
)
