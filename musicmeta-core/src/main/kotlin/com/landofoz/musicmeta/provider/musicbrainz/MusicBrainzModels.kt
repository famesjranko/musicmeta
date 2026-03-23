package com.landofoz.musicmeta.provider.musicbrainz

/** Internal DTOs for MusicBrainz API responses. */

data class MusicBrainzRelease(
    val id: String,
    val title: String,
    val artistCredit: String?,
    val date: String?,
    val country: String?,
    val barcode: String?,
    val tags: List<String>,
    val tagCounts: List<TagCount> = emptyList(),
    val label: String?,
    val releaseType: String?,
    val releaseGroupId: String?,
    val disambiguation: String?,
    val score: Int,
    val hasFrontCover: Boolean = false,
    val tracks: List<MusicBrainzTrack> = emptyList(),
)

data class MusicBrainzArtist(
    val id: String,
    val name: String,
    val sortName: String? = null,
    val type: String?,
    val country: String?,
    val beginDate: String?,
    val endDate: String?,
    val tags: List<String>,
    val tagCounts: List<TagCount> = emptyList(),
    val disambiguation: String?,
    val wikidataId: String?,
    val wikipediaTitle: String?,
    val score: Int,
    val urlRelations: List<MusicBrainzUrlRelation> = emptyList(),
    val bandMembers: List<MusicBrainzBandMember> = emptyList(),
)

data class MusicBrainzRecording(
    val id: String,
    val title: String,
    val isrcs: List<String>,
    val tags: List<String>,
    val tagCounts: List<TagCount> = emptyList(),
    val score: Int,
)

data class MusicBrainzBandMember(
    val name: String,
    val id: String?,
    val role: String?,
    val beginDate: String?,
    val endDate: String?,
    val ended: Boolean,
)

data class MusicBrainzReleaseGroup(
    val id: String,
    val title: String,
    val primaryType: String?,
    val firstReleaseDate: String?,
)

data class MusicBrainzTrack(
    val title: String,
    val position: Int,
    val durationMs: Long?,
    val id: String?,
)

data class MusicBrainzUrlRelation(
    val type: String,
    val url: String,
)

data class MusicBrainzCredit(
    val name: String,
    val id: String?,
    val role: String,
    val roleCategory: String?,
)

data class MusicBrainzReleaseGroupDetail(
    val id: String,
    val title: String,
    val releases: List<MusicBrainzEdition>,
)

data class MusicBrainzEdition(
    val id: String,
    val title: String,
    val date: String?,
    val country: String?,
    val barcode: String?,
    val format: String?,
    val label: String?,
    val catalogNumber: String?,
)

data class TagCount(
    val name: String,
    val count: Int,
)
