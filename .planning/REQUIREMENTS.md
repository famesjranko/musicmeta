# Requirements: musicmeta

**Defined:** 2026-03-24
**Core Value:** Consumers get comprehensive, accurate music metadata from a single enrich() call without knowing which APIs exist, how they authenticate, or how to correlate identifiers across services.

## v0.8.0 Requirements

Requirements for Production Readiness milestone. Each maps to roadmap phases.

### HTTP (OkHttp Adapter)

- [x] **HTTP-01**: Developer can create OkHttpEnrichmentClient with their existing OkHttpClient instance and pass it to EnrichmentEngine.Builder
- [x] **HTTP-02**: OkHttp adapter implements all 10 HttpClient methods (JSON, array, body, redirect, POST, HttpResult variants)
- [x] **HTTP-03**: OkHttp adapter correctly maps HTTP status codes to HttpResult sealed variants (429 to RateLimited, 4xx to ClientError, 5xx to ServerError)
- [x] **HTTP-04**: OkHttp adapter delegates gzip decompression and retry to OkHttp (no manual Accept-Encoding header, no built-in retry loop)
- [x] **HTTP-05**: musicmeta-okhttp is an optional module — core does not depend on it

### CACHE (Stale Cache)

- [ ] **CACHE-01**: Developer can configure STALE_IF_ERROR cache mode via EnrichmentConfig
- [ ] **CACHE-02**: Expired cache entries are served with isStale=true when provider returns Error or RateLimited
- [ ] **CACHE-03**: Expired cache entries are NOT served for genuine NotFound results
- [ ] **CACHE-04**: Stale results are not re-written to cache with fresh TTL
- [ ] **CACHE-05**: InMemoryEnrichmentCache supports getIncludingExpired() for stale serving
- [ ] **CACHE-06**: RoomEnrichmentCache supports getIncludingExpired() via new DAO query (no schema migration)

### BATCH (Bulk Enrichment)

- [ ] **BATCH-01**: Developer can call enrichBatch() to enrich multiple requests in sequence
- [ ] **BATCH-02**: enrichBatch() returns Flow emitting results per request as they complete
- [ ] **BATCH-03**: Flow cancellation stops processing remaining requests
- [ ] **BATCH-04**: Cache hits in batch return immediately without rate limiter delay

### PUB (Maven Central Publishing)

- [ ] **PUB-01**: All 3 modules (core, okhttp, android) publish to Maven Central via Central Portal
- [ ] **PUB-02**: Published artifacts include sources and javadoc jars
- [ ] **PUB-03**: Published POMs include correct metadata (license, developer, SCM)
- [ ] **PUB-04**: Artifacts are signed with GPG when key is available
- [ ] **PUB-05**: JitPack remains supported as alternative distribution

## Future Requirements

Deferred to v0.9.0+. Tracked but not in current roadmap.

### Catalog Implementations

- **CAT-01**: LocalLibraryCatalog scans local files and matches by title/artist/fingerprint
- **CAT-02**: SpotifyCatalog checks streaming availability via OAuth
- **CAT-03**: YouTubeMusicCatalog checks availability

### User-Scoped Features

- **USER-01**: ListenBrainz collaborative filtering recommendations with user identity
- **USER-02**: Credit-based discovery ("more from this producer/composer")

### Reactive Integration

- **FLOW-01**: Flow-based progressive API emitting partial results as providers complete

## Out of Scope

Explicitly excluded. Documented to prevent scope creep.

| Feature | Reason |
|---------|--------|
| Flow-based progressive API (enrichFlow) | Identity resolution blocks all emission; mergeable types can't emit until all providers finish. Marginal benefit vs complexity. Callers split enrich() calls today. |
| CACHE_FIRST mode (serve cache, refresh background) | Needs background refresh architecture. STALE_IF_ERROR covers the critical offline case. |
| Real pipelining for bulk enrichment | Sequential with rate limiter is 90% of the value. Optimize when proven bottleneck. |
| OkHttp 5.x support | Requires Kotlin 2.2.x stdlib as transitive dependency. Upgrade with future Kotlin bump. |
| Compose state integration | Out of library scope — consumers wrap in ViewModel/StateFlow |
| org.json replacement (Moshi/serialization) | Massive refactor across all 11 providers; working fine for parsing |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| HTTP-01 | Phase 19 | Complete |
| HTTP-02 | Phase 19 | Complete |
| HTTP-03 | Phase 19 | Complete |
| HTTP-04 | Phase 19 | Complete |
| HTTP-05 | Phase 19 | Complete |
| CACHE-01 | Phase 20 | Pending |
| CACHE-02 | Phase 20 | Pending |
| CACHE-03 | Phase 20 | Pending |
| CACHE-04 | Phase 20 | Pending |
| CACHE-05 | Phase 20 | Pending |
| CACHE-06 | Phase 20 | Pending |
| BATCH-01 | Phase 21 | Pending |
| BATCH-02 | Phase 21 | Pending |
| BATCH-03 | Phase 21 | Pending |
| BATCH-04 | Phase 21 | Pending |
| PUB-01 | Phase 22 | Pending |
| PUB-02 | Phase 22 | Pending |
| PUB-03 | Phase 22 | Pending |
| PUB-04 | Phase 22 | Pending |
| PUB-05 | Phase 22 | Pending |

**Coverage:**
- v0.8.0 requirements: 19 total
- Mapped to phases: 19
- Unmapped: 0

---
*Requirements defined: 2026-03-24*
*Last updated: 2026-03-24 after roadmap creation*
