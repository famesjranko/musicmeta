---
phase: 17-catalog-filtering-interface
plan: 01
subsystem: api
tags: [catalog, filtering, recommendations, kotlin, interface]

# Dependency graph
requires: []
provides:
  - CatalogProvider fun interface for consumer catalog plug-in
  - CatalogFilterMode enum (UNFILTERED, AVAILABLE_ONLY, AVAILABLE_FIRST)
  - CatalogQuery data class for batch availability checks
  - CatalogMatch data class for per-item availability results
  - EnrichmentConfig.catalogProvider and catalogFilterMode fields
  - EnrichmentEngine.Builder.catalog() wiring method
affects: [17-02-catalog-filtering-engine, 18-documentation]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "fun interface (SAM) pattern for consumer-implementable single-method contracts"
    - "config.copy() for immutable config mutation in Builder methods"

key-files:
  created:
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/CatalogProvider.kt
  modified:
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/EnrichmentConfig.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/EnrichmentEngine.kt

key-decisions:
  - "CatalogProvider is a fun interface (SAM) so consumers can use lambda syntax for simple implementations"
  - "catalogProvider defaults to null and catalogFilterMode defaults to UNFILTERED — zero behavior change for existing consumers"
  - "Builder.catalog() uses config.copy() to preserve all other config fields when wiring catalog options"

patterns-established:
  - "Trailing nullable fields with null default for optional engine extensions (CatalogProvider pattern)"
  - "Builder convenience method using config.copy() for atomic multi-field config updates"

requirements-completed: [CAT-01, CAT-02, CAT-04]

# Metrics
duration: 2min
completed: 2026-03-23
---

# Phase 17 Plan 01: Catalog Filtering Interface Summary

**Public contract for consumer catalog plug-in: CatalogProvider fun interface, CatalogFilterMode enum, and backward-compatible wiring into EnrichmentConfig and Builder.catalog()**

## Performance

- **Duration:** 2 min
- **Started:** 2026-03-22T15:13:45Z
- **Completed:** 2026-03-22T15:15:30Z
- **Tasks:** 2
- **Files modified:** 3

## Accomplishments
- Created CatalogProvider.kt with all four types: CatalogFilterMode enum, CatalogQuery, CatalogMatch, and CatalogProvider fun interface
- Added catalogProvider and catalogFilterMode as trailing optional fields in EnrichmentConfig with backward-compatible defaults
- Added Builder.catalog() method in EnrichmentEngine using config.copy() to wire both fields atomically
- All existing tests pass — zero behavior change for consumers who don't use catalog filtering

## Task Commits

Each task was committed atomically:

1. **Task 1: Create CatalogProvider.kt** - `2e5a06c` (feat)
2. **Task 2: Wire into EnrichmentConfig and Builder** - `a96c29f` (feat)

## Files Created/Modified
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/CatalogProvider.kt` - New file: CatalogFilterMode, CatalogQuery, CatalogMatch, CatalogProvider fun interface
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/EnrichmentConfig.kt` - Added catalogProvider and catalogFilterMode fields with KDoc
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/EnrichmentEngine.kt` - Added Builder.catalog() method

## Decisions Made
- CatalogProvider is a `fun interface` (SAM) so consumers can use lambda syntax for simple implementations without a full class
- Both new EnrichmentConfig fields are placed as trailing params with safe defaults (null / UNFILTERED) ensuring all existing call sites compile unchanged
- Builder.catalog() uses `config.copy()` rather than storing separate fields, preserving all other config values atomically

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Public contract for catalog filtering is complete and compiled
- Plan 17-02 can implement catalog filtering logic inside DefaultEnrichmentEngine using config.catalogProvider and config.catalogFilterMode
- No blockers

---
*Phase: 17-catalog-filtering-interface*
*Completed: 2026-03-23*
