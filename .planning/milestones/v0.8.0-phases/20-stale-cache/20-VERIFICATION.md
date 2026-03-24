---
phase: 20-stale-cache
verified: 2026-03-24T12:00:00Z
status: passed
score: 10/10 must-haves verified
re_verification: false
---

# Phase 20: Stale Cache Verification Report

**Phase Goal:** Consumers running under degraded network conditions (Error or RateLimited responses) get the last known data with an `isStale` flag instead of empty results
**Verified:** 2026-03-24T12:00:00Z
**Status:** PASSED
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths (from ROADMAP.md Success Criteria)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Developer can set `CacheMode.STALE_IF_ERROR` in `EnrichmentConfig` — existing callers with no `cacheMode` set see no behavior change | VERIFIED | `EnrichmentConfig.cacheMode: CacheMode = CacheMode.NETWORK_FIRST` at line 43; default preserves existing behavior |
| 2 | When provider returns `Error` or `RateLimited` and an expired cache entry exists, engine returns that entry as `EnrichmentResult.Success` with `isStale = true` | VERIFIED | `applyStaleCache()` in `DefaultEnrichmentEngine` lines 284-298 checks `is Error || is RateLimited` and calls `cache.getIncludingExpired()`, then does `stale.copy(isStale = true)`; covered by 2 engine tests |
| 3 | When provider returns `NotFound` and an expired cache entry exists, engine returns `NotFound` — stale data not served for genuine not-found | VERIFIED | `applyStaleCache()` only matches `Error` and `RateLimited`; `NotFound` falls through untouched; covered by engine test `STALE_IF_ERROR does not serve stale for genuine NotFound` |
| 4 | A stale result served from cache is not re-written to cache; expired entry's TTL is not renewed | VERIFIED | Cache write loop at line 102: `if (result is EnrichmentResult.Success && !result.isStale)`; covered by engine test `stale result is not re-written to cache` |
| 5 | `InMemoryEnrichmentCache.getIncludingExpired()` returns the expired entry directly without calling `get()` internally | VERIFIED | `getIncludingExpired` at lines 25-30 acquires its own `mutex.withLock` and accesses `entries` map directly — does NOT delegate to `get()`; deadlock prevention confirmed |

**Score:** 5/5 success criteria verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/cache/CacheMode.kt` | `enum class CacheMode` with `NETWORK_FIRST` and `STALE_IF_ERROR` | VERIFIED | File exists, 12 lines, both enum values present with KDoc |
| `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/EnrichmentResult.kt` | `isStale: Boolean = false` as last param of `Success` | VERIFIED | Line 83: `val isStale: Boolean = false` with KDoc explaining staleness semantics |
| `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/EnrichmentCache.kt` | `getIncludingExpired` default method returning null | VERIFIED | Lines 17-18: full default method with null return and backward-compat KDoc |
| `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/EnrichmentConfig.kt` | `cacheMode: CacheMode = CacheMode.NETWORK_FIRST` | VERIFIED | Line 43; import of `CacheMode` at line 3; KDoc param at lines 28-30 |
| `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/cache/InMemoryEnrichmentCache.kt` | `getIncludingExpired` override; `get()` does not delete expired entries | VERIFIED | `get()` line 21 returns null without `entries.remove()`; `getIncludingExpired` lines 25-30 present under own mutex |
| `musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/cache/InMemoryEnrichmentCacheTest.kt` | 3 new stale cache tests | VERIFIED | Tests at lines 111-146: `get returns null but does not remove`, `getIncludingExpired returns expired entry`, `getIncludingExpired returns null for never-cached key` |
| `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/engine/DefaultEnrichmentEngine.kt` | `applyStaleCache` method; `STALE_IF_ERROR` check; `!result.isStale` write guard | VERIFIED | `applyStaleCache` at lines 284-298; check at line 96; write guard at line 102; CacheMode import at line 18 |
| `musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/engine/DefaultEnrichmentEngineTest.kt` | 5 stale cache engine tests | VERIFIED | Tests at lines 1091-1169: Error fallback, RateLimited fallback, NotFound excluded, NETWORK_FIRST unaffected, stale re-cache guard |
| `musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/testutil/FakeEnrichmentCache.kt` | `expiredStore` map; `getIncludingExpired` override; `expiredStore.clear()` in `clear()` | VERIFIED | `expiredStore` at line 10; override at lines 14-17 checks `stored` first then `expiredStore`; cleared at line 24 |
| `musicmeta-android/src/main/kotlin/com/landofoz/musicmeta/android/cache/EnrichmentCacheDao.kt` | `getIncludingExpired` query without `expires_at` filter | VERIFIED | Lines 16-19: `@Query` without `expires_at > :now`, no `now` parameter |
| `musicmeta-android/src/main/kotlin/com/landofoz/musicmeta/android/cache/RoomEnrichmentCache.kt` | `getIncludingExpired` override; `deserializeEntity` helper | VERIFIED | `getIncludingExpired` at lines 35-41 delegates to `dao.getIncludingExpired` then `deserializeEntity`; helper extracted at lines 43-62 |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `DefaultEnrichmentEngine.kt` | `EnrichmentCache.kt` | `cache.getIncludingExpired()` in `applyStaleCache` | WIRED | Line 292: `cache.getIncludingExpired(entityKeyFor(request, type), type)` |
| `DefaultEnrichmentEngine.kt` | `CacheMode.kt` | `config.cacheMode == CacheMode.STALE_IF_ERROR` | WIRED | Line 96: exact pattern present; import at line 18 |
| `DefaultEnrichmentEngine.kt` | `EnrichmentResult.kt` | `!result.isStale` guard in cache write loop | WIRED | Line 102: `result is EnrichmentResult.Success && !result.isStale` |
| `RoomEnrichmentCache.kt` | `EnrichmentCacheDao.kt` | `dao.getIncludingExpired()` call | WIRED | Line 39: `dao.getIncludingExpired(entityKey, type.name)` |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|-------------|--------|----------|
| CACHE-01 | 20-01-PLAN.md | Developer can configure STALE_IF_ERROR cache mode via EnrichmentConfig | SATISFIED | `EnrichmentConfig.cacheMode: CacheMode = CacheMode.NETWORK_FIRST`; `CacheMode.STALE_IF_ERROR` defined in `CacheMode.kt` |
| CACHE-02 | 20-02-PLAN.md | Expired cache entries served with isStale=true when provider returns Error or RateLimited | SATISFIED | `applyStaleCache()` promotes Error/RateLimited to `stale.copy(isStale = true)`; 2 dedicated engine tests |
| CACHE-03 | 20-02-PLAN.md | Expired cache entries NOT served for genuine NotFound results | SATISFIED | `applyStaleCache()` only handles `Error` and `RateLimited` — `NotFound` excluded by design; 1 dedicated engine test |
| CACHE-04 | 20-02-PLAN.md | Stale results not re-written to cache with fresh TTL | SATISFIED | Cache write guard `&& !result.isStale` at line 102; 1 dedicated engine test asserting `cache.stored.isEmpty()` |
| CACHE-05 | 20-01-PLAN.md | InMemoryEnrichmentCache supports getIncludingExpired() for stale serving | SATISFIED | Override present; `get()` retains expired entries without deletion; 3 dedicated cache tests |
| CACHE-06 | 20-02-PLAN.md | RoomEnrichmentCache supports getIncludingExpired() via new DAO query (no schema migration) | SATISFIED | DAO query without `expires_at` filter added; `RoomEnrichmentCache.getIncludingExpired` wired to `dao.getIncludingExpired` + `deserializeEntity` |

All 6 requirements satisfied. No orphaned requirements for Phase 20 in REQUIREMENTS.md.

### Anti-Patterns Found

None. The files modified in this phase were scanned for:
- TODO/FIXME/placeholder comments — none found in phase files
- Empty implementations — no stub returns
- Hardcoded empty data — none
- Console.log / debug-only implementations — none

Note: `FakeEnrichmentCache.getIncludingExpired` does `stored[key] ?: expiredStore[key]` — this correctly checks the live store first, which is the intended test-fake behavior, not a stub.

### Human Verification Required

None required. All behaviors are verified programmatically:
- Contract types exist and compile (verified via Gradle)
- Test suite (11 cache tests + 5 engine stale tests) passes end-to-end
- Android module compiles with updated DAO

### Build Verification

```
./gradlew :musicmeta-core:test — BUILD SUCCESSFUL (all 61 engine + cache tests pass)
./gradlew :musicmeta-android:compileDebugKotlin — BUILD SUCCESSFUL
```

### Commits Verified

All 5 task commits present in git history:
- `db5a462` feat(20-01): CacheMode enum, isStale flag, cacheMode config, getIncludingExpired interface
- `f217ad3` feat(20-01): InMemoryEnrichmentCache retains expired entries, adds getIncludingExpired
- `6533844` feat(20-02): add getIncludingExpired to FakeEnrichmentCache, EnrichmentCacheDao, and RoomEnrichmentCache
- `44a15ad` test(20-02): add failing stale cache tests (RED phase)
- `4e18877` feat(20-02): wire STALE_IF_ERROR fallback into DefaultEnrichmentEngine (GREEN phase)

---

_Verified: 2026-03-24T12:00:00Z_
_Verifier: Claude (gsd-verifier)_
