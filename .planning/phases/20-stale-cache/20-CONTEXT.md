# Phase 20: Stale Cache - Context

**Gathered:** 2026-03-24
**Status:** Ready for planning

<domain>
## Phase Boundary

Add `CacheMode.STALE_IF_ERROR` to the enrichment engine. When network fails (Error/RateLimited) and an expired cache entry exists, serve it as `Success(isStale=true)` instead of returning the error. Do not serve stale for genuine NotFound. Do not re-cache stale results. Touches InMemoryEnrichmentCache, RoomEnrichmentCache, DefaultEnrichmentEngine, EnrichmentConfig, EnrichmentResult, FakeEnrichmentCache.

</domain>

<decisions>
## Implementation Decisions

### Claude's Discretion

All implementation choices are at Claude's discretion — pure infrastructure phase.

Key constraints from research and docs/v0.8.0.md:
- `CacheMode` enum: `NETWORK_FIRST` (current behavior), `STALE_IF_ERROR` (serve expired on Error/RateLimited). Skip `CACHE_FIRST` — needs background refresh architecture.
- Add `isStale: Boolean = false` to `EnrichmentResult.Success` at the end (preserves source compatibility via default)
- Add `getIncludingExpired()` to `EnrichmentCache` interface with default `= null` (backward compatible for custom implementations)
- `InMemoryEnrichmentCache.get()`: stop eagerly deleting expired entries (just return null, keep entry for stale serving). LRU eviction handles memory.
- `getIncludingExpired()` must NOT call `get()` internally — Kotlin Mutex is not reentrant, would deadlock
- `EnrichmentCacheDao`: add `getIncludingExpired(entityKey, type)` query — same as `get()` but without `AND expires_at > :now`. No schema change, no Room migration.
- `RoomEnrichmentCache`: extract `deserializeEntity()` private method for reuse between `get()` and `getIncludingExpired()`
- Stale fallback goes in `DefaultEnrichmentEngine.enrich()` after timeout catch block (line ~93), before cache write
- Only serve stale for `Error` and `RateLimited` — NOT for `NotFound` (that means provider searched and found nothing)
- Cache write guard at line ~97 must add `&& !result.isStale` to prevent re-caching expired data with fresh TTL
- `FakeEnrichmentCache`: add `expiredStore` map; `getIncludingExpired()` checks `stored` first, then `expiredStore`
- Add `cacheMode: CacheMode = CacheMode.NETWORK_FIRST` to EnrichmentConfig (at end, preserves source compat)

</decisions>

<code_context>
## Existing Code Insights

### Reusable Assets
- `EnrichmentCache` interface at `musicmeta-core/.../cache/EnrichmentCache.kt`
- `InMemoryEnrichmentCache` at `musicmeta-core/.../cache/InMemoryEnrichmentCache.kt` — uses Mutex, LinkedHashMap with accessOrder=true
- `DefaultEnrichmentEngine` at `musicmeta-core/.../engine/DefaultEnrichmentEngine.kt` — timeout catch at line 86, cache write at line 96
- `FakeEnrichmentCache` at `musicmeta-core/...test.../testutil/FakeEnrichmentCache.kt`
- `RoomEnrichmentCache` at `musicmeta-android/.../cache/RoomEnrichmentCache.kt`
- `EnrichmentCacheDao` at `musicmeta-android/.../cache/EnrichmentCacheDao.kt`

### Established Patterns
- Cache key format: `"$entityKey:$type"` (InMemoryEnrichmentCache line 49)
- `mutex.withLock` for thread safety in InMemoryEnrichmentCache
- `@Query` annotations in Room DAO for typed SQL queries
- Default parameter values at end of data class constructors for backward compatibility
- `EnrichmentResult` is a sealed class; `Success` is a data class within it

### Integration Points
- `EnrichmentConfig.cacheMode` → read by `DefaultEnrichmentEngine.enrich()`
- `EnrichmentCache.getIncludingExpired()` → called by engine when `STALE_IF_ERROR` and provider fails
- `EnrichmentResult.Success.isStale` → set by engine, readable by consumers
- `Builder.config()` already passes EnrichmentConfig through

</code_context>

<specifics>
## Specific Ideas

No specific requirements — infrastructure phase. Follow the implementation approach detailed in docs/v0.8.0.md Phase 2 section.

</specifics>

<deferred>
## Deferred Ideas

- CACHE_FIRST mode (serve cache always, refresh in background) — needs background refresh architecture
- Background cache refresh via coroutine scope — out of scope for v0.8.0

</deferred>
