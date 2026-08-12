package com.landofoz.musicmeta

import kotlinx.serialization.Serializable

/** Typed payload returned by a successful enrichment. */
@Serializable
sealed class EnrichmentData {

    @Serializable
    data class Artwork(
        val url: String,
        val width: Int? = null,
        val height: Int? = null,
        val thumbnailUrl: String? = null,
        val sizes: List<ArtworkSize>? = null,
        /** Images from other providers, available when artwork is merged from multiple sources. */
        val alternatives: List<ArtworkSource>? = null,
    ) : EnrichmentData()

    @Serializable
    data class Metadata(
        val genres: List<String>? = null,
        val genreTags: List<GenreTag>? = null,
        val label: String? = null,
        val releaseDate: String? = null,
        val releaseType: String? = null,
        val country: String? = null,
        val barcode: String? = null,
        val disambiguation: String? = null,
        val artistType: String? = null,
        val beginDate: String? = null,
        val endDate: String? = null,
        val isrc: String? = null,
        val trackCount: Int? = null,
        val explicit: Boolean? = null,
        val catalogNumber: String? = null,
        /**
         * Discogs' community rating for this *release*, on Discogs' own 1–5 scale.
         *
         * Album-level and single-source, which is why it is a flat field rather than a
         * [PopularitySignal]. An artist's or a recording's rating is a popularity claim with a
         * source and lives in [Popularity.signals] as [PopularitySignalKind.RATING] — that list is
         * authoritative for those, and nothing back-fills this field from it.
         */
        val communityRating: Float? = null,
    ) : EnrichmentData()

    @Serializable
    data class Lyrics(
        val syncedLyrics: String? = null,
        val plainLyrics: String? = null,
        val isInstrumental: Boolean = false,
    ) : EnrichmentData()

    @Serializable
    data class Biography(
        val text: String,
        val source: String,
        val language: String = "en",
        val thumbnailUrl: String? = null,
    ) : EnrichmentData()

    @Serializable
    data class SimilarArtists(
        val artists: List<SimilarArtist>,
    ) : EnrichmentData()

    /**
     * [signals] is the authoritative record: one entry per source, in the unit that source measures.
     * The flat fields above it are a derived convenience — the merger back-fills each from the
     * first source that reported it, so a caller wanting one number does not have to pick. Where the
     * two disagree, [signals] is what the providers actually said.
     */
    @Serializable
    data class Popularity(
        val listenCount: Long? = null,
        val listenerCount: Long? = null,
        val rank: Int? = null,
        val topTracks: List<PopularTrack>? = null,
        /**
         * Every source's own claim, kept unmerged and never summed — a Last.fm scrobble count, a
         * ListenBrainz listen count and a MusicBrainz community rating measure different things.
         * Empty on a result that never reached the merger, or from a source carrying no signal.
         */
        val signals: List<PopularitySignal> = emptyList(),
    ) : EnrichmentData()

    @Serializable
    data class BandMembers(val members: List<BandMember>) : EnrichmentData()

    @Serializable
    data class Discography(val albums: List<DiscographyAlbum>) : EnrichmentData()

    @Serializable
    data class Tracklist(val tracks: List<TrackInfo>) : EnrichmentData()

    @Serializable
    data class SimilarTracks(val tracks: List<SimilarTrack>) : EnrichmentData()

    @Serializable
    data class ArtistLinks(val links: List<ExternalLink>) : EnrichmentData()

    @Serializable
    data class Credits(val credits: List<Credit>) : EnrichmentData()

    @Serializable
    data class ReleaseEditions(val editions: List<ReleaseEdition>) : EnrichmentData()

    @Serializable
    data class ArtistTimeline(val events: List<TimelineEvent>) : EnrichmentData()

    @Serializable
    data class RadioPlaylist(val tracks: List<RadioTrack>) : EnrichmentData()

    @Serializable
    data class SimilarAlbums(val albums: List<SimilarAlbum>) : EnrichmentData()

    @Serializable
    data class GenreDiscovery(val relatedGenres: List<GenreAffinity>) : EnrichmentData()

    @Serializable
    data class TopTracks(val tracks: List<TopTrack>) : EnrichmentData()

    @Serializable
    data class TrackPreview(
        val url: String,
        val durationMs: Long = 30000,
        val source: String,
    ) : EnrichmentData()

    /**
     * Track-level structured metadata that doesn't fit [Metadata]'s album/artist-shaped fields.
     * [durationMs] is the *actual track length*, not a preview clip — see [TrackPreview] for that.
     * [albumTitle] is provider-confirmed, unlike an "as entered" caller-supplied string.
     */
    @Serializable
    data class TrackMetadata(
        val durationMs: Long? = null,
        val albumTitle: String? = null,
        val disambiguation: String? = null,
    ) : EnrichmentData()
}

@Serializable
data class ArtworkSize(
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
    val label: String? = null,
)

/** An image from an alternative provider, used when artwork is merged from multiple sources. */
@Serializable
data class ArtworkSource(
    val provider: String,
    val url: String,
    val thumbnailUrl: String? = null,
    val sizes: List<ArtworkSize>? = null,
)

@Serializable
data class SimilarArtist(
    val name: String,
    val identifiers: EnrichmentIdentifiers = EnrichmentIdentifiers(),
    val matchScore: Float,
    val sources: List<String> = emptyList(),
)

/**
 * One provider's popularity claim, in the unit that provider actually measures.
 *
 * The canonical carrier for a rating or a count with a source attached. [kind] says what [value]
 * counts, and comparing two signals is only meaningful when their [kind] and [source] agree —
 * Last.fm counts scrobbles, ListenBrainz counts listens, MusicBrainz reports a 1–5 community
 * rating. [normalized] is a 0..1 rendering where the source's own scale supports one, and `null`
 * where an unbounded count makes it meaningless — the scale's floor maps to 0 and its ceiling to 1,
 * so a 1–5 rating and a 0–100 score are comparable once normalised.
 *
 * The flat fields on [EnrichmentData.Popularity] are derived from these, not the other way round.
 */
@Serializable
data class PopularitySignal(
    /** The provider id that reported it, e.g. `lastfm`, `listenbrainz`, `musicbrainz`. */
    val source: String,
    val kind: PopularitySignalKind,
    val value: Double,
    val normalized: Float? = null,
    /** How many people the [value] rests on, where the source says — a rating's vote count. */
    val sampleSize: Int? = null,
)

/** What a [PopularitySignal.value] counts. Appended to only: a consumer's `when` may stay open. */
@Serializable
enum class PopularitySignalKind {
    /** Total plays reported by the source (Last.fm scrobbles, ListenBrainz listens). */
    LISTEN_COUNT,

    /** Distinct people the source has seen listening. */
    LISTENER_COUNT,

    /** Position in a source's own chart, 1 being the top. */
    RANK,

    /** A community score on the source's own scale — MusicBrainz rates 1–5. */
    RATING,
}

@Serializable
data class PopularTrack(
    val title: String,
    val identifiers: EnrichmentIdentifiers = EnrichmentIdentifiers(),
    val listenCount: Long,
    val rank: Int,
    /**
     * Fields the sibling [TopTrack] already carries from the same upstream DTO
     * ([com.landofoz.musicmeta.provider.listenbrainz.ListenBrainzPopularTrack]); appended here so
     * [EnrichmentData.Popularity.topTracks] doesn't drop them purely because this type predates
     * `TopTrack`. `null` when the source didn't carry it.
     */
    val listenerCount: Long? = null,
    val durationMs: Long? = null,
    val album: String? = null,
)

@Serializable
data class BandMember(
    val name: String,
    val role: String? = null,
    val activePeriod: String? = null,
    val identifiers: EnrichmentIdentifiers = EnrichmentIdentifiers(),
)

@Serializable
data class DiscographyAlbum(
    val title: String,
    val year: String? = null,
    val type: String? = null,
    val thumbnailUrl: String? = null,
    val identifiers: EnrichmentIdentifiers = EnrichmentIdentifiers(),
)

@Serializable
data class TrackInfo(
    val title: String,
    val position: Int,
    val durationMs: Long? = null,
    val identifiers: EnrichmentIdentifiers = EnrichmentIdentifiers(),
)

@Serializable
data class SimilarTrack(
    val title: String,
    val artist: String,
    /**
     * How closely this track matches the seed track — **semantics depend on [sources]**. Last.fm's
     * `track.getSimilar` is genuine track-level similarity. Deezer has no track-similarity endpoint,
     * so its entries are derived from `/artist/{id}/related` + `/artist/{id}/top`: the score there
     * ranks the *artist's* similarity to the seed artist, not similarity between the tracks
     * themselves, applied uniformly to that artist's top tracks. A single non-nullable field is
     * kept (rather than a distinct field per source, as [SimilarAlbum.artistMatchScore] does for
     * albums) because [SimilarTrackMerger][com.landofoz.musicmeta.engine.SimilarTrackMerger]
     * already keeps the genuine Last.fm score on overlap, so provenance is enough to interpret it.
     */
    val matchScore: Float,
    val identifiers: EnrichmentIdentifiers = EnrichmentIdentifiers(),
    val sources: List<String> = emptyList(),
)

@Serializable
data class ExternalLink(
    val type: String,
    val url: String,
    val label: String? = null,
)

@Serializable
data class Credit(
    val name: String,
    val role: String,
    val roleCategory: String? = null,
    val identifiers: EnrichmentIdentifiers = EnrichmentIdentifiers(),
)

@Serializable
data class ReleaseEdition(
    val title: String,
    val format: String? = null,
    val country: String? = null,
    val year: Int? = null,
    val label: String? = null,
    val catalogNumber: String? = null,
    val barcode: String? = null,
    val identifiers: EnrichmentIdentifiers = EnrichmentIdentifiers(),
)

@Serializable
data class TimelineEvent(
    val date: String,
    val type: String,
    val description: String,
    val relatedEntity: String? = null,
    val identifiers: EnrichmentIdentifiers = EnrichmentIdentifiers(),
)

/**
 * One genre or tag claim about an entity.
 *
 * @property curated whether the source drew this name from its own controlled genre vocabulary
 *   (`true`) or from free-text community input (`false`). `null` means the source said nothing —
 *   the only entries that carry it are ones persisted before providers reported the distinction,
 *   so read `null` as "unknown", never as "community".
 */
@Serializable
data class GenreTag(
    val name: String,
    val confidence: Float,
    val sources: List<String> = emptyList(),
    val curated: Boolean? = null,
)

@Serializable
data class RadioTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val identifiers: EnrichmentIdentifiers = EnrichmentIdentifiers(),
)

/**
 * An album recommended alongside a seed album.
 *
 * The only shipped `SIMILAR_ALBUMS` provider derives these from artists similar to the seed
 * *artist* — Deezer exposes no album-level similarity — which is why the score is named
 * [artistMatchScore]. Two albums by one artist therefore yield near-identical lists. See
 * `SimilarAlbumsProvider`'s KDoc.
 *
 * @property artistMatchScore the source artist's similarity rank (1.0 for the closest, falling
 *   with rank) multiplied by an era-proximity factor of 1.2, 1.0 or 0.8. **Not normalised and not
 *   clamped**: the product can exceed 1.0, up to 1.2, and never reaches 0. Rank within one
 *   result list; do not read it as a probability or compare it across providers.
 */
@Serializable
data class SimilarAlbum(
    val title: String,
    val artist: String,
    val year: Int? = null,
    val artistMatchScore: Float,
    val thumbnailUrl: String? = null,
    val identifiers: EnrichmentIdentifiers = EnrichmentIdentifiers(),
)

@Serializable
data class GenreAffinity(
    val name: String,
    val affinity: Float,
    val relationship: String,
    val sourceGenres: List<String>,
)

@Serializable
data class TopTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val listenCount: Long? = null,
    val listenerCount: Long? = null,
    val rank: Int,
    val sources: List<String> = emptyList(),
    val identifiers: EnrichmentIdentifiers = EnrichmentIdentifiers(),
)
