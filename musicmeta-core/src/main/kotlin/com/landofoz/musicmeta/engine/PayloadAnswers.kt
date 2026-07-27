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
}

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
    else -> this != EnrichmentData.Metadata()
}
