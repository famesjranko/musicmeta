# Architecture Research

**Domain:** Music metadata enrichment library — v0.6.0 Recommendations Engine
**Researched:** 2026-03-23
**Confidence:** HIGH (based on direct codebase analysis)

## Standard Architecture

### System Overview

```
enrich(request, types)
    │
    ├── cache check
    │
    ├── identity resolution (MusicBrainz → MBIDs + extra IDs)
    │
    └── resolveTypes()
         │
         ├── standard types     → ProviderChain.resolve()   [first-wins]
         ├── mergeable types    → ProviderChain.resolveAll() [collect-all + GenreMerger]
         └── composite types    → resolveSubTypes() → Synthesizer [build from parts]
```

```
ProviderRegistry
    ├── chainFor(SIMILAR_ARTISTS)  → [ListenBrainz(50), Deezer(50)*]  * new
    ├── chainFor(SIMILAR_TRACKS)   → [LastFm(100)*, Deezer(50)*]      * new
    ├── chainFor(SIMILAR_ALBUMS)   → [composite: needs sub-types]      * new
    ├── chainFor(GENRE_NEIGHBORS)  → [GenreNeighborProvider(100)*]     * new utility
    └── chainFor(LISTENING_RECS)   → [ListenBrainzCFProvider(100)*]    * new
```

### Component Responsibilities

| Component | Responsibility | v0.6.0 Change |
|-----------|----------------|---------------|
| `DeezerProvider` | Album art, discography, tracks | Add SIMILAR_ARTISTS, SIMILAR_TRACKS via new API methods |
| `DeezerApi` | Raw Deezer HTTP calls | Add `getRelatedArtists(deezerId)` and `getArtistRadio(deezerId)` |
| `ListenBrainzProvider` | Popularity, discography, SIMILAR_ARTISTS | Add LISTENING_RECOMMENDATIONS capability (new sub-provider or new provider) |
| `ListenBrainzApi` | Raw ListenBrainz HTTP calls | Add `getCFRecommendations(username)` |
| `GenreMerger` | Normalize + merge genre tags | No change — used by new GenreNeighborsProvider as input |
| `GenreNeighborsProvider` | Compute genre affinity neighbors | New engine-internal provider backed by GenreMerger scores |
| `SimilarAlbumsSynthesizer` | Synthesize SIMILAR_ALBUMS from sub-types | New, mirrors TimelineSynthesizer |
| `DefaultEnrichmentEngine` | Orchestrate resolution | Add SIMILAR_ALBUMS to COMPOSITE_DEPENDENCIES, GENRE_NEIGHBORS to MERGEABLE_TYPES |

## Integration Decisions Per Module

### 1. Deezer SIMILAR_ARTISTS — Standard Provider Addition

**Integration:** Add `SIMILAR_ARTISTS` capability to existing `DeezerProvider` at priority 50 (fallback behind ListenBrainz at 50; both run when first returns NotFound).

**API endpoint:** `GET /artist/{id}/related` — returns Artist objects. No auth required. Public API.

**Key constraint:** Deezer uses its own artist ID (not MBID). The existing `enrichDiscography` already stores `deezerId` in `resolvedIdentifiers.extra["deezerId"]`. The new `enrichSimilarArtists` in DeezerProvider must handle the case where `deezerId` is absent: fall back to `searchArtist(name)` then call `/artist/{id}/related`. This is the same pattern already used in `enrichDiscography`.

**New `DeezerApi` method:**
```kotlin
suspend fun getRelatedArtists(artistId: Long, limit: Int = 20): List<DeezerRelatedArtist>
// GET /artist/{artistId}/related
```

**New `DeezerModels.kt` type:**
```kotlin
data class DeezerRelatedArtist(val id: Long, val name: String, val pictureUrl: String?)
```

**Mapper:** `DeezerMapper.toSimilarArtists(artists)` returning `EnrichmentData.SimilarArtists`.

**Chain result:** `SIMILAR_ARTISTS` chain becomes [ListenBrainz(50), Deezer(50)] — first-wins. Priority is equal so order depends on registration order. If deterministic ordering is needed, set Deezer to 40.

**ProviderCapability:**
```kotlin
ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, priority = 40)
// NONE identifier requirement — does fuzzy name search then ID lookup
```

---

### 2. Deezer SIMILAR_TRACKS — New Provider Capability on Existing Provider

**Integration:** Add `SIMILAR_TRACKS` capability to existing `DeezerProvider`. No existing providers handle `SIMILAR_TRACKS` (the chain does not exist yet). Deezer becomes the primary.

**API endpoint:** `GET /artist/{id}/radio` — returns up to 25 track objects. No auth required.

**Rationale:** "Radio" in Deezer terminology IS similar tracks seeded by artist. The endpoint is named `/radio` but the result is a flat list of `SimilarTrack`-shaped objects. No new `EnrichmentType` for "radio" is needed — map the results to `EnrichmentData.SimilarTracks`.

**Request type:** `ForArtist` (seed by artist) or `ForTrack` (seed by track — use the track's artist). Since `SIMILAR_TRACKS` is logically a track-context result, both request types should work: extract artist from request, find Deezer artist ID, call `/artist/{id}/radio`.

**New `DeezerApi` method:**
```kotlin
suspend fun getArtistRadio(artistId: Long, limit: Int = 25): List<DeezerRadioTrack>
// GET /artist/{artistId}/radio
```

**New `DeezerModels.kt` type:**
```kotlin
data class DeezerRadioTrack(val id: Long, val title: String, val artistName: String, val durationSec: Int)
```

**Mapper:** `DeezerMapper.toSimilarTracks(tracks)` returning `EnrichmentData.SimilarTracks`.

**ProviderCapability:**
```kotlin
ProviderCapability(EnrichmentType.SIMILAR_TRACKS, priority = 100)
// Priority 100 — Deezer is currently the only provider
```

---

### 3. SIMILAR_ALBUMS — New Composite Type

**Integration:** Follows `ARTIST_TIMELINE` composite pattern exactly. Add to `COMPOSITE_DEPENDENCIES`. Add new `SimilarAlbumsSynthesizer` object.

**New `EnrichmentType`:**
```kotlin
SIMILAR_ALBUMS(30L * 24 * 60 * 60 * 1000),  // 30-day TTL, same as SIMILAR_ARTISTS
```

**New `EnrichmentData` subtype:**
```kotlin
@Serializable
data class SimilarAlbums(val albums: List<SimilarAlbum>) : EnrichmentData()

@Serializable
data class SimilarAlbum(
    val title: String,
    val artist: String,
    val year: String? = null,
    val thumbnailUrl: String? = null,
    val matchReason: String,  // "similar_artist" | "genre_match" | "era_match"
    val confidence: Float,
    val identifiers: EnrichmentIdentifiers = EnrichmentIdentifiers(),
)
```

**Sub-type dependencies:**
```kotlin
EnrichmentType.SIMILAR_ALBUMS to setOf(
    EnrichmentType.SIMILAR_ARTISTS,
    EnrichmentType.GENRE,
    EnrichmentType.ARTIST_DISCOGRAPHY,  // provides era/year data for seed artist
)
```

**Synthesis logic in `SimilarAlbumsSynthesizer`:**
1. Extract `SimilarArtists` — for each similar artist, their discography is not available without additional API calls. Instead, use the similar artist names + scores as album affinity signals.
2. Extract `GenreTag` list from merged GENRE result — use top-N genres as match criteria.
3. Use `ARTIST_DISCOGRAPHY` albums' year range to determine "era" — define era as ±5 years of the seed artist's active period.
4. Output: List of `SimilarAlbum` entries with a `matchReason` field indicating why each was included.

**Critical limitation:** The synthesizer cannot produce actual album titles from similar artists without additional enrichment calls (would require `enrich(ForArtist(similarArtist), ARTIST_DISCOGRAPHY)` for each similar artist). This would be expensive. Instead, `SimilarAlbums` in v0.6.0 should output artist-level recommendations with `matchReason = "similar_artist"` as the primary signal, deferring per-similar-artist discography lookup to a future milestone.

**Engine change in `DefaultEnrichmentEngine`:**
```kotlin
private val COMPOSITE_DEPENDENCIES = mapOf(
    EnrichmentType.ARTIST_TIMELINE to setOf(
        EnrichmentType.ARTIST_DISCOGRAPHY,
        EnrichmentType.BAND_MEMBERS,
    ),
    EnrichmentType.SIMILAR_ALBUMS to setOf(    // NEW
        EnrichmentType.SIMILAR_ARTISTS,
        EnrichmentType.GENRE,
        EnrichmentType.ARTIST_DISCOGRAPHY,
    ),
)
```

Add `ARTIST_TIMELINE -> synthesizeTimeline()`, `SIMILAR_ALBUMS -> synthesizeSimilarAlbums()` to the `when` in `synthesizeComposite()`.

---

### 4. Radio/Mix — No New Type Needed

**Decision:** Radio/Mix maps directly to `SIMILAR_TRACKS`. The Deezer `/artist/{id}/radio` endpoint is the implementation vehicle. No new `EnrichmentType` is warranted — "radio" describes the generation mechanism, not a distinct data shape.

**If callers need to distinguish "editorial similar tracks" from "radio tracks":** add a `source: String` field to `SimilarTrack` data class. This is additive and non-breaking.

---

### 5. Credit-Based Discovery — New Engine Method Required

**Problem:** `EnrichmentRequest` models entity enrichment ("tell me about this album"). Credit-based discovery is a query ("what other works involve this person?"). These are fundamentally different operations.

**The existing model breaks here:** `ForAlbum(title, artist)` and `ForTrack(title, artist)` describe the subject being enriched. A credit-based query has the producer/composer MBID as the query key, not the enrichment subject.

**Decision:** Add a new method to `EnrichmentEngine` interface rather than a new `EnrichmentRequest` subtype.

```kotlin
interface EnrichmentEngine {
    // ... existing methods ...

    /**
     * Discover works associated with a credited person (producer, composer, etc.).
     * Uses the person's MusicBrainz ID to find recordings and release groups
     * they contributed to via MusicBrainz relationship queries.
     *
     * @param personMbid MusicBrainz ID of the person (from Credit.identifiers.musicBrainzId)
     * @param role Optional role filter (e.g., "producer", "composer")
     * @param limit Maximum results to return
     */
    suspend fun discoverByCredit(
        personMbid: String,
        role: String? = null,
        limit: Int = 20,
    ): List<CreditDiscoveryResult>
}
```

**New data type:**
```kotlin
data class CreditDiscoveryResult(
    val type: String,           // "recording" | "release_group"
    val title: String,
    val artist: String,
    val role: String,
    val year: String? = null,
    val identifiers: EnrichmentIdentifiers = EnrichmentIdentifiers(),
)
```

**Implementation:** `DefaultEnrichmentEngine.discoverByCredit()` calls `MusicBrainzApi` directly (not through the provider chain) using the person-relationship endpoint: `GET /ws/2/artist/{mbid}?inc=recording-rels+release-group-rels`.

**No new `EnrichmentType`** needed. No new provider. The result is not cached via the standard TTL cache (cache key model doesn't apply). Consider a short in-memory cache keyed on `personMbid+role`.

**Alternative rejected:** A `ForPerson` `EnrichmentRequest` subtype was considered but rejected because: (a) it leaks the "MusicBrainz person" abstraction into a public API that currently has no notion of "person" as a first-class entity; (b) it would require handling in every provider's `enrich()` method; (c) all existing providers would return `NotFound` for `ForPerson` requests, requiring boilerplate guards everywhere.

---

### 6. Genre Discovery — Utility Function, Not New Provider

**Decision:** Genre discovery (finding "neighboring" genres from confidence scores) is a pure computation over data already in memory. It does not require an API call.

**Implementation: Extend `GenreMerger` or add `GenreGraph` utility object.**

```kotlin
object GenreGraph {
    /**
     * Given a list of GenreTag (from GENRE enrichment result), returns
     * neighboring genres ordered by affinity score.
     * Affinity is computed from co-occurrence patterns encoded in a static
     * adjacency map (genre -> list of related genres with weights).
     */
    fun neighbors(tags: List<GenreTag>, limit: Int = 10): List<GenreTag>
}
```

**No new `EnrichmentType`** is needed if this is exposed as a utility. Genre neighbors are derived from the already-cached `GENRE` result.

**Alternative considered:** A `GENRE_NEIGHBORS` `EnrichmentType` backed by a `GenreNeighborsProvider`. Rejected because: genre neighbor relationships are static graph data, not API-sourced; wrapping it in the provider/chain machinery adds complexity with no benefit. A utility object is the right abstraction here, consistent with how `GenreMerger` is structured.

**Where to expose it:** Add to `EnrichmentEngine` as a synchronous utility method, or expose `GenreGraph` as a public top-level utility that consumers call directly after getting a `GENRE` result.

```kotlin
// Option A: Engine method
fun genreNeighbors(tags: List<GenreTag>, limit: Int = 10): List<GenreTag>

// Option B: Public utility (preferred — consistent with GenreMerger pattern)
// Consumers call: GenreGraph.neighbors(genreResult.genreTags, limit = 10)
```

**Option B is preferred** because it keeps the engine interface focused on enrichment, matches the `GenreMerger` precedent, and is stateless/testable in isolation.

---

### 7. ListenBrainz Collaborative Filtering — Auth Model Analysis

**Finding (HIGH confidence — verified against official docs):** The ListenBrainz CF endpoint `GET /1/cf/recommendation/user/{username}/recording` does NOT require a user auth token. It is a public read endpoint. The `username` is the ListenBrainz username string (e.g., "alice"), not a token.

**This resolves the auth concern.** The current auth model (API keys on providers at construction time) does not need to change. There are no user tokens to handle.

**The actual problem:** `EnrichmentRequest` has no `username` field. The CF endpoint is not "enrich this artist/album/track" — it is "fetch recommendations for this user." It has the same shape problem as credit-based discovery.

**Decision:** Add a second new engine method.

```kotlin
interface EnrichmentEngine {
    /**
     * Fetch listening-history-based track recommendations for a ListenBrainz user.
     * Uses ListenBrainz collaborative filtering (CF) endpoint.
     * Does not require authentication — ListenBrainz CF is a public endpoint.
     *
     * @param listenBrainzUsername The ListenBrainz username (public profile username)
     * @param limit Number of recommendations to return (default 25, max 100)
     */
    suspend fun listeningRecommendations(
        listenBrainzUsername: String,
        limit: Int = 25,
    ): List<ListeningRecommendation>
}
```

**New data type:**
```kotlin
data class ListeningRecommendation(
    val score: Float,
    val identifiers: EnrichmentIdentifiers,  // recording_mbid in musicBrainzId field
)
```

**Implementation:** `DefaultEnrichmentEngine.listeningRecommendations()` calls `ListenBrainzApi.getCFRecommendations(username, limit)` directly.

**New `ListenBrainzApi` method:**
```kotlin
suspend fun getCFRecommendations(
    username: String,
    count: Int = 25,
): List<ListenBrainzCFRecommendation>
// GET /1/cf/recommendation/user/{username}/recording?count={count}
// Returns 204 if no recommendations generated yet, 404 if user not found
```

**New `ListenBrainzModels.kt` type:**
```kotlin
data class ListenBrainzCFRecommendation(
    val recordingMbid: String,
    val score: Float,
)
```

**No new `EnrichmentProvider`** needed. No new `EnrichmentType`. Results are not part of the standard `enrich()` pipeline.

---

### 8. New EnrichmentType Values

| Type | TTL | Chain Type | Provider(s) |
|------|-----|-----------|-------------|
| `SIMILAR_ALBUMS` | 30 days | Composite | Synthesized from SIMILAR_ARTISTS + GENRE + ARTIST_DISCOGRAPHY |

No other new `EnrichmentType` values are needed. `SIMILAR_TRACKS` and `SIMILAR_ARTISTS` already exist.

### New EnrichmentData Subtypes

| Subtype | Used By | Fields |
|---------|---------|--------|
| `SimilarAlbums(albums: List<SimilarAlbum>)` | `SIMILAR_ALBUMS` type | List of albums with matchReason |
| `SimilarAlbum` | `SimilarAlbums` | title, artist, year, thumbnailUrl, matchReason, confidence, identifiers |

Top-level (non-sealed) data classes for new engine methods:
- `CreditDiscoveryResult` — for `discoverByCredit()`
- `ListeningRecommendation` — for `listeningRecommendations()`

These are NOT `EnrichmentData` subtypes because they are not returned via the standard `enrich()` call.

---

## Component Boundaries

```
EnrichmentEngine (public interface)
    enrich(request, types) — standard pipeline, unchanged
    search(request, limit) — unchanged
    discoverByCredit(personMbid, role, limit) — NEW: credit-based query
    listeningRecommendations(username, limit) — NEW: user-scoped CF

DefaultEnrichmentEngine
    COMPOSITE_DEPENDENCIES — add SIMILAR_ALBUMS entry
    synthesizeComposite() — add SIMILAR_ALBUMS case
    synthesizeSimilarAlbums() — NEW private method
    discoverByCredit() — NEW: delegates to MusicBrainzApi directly
    listeningRecommendations() — NEW: delegates to ListenBrainzApi directly

DeezerProvider (extended)
    capabilities — add SIMILAR_ARTISTS(40), SIMILAR_TRACKS(100)
    enrich() — add cases for both types
    enrichSimilarArtists() — NEW private method
    enrichSimilarTracks() — NEW private method

DeezerApi (extended)
    getRelatedArtists(artistId, limit) — NEW
    getArtistRadio(artistId, limit) — NEW

GenreGraph (NEW utility object)
    neighbors(tags, limit) — pure function, no API calls

SimilarAlbumsSynthesizer (NEW object)
    synthesize(similarArtists, genre, discography) — mirrors TimelineSynthesizer
```

## Data Flow

### Standard SIMILAR_ARTISTS Resolution

```
enrich(ForArtist("Radiohead"), {SIMILAR_ARTISTS})
    → identity resolution → MBID stored in request
    → resolveTypes()
    → ProviderChain(SIMILAR_ARTISTS).resolve(request)
        → ListenBrainz(50): getSimilarArtists(mbid) → Success or NotFound
        → Deezer(40): searchArtist(name) → getRelatedArtists(deezerId) → Success
    → first Success returned, chain short-circuits
```

### SIMILAR_ALBUMS Composite Resolution

```
enrich(ForArtist("Radiohead"), {SIMILAR_ALBUMS})
    → identity resolution
    → resolveTypes()
        → compositeTypes = [SIMILAR_ALBUMS]
        → compositeSubTypes = [SIMILAR_ARTISTS, GENRE, ARTIST_DISCOGRAPHY]
        → resolve sub-types concurrently (GENRE via resolveAll + merge)
        → synthesizeComposite(SIMILAR_ALBUMS, resolved)
            → SimilarAlbumsSynthesizer.synthesize(
                similarArtists = resolved[SIMILAR_ARTISTS],
                genre = resolved[GENRE],
                discography = resolved[ARTIST_DISCOGRAPHY]
              )
    → SIMILAR_ALBUMS result (sub-types excluded from output)
```

### Credit Discovery (bypasses provider chain)

```
// Caller has a Credit from a previous CREDITS enrichment:
// Credit(name="Rick Rubin", role="producer", identifiers=EnrichmentIdentifiers(musicBrainzId="abc123"))

engine.discoverByCredit(personMbid = "abc123", role = "producer")
    → MusicBrainzApi.getPersonRelationships("abc123", "producer")
    → List<CreditDiscoveryResult>
    // NOT cached via standard EnrichmentCache
```

### Listening Recommendations (bypasses provider chain)

```
engine.listeningRecommendations(listenBrainzUsername = "alice", limit = 25)
    → ListenBrainzApi.getCFRecommendations("alice", 25)
    → List<ListeningRecommendation> (recording MBIDs + scores)
    // Caller can then enrich each recording: enrich(ForTrack(..., mbid=rec.mbid), types)
```

## Architectural Patterns

### Pattern 1: Standard Provider Addition (Deezer SIMILAR_ARTISTS/SIMILAR_TRACKS)

**What:** Add new `ProviderCapability` entries to an existing provider's `capabilities` list. Add the corresponding API methods and mapper functions. The ProviderRegistry automatically builds the chain on startup.

**When to use:** When an existing API (Deezer) supports a new data type you want to expose. Zero changes to the engine.

**Trade-offs:** Simple, low-risk. Limited to entity-scoped queries (album/artist/track).

### Pattern 2: Composite Type Synthesis (SIMILAR_ALBUMS)

**What:** Declare sub-type dependencies in `COMPOSITE_DEPENDENCIES`. The engine resolves sub-types first, then calls a synthesizer. The synthesizer is a pure function operating on `EnrichmentResult` objects.

**When to use:** When a new type is derived from combining multiple existing types. No API calls in the synthesizer itself.

**Trade-offs:** Clean separation of concerns. Synthesis happens after all sub-types are resolved in parallel. Cannot perform additional API calls during synthesis — if more data is needed, it must be declared as a sub-type dependency.

**Example:** `SIMILAR_ALBUMS` depends on `SIMILAR_ARTISTS + GENRE + ARTIST_DISCOGRAPHY`. All three are resolved concurrently before `SimilarAlbumsSynthesizer.synthesize()` is called.

### Pattern 3: Engine-Level Utility (GenreGraph)

**What:** A stateless `object` with pure functions. No provider registration. No chain. Called directly by consumers after obtaining enrichment data.

**When to use:** When the computation is pure — no API calls, operates on data already retrieved, benefits from being usable without engine involvement.

**Trade-offs:** Maximum flexibility. Not cached. Not part of the standard pipeline. Consistent with `GenreMerger` pattern already established.

### Pattern 4: New Engine Method (discoverByCredit, listeningRecommendations)

**What:** Add a new method directly to `EnrichmentEngine` interface and implement in `DefaultEnrichmentEngine`. Calls provider APIs directly without routing through ProviderChain.

**When to use:** When the query has a fundamentally different shape than "enrich this entity" — e.g., user-scoped queries, person-MBID queries, queries that return lists of candidates rather than a single enrichment result.

**Trade-offs:** Bypasses the provider chain abstraction (no circuit breaker, no priority ordering, no fallback). Appropriate for cases where only one provider supports the operation. Cannot be extended via `addProvider()`. If multiple providers are needed later, the method can be refactored to use a chain.

**Do not** add new `EnrichmentRequest` subtypes to handle these cases — that would require every provider's `enrich()` to handle the new request type, causing widespread `NotFound` boilerplate.

## Anti-Patterns

### Anti-Pattern 1: New ForPerson/ForUser EnrichmentRequest Subtypes

**What people do:** Add `ForPerson(mbid)` or `ForUser(username)` as new `EnrichmentRequest` subtypes to handle credit discovery and CF recommendations.

**Why it's wrong:** Every provider's `enrich()` dispatch would need a new `else ->` or explicit `is EnrichmentRequest.ForPerson ->` guard. All 11 existing providers return `NotFound` for these types. The `EnrichmentRequest` model represents "what entity am I enriching?" — persons and users are not music entities in this library's scope.

**Do this instead:** Add dedicated methods to `EnrichmentEngine` (`discoverByCredit()`, `listeningRecommendations()`).

### Anti-Pattern 2: Making Radio/Mix a New EnrichmentType

**What people do:** Add `RADIO_MIX` or `DEEZER_RADIO` as a new `EnrichmentType` to distinguish radio tracks from similar tracks.

**Why it's wrong:** The data shape is identical — a list of tracks with match scores. The generation mechanism (Deezer radio endpoint) is an implementation detail, not a type boundary. Adding a new type multiplies the number of types consumers must know about without adding semantic value.

**Do this instead:** Route Deezer `/artist/{id}/radio` results to `SIMILAR_TRACKS`. If source provenance matters, add `source: String` to `SimilarTrack`.

### Anti-Pattern 3: Synthesizer with Additional API Calls

**What people do:** Inside `SimilarAlbumsSynthesizer.synthesize()`, call `enrich(ForArtist(similarArtist), ARTIST_DISCOGRAPHY)` for each similar artist to get their album lists.

**Why it's wrong:** Synthesizers are called synchronously after parallel sub-type resolution. Performing API calls inside a synthesizer breaks the fan-out model, adds uncounted HTTP calls, and creates hard-to-trace latency.

**Do this instead:** If similar-artist discographies are needed, declare `ARTIST_DISCOGRAPHY` per similar artist as a sub-type dependency at the engine level. For v0.6.0, scope `SIMILAR_ALBUMS` to artist-level signals rather than actual album lists.

### Anti-Pattern 4: New EnrichmentType for GenreNeighbors

**What people do:** Add `GENRE_NEIGHBORS` to the enum, back it with a `GenreNeighborsProvider`, and route it through the provider chain.

**Why it's wrong:** Genre neighbor relationships are static graph data encoded in the library. Wrapping them in the provider/chain machinery (circuit breaker, rate limiter, identifier requirements) adds complexity with zero benefit. There is no external API to call.

**Do this instead:** A `GenreGraph` utility object, consistent with `GenreMerger`. Consumers call it directly on the `GenreTag` list from a `GENRE` result.

## Build Order (Dependencies Drive Sequencing)

| Step | Module | Dependencies | Rationale |
|------|--------|-------------|-----------|
| 1 | Deezer SIMILAR_ARTISTS | None (uses existing `deezerId` storage pattern) | Validates the "add to existing provider" approach before building composite |
| 2 | Deezer SIMILAR_TRACKS | Deezer provider API extension from Step 1 | Same DeezerApi extension pattern; shares `getArtistIdByName()` logic |
| 3 | `SIMILAR_ALBUMS` composite | Steps 1+2 (SIMILAR_ARTISTS must produce results), GENRE already exists, ARTIST_DISCOGRAPHY already exists | Needs real SIMILAR_ARTISTS data to validate synthesizer |
| 4 | `GenreGraph` utility | GENRE enrichment (existing) | Pure computation; no external dependencies; self-contained |
| 5 | `discoverByCredit()` engine method | CREDITS type (existing from v0.5.0) | Independent of other new modules; requires MusicBrainz person-relationship query which may need new MusicBrainzApi method |
| 6 | `listeningRecommendations()` engine method | ListenBrainzApi extension | Independent; needs new API method but no provider chain changes |

**Steps 1-2 are safe to build together** (same DeezerApi extension, same mapper pattern).
**Step 3 blocks on Steps 1-2** (synthesizer needs real SIMILAR_ARTISTS data to be testable).
**Steps 4-6 are independent** of each other and of Steps 1-3.

## Integration Points

### External Services

| Service | Endpoint | Auth | Notes |
|---------|----------|------|-------|
| Deezer | `GET /artist/{id}/related` | None | Returns Artist objects; no auth required |
| Deezer | `GET /artist/{id}/radio` | None | Returns up to 25 Track objects; no auth required |
| ListenBrainz | `GET /1/cf/recommendation/user/{username}/recording` | None (public read) | Returns 204 if no recs generated; 404 if user not found; marked experimental in docs |
| MusicBrainz | `GET /ws/2/artist/{mbid}?inc=recording-rels` | None | Person relationship query for credit discovery; subject to 1 req/sec rate limit |

### Internal Boundaries

| Boundary | Communication | Notes |
|----------|---------------|-------|
| `DefaultEnrichmentEngine` ↔ `SimilarAlbumsSynthesizer` | Direct object call (pure function) | Same as TimelineSynthesizer — no interface needed |
| `DefaultEnrichmentEngine` ↔ `MusicBrainzApi` | Direct call (bypass provider chain) | Engine holds `httpClient`; may need direct `MusicBrainzApi` instance for `discoverByCredit()` |
| `DefaultEnrichmentEngine` ↔ `ListenBrainzApi` | Direct call (bypass provider chain) | Engine holds `httpClient`; may need direct `ListenBrainzApi` instance for `listeningRecommendations()` |
| Consumer ↔ `GenreGraph` | Static utility call | Consumer calls after receiving GENRE result — no engine involvement |

**Note on direct API access in DefaultEnrichmentEngine:** Currently `DefaultEnrichmentEngine` holds an `httpClient` and a `ProviderRegistry` but no direct provider API instances. For `discoverByCredit()` and `listeningRecommendations()`, either (a) construct `MusicBrainzApi` and `ListenBrainzApi` instances inside `DefaultEnrichmentEngine` using the shared `httpClient`, or (b) expose the APIs via the relevant providers. Option (a) is simpler; option (b) avoids duplicating rate limiter configuration. Given the rate limiter on MusicBrainz is critical, prefer option (b): the Builder injects pre-configured API instances alongside providers.

## Sources

- Codebase: `DefaultEnrichmentEngine.kt`, `DeezerProvider.kt`, `DeezerApi.kt`, `ListenBrainzApi.kt`, `ProviderChain.kt`, `TimelineSynthesizer.kt`, `GenreMerger.kt` — direct analysis (HIGH confidence)
- [ListenBrainz Recommendation API](https://listenbrainz.readthedocs.io/en/latest/users/api/recommendation.html) — CF endpoint does not require auth (HIGH confidence)
- [Deezer Artist API — antoineraulin/deezer-api wiki](https://github.com/antoineraulin/deezer-api/wiki/artist) — `/artist/{id}/related` and `/artist/{id}/radio` confirmed (MEDIUM confidence — third-party wrapper, not official docs; official Deezer developer portal requires login)

---
*Architecture research for: v0.6.0 Recommendations Engine — musicmeta library*
*Researched: 2026-03-23*
