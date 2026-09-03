@file:JvmName("DefaultRegistries")

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
 * order. Same rationale as [DEFAULT_MERGERS]: extracted so the `Builder` and this file's derived
 * map share one source. [Builder.addSynthesizer] stays public, so a consumer-registered synthesizer
 * is invisible here.
 */
internal val DEFAULT_SYNTHESIZERS: List<CompositeSynthesizer> = listOf(
    TimelineSynthesizer,
    GenreAffinityMatcher,
)

/**
 * Each composite [EnrichmentType] the default engine synthesizes, mapped to the sub-types it is
 * derived from. A caller building its own attribution for a synthesized result — which names the
 * synthesizer, an entity no reader can be sent to and no upstream's terms cover — reads this to
 * credit whoever answered the types it was derived from instead of hand-copying the graph. Covers
 * only the engine's built-in synthesizers; a synthesizer a caller adds through
 * [EnrichmentEngine.Builder.addSynthesizer] is not reflected here.
 *
 * Reads the graph outside the per-call snapshot on purpose: attribution, never scheduling, and
 * only the two built-in objects above — a consumer synthesizer's dependencies must be read through
 * the snapshot instead.
 */
public val DEFAULT_SYNTHESIZER_DEPENDENCIES: Map<EnrichmentType, Set<EnrichmentType>> =
    DEFAULT_SYNTHESIZERS.associate { it.type to it.dependencies }
