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
     * A release-group id CAA can try for the recording's art, picked from the recording's carried
     * `releases` by tier (first match within the best tier wins — see
     * `MusicBrainzParser.findArtReleaseGroup`):
     * 1. Official release, release-group primary-type Album — same shape as
     *    [hasOfficialAlbumRelease].
     * 2. release status exactly "Official", any release-group primary-type (e.g. a box set the
     *    search payload embedded instead of the plain album, since MB only embeds releases matching
     *    the query's `release:` hint). MB's other statuses (Promotion, Bootleg, Pseudo-Release,
     *    Withdrawn, Cancelled) are not special-cased here — they all fall through to tier 3.
     * 3. any release carrying a release-group id at all, regardless of status — including a
     *    Bootleg-only recording's release-group, accepted deliberately as a last resort.
     *
     * Null when none of the three tiers finds a release-group id. Deliberately looser than
     * [hasOfficialAlbumRelease], which stays strict for ranking — some art (or a cheap CAA
     * NotFound) beats none for [MusicBrainzMapper.toTrackIdentifiers], which fills
     * `musicBrainzReleaseGroupId` from this field with no extra lookup.
     */
    val artReleaseGroupId: String? = null,
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
