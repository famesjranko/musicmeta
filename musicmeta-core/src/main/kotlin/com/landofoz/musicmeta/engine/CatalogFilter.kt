package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.CatalogFilterMode
import com.landofoz.musicmeta.CatalogMatch
import com.landofoz.musicmeta.CatalogProvider
import com.landofoz.musicmeta.CatalogQuery
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentLogger
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val TAG = "CatalogFilter"

/**
 * Recommendation types that support catalog availability filtering.
 * Only results of these types are passed to [CatalogProvider.checkAvailability].
 */
internal val RECOMMENDATION_TYPES = setOf(
    EnrichmentType.SIMILAR_ARTISTS,
    EnrichmentType.SIMILAR_ALBUMS,
    EnrichmentType.ARTIST_RADIO,
    EnrichmentType.ARTIST_RADIO_DISCOVERY,
    EnrichmentType.SIMILAR_TRACKS,
    EnrichmentType.ARTIST_TOP_TRACKS,
)

/**
 * Converts a recommendation [EnrichmentData] payload into a list of [CatalogQuery] objects
 * for availability checking. Returns null for non-recommendation data types.
 */
internal fun toQueries(data: EnrichmentData): List<CatalogQuery>? = when (data) {
    is EnrichmentData.SimilarArtists -> data.artists.map { a ->
        CatalogQuery(title = a.name, artist = a.name, identifiers = a.identifiers)
    }
    is EnrichmentData.SimilarAlbums -> data.albums.map { a ->
        CatalogQuery(title = a.title, artist = a.artist, identifiers = a.identifiers)
    }
    is EnrichmentData.RadioPlaylist -> data.tracks.map { t ->
        CatalogQuery(title = t.title, artist = t.artist, album = t.album, identifiers = t.identifiers)
    }
    is EnrichmentData.SimilarTracks -> data.tracks.map { t ->
        CatalogQuery(title = t.title, artist = t.artist, identifiers = t.identifiers)
    }
    is EnrichmentData.TopTracks -> data.tracks.map { t ->
        CatalogQuery(title = t.title, artist = t.artist, album = t.album, identifiers = t.identifiers)
    }
    else -> null
}

/**
 * Applies the given [mode] to [result] using [matches], and returns the updated result.
 * - [CatalogFilterMode.AVAILABLE_ONLY]: removes unavailable items; returns NotFound if empty.
 * - [CatalogFilterMode.AVAILABLE_FIRST]: moves available items before unavailable, preserving relative order.
 * - [CatalogFilterMode.UNFILTERED]: returns [result] unchanged.
 */
internal fun applyMode(
    result: EnrichmentResult.Success,
    matches: List<CatalogMatch>,
    mode: CatalogFilterMode,
): EnrichmentResult {
    if (mode == CatalogFilterMode.UNFILTERED) return result

    val indexed = matches.indices.map { i ->
        i to matches.getOrElse(i) { CatalogMatch(available = true, source = "unknown") }
    }
    val filteredIndices: List<Int> = when (mode) {
        CatalogFilterMode.AVAILABLE_ONLY -> indexed.filter { (_, m) -> m.available }.map { (i, _) -> i }
        CatalogFilterMode.AVAILABLE_FIRST -> {
            val avail = indexed.filter { (_, m) -> m.available }.map { (i, _) -> i }
            val unavail = indexed.filter { (_, m) -> !m.available }.map { (i, _) -> i }
            avail + unavail
        }
        CatalogFilterMode.UNFILTERED -> return result
    }

    if (filteredIndices.isEmpty()) return EnrichmentResult.NotFound(result.type, result.provider)

    val newData = reorderData(result.data, filteredIndices) ?: return result
    if (newData == result.data) return result
    return result.copy(data = newData)
}

/**
 * Reorders the items in a recommendation [EnrichmentData] payload according to [indices].
 * Returns null for non-recommendation data types.
 */
internal fun reorderData(data: EnrichmentData, indices: List<Int>): EnrichmentData? = when (data) {
    is EnrichmentData.SimilarArtists -> data.copy(artists = indices.mapNotNull { data.artists.getOrNull(it) })
    is EnrichmentData.SimilarAlbums -> data.copy(albums = indices.mapNotNull { data.albums.getOrNull(it) })
    is EnrichmentData.RadioPlaylist -> data.copy(tracks = indices.mapNotNull { data.tracks.getOrNull(it) })
    is EnrichmentData.SimilarTracks -> data.copy(tracks = indices.mapNotNull { data.tracks.getOrNull(it) })
    is EnrichmentData.TopTracks -> data.copy(tracks = indices.mapNotNull { data.tracks.getOrNull(it) })
    else -> null
}

/**
 * Applies catalog availability filtering to recommendation results in-place. No-op when null or UNFILTERED.
 *
 * A throwing [CatalogProvider] costs that type its filtering, not the whole enrichment: filtering
 * ranks and trims results the providers already produced, so degrading to the unfiltered result
 * keeps the fetched data — and the cache write that follows — rather than losing both. Cancellation
 * is settled as `CacheGuard` and `StrategyGuard` settle it: `ensureActive()` asks whether *our* job
 * was cancelled, so a consumer's own `withTimeout` around their catalog lookup stops their lookup
 * and not `enrich()`.
 */
internal suspend fun applyCatalogFiltering(
    results: MutableMap<EnrichmentType, EnrichmentResult>,
    catalogProvider: CatalogProvider?,
    catalogFilterMode: CatalogFilterMode,
    logger: EnrichmentLogger,
) {
    for (type in RECOMMENDATION_TYPES) {
        val result = results[type] ?: continue
        results[type] = applyCatalogFilteringToType(type, result, catalogProvider, catalogFilterMode, logger)
    }
}

/**
 * [applyCatalogFiltering]'s per-type body, so a per-type settlement pipeline can run it without a
 * shared map: not a [RECOMMENDATION_TYPES] member, not a [EnrichmentResult.Success], nothing to
 * filter, or [catalogFilterMode] is [CatalogFilterMode.UNFILTERED] all return [result] unchanged.
 */
internal suspend fun applyCatalogFilteringToType(
    type: EnrichmentType,
    result: EnrichmentResult,
    catalogProvider: CatalogProvider?,
    catalogFilterMode: CatalogFilterMode,
    logger: EnrichmentLogger,
): EnrichmentResult {
    // isCatalogDegraded is call-scoped: every serve normalizes it to false right here, before any
    // branch below — including an early return (no CatalogProvider configured, UNFILTERED mode, a
    // non-recommendation type, no queries) — gets a chance to hand back a stale true this call
    // never earned, e.g. from a cache hit a differently-configured or since-recovered engine wrote.
    // Only the guarded catch below, when *this* call's own checkAvailability throws, may set it
    // back to true.
    val normalized = if (result is EnrichmentResult.Success) result.copy(isCatalogDegraded = false) else result

    val provider = catalogProvider ?: return normalized
    if (catalogFilterMode == CatalogFilterMode.UNFILTERED) return normalized
    if (type !in RECOMMENDATION_TYPES) return normalized
    val success = normalized as? EnrichmentResult.Success ?: return normalized
    val queries = toQueries(success.data) ?: return normalized
    if (queries.isEmpty()) return normalized

    val matches = try {
        provider.checkAvailability(queries)
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        logger.warn(TAG, "${type.name}: CatalogProvider threw, leaving results unfiltered: ${e.message}", e)
        return success.copy(isCatalogDegraded = true)
    }
    return applyMode(success, matches, catalogFilterMode)
}
