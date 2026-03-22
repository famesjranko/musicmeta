# Phase 12: Engine Refactoring - Context

**Gathered:** 2026-03-23
**Status:** Ready for planning

<domain>
## Phase Boundary

Extract merger and composite dispatch logic from DefaultEnrichmentEngine into focused interfaces (ResultMerger and CompositeSynthesizer) so new mergeable types (SIMILAR_ARTISTS in Phase 13) and new composite types (GENRE_DISCOVERY in Phase 16) can be added without modifying the engine class. Bring the engine under 300 lines.

</domain>

<decisions>
## Implementation Decisions

### Claude's Discretion

All implementation choices are at Claude's discretion — pure infrastructure phase.

Key constraints from research and reference plan:
- ResultMerger interface should generalize the existing `mergeGenreResults()` method — GenreMerger becomes the first ResultMerger implementation
- CompositeSynthesizer interface should generalize `synthesizeComposite()` — TimelineSynthesizer becomes the first CompositeSynthesizer implementation
- MERGEABLE_TYPES and COMPOSITE_DEPENDENCIES companion sets should move into the new registries
- Existing tests must pass without modification (pure refactor, no behavior change)

</decisions>

<code_context>
## Existing Code Insights

### Reusable Assets
- `GenreMerger` object (67 lines) — pure function merge(tags) pattern, will implement ResultMerger
- `TimelineSynthesizer` object (114 lines) — pure function synthesize(metadata, discography, bandMembers) pattern, will implement CompositeSynthesizer
- `ProviderChain.resolve()` / `resolveAll()` — chain resolution is already clean

### Established Patterns
- Stateless object pattern (GenreMerger, TimelineSynthesizer, ConfidenceCalculator)
- Engine delegates to objects via companion set membership (MERGEABLE_TYPES, COMPOSITE_DEPENDENCIES)
- `DefaultEnrichmentEngine.kt` is currently 351 lines with these extractable blocks:
  - `mergeGenreResults()` (lines 215-244) — 30 lines, GENRE-specific merge dispatch
  - `synthesizeComposite()` + `synthesizeTimeline()` (lines 246-276) — 30 lines, ARTIST_TIMELINE-specific synthesis

### Integration Points
- `resolveTypes()` calls `mergeGenreResults()` for MERGEABLE_TYPES — will call ResultMerger.merge() instead
- `resolveTypes()` calls `synthesizeComposite()` for COMPOSITE_DEPENDENCIES — will call CompositeSynthesizer.synthesize() instead
- Phase 13 will add SIMILAR_ARTISTS to MERGEABLE_TYPES and register SimilarArtistMerger
- Phase 16 will add GENRE_DISCOVERY to COMPOSITE_DEPENDENCIES and register GenreAffinityMatcher

</code_context>

<specifics>
## Specific Ideas

No specific requirements — infrastructure phase.

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope.

</deferred>
