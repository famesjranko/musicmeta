---
phase: 20-stale-cache
plan: 01
subsystem: cache
tags: [cache, stale-if-error, kotlin, coroutines, inmemory]

# Dependency graph
requires: []
provides:
  - CacheMode enum with NETWORK_FIRST and STALE_IF_ERROR values
  - isStale: Boolean = false field on EnrichmentResult.Success
  - cacheMode: CacheMode = CacheMode.NETWORK_FIRST field on EnrichmentConfig
  - getIncludingExpired() default method on EnrichmentCache interface
  - InMemoryEnrichmentCache retains expired entries and serves them via getIncludingExpired()
affects:
  - 20-02 (engine wiring of STALE_IF_ERROR behavior uses these contracts)
  - musicmeta-android (RoomEnrichmentCache may implement getIncludingExpired)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Expired cache entries retained in LRU map — not eagerly deleted; LRU eviction handles memory"
    - "getIncludingExpired() accesses entries map directly under own mutex.withLock to avoid non-reentrant deadlock"

key-files:
  created:
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/cache/CacheMode.kt
  modified:
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/EnrichmentResult.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/EnrichmentConfig.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/EnrichmentCache.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/cache/InMemoryEnrichmentCache.kt
    - musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/cache/InMemoryEnrichmentCacheTest.kt

key-decisions:
  - "Mutex non-reentrant in InMemoryEnrichmentCache: getIncludingExpired() must access entries map directly under its own lock, never by delegating to get()"
  - "Expired entries retained (not deleted) on get(): LRU eviction (LinkedHashMap accessOrder + maxEntries) manages memory, enabling stale serving without separate storage"

patterns-established:
  - "isStale: Boolean = false as last param of EnrichmentResult.Success — zero breaking changes to existing callers"
  - "Interface default method pattern for getIncludingExpired(): backward-compatible null default for non-implementing caches"

requirements-completed: [CACHE-01, CACHE-05]

# Metrics
duration: 2min
completed: 2026-03-24
---

# Phase 20 Plan 01: Stale Cache Contracts Summary

**CacheMode enum, isStale flag on Success, getIncludingExpired interface method, and InMemoryEnrichmentCache retained-expiry implementation with 3 new TDD-driven tests**

## Performance

- **Duration:** ~2 min
- **Started:** 2026-03-24T11:06:36Z
- **Completed:** 2026-03-24T11:08:24Z
- **Tasks:** 2
- **Files modified:** 6

## Accomplishments
- Created `CacheMode` enum (`NETWORK_FIRST`, `STALE_IF_ERROR`) as foundational contract for stale cache behavior
- Added `isStale: Boolean = false` to `EnrichmentResult.Success` and `cacheMode: CacheMode = CacheMode.NETWORK_FIRST` to `EnrichmentConfig` — both use defaults so all existing code compiles unchanged
- Added `getIncludingExpired()` default method to `EnrichmentCache` interface (returns null) for backward-compatible stale access
- `InMemoryEnrichmentCache.get()` no longer eagerly deletes expired entries; `getIncludingExpired()` accesses the map directly under its own mutex lock to prevent deadlock

## Task Commits

Each task was committed atomically:

1. **Task 1: CacheMode enum, isStale flag, cacheMode config, getIncludingExpired interface** - `db5a462` (feat)
2. **Task 2: InMemoryEnrichmentCache retains expired entries, adds getIncludingExpired** - `f217ad3` (feat)

**Plan metadata:** (docs commit below)

_Note: Task 2 used TDD — tests written first (RED), then implementation (GREEN)_

## Files Created/Modified
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/cache/CacheMode.kt` - New enum: NETWORK_FIRST, STALE_IF_ERROR
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/EnrichmentResult.kt` - Added isStale: Boolean = false to Success
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/EnrichmentConfig.kt` - Added cacheMode: CacheMode = CacheMode.NETWORK_FIRST
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/EnrichmentCache.kt` - Added getIncludingExpired() default method
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/cache/InMemoryEnrichmentCache.kt` - Retained expired entries; added getIncludingExpired override
- `musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/cache/InMemoryEnrichmentCacheTest.kt` - 3 new tests (11 total, all passing)

## Decisions Made
- Mutex non-reentrant in InMemoryEnrichmentCache: `getIncludingExpired()` accesses the entries map directly under its own `mutex.withLock`, never by delegating to `get()` — prevents deadlock
- Expired entries are retained in `get()` rather than deleted; LRU eviction (LinkedHashMap accessOrder + maxEntries cap) manages memory, enabling stale serving without separate storage

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- All type contracts and InMemoryEnrichmentCache implementation ready for Plan 02
- Plan 02 wires STALE_IF_ERROR into the engine's cache-read path and updates RoomEnrichmentCache (Android)
- No blockers

## Self-Check: PASSED

- FOUND: musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/cache/CacheMode.kt
- FOUND: .planning/phases/20-stale-cache/20-01-SUMMARY.md
- FOUND: commit db5a462 (Task 1)
- FOUND: commit f217ad3 (Task 2)

---
*Phase: 20-stale-cache*
*Completed: 2026-03-24*
