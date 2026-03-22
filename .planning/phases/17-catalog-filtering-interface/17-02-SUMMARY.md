---
phase: 17-catalog-filtering-interface
plan: 02
subsystem: engine
tags: [catalog-filtering, recommendation, enrichment-engine, kotlin, coroutines]

# Dependency graph
requires:
  - phase: 17-01
    provides: CatalogProvider interface, CatalogFilterMode enum, CatalogQuery, CatalogMatch types, EnrichmentConfig.catalogProvider/catalogFilterMode fields

provides:
  - applyCatalogFiltering() private method in DefaultEnrichmentEngine, called after resolveTypes() and before cache.put()
  - CatalogFilter.kt with RECOMMENDATION_TYPES constant, toQueries(), applyMode(), reorderData() helpers
  - 9 unit tests in CatalogFilteringTest.kt covering all three filter modes and edge cases

affects: [phase-18-documentation, any phase adding new recommendation types]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Filtering helpers extracted to CatalogFilter.kt to keep DefaultEnrichmentEngine.kt under 300 lines"
    - "Delegation pattern: engine calls applyCatalogFiltering() which delegates to top-level helpers in CatalogFilter.kt"

key-files:
  created:
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/engine/CatalogFilter.kt
    - musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/engine/CatalogFilteringTest.kt
  modified:
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/engine/DefaultEnrichmentEngine.kt

key-decisions:
  - "Filtering helpers (toQueries, applyMode, reorderData) extracted to CatalogFilter.kt because DefaultEnrichmentEngine.kt was 286 lines and adding ~60 lines would exceed the 300-line max"
  - "AVAILABLE_ONLY with all items filtered out returns EnrichmentResult.NotFound rather than an empty list, preserving the sealed result type invariant"
  - "Filtering runs inside the withTimeout block so slow CatalogProvider implementations respect the engine timeout"

patterns-established:
  - "Recommendation post-processing: insert after resolveTypes(), before cache.put()"
  - "Batch availability check: one checkAvailability() call per recommendation type per enrich() call"

requirements-completed: [CAT-03, CAT-04]

# Metrics
duration: 3min
completed: 2026-03-22
---

# Phase 17 Plan 02: Catalog Filtering Behavior Summary

**Post-resolution catalog filtering via applyCatalogFiltering() wired into DefaultEnrichmentEngine, with AVAILABLE_ONLY, AVAILABLE_FIRST, and UNFILTERED modes and 9 unit tests**

## Performance

- **Duration:** 3 min
- **Started:** 2026-03-22T15:16:43Z
- **Completed:** 2026-03-22T15:19:09Z
- **Tasks:** 2 (1 TDD implementation, 1 regression verification)
- **Files modified:** 3

## Accomplishments

- Implemented `applyCatalogFiltering()` in `DefaultEnrichmentEngine` — called after `resolveTypes()`, before `cache.put()`, inside the timeout block
- Extracted filtering helpers (`toQueries`, `applyMode`, `reorderData`, `RECOMMENDATION_TYPES`) to `CatalogFilter.kt` to keep `DefaultEnrichmentEngine.kt` under the 300-line max
- Wrote 9 tests in `CatalogFilteringTest.kt` covering all modes, null-provider passthrough, non-recommendation type bypass, and the all-unavailable NotFound edge case
- Full test suite passes with no regressions

## Task Commits

Each task was committed atomically:

1. **Task 1: Implement applyCatalogFiltering() in DefaultEnrichmentEngine** - `15b2957` (feat)
2. **Task 2: Run full test suite** - no files changed, verification only

**Plan metadata:** (final docs commit, see below)

_Note: Task 1 used TDD — tests written first (RED), then implementation (GREEN), committed together._

## Files Created/Modified

- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/engine/CatalogFilter.kt` - RECOMMENDATION_TYPES constant, toQueries(), applyMode(), reorderData() top-level helpers
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/engine/DefaultEnrichmentEngine.kt` - Added CatalogFilterMode import, applyCatalogFiltering() method, call site after resolveTypes()
- `musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/engine/CatalogFilteringTest.kt` - 9 unit tests for all filter modes and edge cases

## Decisions Made

- Filtering helpers extracted to `CatalogFilter.kt` — `DefaultEnrichmentEngine.kt` was at 286 lines; adding 60 lines of helpers would exceed the 300-line max per CLAUDE.md
- `AVAILABLE_ONLY` with all items unavailable returns `EnrichmentResult.NotFound` — preserves the sealed type invariant that Success always has non-empty data
- Filtering runs inside `withTimeout` so a slow `CatalogProvider` respects the engine-level timeout

## Deviations from Plan

None — plan executed exactly as written. The extraction to `CatalogFilter.kt` was explicitly planned in the action block ("If total exceeds 300, extract...").

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- Phase 17 (Catalog Filtering Interface) is now complete — both plans executed
- Phase 18 (Documentation) can proceed; both the interface (plan 01) and behavior (plan 02) are shipped
- Adding new recommendation types in future phases requires adding them to `RECOMMENDATION_TYPES` in `CatalogFilter.kt`

---
*Phase: 17-catalog-filtering-interface*
*Completed: 2026-03-22*
