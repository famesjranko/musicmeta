---
phase: 05-deepening
plan: 02
subsystem: api
tags: [lastfm, listenbrainz, track-popularity, batch-endpoints, http-post]

requires:
  - phase: 01-bug-fixes
    provides: "Last.fm TRACK_POPULARITY removed due to artist-level data bug"
  - phase: 02-provider-abstraction
    provides: "IdentifierRequirement enum for typed provider skipping"
provides:
  - "Last.fm TRACK_POPULARITY capability restored via track.getInfo"
  - "ListenBrainz batch POST endpoints for recording and artist popularity"
  - "ListenBrainz top-release-groups-for-artist endpoint"
  - "HttpClient postJson/postJsonArray methods for POST requests"
  - "ListenBrainz TRACK_POPULARITY as fallback (priority 50)"
affects: [05-deepening]

tech-stack:
  added: []
  patterns: ["HttpClient POST methods for batch API endpoints", "Batch endpoint with single-item fallback pattern"]

key-files:
  created: []
  modified:
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/http/HttpClient.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/http/DefaultHttpClient.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/lastfm/LastFmApi.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/lastfm/LastFmModels.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/lastfm/LastFmMapper.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/lastfm/LastFmProvider.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/listenbrainz/ListenBrainzApi.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/listenbrainz/ListenBrainzModels.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/listenbrainz/ListenBrainzMapper.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/listenbrainz/ListenBrainzProvider.kt

key-decisions:
  - "Last.fm TRACK_POPULARITY uses track.getInfo for track-level playcount, not artist.getinfo"
  - "ListenBrainz batch POST returns JSON arrays, requiring postJsonArray on HttpClient"
  - "ListenBrainz ARTIST_POPULARITY uses batch endpoint with top-recordings fallback"
  - "ListenBrainz TRACK_POPULARITY at priority 50 (fallback to Last.fm at 100)"

patterns-established:
  - "Batch-first with single-request fallback: try batch endpoint, fall back to existing GET if empty"

requirements-completed: [DEEP-03, DEEP-05]

duration: 5min
completed: 2026-03-21
---

# Phase 05 Plan 02: Track Popularity & Batch Endpoints Summary

**Last.fm TRACK_POPULARITY restored via track.getInfo with track-level playcount/listeners; ListenBrainz batch POST endpoints for recording and artist popularity with top-recordings fallback**

## Performance

- **Duration:** 5 min
- **Started:** 2026-03-21T09:47:30Z
- **Completed:** 2026-03-21T09:52:36Z
- **Tasks:** 2
- **Files modified:** 10

## Accomplishments
- Restored Last.fm TRACK_POPULARITY using track.getInfo (previously removed in BUG-03 for returning artist-level data)
- Added HttpClient postJson/postJsonArray methods enabling POST-based batch API calls
- Wired ListenBrainz batch recording popularity, batch artist popularity, and top release groups endpoints
- Both Last.fm (priority 100) and ListenBrainz (priority 50) now serve TRACK_POPULARITY through the provider chain

## Task Commits

Each task was committed atomically:

1. **Task 1: Last.fm track.getInfo for TRACK_POPULARITY** - `8b712a5` (feat)
2. **Task 2: ListenBrainz batch endpoints** - `7bbe3aa` (feat)

_Note: TDD tasks had RED/GREEN phases within each commit_

## Files Created/Modified
- `musicmeta-core/src/main/kotlin/.../http/HttpClient.kt` - Added postJson and postJsonArray interface methods
- `musicmeta-core/src/main/kotlin/.../http/DefaultHttpClient.kt` - POST implementation via HttpURLConnection
- `musicmeta-core/src/main/kotlin/.../lastfm/LastFmApi.kt` - Added getTrackInfo() calling track.getInfo
- `musicmeta-core/src/main/kotlin/.../lastfm/LastFmModels.kt` - Added LastFmTrackInfo data class
- `musicmeta-core/src/main/kotlin/.../lastfm/LastFmMapper.kt` - Added toTrackPopularity mapper
- `musicmeta-core/src/main/kotlin/.../lastfm/LastFmProvider.kt` - Added TRACK_POPULARITY capability and routing
- `musicmeta-core/src/main/kotlin/.../listenbrainz/ListenBrainzApi.kt` - Added batch recording/artist/release-group endpoints
- `musicmeta-core/src/main/kotlin/.../listenbrainz/ListenBrainzModels.kt` - Added RecordingPopularity, ArtistPopularity, TopReleaseGroup models
- `musicmeta-core/src/main/kotlin/.../listenbrainz/ListenBrainzMapper.kt` - Added track/artist popularity and discography mappers
- `musicmeta-core/src/main/kotlin/.../listenbrainz/ListenBrainzProvider.kt` - Added TRACK_POPULARITY, refactored to batch-first routing
- `musicmeta-core/src/test/.../lastfm/LastFmProviderTest.kt` - Track popularity tests
- `musicmeta-core/src/test/.../listenbrainz/ListenBrainzProviderTest.kt` - Batch endpoint and fallback tests
- `musicmeta-core/src/test/.../testutil/FakeHttpClient.kt` - POST support for tests

## Decisions Made
- Last.fm TRACK_POPULARITY uses track.getInfo for track-level playcount (not artist.getinfo which was the BUG-03 issue)
- ListenBrainz batch POST endpoints return JSON arrays, requiring a separate postJsonArray method on HttpClient
- ListenBrainz ARTIST_POPULARITY uses batch endpoint first, falls back to existing top-recordings approach if batch returns empty
- ListenBrainz TRACK_POPULARITY set at priority 50 (fallback) while Last.fm is at priority 100 (primary) since Last.fm has larger user base

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Track popularity is fully functional through both providers
- HttpClient POST infrastructure ready for any future POST-based API integrations
- ListenBrainz top-release-groups endpoint wired and mapper ready for ARTIST_DISCOGRAPHY capability addition

## Self-Check: PASSED

All 10 source files verified present. Both task commits (8b712a5, 7bbe3aa) verified in git log. SUMMARY.md created.

---
*Phase: 05-deepening*
*Completed: 2026-03-21*
