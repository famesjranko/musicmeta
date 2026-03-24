package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.CatalogFilterMode
import com.landofoz.musicmeta.CatalogMatch
import com.landofoz.musicmeta.CatalogProvider
import com.landofoz.musicmeta.CatalogQuery
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType

/**
 * Recommendation types that support catalog availability filtering.
 * Only results of these types are passed to [CatalogProvider.checkAvailability].
 */
internal val RECOMMENDATION_TYPES = setOf(
    EnrichmentType.SIMILAR_ARTISTS,
    EnrichmentType.SIMILAR_ALBUMS,
    EnrichmentType.ARTIST_RADIO,
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

/** Applies catalog availability filtering to recommendation results in-place. No-op when null or UNFILTERED. */
internal suspend fun applyCatalogFiltering(
    results: MutableMap<EnrichmentType, EnrichmentResult>,
    catalogProvider: CatalogProvider?,
    catalogFilterMode: CatalogFilterMode,
) {
    val provider = catalogProvider ?: return
    if (catalogFilterMode == CatalogFilterMode.UNFILTERED) return

    for (type in RECOMMENDATION_TYPES) {
        val result = results[type] as? EnrichmentResult.Success ?: continue
        val queries = toQueries(result.data) ?: continue
        if (queries.isEmpty()) continue

        val matches = provider.checkAvailability(queries)
        results[type] = applyMode(result, matches, catalogFilterMode)
    }
}
