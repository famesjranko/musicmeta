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
    /** The curated `genres` subset of [tagCounts], present on any response asking for `inc=genres`. */
    val genreCounts: List<TagCount> = emptyList(),
    /**
     * Whether this response could have carried curated genres at all. False for a search hit, which
     * has no `inc=` parameter to ask with — so an empty [genreCounts] there means "not asked", and
     * marking its tags as uncurated would state something nobody checked.
     */
    val curationKnown: Boolean = false,
    val label: String?,
    val releaseType: String?,
    val releaseGroupId: String?,
    val disambiguation: String?,
    val score: Int,
    /**
     * Whether the Cover Art Archive holds a front cover for this release, or null where the
     * response could not say. A search hit carries no `cover-art-archive` object at all — only a
     * release lookup does — so a false there would state something nobody checked.
     */
    val hasFrontCover: Boolean? = null,
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
    /**
     * The release group's `first-release-date` — the earliest release MusicBrainz holds in it, so
     * the floor under every pressing of this album. Unlike [date], which is this pressing's own and
     * moves with every remaster. Present on a release lookup, which asks `inc=release-groups`.
     */
    val releaseGroupFirstReleaseDate: String? = null,
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
    /** The curated `genres` subset of [tagCounts], present on any response asking for `inc=genres`. */
    val genreCounts: List<TagCount> = emptyList(),
    /**
     * Whether this response could have carried curated genres at all. False for a search hit, which
     * has no `inc=` parameter to ask with — so an empty [genreCounts] there means "not asked", and
     * marking its tags as uncurated would state something nobody checked.
     */
    val curationKnown: Boolean = false,
    val disambiguation: String?,
    val wikidataId: String?,
    val wikipediaTitle: String?,
    val score: Int,
    val urlRelations: List<MusicBrainzUrlRelation> = emptyList(),
    val bandMembers: List<MusicBrainzBandMember> = emptyList(),
    /** Alternative names MusicBrainz holds for this artist; a search hit carries them without `inc=`. */
    val aliases: List<MusicBrainzAlias> = emptyList(),
    /** Community rating from `inc=ratings`; null unless someone has voted. */
    val rating: MusicBrainzRating? = null,
)

/**
 * MusicBrainz's community rating, 1–5 with a vote count.
 *
 * Mapped to a [com.landofoz.musicmeta.PopularitySignal] of kind `RATING` by
 * [MusicBrainzMapper.toPopularity], which is what `ARTIST_POPULARITY` and `TRACK_POPULARITY` answer
 * with here. It rides free on lookups already made for other reasons, so neither type costs a
 * request of its own.
 */
internal data class MusicBrainzRating(
    val value: Float,
    val votes: Int,
)

/**
 * One entry of an artist's `aliases` array.
 *
 * [primary] and [locale] together are MusicBrainz's own signal that a name is one the artist is
 * published under rather than a search aid: a locale-tagged alias is a localisation of the name, and
 * `primary: true` marks the one to prefer within that locale. [type] is `"Artist name"`,
 * `"Search hint"` or `"Legal name"` — the "Search hint" entries are misspellings and typo-catchers
 * MusicBrainz keeps for its own indexer, which is why they are not treated as names the artist goes
 * by.
 */
internal data class MusicBrainzAlias(
    val name: String,
    val type: String?,
    val locale: String?,
    val primary: Boolean,
)

internal data class MusicBrainzRecording(
    val id: String,
    val title: String,
    val isrcs: List<String>,
    val tags: List<String>,
    val tagCounts: List<TagCount> = emptyList(),
    /** The curated `genres` subset of [tagCounts], present on any response asking for `inc=genres`. */
    val genreCounts: List<TagCount> = emptyList(),
    /**
     * Whether this response could have carried curated genres at all. False for a search hit, which
     * has no `inc=` parameter to ask with — so an empty [genreCounts] there means "not asked", and
     * marking its tags as uncurated would state something nobody checked.
     */
    val curationKnown: Boolean = false,
    /** Community rating from `inc=ratings`; null unless someone has voted. See [MusicBrainzRating]. */
    val rating: MusicBrainzRating? = null,
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
     * A release-group id CAA can try for the recording's art, picked by
     * [MusicBrainzParser.findArtReleaseGroup]'s tiers; null when none of them finds one. Backs
     * [MusicBrainzMapper.toTrackIdentifiers]'s `musicBrainzReleaseGroupId` with no extra lookup.
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
