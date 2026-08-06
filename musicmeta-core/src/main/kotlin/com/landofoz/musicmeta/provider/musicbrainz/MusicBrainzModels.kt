package com.landofoz.musicmeta.provider.musicbrainz

/** Internal DTOs for MusicBrainz API responses. */

internal data class MusicBrainzRelease(
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

internal data class MusicBrainzArtist(
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

internal data class MusicBrainzRecording(
    val id: String,
    val title: String,
    val isrcs: List<String>,
    val tags: List<String>,
    val tagCounts: List<TagCount> = emptyList(),
    val score: Int,
    val disambiguation: String? = null,
    /**
     * True when at least one of the recording's carried `releases` is status "Official" on an
     * Album release-group — a same-response signal (no extra lookup) that this take is the studio
     * original rather than a single/compilation-only or non-official recording.
     */
    val hasOfficialAlbumRelease: Boolean = false,
    /**
     * The release-group id of the first Official+Album release in [hasOfficialAlbumRelease]'s scan
     * (null when that scan finds nothing, or the matching release-group carries no id). Lets
     * [MusicBrainzMapper.toTrackIdentifiers] fill `musicBrainzReleaseGroupId` for a track without an
     * extra lookup — the recording search response already carries it.
     */
    val officialAlbumReleaseGroupId: String? = null,
)

internal data class MusicBrainzBandMember(
    val name: String,
    val id: String?,
    val role: String?,
    val beginDate: String?,
    val endDate: String?,
    val ended: Boolean,
)

internal data class MusicBrainzReleaseGroup(
    val id: String,
    val title: String,
    val primaryType: String?,
    val firstReleaseDate: String?,
)

internal data class MusicBrainzTrack(
    val title: String,
    val position: Int,
    val durationMs: Long?,
    val id: String?,
)

internal data class MusicBrainzUrlRelation(
    val type: String,
    val url: String,
)

internal data class MusicBrainzCredit(
    val name: String,
    val id: String?,
    val role: String,
    val roleCategory: String?,
)

internal data class MusicBrainzReleaseGroupDetail(
    val id: String,
    val title: String,
    val releases: List<MusicBrainzEdition>,
)

internal data class MusicBrainzEdition(
    val id: String,
    val title: String,
    val date: String?,
    val country: String?,
    val barcode: String?,
    val format: String?,
    val label: String?,
    val catalogNumber: String?,
)

internal data class TagCount(
    val name: String,
    val count: Int,
)
