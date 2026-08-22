package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentType

/**
 * The mergers [EnrichmentEngine.Builder] registers when a caller adds none, in registration order.
 * Extracted from the literal list the `Builder` used to hold inline so a test can walk the
 * registered set rather than restate it — [Builder.addMerger] stays public, so a consumer-registered
 * merger is still invisible here.
 */
internal val DEFAULT_MERGERS: List<ResultMerger> = listOf(
    GenreMerger,
    SimilarArtistMerger,
    SimilarTrackMerger,
    ArtworkMerger(EnrichmentType.ARTIST_PHOTO),
    ArtworkMerger(EnrichmentType.ALBUM_ART),
    TopTrackMerger,
    PopularityMerger(EnrichmentType.ARTIST_POPULARITY),
    PopularityMerger(EnrichmentType.TRACK_POPULARITY),
)

/**
 * The synthesizers [EnrichmentEngine.Builder] registers when a caller adds none, in registration
 * order. Same rationale as [DEFAULT_MERGERS], but public: a caller building its own attribution
 * from a [CompositeSynthesizer.type] needs to know which composite types the default engine
 * derives and from what, without hand-copying the graph — read [CompositeSynthesizer.type] and
 * [CompositeSynthesizer.dependencies] off each entry. A synthesizer a caller registers through
 * [EnrichmentEngine.Builder.addSynthesizer] is never added here.
 */
val DEFAULT_SYNTHESIZERS: List<CompositeSynthesizer> = listOf(
    TimelineSynthesizer,
    GenreAffinityMatcher,
)
