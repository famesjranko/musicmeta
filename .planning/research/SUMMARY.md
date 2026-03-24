# Project Research Summary

**Project:** musicmeta v0.8.0 Production Readiness
**Domain:** Kotlin/JVM music metadata enrichment library — OkHttp adapter, stale cache, bulk enrichment, Maven Central
**Researched:** 2026-03-24
**Confidence:** HIGH

## Executive Summary

v0.8.0 is a production readiness milestone, not a feature expansion. The four features — OkHttp adapter module, stale-while-revalidate cache, bulk enrichment Flow API, and Maven Central publishing — each remove a specific adoption blocker for production Android teams. The existing architecture accommodates all four additions cleanly: the `HttpClient` interface has an integration point at `EnrichmentEngine.Builder.httpClient()` (line 78) that accepts any implementation; `EnrichmentResult.Success` can absorb the `isStale` flag with a default that preserves source compatibility; `Flow` is already a transitive dependency so `enrichBatch()` needs no new dep; and the Gradle build structure accepts per-module publishing config without restructuring.

The recommended implementation order is OkHttp adapter first (creates the third module required for Maven Central config), stale cache second (so bulk enrichment inherits offline support for free), batch API third (minimal implementation, relies on stale cache), and Maven Central last (requires all three modules to exist). This ordering is already in `docs/v0.8.0.md` and is confirmed correct by dependency analysis. Phases 1 and 2 are independent of each other and could proceed concurrently if needed.

The single highest-risk item is Phase 4: the `docs/v0.8.0.md` plan targets the defunct OSSRH publishing infrastructure, which was shut down on June 30, 2025. The entire publishing mechanism must be replaced with the `vanniktech/gradle-maven-publish-plugin` (v0.36.0) targeting Sonatype Central Portal. Every other design decision in the plan has been verified correct against actual source files and official documentation. Implementation risk is LOW for Phases 1-3 (clear patterns, existing codebase structure accommodates all changes without restructuring) and MEDIUM-HIGH for Phase 4 (process complexity: Central Portal account, namespace verification, GPG key setup, first-publish manual approval).

## Key Findings

### Recommended Stack

The existing validated stack (Kotlin 2.1.0, coroutines 1.9.0, kotlinx.serialization 1.7.3, org.json, JUnit 4.13.2, Turbine 1.2.0) requires no changes for Phases 1-3. Only two new library entries and one new plugin enter the build for v0.8.0.

**Core technologies — new additions only:**
- `com.squareup.okhttp3:okhttp:4.12.0`: HTTP client implementation for the adapter module — 4.12.0 is the final stable 4.x release; OkHttp 5.x is rejected because it forces Kotlin 2.2.x stdlib onto library consumers and renames MockWebServer coordinates with no compensating benefit at this milestone
- `com.squareup.okhttp3:mockwebserver:4.12.0`: Integration test server for adapter — standard tool; the 4.x coordinate is unchanged (the 5.x rename to `mockwebserver3-junit4` does not apply here)
- `com.vanniktech.maven.publish:0.36.0` (Gradle plugin): Maven Central publishing via Central Portal — the only working publishing path since OSSRH shutdown; handles sources/javadoc jars, GPG signing, and Android variant timing automatically per module

See `.planning/research/STACK.md` for the exact version catalog additions and per-module `build.gradle.kts` templates (including the `musicmeta-okhttp` build file).

### Expected Features

All four v0.8.0 features are P1. No feature is optional; each removes a concrete adoption blocker.

**Must have (table stakes):**
- OkHttp adapter implementing all 10 `HttpClient` methods — Android teams managing an `OkHttpClient` singleton cannot use the library without it; running two HTTP stacks in one app is not viable
- Stale serving for `Error` and `RateLimited` only (not `NotFound`) — mobile offline support is expected in any enrichment library targeting Android; returning empty results on network blip is a reliability failure
- `CacheMode` enum with `NETWORK_FIRST` default — existing callers must see no behavior change; `STALE_IF_ERROR` is explicit opt-in
- `isStale: Boolean = false` on `EnrichmentResult.Success` — consumers need to distinguish fallback data from fresh results to show staleness indicators or decide to retry
- `enrichBatch()` returning `Flow<Pair<EnrichmentRequest, EnrichmentResults>>` — the most common real-world use case (enrich a full music library) has no ergonomic API today; callers write boilerplate for loops
- Maven Central distribution with sources jar, javadoc jar, and GPG-signed artifacts — JitPack blocks corporate adoption; Maven Central is required for Dependabot, Renovate, and security scanning

**Should have (differentiators):**
- `musicmeta-okhttp` as a separate module — core stays dependency-light (org.json + coroutines only); consumers who don't want OkHttp never pull it in; this is the Ktor client engine module pattern
- `enrichBatch()` cooperative cancellation — `take(N)` from the cold Flow stops processing with no wasted API calls; this is worth documenting explicitly as a feature
- `getIncludingExpired()` with default null on `EnrichmentCache` interface — custom implementations auto-safe without breaking; only `InMemoryEnrichmentCache` and `RoomEnrichmentCache` actively provide stale data

**Defer (v0.8.x or v0.9+):**
- `CACHE_FIRST` mode (serve stale always, background refresh) — requires background coroutine scope management and a consumer notification mechanism; different architecture; explicitly out of v0.8.0 scope
- `STALE_WHILE_REVALIDATE` proactive mode — only if `STALE_IF_ERROR` proves insufficient in practice
- Parallel `enrichBatch` — MusicBrainz rate limiter (1 req/sec) makes parallelism counterproductive; sequential is 90% of the value; concurrency is a future optimization
- OkHttp 5.x variant of the adapter — only if consumer demand emerges; this upgrade belongs alongside a Kotlin 2.2.x bump

See `.planning/research/FEATURES.md` for full anti-feature analysis and feature dependency graph.

### Architecture Approach

The architecture is purely additive for Phases 1 and 3; Phase 2 modifies 6 core files and 2 Android files; Phase 4 touches only build files. No existing interfaces are broken — every change either extends an interface with a backward-compatible default implementation or adds a field with a default value. The `HttpClient` interface integration point accepts any implementation, so `OkHttpEnrichmentClient` plugs in with no engine changes required.

**Major components and changes:**
1. `OkHttpEnrichmentClient` (new file, new module) — implements all 10 `HttpClient` methods via `OkHttp Call.execute()` wrapped in `withContext(Dispatchers.IO)`; three private helpers (`buildGetRequest`, `buildPostRequest`, `toHttpResult`) keep each public method under 40 lines
2. `EnrichmentCache` interface — gains `getIncludingExpired()` with default `null`; `InMemoryEnrichmentCache` changes eager-delete at line 21 to return-null-only; `RoomEnrichmentCache` adds a new DAO query (no schema migration needed)
3. `DefaultEnrichmentEngine` — inserts `applyStaleCache()` after the timeout catch block (line 93); adds `!result.isStale` guard to the cache write loop at line 97; adds explicit `enrichBatch()` override
4. `EnrichmentEngine` interface — gains `enrichBatch()` default implementation (6 lines); `CacheMode` enum added to cache package
5. Build system — vanniktech plugin applied per module; all three modules publish version `0.8.0` to Central Portal

**Critical correction to `docs/v0.8.0.md` Phase 4:** The plan's "subprojects block with OSSRH URLs" approach is entirely wrong and must not be implemented. Replace with per-module vanniktech DSL. The `AndroidSingleVariantLibrary` configure block replaces both `android { publishing { singleVariant() } }` and `afterEvaluate { publishing { } }`. The `java { withSourcesJar(); withJavadocJar() }` blocks are also unnecessary — the vanniktech plugin adds them automatically.

**Minor correction:** `docs/v0.8.0.md` and `PROJECT.md` refer to "12 HttpClient methods" — actual count verified from source is 10 (6 nullable + 4 HttpResult). No impact on implementation scope; implement all 10 methods.

See `.planning/research/ARCHITECTURE.md` for a file-by-file change map with exact line references confirmed against source, and a complete before/after validation table for all claims in `docs/v0.8.0.md`.

### Critical Pitfalls

1. **OkHttp transparent GZIP disabled by manual `Accept-Encoding` header** — Do NOT set `Accept-Encoding: gzip` in `buildGetRequest()`. OkHttp adds this header automatically and decompresses transparently; manually setting it signals "caller will decompress" and causes binary gzip bytes to reach the JSON parser. Test with a MockWebServer endpoint serving a gzip-compressed JSON response body.

2. **OSSRH is shut down — `docs/v0.8.0.md` Phase 4 targets a dead system** — All OSSRH endpoints (`s01.oss.sonatype.org`, `oss.sonatype.org`) have rejected uploads since June 30, 2025. Replace the entire Phase 4 publishing approach with the vanniktech plugin targeting `SonatypeHost.CENTRAL_PORTAL`. Use Central Portal user tokens (generated at `central.sonatype.com/account`) — not OSSRH login credentials — stored in `~/.gradle/gradle.properties`.

3. **Stale results re-cached with fresh TTL** — The engine cache write loop at line 97 writes all `Success` results. After stale serving, stale results are `EnrichmentResult.Success` instances with `isStale = true`. Without the `&& !result.isStale` guard, expired data gets a new TTL and remains stale indefinitely even after the provider recovers.

4. **`InMemoryEnrichmentCache` mutex deadlock in `getIncludingExpired`** — Kotlin's `Mutex` is not reentrant. If `getIncludingExpired()` calls `get()` internally to reduce duplication, the second `mutex.withLock` on the same coroutine deadlocks. Both methods must access the `entries` map directly under their own lock without delegating to each other.

5. **OkHttp response body must be closed explicitly** — `response.body?.string()` reads the body but does not guarantee closure if an exception is thrown before the read. Wrap every `execute()` call with `response.use { }` to guarantee connection pool release on all paths.

See `.planning/research/PITFALLS.md` for 8 critical pitfalls with warning signs, recovery steps, and an 11-item "looks done but isn't" verification checklist.

## Implications for Roadmap

The `docs/v0.8.0.md` phase ordering is confirmed correct by dependency analysis. The rationale is dependency-driven, not arbitrary.

### Phase 1: OkHttp Adapter Module (`musicmeta-okhttp`)

**Rationale:** Creates the third Gradle module that Phase 4 (Maven Central) must configure. Also the single largest implementation task (10 methods) and validates that the `HttpClient` interface is fully implementable without changes to the engine. Phases 2 and 3 are independent of Phase 1 but Phase 4 depends on it.
**Delivers:** New `musicmeta-okhttp` Gradle module; `OkHttpEnrichmentClient` implementing all 10 `HttpClient` methods; MockWebServer integration tests; version catalog additions for okhttp and mockwebserver.
**Addresses:** Android adoption blocker (dual HTTP stack problem); OkHttp ecosystem compatibility.
**Avoids:** GZIP decompression footgun (do not set `Accept-Encoding`), redirect-following misconfiguration (create `noRedirectClient` at construction time), response body connection leaks (wrap with `response.use { }`).
**Research flag:** No additional research needed — OkHttp patterns are well-documented; all 10 method behaviors verified against `DefaultHttpClient.kt`; implementation plan confirmed against source.

### Phase 2: Stale-While-Revalidate Cache

**Rationale:** Must precede Phase 3 so `enrichBatch()` inherits offline support automatically. The stale cache infrastructure (`CacheMode`, `isStale`, `getIncludingExpired`) is a prerequisite for the batch API to behave correctly under degraded network conditions. Technically independent of Phase 1 — touches non-overlapping files.
**Delivers:** `CacheMode` enum; `isStale: Boolean = false` on `EnrichmentResult.Success`; `getIncludingExpired()` on `EnrichmentCache` interface; `STALE_IF_ERROR` logic in `DefaultEnrichmentEngine`; new DAO query in `EnrichmentCacheDao`; updated `RoomEnrichmentCache`; updated `FakeEnrichmentCache`; 8 new test cases.
**Addresses:** Mobile offline scenario; staleness transparency for UI consumers.
**Avoids:** Stale re-caching (add `!result.isStale` guard), mutex deadlock (direct map access under lock, never call `get()` from `getIncludingExpired()`), stale-for-`NotFound` correctness error.
**Research flag:** No additional research needed — all file locations, line numbers, and change boundaries verified against actual source. No schema migration required.

### Phase 3: Bulk Enrichment Flow API

**Rationale:** Minimal implementation (~20 lines across two files). Depends on Phase 2 so each `enrich()` call inside the batch automatically uses `cacheMode` and the stale fallback path. Implementation effort is in test coverage, not code.
**Delivers:** `enrichBatch()` default method on `EnrichmentEngine` interface; explicit override in `DefaultEnrichmentEngine`; `EnrichmentBatchTest.kt` with 5 Turbine test cases (normal batch, cancellation, empty list, exception safety, force-refresh propagation).
**Addresses:** Most common real-world use case (process a full music library); cooperative cancellation via cold Flow.
**Avoids:** Concurrent enrichBatch anti-pattern (MusicBrainz rate limiter makes parallelism counterproductive); exception propagation terminating the entire batch (add `try/catch` around `enrich()` in `DefaultEnrichmentEngine.enrichBatch()` override).
**Research flag:** No additional research needed — sequential cold Flow is the simplest correct implementation; Turbine is already in the test bundle.

### Phase 4: Maven Central Publishing

**Rationale:** Requires all three modules to exist (Phase 1 creates `musicmeta-okhttp`). Cannot configure publishing for a module that doesn't exist. Process complexity is high but Kotlin/build file changes are small.
**Delivers:** All three modules published as `0.8.0` to Maven Central via Central Portal; sources jars, javadoc jars, GPG-signed artifacts; complete POM metadata; credential placeholder comments in `gradle.properties`; updated README with Maven Central coordinates.
**Addresses:** Corporate adoption blocker (JitPack-only distribution); Dependabot/Renovate/security scanner compatibility.
**Avoids:** OSSRH-targeting failure (use vanniktech plugin + `CENTRAL_PORTAL` host), Android signing timing errors from root `subprojects` block (use per-module vanniktech DSL instead).
**Research flag:** PLAN CORRECTION REQUIRED before implementation — the `docs/v0.8.0.md` Phase 4 section must be rewritten entirely to use the vanniktech plugin. The OSSRH approach in the plan will fail immediately on the first publishing attempt. See `.planning/research/STACK.md` for the exact replacement configuration and `.planning/research/PITFALLS.md` Pitfalls 5 and 6 for the detailed correction.

### Phase Ordering Rationale

- **Phase 1 before Phase 4:** Maven Central config must cover all three modules; `musicmeta-okhttp` must exist before Phase 4 can be written.
- **Phase 2 before Phase 3:** `enrichBatch()` calls `enrich()` which uses `config.cacheMode`; without stale cache in place, batch silently propagates `RateLimited` errors for every rate-limited item in large libraries.
- **Phase 1 and Phase 2 are independent:** They touch non-overlapping files; they can proceed concurrently if needed.
- **Phase 4 last:** All module artifacts must exist; Central Portal account, namespace verification, and GPG setup are external blocking prerequisites.

### Research Flags

Phases with well-documented patterns (no additional research needed):
- **Phase 1:** OkHttp adapter is a standard Kotlin/JVM pattern; all method behaviors verified against `DefaultHttpClient.kt`; MockWebServer integration patterns are established.
- **Phase 2:** All file locations, line numbers, and change boundaries verified against actual source. No ambiguity.
- **Phase 3:** Trivially simple implementation; Turbine testing patterns already established in the test suite.

Phase requiring plan correction before implementation:
- **Phase 4:** The research is complete; no `/gsd:research-phase` needed. However, the `docs/v0.8.0.md` Phase 4 section must be rewritten before any build file changes are made. The vanniktech plugin configuration documented in `STACK.md` and `PITFALLS.md` is the authoritative replacement.

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | HIGH | All versions verified on Maven Central; OkHttp 4.12.0 rationale confirmed against 4.x and 5.x changelogs; vanniktech 0.36.0 confirmed as latest stable; OSSRH shutdown confirmed via official Sonatype announcement |
| Features | HIGH | OkHttp and Maven Central findings from official docs and changelogs; stale cache and bulk API findings from direct codebase analysis; all 10 `HttpClient` methods confirmed in source; anti-feature rationale verified against codebase constraints |
| Architecture | HIGH | All line numbers, file paths, and class structures verified against actual source files; no speculative claims; every assertion in `docs/v0.8.0.md` validated claim-by-claim with source citations |
| Pitfalls | HIGH | OkHttp gzip pitfall verified from official OkHttp issue #1579; OSSRH EOL from official Sonatype announcement; mutex non-reentrancy from Kotlin coroutines docs; Gradle signing timing from Gradle issue tracker; Flow cancellation from official Kotlin docs |

**Overall confidence:** HIGH

### Gaps to Address

- **HttpClient method count discrepancy:** `PROJECT.md` says "12 methods" but `HttpClient.kt` has 10. No impact on implementation — implement all 10. Update the count in `PROJECT.md` when the milestone is complete.
- **Central Portal namespace verification:** Whether `com.landofoz` is already registered in Sonatype Central Portal is unknown. If unverified, the first publish requires a manual approval step from Sonatype (typically 1-2 business days). Factor this into delivery timeline for Phase 4.
- **GPG key existence:** Research cannot determine if an existing GPG key suitable for Maven Central signing is available. If not, key generation, configuration, and upload to a public keyserver add pre-Phase-4 setup time.
- **`Accept-Encoding` in `buildGetRequest` — most likely implementation mistake:** Ensure the Phase 1 implementation review explicitly checks that `buildGetRequest()` does not include an `Accept-Encoding: gzip` header. This is the highest-probability mistake when porting logic from `DefaultHttpClient`.

## Sources

### Primary (HIGH confidence)
- Direct codebase analysis — `HttpClient.kt`, `DefaultHttpClient.kt`, `DefaultEnrichmentEngine.kt`, `InMemoryEnrichmentCache.kt`, `RoomEnrichmentCache.kt`, `EnrichmentResult.kt`, `EnrichmentCache.kt`, `EnrichmentConfig.kt`, `EnrichmentEngine.kt`, `EnrichmentCacheDao.kt`, `FakeEnrichmentCache.kt`, all `build.gradle.kts` files, `gradle/libs.versions.toml`, `settings.gradle.kts`
- [Maven Central: com.squareup.okhttp3:okhttp](https://central.sonatype.com/artifact/com.squareup.okhttp3/okhttp) — version history confirmed; 4.12.0 final 4.x release confirmed
- [OkHttp 4.x changelog](https://square.github.io/okhttp/changelogs/changelog_4x/) — 4.12.0 final 4.x release (Oct 2023) confirmed
- [OkHttp 5.x changelog](https://square.github.io/okhttp/changelogs/changelog/) — Kotlin 2.2.x requirement and `mockwebserver3` rename confirmed
- [Sonatype OSSRH sunset announcement](https://central.sonatype.org/news/20250326_ossrh_sunset/) — EOL June 30, 2025 confirmed
- [Sonatype OSSRH EOL page](https://central.sonatype.org/pages/ossrh-eol/) — shutdown and compatibility shim confirmed
- [vanniktech plugin GitHub releases](https://github.com/vanniktech/gradle-maven-publish-plugin/releases) — 0.36.0 latest stable (Jan 18, 2025) confirmed
- [vanniktech plugin Central Portal docs](https://vanniktech.github.io/gradle-maven-publish-plugin/central/) — `CENTRAL_PORTAL` host, `AndroidSingleVariantLibrary`, `signAllPublications()` DSL confirmed
- [OkHttp gzip issue #1579](https://github.com/square/okhttp/issues/1579) — manual `Accept-Encoding` disables transparent decompression confirmed
- [Kotlin Flow docs](https://kotlinlang.org/docs/flow.html) — cooperative cancellation at suspension points confirmed

### Secondary (MEDIUM confidence)
- [Gradle signing issue #13419](https://github.com/gradle/gradle/issues/13419) — `afterEvaluate` timing requirement for Android module signing
- [Gradle Maven publishing docs](https://docs.gradle.org/current/userguide/publishing_maven.html) — `subprojects` anti-pattern for publishing config
- [OkHttp MockWebServer 5.x coordinate change issue #7339](https://github.com/square/okhttp/issues/7339) — `mockwebserver3-junit4` rename in 5.x confirmed

---
*Research completed: 2026-03-24*
*Ready for roadmap: yes*
