package com.cascade.enrichment.provider.wikipedia

/** Summary data from the Wikipedia REST API page/summary endpoint. */
data class WikipediaSummary(
    val title: String,
    val extract: String,
    val description: String?,
    val thumbnailUrl: String?,
)
