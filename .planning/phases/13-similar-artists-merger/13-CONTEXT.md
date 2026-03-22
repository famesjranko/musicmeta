# Phase 13: Similar Artists + Merger - Context

**Gathered:** 2026-03-23
**Status:** Ready for planning

<domain>
## Phase Boundary

Add Deezer as the third SIMILAR_ARTISTS provider, promote SIMILAR_ARTISTS to a mergeable type (like GENRE), and build SimilarArtistMerger to deduplicate and score results from all three providers (Last.fm, ListenBrainz, Deezer). Add a `sources` field to SimilarArtist for merge transparency.

</domain>

<decisions>
## Implementation Decisions

### Score Normalization
- Deezer match scores derived from list position: `1.0 - (index / count) * 0.9` (position 1 = 1.0, position 20 = 0.1)
- Deezer result confidence: 0.7 via ConfidenceCalculator.fuzzyMatch(true) — lower than Last.fm semantic confidence
- Request limit 20 from Deezer `/artist/{id}/related` (Deezer default, matches Last.fm typical count)

### Merge Strategy
- Additive scoring (consistent with GenreMerger pattern) — sum match scores across providers, cap at 1.0
- Rewards cross-provider agreement: artists recommended by 3 providers rank higher than single-provider artists
- SimilarArtistMerger as stateless object (same pattern as GenreMerger)

### Data Model
- Deezer artist ID stored in `identifiers.extra["deezerId"]` — consistent with discogsReleaseId/discogsMasterId pattern from v0.5.0
- Add `sources: List<String> = emptyList()` to SimilarArtist data class — matches GenreTag.sources pattern, backward compatible
- SimilarArtistMerger dedup by normalized name (lowercase trim), prefer MBID when available

### Integration
- Deezer artist search verified via `ArtistMatcher.isMatch()` guard — existing pattern from Deezer discography flow
- SIMILAR_ARTISTS added to ResultMerger registry (Phase 12 infrastructure) — SimilarArtistMerger : ResultMerger
- Deezer SIMILAR_ARTISTS capability at priority 30 (tertiary to Last.fm 100 and ListenBrainz 50)
- Existing Last.fm and ListenBrainz providers need `sources` populated in their mapper output

### Claude's Discretion
- Internal implementation details of SimilarArtistMerger (name normalization logic, identifier merge strategy)
- Whether to share Deezer artist search logic with existing enrichDiscography or duplicate it
- Test fixture structure and test case granularity

</decisions>

<code_context>
## Existing Code Insights

### Reusable Assets
- `GenreMerger` object — reference implementation for merger pattern (additive confidence, alias normalization, dedup by normalized key)
- `ResultMerger` interface (Phase 12) — `val type: EnrichmentType` + `fun merge(results: List<Success>): EnrichmentResult`
- `ArtistMatcher.isMatch()` — fuzzy name matching for Deezer search verification
- `ConfidenceCalculator` — standardized scoring (fuzzyMatch, idBasedLookup, authoritative)
- `DeezerProvider.enrichDiscography()` — existing pattern: searchArtist → getArtistAlbums, can reuse searchArtist logic

### Established Patterns
- Provider structure: Api.kt → Models.kt → Mapper.kt → Provider.kt
- Capability registration: `ProviderCapability(type, priority, identifierRequirement)`
- `extra` map for non-standard IDs: `identifiers.withExtra("deezerId", id.toString())`
- Merger registration via `EnrichmentEngine.Builder.addMerger()`

### Integration Points
- `DeezerApi.kt` — add `getRelatedArtists(artistId: Long, limit: Int)` method
- `DeezerModels.kt` — add `DeezerRelatedArtist(id, name)` DTO
- `DeezerMapper.kt` — add `toSimilarArtists()` method
- `DeezerProvider.kt` — add SIMILAR_ARTISTS capability + `enrichSimilarArtists()` dispatch
- `LastFmMapper.kt` + `ListenBrainzMapper.kt` — populate `sources` field on SimilarArtist
- `EnrichmentEngine.kt` (Builder) — register SimilarArtistMerger via addMerger()
- `EnrichmentData.kt` — add `sources` field to SimilarArtist

</code_context>

<specifics>
## Specific Ideas

- Reference plan suggests Deezer at priority 30 (not 50) since it's the least semantic of the three sources
- Research warns: Deezer artist ID must be resolved via searchArtist, not assumed cached — check `identifiers.extra["deezerId"]` first, fall back to search
- ListenBrainz scores are already normalized internally (0-1 range) per existing provider code

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope.

</deferred>
