---
phase: 06-tech-debt-cleanup
plan: 03
subsystem: http
tags: [kotlin, httpclient, httpresult, errorkind, deezer, lastfm, discogs, listenbrainz, providers]

# Dependency graph
requires:
  - phase: 06-tech-debt-cleanup
    plan: 01
    provides: "fetchJsonResult, fetchJsonArrayResult, postJsonResult, postJsonArrayResult on HttpClient; FakeHttpClient.givenIoException()"
provides:
  - DeezerApi migrated from fetchJson to fetchJsonResult (4 call sites)
  - LastFmApi migrated from fetchJson to fetchJsonResult (5 call sites)
  - DiscogsApi migrated from fetchJson to fetchJsonResult (3 call sites)
  - ListenBrainzApi migrated from fetchJsonArray/postJsonArray to fetchJsonArrayResult/postJsonArrayResult (4 call sites)
  - All 4 Providers emit ErrorKind via mapError() helper
  - Error-path tests for all 4 providers
  - All 11 providers now use HttpResult/ErrorKind uniformly (DEBT-01 and DEBT-02 complete)
affects: [06-04, all future provider plans]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "HttpResult migration: Api classes use fetchJsonResult/fetchJsonArrayResult/postJsonArrayResult with when-else->null pattern"
    - "mapError() private helper in each Provider: IOException->NETWORK, JSONException->PARSE, else->UNKNOWN"
    - "ListenBrainz uses fetchJsonArrayResult and postJsonArrayResult (not fetchJsonResult)"

key-files:
  created: []
  modified:
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/deezer/DeezerApi.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/deezer/DeezerProvider.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/lastfm/LastFmApi.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/lastfm/LastFmProvider.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/discogs/DiscogsApi.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/discogs/DiscogsProvider.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/listenbrainz/ListenBrainzApi.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/listenbrainz/ListenBrainzProvider.kt
    - musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/provider/deezer/DeezerProviderTest.kt
    - musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/provider/lastfm/LastFmProviderTest.kt
    - musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/provider/discogs/DiscogsProviderTest.kt
    - musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/provider/listenbrainz/ListenBrainzProviderTest.kt

key-decisions:
  - "Api classes keep nullable return types: HttpResult error branches convert to null via when-else pattern, preserving existing Provider interface shape"
  - "ListenBrainzApi uses fetchJsonArrayResult/postJsonArrayResult (not fetchJsonResult) because all 4 endpoints return JSONArray responses"
  - "mapError() helper pattern applied consistently: IOException->NETWORK, JSONException->PARSE, else->UNKNOWN"

patterns-established:
  - "ListenBrainz-specific: use fetchJsonArrayResult/postJsonArrayResult for array-returning endpoints"

requirements-completed: [DEBT-01, DEBT-02]

# Metrics
duration: 15min
completed: 2026-03-22
---

# Phase 6 Plan 03: Deezer, Last.fm, Discogs, ListenBrainz HttpResult Migration Summary

**4 remaining providers migrated from nullable fetchJson/fetchJsonArray/postJsonArray to typed HttpResult variants with ErrorKind error classification, completing the DEBT-01 and DEBT-02 milestone across all 11 providers**

## Performance

- **Duration:** 15 min
- **Started:** 2026-03-22
- **Completed:** 2026-03-22
- **Tasks:** 2
- **Files modified:** 12

## Accomplishments
- Migrated DeezerApi (4 fetchJson sites), LastFmApi (5 fetchJson sites), DiscogsApi (3 fetchJson sites), and ListenBrainzApi (2 fetchJsonArray + 2 postJsonArray sites) to HttpResult variants
- Added mapError() private helper to all 4 Providers: IOException->NETWORK, JSONException->PARSE, else->UNKNOWN
- Added error-path unit test for each of the 4 providers using givenIoException()
- All 11 provider Api files now show 0 legacy nullable HTTP calls and all 11 providers have ErrorKind references

## Task Commits

Each task was committed atomically:

1. **Task 1: Migrate Deezer and Last.fm to HttpResult/ErrorKind** - `c49a0cc` (feat)
2. **Task 2: Migrate Discogs and ListenBrainz to HttpResult/ErrorKind** - `ef18b24` (feat)

## Files Created/Modified
- `provider/deezer/DeezerApi.kt` - 4 fetchJson -> fetchJsonResult
- `provider/deezer/DeezerProvider.kt` - Added mapError() IOException->NETWORK, JSONException->PARSE
- `provider/lastfm/LastFmApi.kt` - 5 fetchJson -> fetchJsonResult
- `provider/lastfm/LastFmProvider.kt` - Added mapError() IOException->NETWORK, JSONException->PARSE
- `provider/discogs/DiscogsApi.kt` - 3 fetchJson -> fetchJsonResult
- `provider/discogs/DiscogsProvider.kt` - Added mapError() IOException->NETWORK, JSONException->PARSE
- `provider/listenbrainz/ListenBrainzApi.kt` - 2 fetchJsonArray -> fetchJsonArrayResult, 2 postJsonArray -> postJsonArrayResult
- `provider/listenbrainz/ListenBrainzProvider.kt` - Added mapError() + try/catch in enrichArtistPopularity and enrichTrackPopularity
- 4 Provider test files - Added ErrorKind.NETWORK error-path test per provider

## Decisions Made
- ListenBrainzApi uses `fetchJsonArrayResult` and `postJsonArrayResult` (not `fetchJsonResult`) because all 4 ListenBrainz endpoints return JSONArray responses, not JSONObject.
- Api classes keep nullable return types via when-else->null pattern to preserve existing Provider interface shape (consistent with Plan 01 decision).

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- DEBT-01 (HttpResult migration) and DEBT-02 (ErrorKind adoption) are now complete across all 11 providers
- All 11 Api files: legacy=0, modern >= 1; all 11 Provider files: ErrorKind refs >= 3
- Plan 06-04 (ListenBrainz/Discogs wiring) is now unblocked

---
*Phase: 06-tech-debt-cleanup*
*Completed: 2026-03-22*

## Self-Check: PASSED

- FOUND: DeezerApi.kt
- FOUND: LastFmApi.kt
- FOUND: DiscogsApi.kt
- FOUND: ListenBrainzApi.kt
- FOUND: 06-03-SUMMARY.md
- FOUND: c49a0cc (Task 1 commit)
- FOUND: ef18b24 (Task 2 commit)
