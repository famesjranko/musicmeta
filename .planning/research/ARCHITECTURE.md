# Architecture Research

**Domain:** Music metadata enrichment library — v0.8.0 Production Readiness
**Researched:** 2026-03-24
**Confidence:** HIGH (based on direct codebase analysis of all relevant files)

## System Overview

```
Consumer
    │
    ▼
EnrichmentEngine interface         ← adds enrichBatch() in Phase 3
    │
    ▼
DefaultEnrichmentEngine
    ├── cache check  (EnrichmentCache.get())
    │       └── stale fallback: cache.getIncludingExpired() when STALE_IF_ERROR  ← Phase 2
    ├── identity resolution (MusicBrainzProvider)
    └── resolveTypes() — concurrent fan-out
            ├── standard types    → ProviderChain.resolve()    [first-wins]
            ├── mergeable types   → ProviderChain.resolveAll() [collect-all + merger]
            └── composite types   → synthesizer
                    │
                    └── cache write loop (skips isStale results)  ← Phase 2

EnrichmentEngine.Builder
    └── httpClient(OkHttpEnrichmentClient)   ← Phase 1 integration point
              │
              ▼
         OkHttpEnrichmentClient  (new module: musicmeta-okhttp)
              └── implements HttpClient — all 12 methods via OkHttp Call API
```

## New Module: musicmeta-okhttp

### Location and Structure

```
musicmeta-okhttp/
├── build.gradle.kts
└── src/
    ├── main/kotlin/com/landofoz/musicmeta/okhttp/
    │   └── OkHttpEnrichmentClient.kt
    └── test/kotlin/com/landofoz/musicmeta/okhttp/
        └── OkHttpEnrichmentClientTest.kt
```

### Integration Point

The single integration point is `EnrichmentEngine.Builder.httpClient(client: HttpClient)` at `EnrichmentEngine.kt` line 78. The builder already accepts any `HttpClient` implementation; no changes to the engine, builder, or providers are needed. The new module is purely additive.

**Gradle dependency chain:**
```
musicmeta-okhttp
    api(project(":musicmeta-core"))   — consumers get HttpClient interface transitively
    api(libs.okhttp)                   — OkHttpClient is part of the public API surface
    implementation(libs.json)          — org.json for JSON parsing (same as core)
```

The module uses `api` for both `musicmeta-core` and `okhttp` because `OkHttpEnrichmentClient`'s constructor takes `OkHttpClient` as a parameter — callers need OkHttp on their classpath to configure it.

### HttpClient Method Mapping

All 12 methods must be implemented. Two families exist:

| Family | Methods | Return on error |
|--------|---------|----------------|
| Nullable | `fetchJson`, `fetchJsonArray`, `fetchBody`, `fetchRedirectUrl`, `postJson`, `postJsonArray` | null |
| HttpResult | `fetchJsonResult`, `fetchJsonArrayResult`, `postJsonResult`, `postJsonArrayResult` | typed HttpResult variant |

**Key behavioral differences from DefaultHttpClient (HIGH confidence — from source):**

| Behavior | DefaultHttpClient | OkHttpEnrichmentClient |
|----------|-------------------|------------------------|
| Retry logic | 3 retries, exponential backoff, Retry-After parsing, max 120s cap | None — delegate to OkHttp interceptors |
| GZIP decompression | Manual via GZIPInputStream | Automatic (OkHttp handles it — do NOT set Accept-Encoding: gzip manually) |
| Redirect following | Manual `instanceFollowRedirects = false` + Location header | `client.newBuilder().followRedirects(false).build()` for `fetchRedirectUrl` only |
| Error body reading | `conn.errorStream` | `response.body?.string()` (OkHttp handles stream selection) |
| Timeout config | Constructor `timeoutMs` parameter | Inherited from caller's `OkHttpClient` instance |

**Status code mapping** (match DefaultHttpClient exactly):
- 429 → `HttpResult.RateLimited(retryAfterMs)` — parse `Retry-After` header in seconds × 1000
- 400-499 → `HttpResult.ClientError(code, body)`
- 500-599 → `HttpResult.ServerError(code, body)`
- 200-299 → `HttpResult.Ok(parsed, code)`
- IOException → `HttpResult.NetworkError(message, cause)`

All methods use `withContext(Dispatchers.IO)` — consistent with DefaultHttpClient.

### Private Helper Structure

The doc recommends three private helpers to keep each public method under 40 lines:
- `buildGetRequest(url): Request` — builds GET with User-Agent + Accept headers
- `buildPostRequest(url, body): Request` — builds POST with Content-Type: application/json
- `toHttpResult(response): HttpResult<String>` — maps OkHttp Response to HttpResult with raw body

The nullable methods call helpers then parse JSON; the HttpResult methods call helpers then invoke `toHttpResult`.

## Modified Files: Phase 2 (Stale Cache)

### Component Responsibilities

| Component | Current Responsibility | v0.8.0 Change |
|-----------|----------------------|---------------|
| `EnrichmentCache` | get/put/invalidate interface | Add `getIncludingExpired()` with default `= null` |
| `InMemoryEnrichmentCache` | LRU in-memory cache | Change expiry check from eager-delete to return-null-only; add `getIncludingExpired()` |
| `RoomEnrichmentCache` | Room-backed persistent cache | Extract `deserializeEntity()` private method; add `getIncludingExpired()` via new DAO method |
| `EnrichmentCacheDao` | Room SQL queries | Add `getIncludingExpired` query (no migration needed) |
| `DefaultEnrichmentEngine.enrich()` | Orchestrate enrichment | Insert `applyStaleCache()` call after timeout catch block; guard cache write with `!result.isStale` |
| `EnrichmentResult.Success` | Result data class | Add `isStale: Boolean = false` at end of constructor |
| `EnrichmentConfig` | Engine configuration | Add `cacheMode: CacheMode = CacheMode.NETWORK_FIRST` at end of constructor |
| `FakeEnrichmentCache` | Test fake | Add `expiredStore` map; implement `getIncludingExpired()` |

### New File: CacheMode.kt

```
musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/cache/CacheMode.kt
```

```kotlin
enum class CacheMode {
    NETWORK_FIRST,   // default — never serve stale
    STALE_IF_ERROR,  // serve expired cache when provider returns Error or RateLimited
}
```

`CACHE_FIRST` is intentionally excluded — needs background refresh architecture that is out of scope.

### Data Flow: Stale Fallback

```
enrich(request, types, forceRefresh=false)
    │
    ├── cache.get() hits          → Success returned immediately
    ├── cache.get() miss          → proceed to provider fan-out
    │
    ▼
withTimeout(enrichTimeoutMs) {
    resolveTypes() → Map<EnrichmentType, EnrichmentResult>
}                                          ← results may contain Error / RateLimited
    │
    ├── TimeoutCancellationException caught (line 86-93)
    │       fills missing types with Error(errorKind=TIMEOUT)
    │
    ▼  [INSERT: after line 93, before line 95]
if config.cacheMode == STALE_IF_ERROR:
    applyStaleCache(request, results, types)
        for each type where result is Error or RateLimited:
            val expired = cache.getIncludingExpired(entityKey, type)
            if expired != null:
                results[type] = expired.copy(isStale = true)
    │
    ▼
cache write loop (line 96-108)
    guard: result is Success && !result.isStale
    (stale results are NOT re-written — would re-cache expired data with fresh TTL)
```

**Critical boundary — stale NOT served for:**
- `NotFound` — means provider searched and found nothing; stale would be misleading
- Any type already in `results` as `Success` from a live provider

**Stale IS served for:**
- `Error` (all ErrorKind values including TIMEOUT — TIMEOUT arrives as Error per line 90)
- `RateLimited`

### InMemoryEnrichmentCache Change (Line 21)

Current code (eager delete on expiry):
```kotlin
if (clock() > entry.expiresAt) { entries.remove(key); return null }
```

Change to (keep entry for stale serving):
```kotlin
if (clock() > entry.expiresAt) return null
```

The entry stays in the `LinkedHashMap`. LRU eviction (max 500 entries, `accessOrder=true`) handles memory pressure. `getIncludingExpired()` reads the same map without the expiry check. Both methods must use `mutex.withLock`.

### RoomEnrichmentCache: Deserialization Extraction

Lines 32-49 in `get()` contain deserialization logic that must be extracted into `deserializeEntity(entity: EnrichmentCacheEntity): EnrichmentResult.Success?` private method. Both `get()` and the new `getIncludingExpired()` call it. The only difference between the two methods is which DAO query they use.

### EnrichmentCacheDao: New Query (No Migration)

The new query is identical to the existing `get()` query minus the `AND expires_at > :now` clause and `now` parameter. No new columns, no schema change, no Room migration needed.

### EnrichmentResult.Success: isStale Addition

`isStale` must be added at the end of the constructor with default `false` to preserve source compatibility for existing call sites using positional or named construction. The data class `.copy()` method is used by the engine to stamp stale results: `expired.copy(isStale = true)`.

## Modified Files: Phase 3 (Bulk Enrichment)

### Integration Point

`enrichBatch()` is added as a default method on the `EnrichmentEngine` interface — not just `DefaultEnrichmentEngine`. This means:
1. Any custom `EnrichmentEngine` implementation gets it for free
2. The method is part of the public API contract
3. `DefaultEnrichmentEngine` overrides it for future optimization capability

### Default Implementation Location

The default implementation lives directly in the interface body at `EnrichmentEngine.kt`:

```kotlin
fun enrichBatch(
    requests: List<EnrichmentRequest>,
    types: Set<EnrichmentType>,
    forceRefresh: Boolean = false,
): Flow<Pair<EnrichmentRequest, EnrichmentResults>> = flow {
    for (request in requests) {
        emit(request to enrich(request, types, forceRefresh))
    }
}
```

`Flow` is from `kotlinx-coroutines-core`, which is already a dependency in `musicmeta-core`. No new dependency.

### Sequential vs Concurrent Design Decision

Sequential iteration is deliberate:
- MusicBrainz rate limiter (1.1s between requests) makes concurrent processing counterproductive — concurrent requests would hit the rate limiter serially anyway
- Cache hits complete instantly, making the sequential overhead negligible in practice
- Cancellation semantics are clean: `take(1)` cancels the flow, remaining requests are not processed
- Parallel implementation is a future optimization that can be done without interface change

### Data Flow

```
enrichBatch(requests=[R1, R2, R3], types)
    │
    ├── emit(R1 to enrich(R1, types))   ← may hit cache (fast) or providers (slow)
    ├── emit(R2 to enrich(R2, types))   ← rate limiter throttles naturally
    └── emit(R3 to enrich(R3, types))
```

If Phase 2 (stale cache) is implemented first, `enrichBatch()` inherits offline support automatically — each `enrich()` call uses `config.cacheMode` and the stale fallback path.

## Modified Files: Phase 4 (Maven Central)

### Current Publishing State

Both existing modules have minimal publishing:
- Version `0.1.0` hardcoded
- No repository URLs, no POM metadata, no signing, no source/javadoc jars
- `musicmeta-android` uses `afterEvaluate` (required for Android library variants)
- `musicmeta-core` uses standard `java` component

### Root build.gradle.kts Expansion

The root build file is currently just plugin declarations (`apply false`). A `subprojects` block is added containing:
- POM metadata (name, description, license Apache 2.0, developer, SCM)
- `signing` plugin configuration (conditional on GPG key presence)
- OSSRH repository URLs (snapshots + releases)
- Credentials from `gradle.properties` or env vars

### Per-Module Changes

| Module | Change |
|--------|--------|
| `musicmeta-core` | `java { withSourcesJar(); withJavadocJar() }` |
| `musicmeta-okhttp` | `java { withSourcesJar(); withJavadocJar() }` (same pattern as core) |
| `musicmeta-android` | `android { publishing { singleVariant("release") { withSourcesJar(); withJavadocJar() } } }` |

The Android module needs `singleVariant("release")` because Android library variants aren't available at configuration time — this pairs with the existing `afterEvaluate` block at line 43.

### OkHttp Module Pattern

The OkHttp module is pure JVM (no Android SDK). Follow `musicmeta-core`'s simpler pattern (no `afterEvaluate`). This means the module can use the standard `kotlin-jvm` plugin and `maven-publish` without Android-specific complexity.

## Build Order and Dependencies

| Phase | What | Dependencies | Rationale |
|-------|------|-------------|-----------|
| 1 | OkHttp adapter (`musicmeta-okhttp`) | None (additive new module, uses existing `HttpClient` interface) | Creates the third module that Maven Central config must include; validates HttpClient interface completeness |
| 2 | Stale cache (`CacheMode`, `getIncludingExpired`, `isStale`) | None (modifications to core + android, no cross-phase deps) | Independent of OkHttp; should exist before bulk so bulk inherits offline support |
| 3 | Bulk enrichment (`enrichBatch()`) | Phase 2 (stale cache should exist so batch requests benefit from offline fallback) | Simple addition to existing interface; depends on `enrich()` which is stable |
| 4 | Maven Central publishing | All 3 modules must exist (Phase 1 creates `musicmeta-okhttp`) | Publishing config references all module artifacts; can't configure missing modules |

Phase 2 and Phase 1 are technically independent — they touch non-overlapping files. The ordering (1 then 2 then 3 then 4) is recommended because it matches increasing integration complexity and ensures Phase 4 has all artifacts ready.

## Integration Points: New vs Modified

### Phase 1 — Purely New (no existing files modified)

| Type | Component | Notes |
|------|-----------|-------|
| New file | `OkHttpEnrichmentClient.kt` | Implements existing `HttpClient` interface |
| New file | `OkHttpEnrichmentClientTest.kt` | Uses MockWebServer (real OkHttp integration test) |
| New file | `musicmeta-okhttp/build.gradle.kts` | Pure JVM, follow core pattern |
| Edit | `gradle/libs.versions.toml` | Add okhttp = "4.12.0", library refs, mockwebserver |
| Edit | `settings.gradle.kts` | Add `include(":musicmeta-okhttp")` (currently only core + android) |

### Phase 2 — Modifications Spread Across Both Modules

| Type | Component | What Changes |
|------|-----------|-------------|
| New file | `musicmeta-core/.../cache/CacheMode.kt` | New enum |
| Edit | `EnrichmentResult.kt` | Add `isStale: Boolean = false` to `Success` |
| Edit | `EnrichmentCache.kt` | Add `getIncludingExpired()` with default null impl |
| Edit | `EnrichmentConfig.kt` | Add `cacheMode: CacheMode = CacheMode.NETWORK_FIRST` |
| Edit | `InMemoryEnrichmentCache.kt` | Change expiry handling; add `getIncludingExpired()` |
| Edit | `DefaultEnrichmentEngine.kt` | Insert stale fallback after timeout catch; guard cache write |
| Edit | `FakeEnrichmentCache.kt` | Add `expiredStore`; implement `getIncludingExpired()` |
| Edit | `InMemoryEnrichmentCacheTest.kt` | 3 new test cases |
| Edit | `DefaultEnrichmentEngineTest.kt` | 5 new test cases |
| Edit | `EnrichmentCacheDao.kt` (android) | Add `getIncludingExpired` query |
| Edit | `RoomEnrichmentCache.kt` (android) | Extract deserialization; add `getIncludingExpired()` |

### Phase 3 — Minimal (2 files + 1 new test file)

| Type | Component | What Changes |
|------|-----------|-------------|
| Edit | `EnrichmentEngine.kt` | Add `enrichBatch()` default method |
| Edit | `DefaultEnrichmentEngine.kt` | Add explicit override |
| New file | `EnrichmentBatchTest.kt` | 5 test cases using Turbine |

### Phase 4 — Build Config Only (no Kotlin source changes)

| Type | Component | What Changes |
|------|-----------|-------------|
| Edit | `build.gradle.kts` (root) | Add subprojects publishing/signing block |
| Edit | `musicmeta-core/build.gradle.kts` | Sources + javadoc jars; version 0.8.0 |
| Edit | `musicmeta-okhttp/build.gradle.kts` | Sources + javadoc jars; version 0.8.0 |
| Edit | `musicmeta-android/build.gradle.kts` | singleVariant publishing; version 0.8.0 |
| Edit | `gradle.properties` | Add credential comment placeholders |
| Edit | `README.md` | Add Maven Central coordinates |

## Validation of docs/v0.8.0.md

The following claims in `docs/v0.8.0.md` were verified against actual source files:

### Verified Correct

| Claim | Source | Status |
|-------|--------|--------|
| HttpClient has 12 methods (6 nullable, 4 HttpResult, fetchBody, fetchRedirectUrl) | `HttpClient.kt` — exactly 10 methods listed, but: fetchBody and fetchRedirectUrl are among the 6 nullable, so count is 6 nullable + 4 HttpResult = 10 total | ISSUE — see below |
| `DefaultHttpClient` is 289 lines | `DefaultHttpClient.kt` — file is 289 lines | CONFIRMED |
| InMemoryEnrichmentCache line 21 eager-delete | `InMemoryEnrichmentCache.kt` line 21 | CONFIRMED |
| Cache key format `"$entityKey:$type"` | `InMemoryEnrichmentCache.kt` line 49 | CONFIRMED |
| `Mutex` used in InMemoryEnrichmentCache | Lines 14-15 of file | CONFIRMED |
| LRU eviction via LinkedHashMap accessOrder=true, max 500 | Lines 9-15 of file | CONFIRMED |
| Timeout catch at line 86 | `DefaultEnrichmentEngine.kt` line 86 | CONFIRMED |
| `results[type] = EnrichmentResult.Error(..., errorKind = ErrorKind.TIMEOUT)` at line ~90 | Line 90 exactly | CONFIRMED |
| Cache write loop at line 96 | `DefaultEnrichmentEngine.kt` line 96 | CONFIRMED |
| `if (result is EnrichmentResult.Success)` at line 97 | Line 97 exactly | CONFIRMED |
| `val resolvedMbid = ...` at line 95 | Line 95 exactly | CONFIRMED |
| `EnrichmentResult.Success` at `EnrichmentResult.kt` line 65 | Line 65 exactly | CONFIRMED |
| `EnrichmentResult.Success` field list (type, data, provider, confidence, resolvedIdentifiers, identityMatchScore, identityMatch) | Lines 66-79 | CONFIRMED |
| `EnrichmentConfig` field list matches doc | `EnrichmentConfig.kt` lines 28-38 | CONFIRMED (radioLimit exists, no cacheMode yet) |
| Builder `httpClient()` at line 78 | `EnrichmentEngine.kt` line 78 | CONFIRMED |
| `withDefaultProviders()` at line 89 | `EnrichmentEngine.kt` line 89 | CONFIRMED |
| `DefaultHttpClient` fallback in `withDefaultProviders()` | Line 89: `val client = httpClient ?: DefaultHttpClient(config.userAgent)` | CONFIRMED |
| `DefaultHttpClient` fallback in `build()` at line 127 | Line 127 exactly | CONFIRMED |
| `RoomEnrichmentCache` deserialization at lines 32-49 | Lines 32-49 match described pattern | CONFIRMED |
| `RoomEnrichmentCache` Json config at lines 22-25 | Lines 22-25: `ignoreUnknownKeys = true`, `encodeDefaults = true` | CONFIRMED |
| `EnrichmentCacheDao.get()` query at line 11-14 | Lines 11-14 match exactly | CONFIRMED |
| `getIncludingExpired` new query (same minus `AND expires_at > :now`) | Verified against existing query | CONFIRMED — correct approach |
| No `FakeEnrichmentCache.getIncludingExpired()` exists yet | Source only has 6 methods | CONFIRMED — needs adding |
| Flow is in kotlinx-coroutines-core (no new dep) | `libs.versions.toml` — `kotlinx-coroutines-core` already in core deps | CONFIRMED |
| Turbine already in test bundle | `libs.versions.toml` bundles.testing | CONFIRMED |
| No OkHttp in version catalog yet | `libs.versions.toml` — not present | CONFIRMED |
| Android module uses `afterEvaluate` for publishing at line 43 | `musicmeta-android/build.gradle.kts` line 43 | CONFIRMED |
| KSP `room.generateKotlin = true` at line 23 | Line 23 | CONFIRMED |
| Room schema at `$projectDir/schemas` at line 21 | Line 21 | CONFIRMED |
| Version `0.1.0` in both modules | Both build files | CONFIRMED |

### Issue Found: HttpClient Method Count

The docs say "All 12 `HttpClient` methods" but `HttpClient.kt` has exactly 10 declared methods. The count of 12 appears in `PROJECT.md` ("HttpClient has 12 methods (6 nullable, 4 HttpResult, fetchBody, fetchRedirectUrl)") but the actual interface has:

Nullable (6): `fetchJson`, `fetchJsonArray`, `fetchBody`, `fetchRedirectUrl`, `postJson`, `postJsonArray`
HttpResult (4): `fetchJsonResult`, `fetchJsonArrayResult`, `postJsonResult`, `postJsonArrayResult`

Total: 10 methods. `fetchBody` and `fetchRedirectUrl` are within the "6 nullable" group, not additional. The docs are counting them correctly in the enumerated list — the "12" in PROJECT.md appears to be a counting error that propagated. The actual implementation task is to implement all 10 methods. This does not affect the implementation plan, only the stated count.

### Minor Observation: EnrichmentCache Interface Location

`docs/v0.8.0.md` refers to "Edit `musicmeta-core/.../EnrichmentCache.kt`" — the actual file is at `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/EnrichmentCache.kt` (top-level package, not in `cache/` subdirectory). This is correct path notation and the edit location is clear.

## Anti-Patterns for v0.8.0

### Anti-Pattern 1: Adding Retry Logic to OkHttpEnrichmentClient

**What:** Replicating DefaultHttpClient's 3-retry loop with exponential backoff inside `OkHttpEnrichmentClient`.

**Why wrong:** OkHttp has a first-class interceptor mechanism designed exactly for retry. Replicating retry in the adapter means two retry mechanisms can conflict. OkHttp users expect to configure behavior via interceptors.

**Do instead:** Document the difference in KDoc. Let callers add `RetryInterceptor` to their `OkHttpClient.Builder`. DefaultHttpClient's retry is a compensating mechanism for its simpler HTTP stack — it's not part of the `HttpClient` interface contract.

### Anti-Pattern 2: Setting Accept-Encoding: gzip in OkHttpEnrichmentClient

**What:** Copying DefaultHttpClient's `conn.setRequestProperty("Accept-Encoding", "gzip")` into OkHttp request builder.

**Why wrong:** OkHttp automatically adds `Accept-Encoding: gzip` and handles transparent decompression. Manually setting it disables the automatic decompression, causing the response body to arrive as raw GZIP bytes which JSONObject parsing will fail to parse.

**Do instead:** Do NOT set `Accept-Encoding` manually. Set only `User-Agent` and `Accept: application/json`.

### Anti-Pattern 3: Serving Stale for NotFound Results

**What:** Applying `getIncludingExpired()` fallback when the provider chain returns `NotFound`.

**Why wrong:** `NotFound` means the provider searched the live API and found no matching entity. Serving stale data in this case would present the user with data for an entity the provider confirmed doesn't exist (or no longer exists).

**Do instead:** Only apply stale fallback for `Error` and `RateLimited` results. These indicate network failure, not absence of data.

### Anti-Pattern 4: Re-caching Stale Results

**What:** Removing the `!result.isStale` guard from the cache write loop.

**Why wrong:** Writing a stale result to the cache with a fresh TTL means the expired data gets a new expiry time. On the next call, `cache.get()` returns it as if it were fresh. The stale fallback is meant to be temporary — it should be transparent, not persist.

**Do instead:** Guard the cache write: `if (result is EnrichmentResult.Success && !result.isStale)`.

### Anti-Pattern 5: Concurrent enrichBatch Implementation

**What:** Using `coroutineScope { requests.map { async { enrich(it, types) } }.awaitAll() }` in `enrichBatch`.

**Why wrong:** The MusicBrainz rate limiter enforces 1 req/sec per provider. With 11 concurrent requests, all identity resolution calls queue behind the rate limiter anyway. The benefit of concurrency is negated, while the complexity of managing concurrent cache writes and partial failures increases significantly.

**Do instead:** Sequential iteration via `for (request in requests) emit(...)`. Rate limiter naturally throttles, cache hits return fast. Concurrency is a future optimization deferred to v0.9.0+.

## Sources

- Direct codebase analysis (HIGH confidence):
  - `HttpClient.kt` — 10 methods confirmed
  - `DefaultHttpClient.kt` — 289 lines, retry/GZIP/redirect behavior confirmed
  - `EnrichmentEngine.kt` — Builder.httpClient() at line 78, withDefaultProviders at line 89, build() at line 121
  - `DefaultEnrichmentEngine.kt` — timeout catch at line 86, cache write loop at line 96-108, line numbers all confirmed
  - `EnrichmentResult.kt` — Success fields at lines 65-79 confirmed
  - `EnrichmentConfig.kt` — all fields confirmed, no cacheMode yet
  - `EnrichmentCache.kt` — 5 methods, no getIncludingExpired yet
  - `InMemoryEnrichmentCache.kt` — LRU LinkedHashMap, Mutex, eager-delete at line 21 confirmed
  - `RoomEnrichmentCache.kt` — deserialization at lines 32-49, Json config at lines 22-25 confirmed
  - `EnrichmentCacheDao.kt` — get() query at lines 11-14 confirmed
  - `FakeEnrichmentCache.kt` — stored/storedTtls/manualSelections, no expiredStore yet
  - `musicmeta-core/build.gradle.kts`, `musicmeta-android/build.gradle.kts`, `build.gradle.kts` (root)
  - `gradle/libs.versions.toml` — all versions and bundles confirmed
  - `settings.gradle.kts` — currently only includes core + android
- `docs/v0.8.0.md` — implementation plan validated against source

---
*Architecture research for: v0.8.0 Production Readiness — musicmeta library*
*Researched: 2026-03-24*
