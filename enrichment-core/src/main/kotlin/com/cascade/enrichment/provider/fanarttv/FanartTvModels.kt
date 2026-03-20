package com.cascade.enrichment.provider.fanarttv

data class FanartTvArtistImages(
    val thumbnails: List<String>,
    val backgrounds: List<String>,
    val logos: List<String>,
    val banners: List<String>,
    val albumCovers: List<String> = emptyList(),
    val cdArt: List<String> = emptyList(),
)
