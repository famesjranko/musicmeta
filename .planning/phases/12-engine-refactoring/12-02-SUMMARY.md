---
phase: 12-engine-refactoring
plan: "02"
subsystem: engine
tags: [kotlin, enrichment-engine, strategy-pattern, ResultMerger, CompositeSynthesizer, GenreMerger, TimelineSynthesizer]

requires:
  - phase: 12-01
    provides: ResultMerger interface, CompositeSynthesizer interface, GenreMerger object, TimelineSynthesizer object

provides:
  - DefaultEnrichmentEngine delegates all mergeable-type dispatch to ResultMerger instances via map lookup
  - DefaultEnrichmentEngine delegates all composite-type dispatch to CompositeSynthesizer instances via map lookup
  - mergeableTypes and compositeDependencies derived from registered mergers/synthesizers (no hardcoded companion sets)
  - EnrichmentEngine.Builder exposes addMerger() and addSynthesizer() for external extensibility
  - DefaultEnrichmentEngine constructor accepts mergers/synthesizers with backward-compatible defaults

affects:
  - 13-similar-artists
  - 16-genre-discovery

tech-stack:
  added: []
  patterns:
    - Strategy pattern: mergers and synthesizers are pluggable via constructor injection
    - Backward-compatible defaults: listOf(GenreMerger) and listOf(TimelineSynthesizer) as constructor defaults
    - Map-based dispatch: associateBy(type) for O(1) lookup at runtime

key-files:
  created: []
  modified:
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/engine/DefaultEnrichmentEngine.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/EnrichmentEngine.kt

key-decisions:
  - "Default constructor params use listOf(GenreMerger)/listOf(TimelineSynthesizer) not emptyList() to ensure backward compat with tests that construct DefaultEnrichmentEngine directly"
  - "Builder pre-populates merger/synthesizer lists so withDefaultProviders() doesn't need to add them explicitly"
  - "mergeableTypes and compositeDependencies are computed properties (get() =) not stored vals, ensuring they reflect any mutation of the underlying maps"

requirements-completed: [ENG-01, ENG-02, ENG-03]

duration: 5min
completed: 2026-03-22
---

# Phase 12 Plan 02: Engine Refactoring (Delegation) Summary

**DefaultEnrichmentEngine refactored to delegate genre merging and composite synthesis to pluggable ResultMerger and CompositeSynthesizer strategies via map lookup, removing 65 lines of inline dispatch logic**

## Performance

- **Duration:** 5 min
- **Started:** 2026-03-22T13:37:00Z
- **Completed:** 2026-03-22T13:39:57Z
- **Tasks:** 2
- **Files modified:** 2

## Accomplishments

- Removed three inline methods (`mergeGenreResults`, `synthesizeComposite`, `synthesizeTimeline`) from DefaultEnrichmentEngine — logic now lives in GenreMerger.merge() and TimelineSynthesizer.synthesize()
- Removed hardcoded `MERGEABLE_TYPES` and `COMPOSITE_DEPENDENCIES` companion sets — engine now discovers these from registered mergers/synthesizers
- Engine reduced from 351 to 286 lines (under 300 limit); builder updated with `addMerger()` and `addSynthesizer()` extension points
- All existing tests pass without modification (backward compat via default constructor params)

## Task Commits

Each task was committed atomically:

1. **Task 1: Refactor DefaultEnrichmentEngine to accept and delegate to ResultMerger and CompositeSynthesizer registries** - `96cc3f2` (refactor)
2. **Task 2: Update EnrichmentEngine.Builder to wire default mergers and synthesizers** - `6238618` (feat)

## Files Created/Modified

- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/engine/DefaultEnrichmentEngine.kt` - Removed inline dispatch methods; added mergers/synthesizers constructor params and map lookups
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/EnrichmentEngine.kt` - Added GenreMerger/TimelineSynthesizer imports, builder lists, addMerger()/addSynthesizer() methods, and build() wiring

## Decisions Made

- Default constructor params use `listOf(GenreMerger)` and `listOf(TimelineSynthesizer)` rather than `emptyList()` so existing test code that constructs `DefaultEnrichmentEngine(registry, cache, httpClient, config)` continues to work without modification
- Builder pre-populates merger/synthesizer lists with the same defaults, so `withDefaultProviders()` doesn't need to add them again
- `mergeableTypes` and `compositeDependencies` are computed properties (`get() =`) rather than stored `val`s, reflecting the merged map structure cleanly

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None.

## Next Phase Readiness

- Phase 13 (Similar Artists) can now implement `SimilarArtistsMerger : ResultMerger` and register it via `builder.addMerger(SimilarArtistsMerger)` — no engine changes needed
- Phase 16 (Genre Discovery) can implement `GenreDiscoverySynthesizer : CompositeSynthesizer` with GENRE as a dependency — no engine changes needed
- Engine is at 286 lines (well under 300 limit), ready for future extension without modification

---
*Phase: 12-engine-refactoring*
*Completed: 2026-03-22*
