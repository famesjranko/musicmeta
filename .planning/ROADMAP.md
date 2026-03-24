# Roadmap: musicmeta

## Milestones

- ✅ **v0.4.0 Provider Abstraction Overhaul** — Phases 1-5 (shipped 2026-03-21)
- ✅ **v0.5.0 New Capabilities & Tech Debt Cleanup** — Phases 6-11 (shipped 2026-03-22)
- ✅ **v0.6.0 Recommendations Engine** — Phases 12-18 (shipped 2026-03-23)
- 🚧 **v0.8.0 Production Readiness** — Phases 19-22 (in progress)

## Phases

<details>
<summary>✅ v0.4.0 Provider Abstraction Overhaul (Phases 1-5) — SHIPPED 2026-03-21</summary>

- [x] Phase 1: Bug Fixes (2/2 plans) — completed 2026-03-21
- [x] Phase 2: Provider Abstraction (3/3 plans) — completed 2026-03-21
- [x] Phase 3: Public API Cleanup (2/2 plans) — completed 2026-03-21
- [x] Phase 4: New Types (4/4 plans) — completed 2026-03-21
- [x] Phase 5: Deepening (4/4 plans) — completed 2026-03-21

Full details: `.planning/milestones/v0.4.0-ROADMAP.md`

</details>

<details>
<summary>✅ v0.5.0 New Capabilities & Tech Debt Cleanup (Phases 6-11) — SHIPPED 2026-03-22</summary>

- [x] Phase 6: Tech Debt Cleanup (4/4 plans) — completed 2026-03-21
- [x] Phase 7: Credits & Personnel (2/2 plans) — completed 2026-03-21
- [x] Phase 8: Release Editions (2/2 plans) — completed 2026-03-21
- [x] Phase 9: Artist Timeline (2/2 plans) — completed 2026-03-21
- [x] Phase 10: Genre Enhancement (3/3 plans) — completed 2026-03-21
- [x] Phase 11: Provider Coverage Expansion (3/3 plans) — completed 2026-03-21

Full details: `.planning/milestones/v0.5.0-ROADMAP.md`

</details>

<details>
<summary>✅ v0.6.0 Recommendations Engine (Phases 12-18) — SHIPPED 2026-03-23</summary>

- [x] Phase 12: Engine Refactoring (2/2 plans) — completed 2026-03-22
- [x] Phase 13: Similar Artists + Merger (2/2 plans) — completed 2026-03-22
- [x] Phase 14: Artist Radio (2/2 plans) — completed 2026-03-22
- [x] Phase 15: Similar Albums (2/2 plans) — completed 2026-03-22
- [x] Phase 16: Genre Discovery (2/2 plans) — completed 2026-03-22
- [x] Phase 17: Catalog Filtering Interface (2/2 plans) — completed 2026-03-22
- [x] Phase 18: Integration and Docs (2/2 plans) — completed 2026-03-23

Full details: `.planning/milestones/v0.6.0-ROADMAP.md`

</details>

### 🚧 v0.8.0 Production Readiness (In Progress)

**Milestone Goal:** Address the four production readiness gaps identified by external review — OkHttp adapter for Android teams, offline stale cache fallback, bulk enrichment Flow API, and Maven Central distribution.

- [x] **Phase 19: OkHttp Adapter** - New `musicmeta-okhttp` module; `OkHttpEnrichmentClient` implementing all 10 `HttpClient` methods (completed 2026-03-24)
- [x] **Phase 20: Stale Cache** - `CacheMode.STALE_IF_ERROR` serving expired cache entries on Error/RateLimited with `isStale` flag (completed 2026-03-24)
- [ ] **Phase 21: Bulk Enrichment** - `enrichBatch()` returning `Flow<Pair<EnrichmentRequest, EnrichmentResults>>` with sequential iteration and cooperative cancellation
- [ ] **Phase 22: Maven Central Publishing** - All 3 modules published to Maven Central via Central Portal using vanniktech plugin

## Phase Details

### Phase 19: OkHttp Adapter
**Goal**: Android developers can pass their existing `OkHttpClient` instance to `EnrichmentEngine.Builder` and use all engine features without running two HTTP stacks in their app
**Depends on**: Phase 18 (v0.6.0 complete)
**Requirements**: HTTP-01, HTTP-02, HTTP-03, HTTP-04, HTTP-05
**Success Criteria** (what must be TRUE):
  1. Developer can create `OkHttpEnrichmentClient(okHttpClient)` and pass it to `EnrichmentEngine.Builder.httpClient()` — the engine uses it for all HTTP calls
  2. All 10 `HttpClient` methods work correctly: JSON object, JSON array, body, redirect URL, POST variants, and all four `HttpResult`-returning variants
  3. HTTP 429 responses map to `RateLimited`, 4xx responses map to `ClientError`, 5xx responses map to `ServerError` — the same semantics as `DefaultHttpClient`
  4. Gzip decompression works transparently — OkHttp handles it automatically without a manually set `Accept-Encoding` header
  5. Adding `musicmeta-okhttp` to a project that doesn't declare it adds no transitive OkHttp dependency to `musicmeta-core`
**Plans**: 2 plans

Plans:
- [x] 19-01-PLAN.md — Create musicmeta-okhttp Gradle module and implement OkHttpEnrichmentClient with all 10 methods
- [x] 19-02-PLAN.md — Write MockWebServer integration tests covering all 10 methods, status code mapping, gzip decompression, and response body lifecycle

### Phase 20: Stale Cache
**Goal**: Consumers running under degraded network conditions (Error or RateLimited responses) get the last known data with an `isStale` flag instead of empty results
**Depends on**: Phase 19
**Requirements**: CACHE-01, CACHE-02, CACHE-03, CACHE-04, CACHE-05, CACHE-06
**Success Criteria** (what must be TRUE):
  1. Developer can set `CacheMode.STALE_IF_ERROR` in `EnrichmentConfig` — existing callers with no `cacheMode` set see no behavior change
  2. When the provider returns `Error` or `RateLimited` and an expired cache entry exists, the engine returns that entry as `EnrichmentResult.Success` with `isStale = true`
  3. When the provider returns `NotFound` and an expired cache entry exists, the engine returns `NotFound` — stale data is not served for genuine not-found responses
  4. A stale result served from cache is not re-written to cache; the expired entry's TTL is not renewed
  5. `InMemoryEnrichmentCache.getIncludingExpired()` returns the expired entry directly without calling `get()` internally
**Plans**: 2 plans

Plans:
- [x] 20-01-PLAN.md — Define stale cache contracts: CacheMode enum, isStale on Success, getIncludingExpired on EnrichmentCache, InMemoryEnrichmentCache implementation
- [x] 20-02-PLAN.md — Wire STALE_IF_ERROR into DefaultEnrichmentEngine, update RoomEnrichmentCache and FakeEnrichmentCache, write 8 unit tests

### Phase 21: Bulk Enrichment
**Goal**: Developers can enrich a list of requests with a single call, receiving results as a Flow so they can show progress or stop early — without writing for-loop boilerplate
**Depends on**: Phase 20
**Requirements**: BATCH-01, BATCH-02, BATCH-03, BATCH-04
**Success Criteria** (what must be TRUE):
  1. Developer can call `engine.enrichBatch(requests, types)` and collect a `Flow<Pair<EnrichmentRequest, EnrichmentResults>>` that emits one pair per request as each completes
  2. Cancelling the Flow (e.g., via `take(N)` or scope cancellation) stops processing the remaining requests without error
  3. Cache hits in the batch return immediately — the rate limiter delay is not applied to requests that are served entirely from cache
**Plans**: 1 plan

Plans:
- [ ] 21-01-PLAN.md — Add enrichBatch() default method to EnrichmentEngine interface; explicit override in DefaultEnrichmentEngine; 5 Turbine tests covering batch emission, cancellation, empty list, forceRefresh, and cache-hit bypass

### Phase 22: Maven Central Publishing
**Goal**: Library consumers can declare `musicmeta-core`, `musicmeta-okhttp`, and `musicmeta-android` as Maven Central dependencies — unlocking Dependabot, Renovate, and corporate artifact proxies that block JitPack
**Depends on**: Phase 19, Phase 20, Phase 21
**Requirements**: PUB-01, PUB-02, PUB-03, PUB-04, PUB-05
**Success Criteria** (what must be TRUE):
  1. All three modules publish to Maven Central via Sonatype Central Portal using the vanniktech `gradle-maven-publish-plugin` (v0.36.0) — not OSSRH, which was shut down June 2025
  2. Published artifacts include sources and javadoc jars alongside the main jar
  3. Published POMs include license (Apache 2.0), developer name/email, and SCM URL pointing to the repository
  4. Artifacts are GPG-signed when a key is configured (signing skipped gracefully when key is absent)
  5. JitPack coordinates remain unchanged — existing consumers do not need to update their build files
**Plans**: TBD

Plans:
- [ ] 22-01: Apply vanniktech plugin per-module with `CENTRAL_PORTAL` host, `AndroidSingleVariantLibrary` for android module, POM metadata, GPG signing; add credential placeholders to `gradle.properties`
- [ ] 22-02: Verify publishing dry-run for all three modules; update README with Maven Central coordinates and badge

## Progress

| Phase | Milestone | Plans Complete | Status | Completed |
|-------|-----------|----------------|--------|-----------|
| 1. Bug Fixes | v0.4.0 | 2/2 | Complete | 2026-03-21 |
| 2. Provider Abstraction | v0.4.0 | 3/3 | Complete | 2026-03-21 |
| 3. Public API Cleanup | v0.4.0 | 2/2 | Complete | 2026-03-21 |
| 4. New Types | v0.4.0 | 4/4 | Complete | 2026-03-21 |
| 5. Deepening | v0.4.0 | 4/4 | Complete | 2026-03-21 |
| 6. Tech Debt Cleanup | v0.5.0 | 4/4 | Complete | 2026-03-21 |
| 7. Credits & Personnel | v0.5.0 | 2/2 | Complete | 2026-03-21 |
| 8. Release Editions | v0.5.0 | 2/2 | Complete | 2026-03-21 |
| 9. Artist Timeline | v0.5.0 | 2/2 | Complete | 2026-03-21 |
| 10. Genre Enhancement | v0.5.0 | 3/3 | Complete | 2026-03-21 |
| 11. Provider Coverage | v0.5.0 | 3/3 | Complete | 2026-03-21 |
| 12. Engine Refactoring | v0.6.0 | 2/2 | Complete | 2026-03-22 |
| 13. Similar Artists + Merger | v0.6.0 | 2/2 | Complete | 2026-03-22 |
| 14. Artist Radio | v0.6.0 | 2/2 | Complete | 2026-03-22 |
| 15. Similar Albums | v0.6.0 | 2/2 | Complete | 2026-03-22 |
| 16. Genre Discovery | v0.6.0 | 2/2 | Complete | 2026-03-22 |
| 17. Catalog Filtering Interface | v0.6.0 | 2/2 | Complete | 2026-03-22 |
| 18. Integration and Docs | v0.6.0 | 2/2 | Complete | 2026-03-23 |
| 19. OkHttp Adapter | v0.8.0 | 2/2 | Complete    | 2026-03-24 |
| 20. Stale Cache | v0.8.0 | 2/2 | Complete    | 2026-03-24 |
| 21. Bulk Enrichment | v0.8.0 | 0/1 | Not started | - |
| 22. Maven Central Publishing | v0.8.0 | 0/2 | Not started | - |
