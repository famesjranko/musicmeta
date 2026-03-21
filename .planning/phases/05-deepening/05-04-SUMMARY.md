---
phase: 05-deepening
plan: 04
subsystem: engine
tags: [confidence, scoring, standardization, providers]

requires:
  - phase: 05-deepening (plans 01-03)
    provides: all provider confidence values settled after deepening changes
provides:
  - ConfidenceCalculator utility object with 4 standardized scoring methods
  - all 11 providers using ConfidenceCalculator instead of hardcoded floats
affects: [any future provider additions, confidence tuning]

tech-stack:
  added: []
  patterns: [ConfidenceCalculator for all provider confidence scoring]

key-files:
  created:
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/engine/ConfidenceCalculator.kt
    - musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/engine/ConfidenceCalculatorTest.kt
  modified:
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/musicbrainz/MusicBrainzProvider.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/coverartarchive/CoverArtArchiveProvider.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/deezer/DeezerProvider.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/itunes/ITunesProvider.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/lastfm/LastFmProvider.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/listenbrainz/ListenBrainzProvider.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/fanarttv/FanartTvProvider.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/wikidata/WikidataProvider.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/wikipedia/WikipediaProvider.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/discogs/DiscogsProvider.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/lrclib/LrcLibProvider.kt

key-decisions:
  - "ConfidenceCalculator uses 4 semantic tiers: idBasedLookup (1.0), authoritative (0.95), searchScore (0-1), fuzzyMatch (0.8/0.6)"
  - "MBID-based providers (Wikidata, Fanart.tv, ListenBrainz) promoted to authoritative (0.95) from prior 0.85-0.9"
  - "Search-only providers (Deezer, iTunes, Discogs, LrcLib search, Wikipedia photo) normalized to fuzzyMatch values"

patterns-established:
  - "ConfidenceCalculator: all new providers must use ConfidenceCalculator methods, no raw float confidence values"

requirements-completed: [DEEP-06]

duration: 7min
completed: 2026-03-21
---

# Phase 05 Plan 04: Confidence Scoring Standardization Summary

**ConfidenceCalculator utility with 4 semantic scoring methods replacing hardcoded floats across all 11 providers**

## Performance

- **Duration:** 7 min
- **Started:** 2026-03-21T10:03:11Z
- **Completed:** 2026-03-21T10:10:30Z
- **Tasks:** 2
- **Files modified:** 17

## Accomplishments
- Created ConfidenceCalculator object with idBasedLookup (1.0), authoritative (0.95), searchScore (0-1 clamped), and fuzzyMatch (0.8/0.6) methods
- Replaced all hardcoded confidence constants across 11 providers with ConfidenceCalculator calls
- Updated 4 test assertions for providers whose confidence values shifted (ListenBrainz 0.85->0.95, Wikidata 0.9->0.95, Wikipedia photo 0.7->0.6, LrcLib search 0.7->0.6)
- All 328 tests pass, no hardcoded confidence constants remain in provider code

## Task Commits

Each task was committed atomically:

1. **Task 1: Create ConfidenceCalculator utility with tests (TDD)**
   - `57e584b` (test) - failing tests for ConfidenceCalculator
   - `de11c92` (feat) - implement ConfidenceCalculator with 4 methods
2. **Task 2: Update all 11 providers to use ConfidenceCalculator** - `c9ca787` (refactor)

## Files Created/Modified
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/engine/ConfidenceCalculator.kt` - Standardized confidence scoring utility with 4 methods
- `musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/engine/ConfidenceCalculatorTest.kt` - 7 tests covering all methods and edge cases
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/musicbrainz/MusicBrainzProvider.kt` - idBasedLookup() and searchScore()
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/coverartarchive/CoverArtArchiveProvider.kt` - idBasedLookup()
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/deezer/DeezerProvider.kt` - fuzzyMatch(true/false) depending on ArtistMatcher usage
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/itunes/ITunesProvider.kt` - fuzzyMatch(true)
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/lastfm/LastFmProvider.kt` - fuzzyMatch(true)
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/listenbrainz/ListenBrainzProvider.kt` - authoritative()
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/fanarttv/FanartTvProvider.kt` - authoritative()
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/wikidata/WikidataProvider.kt` - authoritative()
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/wikipedia/WikipediaProvider.kt` - authoritative() for bio, fuzzyMatch(false) for photo
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/discogs/DiscogsProvider.kt` - fuzzyMatch(false)
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/lrclib/LrcLibProvider.kt` - authoritative() for exact, fuzzyMatch(false) for search

## Decisions Made
- ConfidenceCalculator uses 4 semantic tiers matching the PRD specification
- MBID-based providers (Wikidata 0.9->0.95, Fanart.tv 0.9->0.95, ListenBrainz 0.85->0.95) promoted to authoritative tier since they use deterministic ID lookups with reliable data
- Search-only providers normalized: Deezer (0.7->0.6 without artist match, 0.8 with), iTunes (0.65->0.8), LrcLib search (0.7->0.6), Wikipedia photo (0.7->0.6)
- Deezer enrichAlbumMetadata uses fuzzyMatch(true) since it verifies artist name via ArtistMatcher.isMatch (plan text said false but code uses artist matching)

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Test import used kotlin.test instead of org.junit.Assert**
- **Found during:** Task 1 (TDD RED phase)
- **Issue:** Plan template used `kotlin.test.assertEquals` which is not a test dependency; project uses `org.junit.Assert`
- **Fix:** Changed import to `org.junit.Assert.assertEquals` and added float delta parameter
- **Files modified:** ConfidenceCalculatorTest.kt
- **Committed in:** de11c92 (part of GREEN phase commit)

**2. [Rule 1 - Bug] Deezer enrichAlbumMetadata confidence category correction**
- **Found during:** Task 2
- **Issue:** Plan said enrichAlbumMetadata should use `fuzzyMatch(false)` but code uses `ArtistMatcher.isMatch`, which is artist verification
- **Fix:** Used `fuzzyMatch(true)` to accurately reflect the artist matching behavior
- **Files modified:** DeezerProvider.kt
- **Committed in:** c9ca787

---

**Total deviations:** 2 auto-fixed (2 bug fixes)
**Impact on plan:** Both fixes ensure correctness. No scope creep.

## Issues Encountered
- 4 existing tests had hardcoded confidence assertions that needed updating after value shifts (ListenBrainz, Wikidata, Wikipedia photo, LrcLib search). All updated in Task 2 commit.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Phase 05 (Deepening) is now complete with all 4 plans executed
- All providers use standardized confidence scoring via ConfidenceCalculator
- Ready for milestone v0.4.0 completion verification

## Self-Check: PASSED

All created files verified present. All commit hashes (57e584b, de11c92, c9ca787) confirmed in git log.

---
*Phase: 05-deepening*
*Completed: 2026-03-21*
