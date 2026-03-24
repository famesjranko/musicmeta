# Feature Research

**Domain:** Kotlin/JVM library — v0.8.0 Production Readiness (OkHttp adapter, stale cache, bulk enrichment, Maven Central)
**Researched:** 2026-03-24
**Confidence:** HIGH (OkHttp and Maven Central from official docs/changelogs), MEDIUM (stale cache and bulk API from pattern analysis against codebase)

---

## Context: What Already Exists

v0.7.0 shipped:
- `HttpClient` interface — 12 methods, two families: nullable (6) and `HttpResult` (4) plus `fetchBody`, `fetchRedirectUrl`
- `DefaultHttpClient` — `java.net.HttpURLConnection`, 3-retry loop with exponential backoff, manual GZIP decompression
- `InMemoryEnrichmentCache` — LRU 500-entry LinkedHashMap, TTL via `expiresAt`, eager deletion on expiry, `Mutex` for thread safety
- `EnrichmentEngine.enrich()` returning `EnrichmentResults`
- JitPack-only distribution

v0.8.0 adds: OkHttp adapter module, stale-while-revalidate cache mode, bulk enrichment Flow API, Maven Central publishing.

---

## Feature Landscape

### Table Stakes (Users Expect These)

These are non-negotiable for a production-ready Kotlin/JVM library. Missing any of these causes immediate friction for adopters.

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| OkHttp adapter implementing all 12 HttpClient methods | OkHttp is the de-facto standard HTTP client for Android. Any library targeting Android that ships its own HttpURLConnection-based client will be rejected by teams that already manage an OkHttpClient singleton. The adapter pattern is how Retrofit, Ktor, and similar libraries solve this. | LOW | All 12 methods via `Call.execute()` in `withContext(Dispatchers.IO)`. Private helpers `buildGetRequest`, `buildPostRequest`, `toHttpResult` keep each method under 40 lines. |
| OkHttp transparent GZIP — do NOT set Accept-Encoding manually | OkHttp adds `Accept-Encoding: gzip` automatically and decompresses transparently. If the adapter manually sets this header, OkHttp interprets it as "caller will handle decompression" and skips its own decompression, returning raw gzip bytes. This is a well-known footgun. | LOW | Confirmed by OkHttp source and multiple issue reports. `DefaultHttpClient` sets this manually (fine for HttpURLConnection) — the OkHttp adapter must NOT replicate that. KDoc the difference explicitly. |
| fetchRedirectUrl via non-following client | Cover Art Archive returns 307 redirects to image CDN URLs. The adapter must capture the Location header from 3xx responses rather than following them. | LOW | `client.newBuilder().followRedirects(false).build()` per call. OkHttp `response.header("Location")` for the redirect URL. When response is 2xx with no redirect, return the original URL. |
| No retry logic in OkHttp adapter | `DefaultHttpClient` has 3-retry loop with 2s/4s/8s exponential backoff. The OkHttp adapter should NOT replicate this. OkHttp users configure retry via `Interceptor` — building retry into the adapter would conflict with any interceptor the caller has already added. | LOW — intentional omission | Document in KDoc: "Unlike DefaultHttpClient, this adapter performs no retries. Configure retries via OkHttpClient.Builder interceptors." |
| MockWebServer for integration tests | OkHttp adapter tests must run against a real embedded HTTP server. Fakes don't exercise the actual OkHttp call path. MockWebServer is the standard tool for this. | LOW | See OkHttp version note below — MockWebServer coordinates changed in OkHttp 5.x. |
| User-Agent header on every request | MusicBrainz and Wikimedia APIs require a descriptive User-Agent and will rate-limit or block requests without one. The adapter must set it on every request. | LOW | Set via `Request.Builder().header("User-Agent", userAgent)`. Constructor: `OkHttpEnrichmentClient(client: OkHttpClient, userAgent: String = "MusicEnrichmentEngine/1.0")`. |
| Stale serving for Error and RateLimited only | Any caching library with TTL support is expected to handle the offline/degraded case. Serving cached data when the network fails is table stakes for mobile-targeted libraries. | MEDIUM | Must NOT serve stale for `NotFound` — that means the provider searched and found nothing. Stale is only meaningful when a transient failure prevented a lookup that might otherwise succeed. |
| `isStale: Boolean = false` on EnrichmentResult.Success | Consumers need to know when they are seeing cached fallback data vs fresh results, so they can display staleness indicators or decide to retry. | LOW | Add at the end of the `Success` data class with default `false` to preserve source compatibility. Use `.copy(isStale = true)` when stamping. |
| Stale result not re-cached | Serving a stale result and then immediately re-caching it with a fresh TTL would silently extend stale data's lifespan. The cache write loop must skip entries where `isStale == true`. | LOW | One-line guard: `result is EnrichmentResult.Success && !result.isStale` in the cache write loop. |
| CacheMode enum with NETWORK_FIRST default | Opt-in stale serving. Existing callers must not experience changed behaviour. `NETWORK_FIRST` = current behaviour, `STALE_IF_ERROR` = new opt-in. | LOW | Two values is sufficient. `CACHE_FIRST` (serve stale preemptively, background refresh) is a different architecture and explicitly out of scope. |
| `enrichBatch()` returning Flow<Pair<EnrichmentRequest, EnrichmentResults>> | Processing a library of 500 albums one-by-one with individual `enrich()` calls is the most common use case. Callers need a way to iterate without managing their own loop. Flow emission is idiomatic Kotlin for this pattern. | LOW | Sequential by design: the rate limiter (MusicBrainz 1 req/sec) makes parallelism counterproductive. Cache hits return fast; rate-limited items slow the stream. Caller cancels with standard Flow cancellation (`take(N)`, scope cancellation). |
| Default implementation in EnrichmentEngine interface | `enrichBatch()` belongs on `EnrichmentEngine` with a default implementation so existing custom engine implementations do not break. | LOW | Default impl: `flow { for (r in requests) emit(r to enrich(r, types, forceRefresh)) }`. `DefaultEnrichmentEngine` provides an explicit override (same logic) to allow future optimization. |
| Maven Central distribution | JitPack is a development convenience, not a production dependency strategy. Corporate Android teams, library aggregators (Dependabot, Renovate), and security scanners all require Maven Central artifacts. Libraries without Maven Central distribution are blocked from adoption at many organizations. | HIGH | See Maven Central section below — OSSRH is dead, must use Central Portal. |
| sources and javadoc jars | Maven Central requirement. Rejections occur without these. IDEs need sources.jar for in-editor documentation. | LOW | `java { withSourcesJar(); withJavadocJar() }` for JVM modules. `android { publishing { singleVariant("release") { withSourcesJar(); withJavadocJar() } } }` for Android module. |
| GPG signing of all artifacts | Maven Central hard requirement for releases. Unsigned artifacts are rejected. | MEDIUM | Conditional signing (`isRequired` based on GPG key presence) allows local builds without a key. CI uses env vars. Use `in-memory-pgp-key` pattern for CI. |
| POM metadata (name, description, license, developer, SCM) | Maven Central validation rejects artifacts with incomplete POM. All five fields are checked by the portal. | LOW | Apache 2.0 license. SCM points to GitHub repo. One developer block is sufficient. |

### Differentiators (Competitive Advantage)

Features that distinguish musicmeta from a generic enrichment wrapper.

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| OkHttp adapter as a separate module | Consumer only pulls OkHttp into their dependency graph if they choose the adapter. The core module stays dependency-light (org.json, kotlinx-coroutines only). This is the pattern used by Ktor's client-engine modules and VK SDK. | LOW | `musicmeta-okhttp` module with `api(project(":musicmeta-core"))` and `api(libs.okhttp)`. Consumers who don't want OkHttp never see it. |
| getIncludingExpired() with backward-compatible default | Custom EnrichmentCache implementations (e.g., encrypted caches, SQLite-backed caches on non-Android JVM) automatically get safe behaviour: stale serving doesn't activate for them (returns null), but they don't break. Only InMemoryEnrichmentCache and RoomEnrichmentCache actively provide stale data. | LOW | Default `= null` implementation on the interface. Backward compatible across all custom implementations. |
| enrichBatch() cancellation stops processing | A Flow-based API inherits cooperative cancellation. Collecting `take(5)` from an enrichBatch of 100 requests stops processing after 5 results — no wasted API calls. This is a real advantage over a callback-based batch API. | LOW — inherent to cold Flow | Sequential cold Flow gets this for free. No extra implementation work. Document it as a feature. |
| forceRefresh propagation through enrichBatch | Callers refreshing a library after a metadata scrape can force-refresh the entire batch with a single flag. | LOW | `forceRefresh` parameter threaded from `enrichBatch()` into each `enrich()` call. |

### Anti-Features (Commonly Requested, Often Problematic)

| Feature | Why Requested | Why Problematic | Alternative |
|---------|---------------|-----------------|-------------|
| CACHE_FIRST mode (serve stale preemptively, refresh in background) | "Always fast" UX — return cached data instantly, update in background | Requires background coroutine scope management, coordination between the background refresh and the cache write, and a notification mechanism for consumers. Different architecture from STALE_IF_ERROR. Out of scope per docs/v0.8.0.md. | `STALE_IF_ERROR` covers the critical offline/degraded case. Callers who need CACHE_FIRST can implement it at the app layer by calling `enrich()` and serving cached results themselves while the call is in flight. |
| Stale serving for NotFound | "Return something rather than nothing" | NotFound means the provider actively searched and found no data. Serving stale for NotFound would present outdated data as current for an entity that may have genuinely changed (artist renamed, album removed). Misleading. | Only serve stale for `Error` and `RateLimited` — transient failures where the data might be correct if the network were up. |
| OkHttp built-in retry in the adapter | "Parity with DefaultHttpClient" | DefaultHttpClient's retry loop is a compensatory mechanism for HttpURLConnection's lack of retry. OkHttp users configure retries via interceptors — adding retry in the adapter conflicts with caller-provided retry interceptors, potentially causing double-retry. | Document the difference. Let callers add `addInterceptor(RetryInterceptor())` to their OkHttpClient. |
| Parallel enrichBatch | "Faster processing for large libraries" | The MusicBrainz rate limiter (1 req/sec) makes parallelism counterproductive — it just generates RateLimited errors faster. Parallelism also makes error attribution harder (which request caused which rate limit). | Sequential with rate limiter is 90% of the value. Callers who want parallelism today can call `enrich()` in their own `async {}` blocks. |
| OkHttp 5.x upgrade | "Use the latest version" | OkHttp 5.x changes MockWebServer package name (`mockwebserver3`), drops Kotlin Multiplatform, and has API changes (Duration instead of TimeUnit for timeouts). Library consumers may still be on 4.x. OkHttp 4.x and 5.x are binary-incompatible in some areas. | Pin to 4.12.0 (latest stable 4.x). This maximizes consumer compatibility. Add a note in docs about 5.x migration path when it stabilizes further. OkHttp 5.3.x is the current stable 5.x but most Android ecosystem is still on 4.x. |
| OSSRH-based Maven Central publishing | "Standard Sonatype publishing" | OSSRH reached end of life on June 30, 2025 and is shut down. Using the legacy OSSRH configuration (`s01.oss.sonatype.org`) will produce a failing build with no clear error. | Use Central Portal directly via vanniktech gradle-maven-publish-plugin with `CENTRAL_PORTAL` host. This is the only working path as of 2025. |
| Root build.gradle.kts subprojects block for publishing | "Centralize shared config" | The `subprojects {}` DSL in root Gradle is considered an anti-pattern by Gradle; it creates implicit coupling and breaks project isolation (issues with configuration cache). | Use the vanniktech plugin applied per-module, or use a Gradle convention plugin in `buildSrc`. The vanniktech plugin supports inheriting shared POM metadata via root `gradle.properties`. |

---

## Feature Dependencies

```
OkHttp Adapter (musicmeta-okhttp module)
    └──requires──> HttpClient interface (musicmeta-core)
    └──requires──> HttpResult sealed class (musicmeta-core)
    └──enables──> Maven Central (3rd module exists before publishing config)

Stale-While-Revalidate
    └──requires──> EnrichmentCache.getIncludingExpired() (new interface method)
    └──requires──> InMemoryEnrichmentCache change (stop eager deletion)
    └──requires──> EnrichmentResult.Success.isStale field (new)
    └──requires──> CacheMode enum (new)
    └──requires──> EnrichmentConfig.cacheMode field (new)
    └──enhances──> enrichBatch() (batch inherits offline support automatically)

enrichBatch()
    └──requires──> EnrichmentEngine.enrich() (already exists)
    └──requires──> kotlinx-coroutines Flow (already a transitive dependency)
    └──enhances with──> Stale-While-Revalidate (batch calls benefit from stale serving)

Maven Central Publishing
    └──requires──> All 3 modules exist (musicmeta-okhttp must be created first)
    └──requires──> sources + javadoc jars per module
    └──requires──> GPG signing
    └──requires──> Central Portal account + namespace verified
    └──requires──> vanniktech gradle-maven-publish-plugin (CENTRAL_PORTAL host)
```

### Dependency Notes

- **Stale cache before bulk:** Correct ordering. If bulk is shipped first without stale support, the batch API silently propagates errors for all rate-limited items. Building stale first means batch inherits offline support for free.
- **OkHttp module before Maven Central:** Maven Central config covers all 3 modules. The `musicmeta-okhttp` module must exist before writing the publishing config so all three can be configured at once.
- **enrichBatch() cancellation is free:** Cold Flow with sequential `emit()` calls inside a `for` loop respects cooperative cancellation between items. No extra implementation work — just a test to verify the behaviour.

---

## MVP Definition

### Launch With (v0.8.0)

These four features are the complete v0.8.0 scope. Each addresses a specific production readiness gap.

- [ ] **OkHttp adapter** — removes the adoption blocker for Android teams. Without this, any team managing an OkHttpClient cannot use the library without running two HTTP stacks.
- [ ] **Stale-while-revalidate cache** — required for mobile offline scenarios. An enrichment library that returns empty results when the network blips is unreliable for offline-capable apps.
- [ ] **enrichBatch() with Flow** — the most common real-world use case (enrich a full music library) has no ergonomic API today. Callers write boilerplate for loops.
- [ ] **Maven Central publishing** — JitPack-only blocks corporate adoption. Maven Central is the distribution standard.

### Add After Validation (v0.8.x — if issues emerge)

- [ ] **OkHttp 5.x variant** — only if consumer demand emerges. Pin to 4.12.0 for now to maximize compatibility.
- [ ] **`STALE_WHILE_REVALIDATE` mode** — a proactive stale mode (serve stale + refresh in background) if `STALE_IF_ERROR` proves insufficient. Requires background coroutine scope management.
- [ ] **enrichBatch() with concurrency** — parallel execution with configurable concurrency limit. Only if profiling shows sequential is too slow for practical library sizes.

### Future Consideration (v0.9+)

- [ ] **CACHE_FIRST mode** — serve stale always, background refresh. Different architecture, deferred per PROJECT.md.
- [ ] **Ktor client adapter** — Kotlin Multiplatform target. Requires KMP refactor of core.
- [ ] **Flow-based progressive enrichment** — emit partial results as providers resolve. Blocked by identity resolution (must complete before downstream providers can start) and mergeable types (can't emit until all providers finish).

---

## Feature Prioritization Matrix

| Feature | User Value | Implementation Cost | Priority |
|---------|------------|---------------------|----------|
| OkHttp adapter | HIGH — Android adoption blocker | LOW — implement 12 interface methods | P1 |
| Stale-while-revalidate | HIGH — mobile offline table stakes | MEDIUM — 4 files in core, 2 in android | P1 |
| enrichBatch() | HIGH — most common use case | LOW — default interface impl + override | P1 |
| Maven Central publishing | HIGH — corporate adoption blocker | MEDIUM — Central Portal setup, signing | P1 |

All four are P1. The ordering in docs/v0.8.0.md (OkHttp → Stale → Batch → Maven Central) is correct based on dependencies.

---

## Validation of docs/v0.8.0.md Design Decisions

Assessed against research findings. Flag items are gaps or incorrect assumptions.

### Phase 1: OkHttp Adapter

| Decision in docs/v0.8.0.md | Assessment | Notes |
|-----------------------------|------------|-------|
| OkHttp version `4.12.0` | CORRECT | 4.12.0 is the latest stable 4.x. OkHttp 5.x (current: 5.3.2) changes MockWebServer package (`mockwebserver3`) and drops KMP support. Pin to 4.x for maximum consumer compatibility. Flag: if consumers report 5.x conflicts, add a 5.x variant later. |
| MockWebServer for tests | CORRECT | Standard tool. With 4.12.0: `com.squareup.okhttp3:mockwebserver:4.12.0` and package `okhttp3.mockwebserver`. |
| No retry logic in adapter | CORRECT | Critical correctness decision. DefaultHttpClient retries compensate for HttpURLConnection's limitations. OkHttp interceptors handle retry. Documented difference in KDoc is the right approach. |
| Do not set Accept-Encoding manually | CORRECT, but not stated explicitly in docs | The doc says "GZIP: handled automatically by OkHttp (no manual GZIPInputStream)" but does not explicitly say "do NOT set the Accept-Encoding header." If the adapter's `buildGetRequest` copies DefaultHttpClient's header-setting pattern, it will break transparent decompression. This is the most likely implementation mistake. |
| `withContext(Dispatchers.IO)` on all methods | CORRECT | OkHttp calls are blocking despite the coroutine interface. `Dispatchers.IO` is required. |
| `client.newBuilder().followRedirects(false)` for fetchRedirectUrl | CORRECT | Standard OkHttp pattern for capturing Location header. `response.header("Location")` returns the value. Handle null (no Location) by returning the original URL if status was 2xx, null otherwise. |
| `toHttpResult(response)` private helper | CORRECT | Status code mapping: 429 → RateLimited (parse Retry-After as seconds × 1000), 400-499 → ClientError, 500-599 → ServerError, 200-299 → Ok, else → ClientError. OkHttp error body is `response.body?.string()` (works for both success and error). |
| Constructor `(client: OkHttpClient, userAgent: String)` | CORRECT | Caller owns OkHttpClient lifecycle (connection pool, timeouts, interceptors). Library should not create it. |

**Gap identified:** The `Accept-Encoding: gzip` footgun should be called out explicitly in the implementation notes — not just "GZIP handled automatically." The builder helper `buildGetRequest` must not call `.addHeader("Accept-Encoding", "gzip")`.

### Phase 2: Stale-While-Revalidate Cache

| Decision in docs/v0.8.0.md | Assessment | Notes |
|-----------------------------|------------|-------|
| CacheMode enum with only NETWORK_FIRST and STALE_IF_ERROR | CORRECT | Two modes is right. CACHE_FIRST is a different architecture (requires background scope). Adding a third mode now would invite confusion. |
| isStale field added to Success with default false | CORRECT | Default false preserves source compatibility. `.copy(isStale = true)` for stamping is idiomatic data class usage. |
| getIncludingExpired() default implementation = null | CORRECT | Custom cache implementations auto-safe. They don't support stale serving but they also don't break. The null return causes the stale logic in the engine to find nothing and propagate the original Error/RateLimited result unchanged. |
| Stop eager deletion in InMemoryEnrichmentCache.get() | CORRECT | Change line 21: remove `entries.remove(key)`, just `return null`. The expired entry stays for `getIncludingExpired()`. LRU eviction (LinkedHashMap accessOrder + size cap) handles memory. |
| No stale for NotFound | CORRECT | Critical correctness decision. NotFound = provider searched and found nothing = the data may not exist. Serving stale here would present outdated data as if the entity still has it. Only Error and RateLimited are transient failures. |
| Stale result not re-written to cache | CORRECT | The cache write guard `&& !result.isStale` prevents extending the TTL of expired data. Without this, a stale result would be re-cached as if it were fresh. |
| applyStaleCache() as private method in engine | CORRECT | Keeps the main enrich() flow readable. The stale logic is non-trivial (iterate types, check each result type, call getIncludingExpired, stamp isStale). Extracting to a method keeps enrich() under 40 lines. |
| No Room migration needed | CORRECT | The new DAO query `getIncludingExpired` uses the same table and columns as `get()` — just omits `AND expires_at > :now`. No schema change. |
| Timeout errors covered | CORRECT | Timeout errors arrive as `EnrichmentResult.Error` with `errorKind = ErrorKind.TIMEOUT`. They are covered by the `is EnrichmentResult.Error` check. No special case needed. |

**No gaps identified in Phase 2 design.** The plan is correct and complete.

### Phase 3: Bulk Enrichment

| Decision in docs/v0.8.0.md | Assessment | Notes |
|-----------------------------|------------|-------|
| Flow<Pair<EnrichmentRequest, EnrichmentResults>> return type | CORRECT | Cold Flow is the right abstraction. Pair<Request, Results> lets callers match results back to requests when processing in bulk. |
| Sequential iteration via for loop | CORRECT | Rate limiter (1 req/sec for MusicBrainz) means parallelism just generates more RateLimited results. Cache hits return fast; sequential is fast when cached. |
| Default implementation on interface | CORRECT | Backwards compatible for custom engine implementations. They inherit bulk for free. |
| Explicit override in DefaultEnrichmentEngine | CORRECT | Allows future optimization (e.g., cache-only fast path, batched identity resolution) without changing the interface signature. |
| Turbine for Flow tests | CORRECT | Turbine is already in the test bundle (`libs.bundles.testing`). `awaitItem()`, `awaitComplete()`, `cancelAndConsumeRemainingEvents()` are the right test primitives. |
| `take(1)` cancellation test | CORRECT | Cold Flow with sequential `emit()` respects cooperative cancellation between items. `take(1)` stops collection after the first emission. The second `enrich()` call is never made. The test verifying "remaining not processed" is valid and important. |
| `forceRefresh: Boolean = false` parameter | CORRECT | Threaded through to each `enrich()` call. No additional logic needed. |

**No gaps identified in Phase 3 design.** The plan is correct and complete.

### Phase 4: Maven Central Publishing

| Decision in docs/v0.8.0.md | Assessment | Notes |
|-----------------------------|------------|-------|
| OSSRH repository URLs | **INCORRECT — CRITICAL GAP** | OSSRH was shut down on June 30, 2025. `s01.oss.sonatype.org` and `oss.sonatype.org` no longer accept publishing requests. Using these URLs will fail silently or with an auth error. |
| "OSSRH config, credentials from gradle.properties" | **INCORRECT — CRITICAL GAP** | The OSSRH credential system (Jira-based username/password) is gone. Central Portal uses user tokens generated from the Central Portal web UI — a different credential type at a different URL. |
| Root build.gradle.kts subprojects block | **QUESTIONABLE** | The `subprojects {}` DSL approach creates implicit project coupling and is incompatible with Gradle's configuration cache. The Gradle team explicitly recommends against it in favour of convention plugins. For a 3-module project this is unlikely to cause immediate problems, but it goes against current Gradle best practices. |
| `java { withSourcesJar(); withJavadocJar() }` | CORRECT | Standard Maven Central requirement for JVM modules. |
| Android module `singleVariant("release")` | CORRECT | Android library publishing requires variant selection. `release` variant is correct. |
| Version `0.8.0` in all three modules | CORRECT | Version bump is required. All three modules should publish the same version. |

**Critical gap: the entire publishing mechanism must use Central Portal, not OSSRH.** The correct approach as of 2025:

Use vanniktech `gradle-maven-publish-plugin` (current version: 0.36.0) applied per module. Configuration:
```kotlin
// In each module's build.gradle.kts:
plugins {
    id("com.vanniktech.maven.publish") version "0.36.0"
}
mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    coordinates("com.landofoz", "musicmeta-core", "0.8.0")
    pom { /* name, description, license, developer, scm */ }
}
```
Credentials: Central Portal user tokens (not OSSRH credentials) stored in `~/.gradle/gradle.properties` as `mavenCentralUsername` and `mavenCentralPassword`.

---

## Phase-Specific Complexity Notes

### Phase 1 (OkHttp Adapter) — LOW overall
- ~200 lines implementation file (within 300-line max)
- 12 methods but heavy repetition — private helpers keep each method short
- Main risk: the Accept-Encoding footgun (do not set manually)
- Secondary risk: fetchRedirectUrl — verify null Location header handling for 2xx responses

### Phase 2 (Stale Cache) — MEDIUM overall
- Touches 4 core files + 2 Android files + 2 test files + 1 fake
- No schema changes, no migration files
- Main risk: subtle LRU interaction — LinkedHashMap with accessOrder=true reorders on `get()`. The `getIncludingExpired()` call-through in the engine should not accidentally promote the expired entry in LRU order (making it harder to evict). Call `getIncludingExpired()` directly on the map without going through `get()` to avoid the side effect. The `entries[key]` access in `getIncludingExpired()` WILL still update LRU order in LinkedHashMap — acceptable given the entry is going to be used.
- Secondary risk: RoomEnrichmentCache `deserializeEntity()` extraction — the deserialization code uses `try/catch` blocks that must be preserved exactly

### Phase 3 (Bulk Enrichment) — LOW overall
- ~20 lines of implementation across two files
- Main risk: none — sequential cold Flow is the simplest correct implementation
- Test coverage is the main effort

### Phase 4 (Maven Central) — MEDIUM-HIGH overall (process complexity, not code complexity)
- Code changes are small (build files only)
- Process complexity: Central Portal account, namespace verification, GPG key generation/upload, first-publish approval
- First publish to Maven Central requires manual approval in the Portal UI — not automated
- The `publishAndReleaseToMavenCentral` task requires `automaticRelease = true` or manual Portal approval step

---

## Sources

- OkHttp documentation (official): https://square.github.io/okhttp/ (HIGH confidence)
- OkHttp changelog (current version 5.3.2, latest 4.x is 4.12.0): https://square.github.io/okhttp/changelogs/changelog/ (HIGH confidence — official)
- OkHttp gzip transparent decompression: https://github.com/square/okhttp/issues/2132 (HIGH confidence — official issue, confirmed by docs)
- Sonatype OSSRH End of Life announcement: https://central.sonatype.org/news/20250326_ossrh_sunset/ (HIGH confidence — official)
- OSSRH Sunset documentation: https://central.sonatype.org/pages/ossrh-eol/ (HIGH confidence — official)
- vanniktech gradle-maven-publish-plugin Central Portal docs: https://vanniktech.github.io/gradle-maven-publish-plugin/central/ (HIGH confidence — official plugin docs, version 0.36.0)
- Kotlin Flow cancellation (take(1) cooperative cancellation): https://kotlinlang.org/docs/flow.html (HIGH confidence — official Kotlin docs)
- Turbine Flow testing library: https://github.com/cashapp/turbine (HIGH confidence — official repo)
- OkHttp MockWebServer 5.x coordinate change: https://github.com/square/okhttp/issues/7339 (HIGH confidence — official issue)
- Gradle publishing best practices (subprojects anti-pattern): https://docs.gradle.org/current/userguide/publishing_maven.html (MEDIUM confidence — official Gradle docs)

---

*Feature research for: musicmeta v0.8.0 Production Readiness*
*Researched: 2026-03-24*
