---
phase: 20-stale-cache
plan: 02
subsystem: cache
tags: [stale-cache, enrichment-engine, room, tdd, coroutines]

# Dependency graph
requires:
  - phase: 20-01
    provides: CacheMode enum, EnrichmentResult.isStale field, EnrichmentCache.getIncludingExpired interface method, EnrichmentConfig.cacheMode field
provides:
  - STALE_IF_ERROR fallback logic in DefaultEnrichmentEngine (applyStaleCache method)
  - Stale re-cache guard (! isStale guard in cache write loop)
  - FakeEnrichmentCache.expiredStore and getIncludingExpired override for test use
  - EnrichmentCacheDao.getIncludingExpired query (no expiry filter)
  - RoomEnrichmentCache.getIncludingExpired override and deserializeEntity helper
  - 5 new engine tests covering all stale cache behaviors
affects: [21-bulk-enrichment, 22-maven-central]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "applyStaleCache private suspend fun: iterates uncachedTypes, promotes Error/RateLimited to stale Success — NotFound is excluded because the provider confirmed absence"
    - "!result.isStale guard in cache write loop: prevents expired data from re-receiving a fresh TTL"
    - "deserializeEntity helper in RoomEnrichmentCache: factors shared deserialization logic between get() and getIncludingExpired()"
    - "FakeEnrichmentCache.expiredStore: separate map for test setup of expired-but-available data, checked second after stored"
    - "TDD via RED/GREEN commits: failing tests committed before implementation to confirm test validity"

key-files:
  created: []
  modified:
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/engine/DefaultEnrichmentEngine.kt
    - musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/engine/DefaultEnrichmentEngineTest.kt
    - musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/testutil/FakeEnrichmentCache.kt
    - musicmeta-android/src/main/kotlin/com/landofoz/musicmeta/android/cache/EnrichmentCacheDao.kt
    - musicmeta-android/src/main/kotlin/com/landofoz/musicmeta/android/cache/RoomEnrichmentCache.kt

key-decisions:
  - "applyStaleCache operates on uncachedTypes (not all types): types already served from fresh cache never need stale fallback"
  - "NotFound excluded from stale fallback: provider confirmed absence, serving stale data would mislead consumers into thinking prior data is valid when the provider has definitively not found it"
  - "applyStaleCache inserted before cache write loop: stale results are visible but the !isStale guard prevents them from being re-cached"

patterns-established:
  - "Stale fallback is post-processing: engine runs providers normally, then applyStaleCache upgrades Error/RateLimited results to stale Success — clean separation of concerns"
  - "isStale propagation: stale.copy(isStale = true) preserves all original result fields while marking degraded mode"

requirements-completed: [CACHE-02, CACHE-03, CACHE-04, CACHE-06]

# Metrics
duration: 15min
completed: 2026-03-24
---

# Phase 20 Plan 02: Stale Cache Engine Wiring Summary

**STALE_IF_ERROR fallback wired into DefaultEnrichmentEngine: expired cache served on Error/RateLimited with isStale=true, stale results excluded from re-caching, RoomEnrichmentCache and FakeEnrichmentCache updated with getIncludingExpired support**

## Performance

- **Duration:** 15 min
- **Started:** 2026-03-24T11:10:00Z
- **Completed:** 2026-03-24T11:25:00Z
- **Tasks:** 2
- **Files modified:** 5

## Accomplishments
- FakeEnrichmentCache gains `expiredStore` map and `getIncludingExpired` override for stale test setup
- EnrichmentCacheDao adds `getIncludingExpired` query without the `expires_at > :now` filter
- RoomEnrichmentCache extracts `deserializeEntity` helper and adds `getIncludingExpired` override
- DefaultEnrichmentEngine: `applyStaleCache()` called after timeout catch when `STALE_IF_ERROR` mode; promotes `Error`/`RateLimited` results to expired-cache `Success` with `isStale=true`
- Cache write guard updated to `result is EnrichmentResult.Success && !result.isStale` — prevents stale data from receiving a fresh TTL
- 5 new engine tests pass (Error fallback, RateLimited fallback, NotFound excluded, NETWORK_FIRST unaffected, stale re-cache guard); all existing tests pass

## Task Commits

Each task was committed atomically:

1. **Task 1: Update FakeEnrichmentCache, EnrichmentCacheDao, and RoomEnrichmentCache** - `6533844` (feat)
2. **Task 2 RED: Add failing stale cache tests** - `44a15ad` (test)
3. **Task 2 GREEN: Wire STALE_IF_ERROR into DefaultEnrichmentEngine** - `4e18877` (feat)

_Note: TDD task has two commits (test RED → feat GREEN)_

## Files Created/Modified
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/engine/DefaultEnrichmentEngine.kt` - Added CacheMode import, `applyStaleCache()` method, STALE_IF_ERROR check, `!result.isStale` cache write guard
- `musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/engine/DefaultEnrichmentEngineTest.kt` - 5 new stale cache tests with `staleConfig` and `staleEngine()` helpers
- `musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/testutil/FakeEnrichmentCache.kt` - Added `expiredStore`, `getIncludingExpired` override, `expiredStore.clear()` in `clear()`
- `musicmeta-android/src/main/kotlin/com/landofoz/musicmeta/android/cache/EnrichmentCacheDao.kt` - Added `getIncludingExpired` DAO query without expiry filter
- `musicmeta-android/src/main/kotlin/com/landofoz/musicmeta/android/cache/RoomEnrichmentCache.kt` - Extracted `deserializeEntity()` helper, added `getIncludingExpired()` override

## Decisions Made
- `applyStaleCache` operates on `uncachedTypes` not all types: types already in the fresh cache never need stale consideration — the stale path is only for types that went to providers
- `NotFound` excluded from stale fallback: a provider returning NotFound means it definitively searched and found nothing; serving stale data would mislead consumers
- The `applyStaleCache` call is placed after the timeout catch block (before the cache write loop) so stale results are present in `results` but the `!isStale` guard ensures they are not persisted with a fresh TTL

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Stale cache feature is complete end-to-end: contracts (Plan 01), InMemoryEnrichmentCache (Plan 01), FakeEnrichmentCache, RoomEnrichmentCache, and engine logic (Plan 02) all implemented and tested
- Phase 20 (stale cache) is fully delivered
- Phase 21 (bulk enrichment) can begin

## Self-Check: PASSED

All 5 key files found. All 3 task commits verified (6533844, 44a15ad, 4e18877).

---
*Phase: 20-stale-cache*
*Completed: 2026-03-24*
