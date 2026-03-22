---
phase: 12-engine-refactoring
plan: "01"
subsystem: engine
tags: [kotlin, interfaces, strategy-pattern, genre-merging, composite-types]

# Dependency graph
requires: []
provides:
  - ResultMerger interface with type + merge(List<Success>) contract
  - CompositeSynthesizer interface with type + dependencies + synthesize() contract
  - GenreMerger implementing ResultMerger for GENRE type
  - TimelineSynthesizer implementing CompositeSynthesizer for ARTIST_TIMELINE type
affects:
  - 13-similar-artists (SimilarArtistMerger will implement ResultMerger)
  - 16-genre-discovery (GENRE_DISCOVERY synthesizer will implement CompositeSynthesizer)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "ResultMerger strategy: implement interface with type + merge(List<Success>) for multi-provider merge types"
    - "CompositeSynthesizer strategy: implement interface with type + dependencies + synthesize(resolved, identity, request) for composite types"

key-files:
  created:
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/engine/ResultMerger.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/engine/CompositeSynthesizer.kt
    - musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/engine/ResultMergerTest.kt
    - musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/engine/CompositeSynthesizerTest.kt
  modified:
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/engine/GenreMerger.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/engine/TimelineSynthesizer.kt

key-decisions:
  - "Interfaces define extension points without modifying DefaultEnrichmentEngine: Phase 13 and Phase 16 plug in by implementing ResultMerger/CompositeSynthesizer respectively"
  - "Both GenreMerger and TimelineSynthesizer remain objects (singletons): stateless strategy pattern preserved"
  - "Existing merge(tags)/synthesize(metadata, discography, bandMembers) methods kept as internal implementation details: new interface overloads delegate to them"

patterns-established:
  - "ResultMerger: object implements interface — add override val type and override fun merge(results) delegating to internal logic"
  - "CompositeSynthesizer: object implements interface — add override val type, override val dependencies, and override fun synthesize(resolved, identity, request) delegating to internal logic"

requirements-completed: [ENG-01, ENG-02]

# Metrics
duration: 2min
completed: "2026-03-22"
---

# Phase 12 Plan 01: Engine Interface Extraction Summary

**ResultMerger and CompositeSynthesizer strategy interfaces extracted with GenreMerger and TimelineSynthesizer as the first implementations**

## Performance

- **Duration:** 2 min
- **Started:** 2026-03-22T13:33:24Z
- **Completed:** 2026-03-22T13:35:29Z
- **Tasks:** 2
- **Files modified:** 6

## Accomplishments

- Created `ResultMerger` interface (type + merge(List<Success>) contract) and `CompositeSynthesizer` interface (type + dependencies + synthesize() contract) as extension points for future phases
- Adapted `GenreMerger` to implement `ResultMerger` with `type = GENRE` and a `merge(results)` overload that extracts genreTags from Metadata results and delegates to the existing tag-level `merge()`
- Adapted `TimelineSynthesizer` to implement `CompositeSynthesizer` with `type = ARTIST_TIMELINE`, `dependencies = {ARTIST_DISCOGRAPHY, BAND_MEMBERS}`, and a `synthesize(resolved, identity, request)` overload that delegates to the existing internal synthesizer
- Added contract-level test files (`ResultMergerTest`, `CompositeSynthesizerTest`) that verify the new interface methods without touching existing tests

## Task Commits

Each task was committed atomically:

1. **Task 1: Create ResultMerger and CompositeSynthesizer interfaces** - `b3e1ce7` (feat)
2. **Task 2 RED: Add failing interface contract tests** - `58985f3` (test)
3. **Task 2 GREEN: Adapt GenreMerger and TimelineSynthesizer** - `6485036` (feat)

**Plan metadata:** (docs commit)

_Note: TDD tasks have multiple commits (test RED → feat GREEN)_

## Files Created/Modified

- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/engine/ResultMerger.kt` - New interface: type + merge(results) contract
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/engine/CompositeSynthesizer.kt` - New interface: type + dependencies + synthesize() contract
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/engine/GenreMerger.kt` - Now implements ResultMerger; added merge(List<Success>) overload
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/engine/TimelineSynthesizer.kt` - Now implements CompositeSynthesizer; added synthesize(resolved, identity, request) overload
- `musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/engine/ResultMergerTest.kt` - New: validates GenreMerger via ResultMerger interface
- `musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/engine/CompositeSynthesizerTest.kt` - New: validates TimelineSynthesizer via CompositeSynthesizer interface

## Decisions Made

- Interfaces define extension points without modifying `DefaultEnrichmentEngine`: Phase 13 and Phase 16 plug in by implementing `ResultMerger`/`CompositeSynthesizer` respectively
- Both `GenreMerger` and `TimelineSynthesizer` remain `object` singletons: stateless strategy pattern preserved
- Existing `merge(tags)` / `synthesize(metadata, discography, bandMembers)` methods kept as internal implementation details: new interface overloads delegate to them, avoiding duplication

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- `ResultMerger` interface ready for Phase 13 to add `SimilarArtistMerger : ResultMerger` without touching `DefaultEnrichmentEngine`
- `CompositeSynthesizer` interface ready for Phase 16 to add a `GENRE_DISCOVERY` synthesizer
- All existing tests pass unchanged; new contract tests provide regression coverage for the interface methods

## Self-Check: PASSED

All created files exist on disk. All task commits verified in git log.

---
*Phase: 12-engine-refactoring*
*Completed: 2026-03-22*
