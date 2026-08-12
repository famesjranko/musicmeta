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

    is EnrichmentData.Popularity ->
        listenCount != null || listenerCount != null || rank != null || !topTracks.isNullOrEmpty()

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
 * Does this cached payload carry genre tags that never learned whether they were curated?
 *
 * [com.landofoz.musicmeta.GenreTag.curated] is `null` on an entry persisted before the field
 * existed, and on one whose provider could not tell — so the payload answers a strictly poorer
 * question than a fresh call would: it cannot say which of its names came from a controlled
 * vocabulary. GENRE's TTL is 90 days, so waiting it out would hide the curated ranking for a quarter
 * of a year on every entity a consumer had already looked up.
 *
 * **Not keyed on `GENRE`.** `EnrichmentResults.genres`/`genreTags` fall back to `ALBUM_METADATA`,
 * which carries the same [EnrichmentData.Metadata] and the same `genreTags`, so a consumer asking
 * only for `ALBUM_METADATA` reads the tags off that entry instead. Any payload holding the field is
 * therefore the unit that heals — and only that: a payload with no `genreTags` at all is untouched,
 * so nothing about this reopens `LABEL`, `COUNTRY`, artwork or any other cached type.
 *
 * Read on the cache path only, as a *miss*: the providers run and the write-back replaces the entry,
 * exactly as an unanswered entry heals ([answers]). It cannot loop, because a provider that *did*
 * look writes `false` — an entity with no curated genres is an answer, not an unknown.
 */
internal fun EnrichmentData.hasUnknownGenreCuration(): Boolean =
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
    // ALBUM_METADATA is the grab bag — any field answers it. A type a future provider decides to
    // answer with Metadata lands here too and gets the same lenient reading; the `when` is not
    // exhaustive over types, so nothing will prompt you. Name its fields above if it needs more.
    // The two list fields are emptied first, so an empty list reads as absent here exactly as it
    // does for GENRE above.
    else -> copy(
        genres = genres?.takeIf { it.isNotEmpty() },
        genreTags = genreTags?.takeIf { it.isNotEmpty() },
    ) != EnrichmentData.Metadata()
}
