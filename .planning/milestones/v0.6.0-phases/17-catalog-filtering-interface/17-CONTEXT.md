# Phase 17: Catalog Filtering Interface - Context

**Gathered:** 2026-03-23
**Status:** Ready for planning

<domain>
## Phase Boundary

Define the CatalogProvider interface and CatalogFilterMode enum that let library consumers plug in their own catalog (local library, streaming service) so recommendation results are pre-filtered or re-ranked by availability. Wire filtering into the engine's post-resolution pipeline for recommendation-type results. Ship NO implementations — consumers implement CatalogProvider themselves.

</domain>

<decisions>
## Implementation Decisions

### Interface Design
- `CatalogProvider` interface with `suspend fun checkAvailability(items: List<CatalogQuery>): List<CatalogMatch>`
- `CatalogQuery(title: String, artist: String, album: String? = null, identifiers: EnrichmentIdentifiers = EnrichmentIdentifiers())`
- `CatalogMatch(available: Boolean, source: String, uri: String? = null, confidence: Float = 1.0f)`
- `CatalogFilterMode` enum: `UNFILTERED`, `AVAILABLE_ONLY`, `AVAILABLE_FIRST`

### Engine Integration
- `EnrichmentConfig` gains optional `catalogProvider: CatalogProvider? = null` and `catalogFilterMode: CatalogFilterMode = UNFILTERED`
- Post-resolution filtering in DefaultEnrichmentEngine — after resolveTypes returns, apply catalog filtering to recommendation-type results only
- Recommendation types to filter: `SIMILAR_ARTISTS`, `SIMILAR_TRACKS`, `SIMILAR_ALBUMS`, `ARTIST_RADIO`
- `UNFILTERED` (default) = return all results unchanged = backward compatible
- `AVAILABLE_ONLY` = remove items where `CatalogMatch.available == false`
- `AVAILABLE_FIRST` = sort available items before unavailable, preserve relative order within each group

### Builder Integration
- `EnrichmentEngine.Builder` gains `catalog(provider: CatalogProvider, mode: CatalogFilterMode)` method
- Config passes through to DefaultEnrichmentEngine

### Backward Compatibility
- No CatalogProvider configured = UNFILTERED mode = identical behavior to previous versions
- All new types/interfaces are additive (no breaking changes)

### Claude's Discretion
- How to extract recommendation items from different EnrichmentData subtypes (SimilarArtists, SimilarAlbums, RadioPlaylist) for CatalogQuery construction
- Whether to add a `CatalogResult` wrapper or annotate items inline
- Test approach (fake CatalogProvider in tests)

</decisions>

<code_context>
## Existing Code Insights

### Reusable Assets
- `DefaultEnrichmentEngine.enrich()` — post-resolution pipeline (after resolveTypes, before cache store) is where filtering fits
- `EnrichmentConfig` — existing config data class, additive fields are safe
- `EnrichmentEngine.Builder` — pattern for adding new config options

### Established Patterns
- Config fields have defaults (`minConfidence = 0.5f`, etc.) — `catalogProvider = null` follows this pattern
- Post-processing precedent: confidence filtering already happens after resolution (`filterByConfidence`)
- `EnrichmentIdentifiers` carried on all recommendation items (SimilarArtist, SimilarAlbum, RadioTrack) — provides matching data for CatalogQuery

### Integration Points
- New file: `CatalogProvider.kt` (interface + CatalogQuery + CatalogMatch + CatalogFilterMode)
- `EnrichmentConfig.kt` — add catalogProvider and catalogFilterMode fields
- `DefaultEnrichmentEngine.kt` — add post-resolution catalog filtering
- `EnrichmentEngine.kt` — Builder.catalog() method

</code_context>

<specifics>
## Specific Ideas

- From the ROADMAP.md catalog awareness section: "Recommendations are only valuable if the user can play them"
- User's original request: "the library should allow for absolute recommendations/radio and 'available for user' recommendations and radio"
- The interface should be simple enough that a consumer with a local file scanner can implement it in ~20 lines

</specifics>

<deferred>
## Deferred Ideas

- LocalLibraryCatalog implementation — v0.8.0
- SpotifyCatalog / YouTubeMusicCatalog implementations — v0.8.0
- Fingerprint-based matching (AcoustID/Chromaprint) — v0.8.0
- Availability scoring (ranking by how accessible items are) — v0.8.0

</deferred>
