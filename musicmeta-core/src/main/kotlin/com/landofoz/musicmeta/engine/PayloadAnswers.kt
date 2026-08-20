package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentType

/**
 * Does this payload actually answer [type]?
 *
 * A provider identifies an entity, then maps whatever that entity happened to carry; nothing makes
 * it check that the mapping produced anything. A tagless MusicBrainz recording matched at score 100
 * maps to an all-null [EnrichmentData.Metadata] and is returned as `Success` at confidence 1.0 — a
 * result a consumer branches into and finds empty.
 *
 * `confidence` cannot catch this and is not meant to: it scores *identification* (see
 * [ConfidenceCalculator]), so a perfect identity match scores highest exactly when the payload is
 * emptiest. The two signals are independent, and this is the second one. The engine demotes a
 * `Success` that fails this check to `NotFound`.
 *
 * Which fields answer a type is a per-type question only for [EnrichmentData.Metadata], the one
 * payload shared across six types; every other payload answers its type iff it carries anything.
 */
// One flat branch per payload class and no nesting: the branch count is the point, and exhaustiveness
// is what makes the compiler ask about a payload class someone adds later. Splitting it to satisfy a
// complexity count would hide exactly that.
@Suppress("CyclomaticComplexMethod")
internal fun EnrichmentData.answers(type: EnrichmentType): Boolean = when (this) {
    is EnrichmentData.Metadata -> answersMetadata(type)

    // Constructed with a non-optional String, so only blankness can empty them.
    is EnrichmentData.Artwork -> url.isNotBlank()
    is EnrichmentData.Biography -> text.isNotBlank()
    is EnrichmentData.TrackPreview -> url.isNotBlank()

    // ponytail: LYRICS_SYNCED and LYRICS_PLAIN both accept either field, matching LrcLibProvider's
    // own guard. Split per type if a consumer asking for synced lyrics must not be handed plain.
    is EnrichmentData.Lyrics ->
        isInstrumental || !syncedLyrics.isNullOrBlank() || !plainLyrics.isNullOrBlank()

    // signals counts on its own: a source reporting only a rating (MusicBrainz) fills no flat
    // field, and demoting it would drop the one thing it had to say.
    is EnrichmentData.Popularity ->
        listenCount != null || listenerCount != null || rank != null ||
            !topTracks.isNullOrEmpty() || signals.isNotEmpty()

    is EnrichmentData.ArtistLinks -> links.isNotEmpty()
    is EnrichmentData.ArtistTimeline -> events.isNotEmpty()
    is EnrichmentData.BandMembers -> members.isNotEmpty()
    is EnrichmentData.Credits -> credits.isNotEmpty()
    is EnrichmentData.Discography -> albums.isNotEmpty()
    is EnrichmentData.GenreDiscovery -> relatedGenres.isNotEmpty()
    is EnrichmentData.RadioPlaylist -> tracks.isNotEmpty()
    is EnrichmentData.ReleaseEditions -> editions.isNotEmpty()
    is EnrichmentData.SimilarAlbums -> albums.isNotEmpty()
    is EnrichmentData.SimilarArtists -> artists.isNotEmpty()
    is EnrichmentData.SimilarTracks -> tracks.isNotEmpty()
    is EnrichmentData.TopTracks -> tracks.isNotEmpty()
    is EnrichmentData.Tracklist -> tracks.isNotEmpty()
    is EnrichmentData.TrackMetadata -> durationMs != null || !albumTitle.isNullOrBlank() ||
        !disambiguation.isNullOrBlank()
}

/**
 * Does this cached entry carry genre tags, under a type a reader takes them from, that never
 * learned whether they were curated?
 *
 * [com.landofoz.musicmeta.GenreTag.curated] is `null` on an entry persisted before the field
 * existed, and on one whose provider could not tell — so the payload answers a strictly poorer
 * question than a fresh call would: it cannot say which of its names came from a controlled
 * vocabulary. GENRE's TTL is 90 days, so waiting it out would hide the curated ranking for a quarter
 * of a year on every entity a consumer had already looked up.
 *
 * Keyed on `GENRE` *and* `ALBUM_METADATA` — the two entries `EnrichmentResults.genres`/`genreTags`
 * read, in that fallback order — and on no other type. The same [EnrichmentData.Metadata] payload
 * is cached per-type under `LABEL`, `RELEASE_DATE`, `RELEASE_TYPE` and `COUNTRY` too, tags and all,
 * but no reader ever takes genre tags from those entries, so an unknown marking there is not a
 * poorer answer to anything. Healing them is worse than pointless: MusicBrainz's degraded mapping
 * writes `curated = null` again on every fetch it cannot ask on, so the "heal" re-misses on every
 * call for the entry's whole TTL — a warm album was measured paying four live MusicBrainz round
 * trips per read this way.
 *
 * Read on the cache path only, as a *miss*: the providers run and the write-back replaces the entry,
 * exactly as an unanswered entry heals ([answers]). On the two types it applies to, convergence is
 * the merger's doing: `GENRE` is written from [GenreMerger], whose output tags always carry a
 * concrete `curated`, and an `ALBUM_METADATA` winner writes `false` where it looked — only a
 * fetch-path that can never ask keeps re-missing, which is the trade the healing accepts there.
 */
internal fun EnrichmentData.hasUnknownGenreCuration(type: EnrichmentType): Boolean =
    (type == EnrichmentType.GENRE || type == EnrichmentType.ALBUM_METADATA) &&
        this is EnrichmentData.Metadata && genreTags?.any { it.curated == null } == true

/**
 * One [EnrichmentData.Metadata] serves six types, and a provider fills only the fields its upstream
 * happened to return — so "non-empty" is not the question, "did it fill the field this type asks
 * about" is. GENRE reads two fields because a provider may supply plain names, weighted tags, or
 * both.
 */
private fun EnrichmentData.Metadata.answersMetadata(type: EnrichmentType): Boolean = when (type) {
    EnrichmentType.GENRE -> !genres.isNullOrEmpty() || !genreTags.isNullOrEmpty()
    // Blank, not just null: an upstream that returns "" has answered nothing either.
    EnrichmentType.LABEL -> !label.isNullOrBlank()
    EnrichmentType.RELEASE_DATE -> !releaseDate.isNullOrBlank()
    EnrichmentType.RELEASE_TYPE -> !releaseType.isNullOrBlank()
    EnrichmentType.COUNTRY -> !country.isNullOrBlank()

    // ALBUM_METADATA is the grab bag — any field answers it. The two list fields are emptied first,
    // so an empty list reads as absent here exactly as it does for GENRE above.
    EnrichmentType.ALBUM_METADATA -> anyFieldFilled()

    // Every type no provider answers with Metadata. Listed rather than elided behind `else` so the
    // compiler names a new EnrichmentType instead of letting it fall through unenumerated — that buys
    // exhaustiveness, not a rejection: every one of them still maps to anyFieldFilled() below, the
    // same grab-bag reading as ALBUM_METADATA, which PayloadAnswersTypeCoverageTest pins as intended.
    EnrichmentType.ALBUM_ART,
    EnrichmentType.ARTIST_PHOTO,
    EnrichmentType.ARTIST_BACKGROUND,
    EnrichmentType.ARTIST_LOGO,
    EnrichmentType.CD_ART,
    EnrichmentType.SIMILAR_ARTISTS,
    EnrichmentType.ARTIST_BIO,
    EnrichmentType.LYRICS_SYNCED,
    EnrichmentType.LYRICS_PLAIN,
    EnrichmentType.TRACK_POPULARITY,
    EnrichmentType.ARTIST_POPULARITY,
    EnrichmentType.BAND_MEMBERS,
    EnrichmentType.SIMILAR_TRACKS,
    EnrichmentType.ARTIST_LINKS,
    EnrichmentType.CREDITS,
    EnrichmentType.ARTIST_DISCOGRAPHY,
    EnrichmentType.ALBUM_TRACKS,
    EnrichmentType.RELEASE_EDITIONS,
    EnrichmentType.ARTIST_BANNER,
    EnrichmentType.ALBUM_ART_BACK,
    EnrichmentType.ALBUM_BOOKLET,
    EnrichmentType.ARTIST_TIMELINE,
    EnrichmentType.ARTIST_RADIO,
    EnrichmentType.ARTIST_RADIO_DISCOVERY,
    EnrichmentType.ARTIST_TOP_TRACKS,
    EnrichmentType.TRACK_PREVIEW,
    EnrichmentType.SIMILAR_ALBUMS,
    EnrichmentType.GENRE_DISCOVERY,
    EnrichmentType.TRACK_METADATA,
    EnrichmentType.ALBUM_DESCRIPTION,
    -> anyFieldFilled()
}

/** Any field carrying something, with an empty list reading as absent. */
private fun EnrichmentData.Metadata.anyFieldFilled(): Boolean = copy(
    genres = genres?.takeIf { it.isNotEmpty() },
    genreTags = genreTags?.takeIf { it.isNotEmpty() },
) != EnrichmentData.Metadata()
