package com.cascade.enrichment.provider.musicbrainz

/** Internal DTOs for MusicBrainz API responses. */

data class MusicBrainzRelease(
    val id: String,
    val title: String,
    val artistCredit: String?,
    val date: String?,
    val country: String?,
    val barcode: String?,
    val tags: List<String>,
    val label: String?,
    val releaseType: String?,
    val releaseGroupId: String?,
    val disambiguation: String?,
    val score: Int,
    val hasFrontCover: Boolean = false,
)

data class MusicBrainzArtist(
    val id: String,
    val name: String,
    val type: String?,
    val country: String?,
    val beginDate: String?,
    val endDate: String?,
    val tags: List<String>,
    val disambiguation: String?,
    val wikidataId: String?,
    val wikipediaTitle: String?,
    val score: Int,
)

data class MusicBrainzRecording(
    val id: String,
    val title: String,
    val isrcs: List<String>,
    val tags: List<String>,
    val score: Int,
)
