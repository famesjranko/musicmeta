# Phase 15: Similar Albums - Context

**Gathered:** 2026-03-23
**Status:** Ready for planning

<domain>
## Phase Boundary

Add new `SIMILAR_ALBUMS` EnrichmentType with a standalone `SimilarAlbumsProvider` (NOT a composite type). The provider internally fetches related artists from Deezer, then their top albums, and scores results by artist similarity and era proximity. ForAlbum requests only. 30-day TTL.

</domain>

<decisions>
## Implementation Decisions

### Architecture — Standalone Provider, Not Composite
- SimilarAlbumsProvider is a regular EnrichmentProvider, registered separately from DeezerProvider
- It receives the same DeezerApi instance via engine Builder (shared RateLimiter)
- Reason: composites can only consume sub-types from the same request — SIMILAR_ALBUMS needs to fetch OTHER artists' discographies, which requires issuing new API calls
- Multi-call pattern precedent: DeezerProvider.enrichDiscography() already does searchArtist → getArtistAlbums (2 API calls)

### Algorithm
1. Search for seed artist on Deezer via `searchArtist(request.artist)` + ArtistMatcher guard
2. Get related artists via `getRelatedArtists(artistId, limit=5)` (reuses Phase 13 endpoint)
3. For each related artist: `getArtistAlbums(relatedArtistId, limit=3)` (reuses existing endpoint)
4. Score each album: `artistMatchScore` from the similar-artist ranking
5. Optional era proximity boost: if seed album has a year, albums within ±5 years get a 1.2x multiplier, ±10 years get 1.0x, beyond gets 0.8x
6. Deduplicate by album title+artist, sort by score descending, cap at 20 results

### Data Model
- New `EnrichmentType.SIMILAR_ALBUMS` with 30-day TTL
- New `EnrichmentData.SimilarAlbums(albums: List<SimilarAlbum>)` sealed subclass
- New `SimilarAlbum(title, artist, year?, artistMatchScore, thumbnailUrl?, identifiers)` — @Serializable
- SimilarAlbum carries `identifiers` with `deezerId` for downstream use

### Provider Details
- Priority 100 (only provider)
- ForAlbum requests only — return NotFound for ForArtist/ForTrack
- Confidence: `ConfidenceCalculator.fuzzyMatch(true)` = 0.8
- Cost: ~6 Deezer API calls per enrichment (1 search + 5 album lookups), cached for 30 days
- Shared DeezerApi instance ensures RateLimiter serializes all Deezer calls

### Claude's Discretion
- Whether to extract Deezer artist ID resolution into a shared helper (DeezerProvider + SimilarAlbumsProvider both use it)
- Internal scoring formula details (era proximity multiplier exact values)
- How to handle partial failures (e.g., 3/5 related artists found but 2 return empty discographies)
- Test fixture structure

</decisions>

<code_context>
## Existing Code Insights

### Reusable Assets
- `DeezerApi.searchArtist()` — artist search by name
- `DeezerApi.getRelatedArtists()` (Phase 13) — related artists with Deezer IDs
- `DeezerApi.getArtistAlbums()` — already exists for ARTIST_DISCOGRAPHY
- `ArtistMatcher.isMatch()` — fuzzy name matching
- `DeezerMapper` — reference for mapping patterns
- `ConfidenceCalculator` — standardized scoring

### Established Patterns
- Multi-call providers: DeezerProvider.enrichDiscography() does search → albums (2 calls)
- Separate provider for separate capability: SimilarAlbumsProvider is a new class, not inside DeezerProvider
- Builder.addProvider() registers it alongside existing providers

### Integration Points
- `EnrichmentType.kt` — add `SIMILAR_ALBUMS(30 * 24 * 60 * 60 * 1000L)`
- `EnrichmentData.kt` — add SimilarAlbums + SimilarAlbum
- New file: `provider/deezer/SimilarAlbumsProvider.kt`
- `EnrichmentEngine.kt` (Builder) — register SimilarAlbumsProvider in withDefaultProviders or equivalent

</code_context>

<specifics>
## Specific Ideas

- Reference plan suggests SimilarAlbumsProvider receives DeezerApi instance via constructor, not creating its own — shares rate limiter
- Year for era proximity comes from ForAlbum request or from the Deezer search result for the seed album
- Related artists' albums already have year data from `getArtistAlbums()` response

</specifics>

<deferred>
## Deferred Ideas

- Genre overlap filtering (matching seed album's genres against similar albums' genres) — adds value but requires resolving GENRE for each similar album, high fan-out cost. Defer to v0.6.x enhancement.

</deferred>
