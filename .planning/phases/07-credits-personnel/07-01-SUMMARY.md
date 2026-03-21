---
phase: 07-credits-personnel
plan: "01"
subsystem: provider
tags: [musicbrainz, credits, kotlin, serialization, tdd]

# Dependency graph
requires:
  - phase: 06-tech-debt-cleanup
    provides: HttpResult/ErrorKind uniform patterns and MusicBrainzApi rateLimiter.execute block pattern
provides:
  - CREDITS EnrichmentType with 30-day TTL
  - Credits and Credit @Serializable data classes with roleCategory field
  - MusicBrainzCredit DTO
  - parseRecordingCredits parser for artist-rels and work-rels
  - toCredits mapper
  - lookupRecording API method (inc=artist-rels+work-rels)
  - MusicBrainz CREDITS capability at priority 100 with MUSICBRAINZ_ID requirement
affects:
  - 07-02 (Discogs credits provider builds on Credits/Credit data classes)
  - future phases using ForTrack + CREDITS enrichment

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "lookupRecording returns raw JSONObject (not model) to avoid bloating MusicBrainzRecording with optional relation fields"
    - "enrichTrackCredits dispatched via type check at top of enrichTrack, following enrichAlbumTracks pattern"
    - "work-rels parsed via nested relations array inside performance-type work objects"

key-files:
  created: []
  modified:
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/EnrichmentType.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/EnrichmentData.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/musicbrainz/MusicBrainzModels.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/musicbrainz/MusicBrainzParser.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/musicbrainz/MusicBrainzMapper.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/musicbrainz/MusicBrainzApi.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/musicbrainz/MusicBrainzProvider.kt
    - musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/provider/musicbrainz/MusicBrainzParserTest.kt
    - musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/provider/musicbrainz/MusicBrainzProviderTest.kt
    - musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/e2e/EnrichmentShowcaseTest.kt

key-decisions:
  - "lookupRecording returns raw JSONObject to avoid bloating MusicBrainzRecording with optional relation fields only present during lookup"
  - "enrichTrackCredits dispatched via type-check guard at top of enrichTrack, consistent with enrichAlbumTracks pattern"
  - "Work-rel composer/lyricist/arranger extracted from nested work.relations array when top-level rel type is performance and target-type is work"

patterns-established:
  - "mapArtistRelType/mapWorkRelType private helpers centralize role-to-category mapping in MusicBrainzParser"

requirements-completed: [CRED-01, CRED-02, CRED-04]

# Metrics
duration: 25min
completed: 2026-03-22
---

# Phase 07 Plan 01: Credits Foundation Summary

**CREDITS enrichment type with MusicBrainz recording lookup parsing 11 role types (vocal, instrument, performer, producer, engineer, mixer, mastering, recording engineer, composer, lyricist, arranger) into Credits/Credit @Serializable data classes with performance/production/songwriting roleCategory**

## Performance

- **Duration:** ~25 min
- **Started:** 2026-03-22T16:15:00Z
- **Completed:** 2026-03-22T16:40:00Z
- **Tasks:** 2
- **Files modified:** 10

## Accomplishments

- CREDITS enum value added to EnrichmentType with 30-day TTL
- Credits/Credit @Serializable data classes with name, role, roleCategory, identifiers fields
- MusicBrainz lookupRecording endpoint with artist-rels+work-rels, parseRecordingCredits parser handling all 11 role types, toCredits mapper
- CREDITS capability wired at priority 100 with MUSICBRAINZ_ID requirement; enrichTrackCredits implemented with correct NotFound/Error handling

## Task Commits

Each task was committed atomically:

1. **Task 1: Define CREDITS type and data model + MusicBrainz API/Parser/Mapper** - `dab2d3d` (feat)
2. **Task 2: Wire MusicBrainz CREDITS capability into Provider** - `84aa589` (feat)

## Files Created/Modified

- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/EnrichmentType.kt` - Added CREDITS(30-day TTL) in Relationships section
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/EnrichmentData.kt` - Added Credits inner class and top-level Credit data class with roleCategory
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/musicbrainz/MusicBrainzModels.kt` - Added MusicBrainzCredit DTO
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/musicbrainz/MusicBrainzParser.kt` - Added parseRecordingCredits, mapArtistRelType, mapWorkRelType private helpers
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/musicbrainz/MusicBrainzMapper.kt` - Added toCredits function
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/musicbrainz/MusicBrainzApi.kt` - Added lookupRecording method
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/musicbrainz/MusicBrainzProvider.kt` - Added CREDITS capability, enrichTrackCredits, CREDITS dispatch in enrichTrack
- `musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/provider/musicbrainz/MusicBrainzParserTest.kt` - Added 7 tests (5 parseRecordingCredits, toCredits, serialization)
- `musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/provider/musicbrainz/MusicBrainzProviderTest.kt` - Added 5 provider tests for CREDITS capability and enrichTrackCredits
- `musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/e2e/EnrichmentShowcaseTest.kt` - Added Credits branch to exhaustive when expression

## Decisions Made

- `lookupRecording` returns raw `JSONObject` rather than a typed model: recording lookup responses with inc=artist-rels+work-rels have deeply nested optional structures that don't fit cleanly in `MusicBrainzRecording` which is used for search results. Returning raw JSON to the parser keeps models lean.
- `enrichTrackCredits` dispatched via `if (type == EnrichmentType.CREDITS) return enrichTrackCredits(request)` at the top of `enrichTrack`, consistent with how `enrichAlbumTracks` is dispatched in `enrichAlbum`.
- Work-rels (composer/lyricist/arranger) extracted from `work.relations` array when the top-level relation is `type="performance"` and `target-type="work"`. This matches the actual MusicBrainz API structure where songwriting credits live on the work, not directly on the recording.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] Added Credits branch to exhaustive when in EnrichmentShowcaseTest**
- **Found during:** Task 1 (after adding Credits sealed class subtype)
- **Issue:** EnrichmentShowcaseTest.kt had an exhaustive `when` over `EnrichmentData` subtypes. Adding `Credits` caused compilation failure.
- **Fix:** Added `is EnrichmentData.Credits -> data.credits.take(4).joinToString(", ") { "${it.name}(${it.role})" }` branch
- **Files modified:** musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/e2e/EnrichmentShowcaseTest.kt
- **Verification:** Tests compile and pass
- **Committed in:** dab2d3d (Task 1 commit)

---

**Total deviations:** 1 auto-fixed (Rule 2 - blocking compilation fix)
**Impact on plan:** Required fix for sealed class exhaustive when. No scope creep.

## Issues Encountered

None - all TDD phases (RED/GREEN) executed as expected.

## Known Stubs

None - Credits data wired from MusicBrainz API via lookupRecording with artist-rels+work-rels.

## Next Phase Readiness

- CREDITS foundation complete; Plan 02 (Discogs credits provider) can reuse Credits/Credit data classes directly
- All existing tests still pass (328+ tests)
- MusicBrainz CREDITS at priority 100; Discogs will be added at priority 50 as fallback

## Self-Check: PASSED

All files exist. Both task commits verified in git history.

---
*Phase: 07-credits-personnel*
*Completed: 2026-03-22*
