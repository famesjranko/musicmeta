package com.landofoz.musicmeta.provider.wikipedia

/** Summary data from the Wikipedia REST API page/summary endpoint. */
data class WikipediaSummary(
    val title: String,
    val extract: String,
    val description: String?,
    val thumbnailUrl: String?,
)

/** Image item from the Wikipedia REST API page/media-list endpoint. */
data class WikipediaMediaItem(
    val title: String,
    val url: String,
    val width: Int?,
    val height: Int?,
)
