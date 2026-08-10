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
    /**
     * Each credited artist's name individually (not joined with join phrases like [artistCredit]).
     * Used only by [MusicBrainzQualifierFallback]'s authoritative-match check — a release can carry
     * multiple credited artists, and the caller's requested artist may be any one of them, not just
     * the first.
     */
    val artistCredits: List<String> = emptyList(),
    /** MusicBrainz's release status (`"Official"`, `"Bootleg"`, `"Promotion"`, `"Pseudo-Release"`, …), used by [MusicBrainzReleaseRanking]. */
    val status: String? = null,
    /** The release group's secondary types (`"Live"`, `"Compilation"`, `"Remix"`, `"Soundtrack"`, …), used by [MusicBrainzReleaseRanking]. */
    val secondaryTypes: List<String> = emptyList(),
    /** Track count from the search hit's own `track-count` field, used by [MusicBrainzReleaseRanking] for its edition-completeness band. */
    val trackCount: Int? = null,
    /**
     * The release group's own disambiguation, which MusicBrainz also echoes onto [disambiguation].
     * [MusicBrainzReleaseRanking.pressingDisambiguation] subtracts it back out.
     */
    val releaseGroupDisambiguation: String? = null,
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
     * Same joined-with-joinphrases string as [MusicBrainzRelease.artistCredit] (e.g. "A feat. B"),
     * built by the same [MusicBrainzParser.extractArtistCredit] — unlike [artistCredits], which
     * exists for individual-name matching, this is what a candidate's display "artist" field wants.
     */
    val artistCredit: String? = null,
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
     * 0. (only when the search carried an album hint) a release whose release-group title matches
     *    the requested album, regardless of status — the recording's `releases` array is not
     *    ordered by relevance to the request, so a compilation ("The Best Of") can sort ahead of
     *    the actually-requested album ("OK Computer") even when both are embedded (#seen live for
     *    Radiohead "Karma Police" / "OK Computer").
     * 1. Official release, release-group primary-type Album — same shape as
     *    [hasOfficialAlbumRelease].
     * 2. release status exactly "Official", any release-group primary-type (e.g. a box set the
     *    search payload embedded instead of the plain album, since MB only embeds releases matching
     *    the query's `release:` hint). MB's other statuses (Promotion, Bootleg, Pseudo-Release,
     *    Withdrawn, Cancelled) are not special-cased here — they all fall through to tier 3.
     * 3. any release carrying a release-group id at all, regardless of status — including a
     *    Bootleg-only recording's release-group, accepted deliberately as a last resort.
     *
     * Null when none of the tiers finds a release-group id. Deliberately looser than
     * [hasOfficialAlbumRelease], which stays strict for ranking — some art (or a cheap CAA
     * NotFound) beats none for [MusicBrainzMapper.toTrackIdentifiers], which fills
     * `musicBrainzReleaseGroupId` from this field with no extra lookup.
     */
    val artReleaseGroupId: String? = null,
    /** The `title` of the same release-group object [artReleaseGroupId] is drawn from — same tiers, no extra lookup. */
    val artReleaseGroupTitle: String? = null,
    /** Recording length in milliseconds, from the search hit's own `length` field. */
    val lengthMs: Long? = null,
    /**
     * MusicBrainz's own `video` flag on the recording — true for a music-video audio track, not a
     * text heuristic on [disambiguation]. Verified live: Radiohead's "Karma Police" music-video
     * recording carries both `"video": true` and `disambiguation: "music video"`, but the flag is
     * the structural signal — a studio take's disambiguation can just as easily be non-blank for an
     * unrelated reason, and a keyword match on disambiguation text is the exact anti-pattern
     * `docs/pitfalls.md` §7 already rejects for MB/Discogs ranking.
     */
    val isVideo: Boolean = false,
    /** Each credited artist's name individually — same shape and purpose as [MusicBrainzRelease.artistCredits]. */
    val artistCredits: List<String> = emptyList(),
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

/**
 * What a lookup by MBID answered, in the three states a caller must tell apart.
 *
 * [Absent] is the only one a name search may stand in for. MusicBrainz answering
 * [com.landofoz.musicmeta.http.HttpResult.ClientError] means it holds nothing under that identifier
 * under any entity type — so the identifier names no entity, and there is none for an answer to be
 * unfaithful to. [Unreadable] names one: MusicBrainz holds it and the body did not parse, so a
 * search hit would be a *different* entity standing in for the one that was asked for.
 *
 * Every transient is thrown by `bodyOrThrowTransient` before either can be reported, so neither is
 * ever a blip a later type might get past — which is what lets a call hold one and stop re-asking.
 */
internal sealed interface MusicBrainzLookup<out T> {
    data class Found<T>(val value: T) : MusicBrainzLookup<T>
    data object Absent : MusicBrainzLookup<Nothing>
    data object Unreadable : MusicBrainzLookup<Nothing>
}
