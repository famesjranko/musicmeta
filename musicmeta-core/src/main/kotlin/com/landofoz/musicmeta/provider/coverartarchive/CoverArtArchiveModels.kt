package com.landofoz.musicmeta.provider.coverartarchive

data class CoverArtArchiveImage(
    val front: Boolean,
    val url: String,
    val thumbnails: Map<String, String>,
    val types: List<String> = emptyList(),
)
