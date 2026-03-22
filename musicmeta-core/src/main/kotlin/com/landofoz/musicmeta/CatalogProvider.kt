package com.landofoz.musicmeta

/**
 * How the enrichment engine applies catalog filtering to recommendation results.
 */
enum class CatalogFilterMode {
    /** Return all recommendation items unchanged. Default behavior. */
    UNFILTERED,
    /** Remove items where CatalogMatch.available == false. */
    AVAILABLE_ONLY,
    /** Sort available items before unavailable; preserve relative order within each group. */
    AVAILABLE_FIRST,
}

/**
 * Describes a single recommendation item the engine wants to check for catalog availability.
 *
 * @param title Track or album title.
 * @param artist Artist name.
 * @param album Album name, if known (null for artist-level queries).
 * @param identifiers Known enrichment identifiers (MBIDs, Deezer IDs, etc.) for the item.
 */
data class CatalogQuery(
    val title: String,
    val artist: String,
    val album: String? = null,
    val identifiers: EnrichmentIdentifiers = EnrichmentIdentifiers(),
)

/**
 * The catalog availability result for a single [CatalogQuery].
 *
 * @param available Whether the item is playable in the consumer's environment.
 * @param source Identifies the catalog that produced this match (e.g., "local-library", "spotify").
 * @param uri Optional deep-link URI for the item within the catalog.
 * @param confidence How confident the catalog is that this match is correct (0.0–1.0).
 */
data class CatalogMatch(
    val available: Boolean,
    val source: String,
    val uri: String? = null,
    val confidence: Float = 1.0f,
)

/**
 * Pluggable catalog awareness for recommendation filtering.
 *
 * Consumers implement this interface to tell the enrichment engine which
 * recommendation results are playable in their environment (local library,
 * streaming service, etc.).
 *
 * The engine calls [checkAvailability] once per recommendation result set,
 * passing a batch of [CatalogQuery] objects. The returned [CatalogMatch] list
 * must be the same size as the input list and in the same order.
 *
 * Example implementation skeleton:
 * ```kotlin
 * class MyLibraryCatalog : CatalogProvider {
 *     override suspend fun checkAvailability(items: List<CatalogQuery>): List<CatalogMatch> =
 *         items.map { query ->
 *             CatalogMatch(available = myLibrary.contains(query.title, query.artist), source = "my-library")
 *         }
 * }
 * ```
 */
fun interface CatalogProvider {
    suspend fun checkAvailability(items: List<CatalogQuery>): List<CatalogMatch>
}
