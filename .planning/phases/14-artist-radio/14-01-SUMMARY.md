---
phase: 14-artist-radio
plan: "01"
subsystem: api
tags: [deezer, enrichment-types, serialization, kotlin, data-model]

# Dependency graph
requires: []
provides:
  - "EnrichmentType.ARTIST_RADIO with 7-day TTL"
  - "EnrichmentData.RadioPlaylist sealed subclass"
  - "RadioTrack top-level @Serializable data class"
  - "DeezerRadioTrack DTO in DeezerModels.kt"
  - "DeezerMapper.toRadioPlaylist() mapper function"
affects: [14-02, plan-02-deezer-provider]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Top-level @Serializable data class for track types alongside sealed EnrichmentData subclasses"
    - "TDD RED-GREEN for contract definitions: test failures confirm missing types before implementation"

key-files:
  created:
    - musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/provider/deezer/DeezerMapperTest.kt
  modified:
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/EnrichmentType.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/EnrichmentData.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/deezer/DeezerModels.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/deezer/DeezerMapper.kt
    - musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/EnrichmentDataSerializationTest.kt
    - musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/e2e/EdgeAnalysisTest.kt
    - musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/e2e/EnrichmentShowcaseTest.kt

key-decisions:
  - "RadioTrack has no matchScore field — radio playlists are ordered sequences, not similarity rankings (per 14-CONTEXT.md D-01)"
  - "durationMs is null when durationSec is 0 — distinguishes unknown duration from zero-length tracks"
  - "DeezerRadioTrack DTOs follow the plain data class pattern (no @Serializable), consistent with all other Deezer DTOs"

patterns-established:
  - "RadioPlaylist inner class + RadioTrack top-level class: follow the SimilarArtists/SimilarArtist split pattern"

requirements-completed: [RAD-01, RAD-03]

# Metrics
duration: 8min
completed: 2026-03-22
---

# Phase 14 Plan 01: Artist Radio Data Contracts Summary

**ARTIST_RADIO enrichment type with RadioPlaylist/RadioTrack model and DeezerMapper.toRadioPlaylist() so Plan 02 has stable contracts to implement against**

## Performance

- **Duration:** ~8 min
- **Started:** 2026-03-22T14:17:00Z
- **Completed:** 2026-03-22T14:21:05Z
- **Tasks:** 2
- **Files modified:** 7

## Accomplishments

- Added `EnrichmentType.ARTIST_RADIO` with 7-day TTL (604800000ms) to the enum
- Defined `EnrichmentData.RadioPlaylist` sealed subclass and top-level `RadioTrack` @Serializable data class with no matchScore field
- Added `DeezerRadioTrack` DTO and `DeezerMapper.toRadioPlaylist()` with correct durationMs conversion and deezerId identifier storage

## Task Commits

Each task was committed atomically:

1. **Task 1: Add ARTIST_RADIO to EnrichmentType and define RadioPlaylist + RadioTrack** - `004f31d` (feat)
2. **Task 2: Add DeezerRadioTrack DTO to DeezerModels and toRadioPlaylist() mapper** - `38d8c88` (feat)

## Files Created/Modified

- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/EnrichmentType.kt` - Added ARTIST_RADIO enum value with 7-day TTL
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/EnrichmentData.kt` - Added RadioPlaylist sealed subclass and RadioTrack top-level class
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/deezer/DeezerModels.kt` - Added DeezerRadioTrack DTO
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/deezer/DeezerMapper.kt` - Added toRadioPlaylist() mapper with RadioTrack import
- `musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/EnrichmentDataSerializationTest.kt` - Added ARTIST_RADIO TTL test and RadioPlaylist round-trip test
- `musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/provider/deezer/DeezerMapperTest.kt` - Created with 6 tests covering all mapping behaviors
- `musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/e2e/EdgeAnalysisTest.kt` - Added RadioPlaylist branch to exhaustive when
- `musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/e2e/EnrichmentShowcaseTest.kt` - Added RadioPlaylist branch to exhaustive when

## Decisions Made

- RadioTrack has no matchScore field — radio playlists are ordered sequences, not similarity rankings
- durationMs is null when durationSec is 0 to distinguish unknown duration from zero-length tracks
- DeezerRadioTrack follows the plain data class pattern (no @Serializable), consistent with all existing Deezer DTOs

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed non-exhaustive when expressions in E2E tests**
- **Found during:** Task 1 (adding RadioPlaylist sealed subclass)
- **Issue:** Adding a new sealed subclass made two exhaustive `when` expressions in `EdgeAnalysisTest.kt` and `EnrichmentShowcaseTest.kt` fail to compile
- **Fix:** Added `is EnrichmentData.RadioPlaylist -> "${data.tracks.size} tracks"` branch to both when expressions
- **Files modified:** musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/e2e/EdgeAnalysisTest.kt, EnrichmentShowcaseTest.kt
- **Verification:** `./gradlew :musicmeta-core:compileTestKotlin` succeeds; all tests pass
- **Committed in:** `004f31d` (Task 1 commit)

---

**Total deviations:** 1 auto-fixed (Rule 1 - Bug)
**Impact on plan:** Necessary fix for compilation; consistent with how all prior sealed subclass additions were handled. No scope creep.

## Issues Encountered

None - the only issue was the expected exhaustive when expression update when adding a sealed subclass, handled as Rule 1 auto-fix.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- Plan 02 (DeezerRadioProvider) can now reference all contracts without data-layer concerns
- All existing tests pass with BUILD SUCCESSFUL
- No stubs — all data classes are fully implemented

---
*Phase: 14-artist-radio*
*Completed: 2026-03-22*
