package com.cascade.enrichment.provider.discogs

data class DiscogsRelease(
    val title: String,
    val label: String?,
    val year: String?,
    val country: String?,
    val coverImage: String?,
    val releaseType: String? = null,
)
