# v0.6.0 — Recommendations Engine

## Context

v0.5.0 completed the "app-ready" metadata story (28 enrichment types, 11 providers, ~57% utilization). The foundation for recommendations already exists: SIMILAR_ARTISTS (2 providers), SIMILAR_TRACKS (Last.fm only), CREDITS with roleCategory, GenreMerger with confidence scores, and the composite type pattern (ARTIST_TIMELINE). v0.6.0 builds discovery features on top of this foundation.

**Problem:** Recommendations are currently shallow — SIMILAR_ARTISTS short-circuits on the first provider (Last.fm), SIMILAR_TRACKS has only one source, and there's no radio/playlist, genre-based, or album-level discovery.

**Outcome:** A recommendation engine with merged multi-provider similar artists, artist radio playlists, synthesized similar albums, and genre-based discovery. The enrichment type count goes from 28 → 31.

---

## Scope Decisions

### In scope (5 modules)

| # | Module | Pattern | New Types | Providers |
|---|--------|---------|-----------|-----------|
| 1 | **Similar Artists enhancement** | Mergeable (like GENRE) | — | +Deezer `/artist/{id}/related` |
| 2 | **Artist Radio** | Standard chain | ARTIST_RADIO | Deezer `/artist/{id}/radio` |
| 3 | **Similar Albums** | Standard provider | SIMILAR_ALBUMS | SimilarAlbumsProvider (uses Deezer APIs internally) |
| 4 | **Genre Discovery** | Composite | GENRE_DISCOVERY | GenreAffinityMatcher (static taxonomy) |
| 5 | **Engine refactoring** | — | — | Extract merger/composite interfaces from engine |

### Deferred

| Module | Reason | Target |
|--------|--------|--------|
| **Credit-Based Discovery** | Cross-entity problem — finding other work by a credited person requires issuing new enrichment requests from within the engine. Needs multi-hop enrichment or a query layer. | v0.7.0 (alongside Developer Experience layer where `trackProfile()` can naturally include "more from this producer") |
| **Listening-Based (ListenBrainz CF)** | Requires user auth token (`/cf/recommendation/user/{user}/recording`). Engine has zero concept of user identity. | v0.8.0 (alongside Catalog Awareness, which also needs user-scoped state) |

---

## Architectural Decisions

### AD-1: SIMILAR_ARTISTS becomes mergeable

With 3 providers (Last.fm tag-based, ListenBrainz collaborative filtering, Deezer internal model), short-circuiting wastes data. Each algorithm surfaces different artists. Merging gives richer, more diverse recommendations.

- Add `SIMILAR_ARTISTS` to `MERGEABLE_TYPES` in `DefaultEnrichmentEngine`
- Create `SimilarArtistMerger` (analogous to `GenreMerger`)
- Add `sources: List<String>` field to `SimilarArtist` data class (matches `GenreTag` pattern)
- Merger deduplicates by normalized artist name, averages match scores, merges identifiers (prefer MBID), tracks source providers

### AD-2: ARTIST_RADIO is a separate type from SIMILAR_TRACKS

SIMILAR_TRACKS is track-seeded ("similar to THIS track"). Deezer radio is artist-seeded ("playlist inspired by THIS artist"). Different semantics, different input, no matchScore on radio tracks.

- New `ARTIST_RADIO` type with `EnrichmentData.RadioPlaylist(tracks: List<RadioTrack>)`
- `RadioTrack(title, artist, album?, durationMs?, identifiers)` — no matchScore
- ForArtist requests only. 7-day TTL (radio is dynamic).

### AD-3: SIMILAR_ALBUMS as a standalone provider (not composite)

True similar-album matching needs album data from multiple artists — composite types can only use already-resolved sub-types from the same request, which doesn't include other artists' discographies.

**Approach:** `SimilarAlbumsProvider` is a regular provider that internally:
1. Searches for seed artist on Deezer
2. Gets related artists via `/artist/{id}/related` (top 5)
3. Gets top albums for each related artist via `/artist/{id}/albums` (top 3 each)
4. Scores by era proximity to seed album's year
5. Returns ranked list of albums by similar artists

This follows the existing pattern where providers make multiple API calls (e.g., `DeezerProvider.enrichDiscography` calls `searchArtist` + `getArtistAlbums`). Cost: ~6 API calls × 100ms = ~600ms, but runs concurrently with other types and result is cached 30 days.

### AD-4: Genre Discovery uses static taxonomy

Dynamic genre relationship data (Wikidata claims, MusicBrainz tag trees) would require new API calls and complex parsing. A curated static taxonomy of ~60-80 genre relationships is pragmatic for v0.6.0 and can be expanded later.

### AD-5: Engine refactoring is mandatory

`DefaultEnrichmentEngine.kt` is at 351 lines (over 300-line max). Adding SIMILAR_ARTISTS merging and a new composite type requires extracting:
- `ResultMerger` interface + dispatch (GenreMerger, SimilarArtistMerger)
- `CompositeSynthesizer` interface + dispatch (TimelineSynthesizer, GenreAffinityMatcher)

This brings the engine back under 250 lines.

---

## Phasing

### Phase 1: Engine Refactoring

Extract merger and composite abstractions from `DefaultEnrichmentEngine` to bring it under 300 lines and prepare for new mergeable/composite types.

**Files modified:**
- `engine/DefaultEnrichmentEngine.kt` — extract merge dispatch + composite dispatch, slim to ~220 lines
- `engine/GenreMerger.kt` — implement `ResultMerger` interface

**Files created:**
- `engine/ResultMerger.kt` — interface + merger registry
- `engine/CompositeSynthesizer.kt` — interface + synthesizer registry

**Tests:**
- Existing engine tests must still pass (no behavior change)

### Phase 2: Deezer Similar Artists + SimilarArtistMerger

Add Deezer as 3rd SIMILAR_ARTISTS provider and make the type mergeable.

**Files modified:**
- `provider/deezer/DeezerApi.kt` — add `getRelatedArtists(artistId: Long)`
- `provider/deezer/DeezerModels.kt` — add `DeezerRelatedArtist(id, name)`
- `provider/deezer/DeezerMapper.kt` — add `toSimilarArtists()`
- `provider/deezer/DeezerProvider.kt` — add SIMILAR_ARTISTS capability (priority 30), add `enrichSimilarArtists()`
- `EnrichmentData.kt` — add `sources: List<String> = emptyList()` to `SimilarArtist`
- `engine/DefaultEnrichmentEngine.kt` — add SIMILAR_ARTISTS to `MERGEABLE_TYPES`

**Files created:**
- `engine/SimilarArtistMerger.kt` — normalize names, dedup, average scores, merge identifiers, track sources

**Deezer `/artist/{id}/related` response shape:**
```json
{"data": [{"id": 123, "name": "Muse", "picture_small": "...", ...}]}
```

**Tests:**
- `SimilarArtistMergerTest.kt` — dedup, score averaging, identifier merging, empty inputs
- `DeezerProviderTest.kt` — similar artists success/empty/error cases
- Engine integration test — SIMILAR_ARTISTS resolves from all 3 providers and merges

### Phase 3: Artist Radio

New `ARTIST_RADIO` enrichment type backed by Deezer `/artist/{id}/radio`.

**Files modified:**
- `EnrichmentType.kt` — add `ARTIST_RADIO(7 days)`
- `EnrichmentData.kt` — add `RadioPlaylist` and `RadioTrack` data classes
- `provider/deezer/DeezerApi.kt` — add `getArtistRadio(artistId: Long, limit: Int = 25)`
- `provider/deezer/DeezerModels.kt` — add `DeezerRadioTrack` (reuse `DeezerTrack` fields + artist name + album title)
- `provider/deezer/DeezerMapper.kt` — add `toRadioPlaylist()`
- `provider/deezer/DeezerProvider.kt` — add ARTIST_RADIO capability (priority 100), add `enrichArtistRadio()`

**Deezer `/artist/{id}/radio` response shape:**
```json
{"data": [{"id": 123, "title": "Creep", "artist": {"name": "Radiohead"}, "album": {"title": "Pablo Honey"}, "duration": 238}]}
```

**Tests:**
- `DeezerProviderTest.kt` — radio success/empty/non-ForArtist returns NotFound
- Serialization round-trip for RadioPlaylist

### Phase 4: Similar Albums

New `SIMILAR_ALBUMS` type with a dedicated `SimilarAlbumsProvider` that makes multiple Deezer API calls internally.

**Files modified:**
- `EnrichmentType.kt` — add `SIMILAR_ALBUMS(30 days)`
- `EnrichmentData.kt` — add `SimilarAlbums` and `SimilarAlbum` data classes

**Files created:**
- `provider/deezer/SimilarAlbumsProvider.kt` — ForAlbum provider (priority 100). Uses DeezerApi to:
  1. `searchArtist(request.artist)` → get Deezer artist ID
  2. `getRelatedArtists(artistId)` → top 5 similar artists (reuses Phase 2 endpoint)
  3. For each: `getArtistAlbums(relatedArtistId, limit=3)` → their top albums
  4. Score albums by year proximity to seed album (if year provided in request)
  5. Return ranked `SimilarAlbums` list

**Data model:**
```kotlin
data class SimilarAlbum(
    val title: String,
    val artist: String,
    val year: String? = null,
    val artistMatchScore: Float,  // from parent similar-artist ranking
    val thumbnailUrl: String? = null,
    val identifiers: EnrichmentIdentifiers = EnrichmentIdentifiers(),
)
```

**Note:** This provider is registered separately from `DeezerProvider` (not inside it) because it has its own capability for a different entity type (ForAlbum). It receives the same `DeezerApi` instance via the engine builder.

**Tests:**
- `SimilarAlbumsProviderTest.kt` — full flow, empty related artists, non-ForAlbum returns NotFound, scoring logic

### Phase 5: Genre Discovery

New `GENRE_DISCOVERY` composite type that finds genre neighbors using a static taxonomy.

**Files modified:**
- `EnrichmentType.kt` — add `GENRE_DISCOVERY(30 days)`
- `EnrichmentData.kt` — add `GenreDiscovery` and `GenreAffinity` data classes
- `engine/DefaultEnrichmentEngine.kt` — add GENRE_DISCOVERY to `COMPOSITE_DEPENDENCIES` → `{GENRE}`

**Files created:**
- `engine/GenreAffinityMatcher.kt` — static genre taxonomy + affinity computation
  - Taxonomy: ~60-80 relationships (parent/child/sibling)
  - Input: `List<GenreTag>` from GENRE resolution
  - Scoring: `affinity = inputConfidence × relationshipWeight` (sibling=0.9, child=0.8, parent=0.7)
  - Output: `GenreDiscovery(relatedGenres: List<GenreAffinity>)` sorted by affinity

**Data model:**
```kotlin
data class GenreDiscovery(val relatedGenres: List<GenreAffinity>) : EnrichmentData()

data class GenreAffinity(
    val name: String,
    val affinity: Float,
    val relationship: String,  // "parent", "child", "sibling"
    val sourceGenres: List<String>,  // which input genres produced this affinity
)
```

**Tests:**
- `GenreAffinityMatcherTest.kt` — taxonomy lookups, scoring, unknown genres, multiple input genres combine

### Phase 6: Integration, Docs & E2E

- Wire `SimilarAlbumsProvider` into `EnrichmentEngine.Builder.withDefaultProviders()`
- Update `EnrichmentShowcaseTest.kt` with v0.6.0 feature spotlight
- Update `README.md` — new types table, recommendation examples
- Update `ROADMAP.md` — mark v0.6.0 complete, update coverage table
- Update `CHANGELOG.md` — v0.6.0 entry
- Update `STORIES.md` — architectural decisions (mergeability, similar albums provider pattern)

---

## New Data Models (added to EnrichmentData.kt)

```kotlin
// Modification: add sources to existing SimilarArtist
data class SimilarArtist(
    val name: String,
    val identifiers: EnrichmentIdentifiers = EnrichmentIdentifiers(),
    val matchScore: Float,
    val sources: List<String> = emptyList(),  // NEW
)

// New sealed subclasses
data class RadioPlaylist(val tracks: List<RadioTrack>) : EnrichmentData()
data class SimilarAlbums(val albums: List<SimilarAlbum>) : EnrichmentData()
data class GenreDiscovery(val relatedGenres: List<GenreAffinity>) : EnrichmentData()

// New supporting classes
data class RadioTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val identifiers: EnrichmentIdentifiers = EnrichmentIdentifiers(),
)

data class SimilarAlbum(
    val title: String,
    val artist: String,
    val year: String? = null,
    val artistMatchScore: Float,
    val thumbnailUrl: String? = null,
    val identifiers: EnrichmentIdentifiers = EnrichmentIdentifiers(),
)

data class GenreAffinity(
    val name: String,
    val affinity: Float,
    val relationship: String,
    val sourceGenres: List<String> = emptyList(),
)
```

## New EnrichmentTypes

```kotlin
// In EnrichmentType.kt
ARTIST_RADIO(7L * 24 * 60 * 60 * 1000),       // 7 days
SIMILAR_ALBUMS(30L * 24 * 60 * 60 * 1000),     // 30 days
GENRE_DISCOVERY(30L * 24 * 60 * 60 * 1000),    // 30 days
```

## New Files Summary

| File | ~Lines | Purpose |
|------|--------|---------|
| `engine/ResultMerger.kt` | ~25 | Interface for type-specific result merging + registry |
| `engine/CompositeSynthesizer.kt` | ~25 | Interface for composite type synthesis + registry |
| `engine/SimilarArtistMerger.kt` | ~70 | Merge similar artists from 3 providers |
| `engine/GenreAffinityMatcher.kt` | ~130 | Static genre taxonomy + affinity computation |
| `provider/deezer/SimilarAlbumsProvider.kt` | ~100 | Multi-call Deezer provider for album recommendations |
| + test files | ~400 | Unit tests for all new components |

## Critical Files to Modify

| File | Current Lines | Changes |
|------|--------------|---------|
| `engine/DefaultEnrichmentEngine.kt` | 351 | Extract merger/composite dispatch → ~220 lines |
| `provider/deezer/DeezerApi.kt` | 117 | +2 endpoints (related artists, radio) → ~170 lines |
| `provider/deezer/DeezerProvider.kt` | 168 | +2 capabilities, +2 enrich methods → ~220 lines |
| `provider/deezer/DeezerModels.kt` | — | +2 data classes |
| `provider/deezer/DeezerMapper.kt` | — | +2 mapping functions |
| `EnrichmentData.kt` | 190 | +3 sealed subclasses, +3 supporting classes, +1 field → ~240 lines |
| `EnrichmentType.kt` | 53 | +3 enum values → ~60 lines |
| `engine/GenreMerger.kt` | 67 | Implement ResultMerger interface → ~72 lines |

## Dependency Graph

```
Phase 1 (Engine Refactoring) ──┬──→ Phase 2 (Similar Artists + Merger) ──→ Phase 4 (Similar Albums)
                               │                                             (reuses Deezer related artists endpoint)
                               ├──→ Phase 3 (Artist Radio)
                               │     (independent, can parallel with Phase 2)
                               │
                               └──→ Phase 5 (Genre Discovery)
                                     (uses CompositeSynthesizer interface from Phase 1)

All phases ──→ Phase 6 (Integration & Docs)
```

## Exploration Findings & Warnings

Key discoveries from codebase exploration that informed this plan or should inform implementation.

### Blockers & Constraints Discovered

1. **`mergeGenreResults()` is hardcoded to GENRE** — The current merge path in `DefaultEnrichmentEngine.kt:215-243` assumes the merged data is `EnrichmentData.Metadata` with `genreTags`. It casts to `Metadata`, reads `.genreTags`, calls `GenreMerger.merge()`, and wraps the result back in `Metadata`. Making SIMILAR_ARTISTS mergeable requires **generalizing this entire path** — the merge dispatch must route to different mergers based on type. This is why the engine refactoring (Phase 1) must come first.

2. **Composite types can only consume sub-types from the same request** — `synthesizeComposite()` receives `resolved: Map<EnrichmentType, EnrichmentResult>` which only contains types resolved in the current `enrich()` call. A composite cannot trigger new enrichment requests for different entities (e.g., fetching discographies for similar artists). This is the fundamental reason SIMILAR_ALBUMS cannot be a composite and must be a standalone provider.

3. **ListenBrainz CF endpoints require user auth** — `/cf/recommendation/user/{user}/recording` takes a username path parameter and returns personalized recommendations. The engine has zero concept of user identity — `EnrichmentRequest` carries entity info (artist/album/track) but nothing about who is requesting. Adding user context would require changes to `EnrichmentRequest`, `EnrichmentConfig`, and cache key generation. This is architecturally significant and correctly deferred.

4. **`DefaultEnrichmentEngine.kt` is at 351 lines** — Already over the 300-line project max. Any additions without refactoring would violate code style rules. The refactoring is not a nice-to-have; it's a prerequisite.

### Existing Bugs That May Affect v0.6.0

The v0.5.0 edge analysis (`docs/v0.5.0-edge-analysis.md`) found 3 unfixed bugs:

1. **BUG-1 (HIGH): Genre merge never activates for ForArtist requests** — `mergeGenreResults()` falls back to single-provider result because Last.fm `getArtistTopTags()` returns empty and MusicBrainz results have `genreTags = null`. **Impact on v0.6.0:** GENRE_DISCOVERY depends on GENRE as a composite sub-type. If GENRE only returns single-provider results for ForArtist, the genre tags will have lower confidence scores (no multi-provider accumulation), which weakens affinity scoring. **Recommendation:** Fix BUG-1 before or during Phase 5.

2. **BUG-2 (MEDIUM): Timeline member dates are malformed** — Foo Fighters shows "03" instead of "2003". Not directly related to v0.6.0 but indicates date parsing fragility in the engine.

3. **BUG-3 (LOW): ARTIST_TIMELINE returns 0 events for ForAlbum** — Composite types don't gracefully handle cross-entity-scope sub-types. Relevant because GENRE_DISCOVERY (ForArtist composite depending on GENRE) needs to work for ForAlbum too — and it may hit the same issue if GENRE resolution behaves differently per request type.

### Architecture Patterns to Follow

1. **`GenreMerger` is a pure function** — `GenreMerger.merge(tags: List<GenreTag>): List<GenreTag>` takes data in, returns data out. No side effects, no API calls, no state. `SimilarArtistMerger` should follow this exact pattern: `merge(artists: List<SimilarArtist>): List<SimilarArtist>`.

2. **`resolveAll()` vs `resolve()` in ProviderChain** — `resolve()` short-circuits on first Success. `resolveAll()` collects ALL Success results, skipping NotFound/RateLimited. The engine uses `resolveAll()` for `MERGEABLE_TYPES` and `resolve()` for everything else. Adding SIMILAR_ARTISTS to `MERGEABLE_TYPES` automatically switches it to `resolveAll()` — no ProviderChain changes needed.

3. **Multi-call provider pattern already exists** — `DeezerProvider.enrichDiscography()` does `searchArtist()` then `getArtistAlbums()` (2 API calls in one enrich). `SimilarAlbumsProvider` extends this to ~6 calls, which is more but not architecturally different.

4. **`resolvedIdentifiers` propagation** — Providers can return `resolvedIdentifiers` in `EnrichmentResult.Success` to pass IDs downstream. `DeezerProvider.enrichDiscography()` returns `resolvedIdentifiers = EnrichmentIdentifiers().withExtra("deezerId", artist.id.toString())`. The engine merges these into the request for subsequent providers. SimilarAlbumsProvider should also return resolved IDs.

### Data Model Considerations

1. **`SimilarArtist` lacks `sources` field** — Unlike `GenreTag(name, confidence, sources)`, the current `SimilarArtist(name, identifiers, matchScore)` doesn't track which providers contributed. Adding `sources: List<String> = emptyList()` is backward-compatible (default empty list) but is essential for merge transparency. Existing providers (Last.fm, ListenBrainz) need updating to populate this field.

2. **`MERGEABLE_TYPES` and `COMPOSITE_DEPENDENCIES` are static companion object sets** — Adding new mergeable/composite types is just adding entries to these collections. No registration ceremony needed. After the Phase 1 refactoring, these will move into the `ResultMerger` and `CompositeSynthesizer` registries.

3. **Serialization: all `EnrichmentData` subclasses need `@Serializable`** — The sealed class hierarchy uses `kotlinx.serialization`. New data classes (RadioPlaylist, SimilarAlbums, GenreDiscovery) and their supporting types all need the annotation. The serialization round-trip test (`EnrichmentDataSerializationTest`) should be extended.

### Provider-Specific Notes

**Deezer:**
- No auth required for any endpoint used in v0.6.0 — all are public.
- Rate limit is ~50 requests / 5 seconds. Current limiter uses 100ms (=10/sec). SimilarAlbumsProvider making ~6 sequential calls would take ~600ms, which is fine but watch for fan-out scenarios where multiple Deezer-backed types are requested simultaneously.
- Deezer IDs are numeric (Long), not UUIDs. Store via `withExtra("deezerId", id.toString())`.
- `/artist/{id}/related` returns up to 25 related artists by default. We should limit to ~10 for merge purposes.
- Artist images are available in search results (`artist.picture_small/medium/big/xl`) but currently thrown away. Not in v0.6.0 scope but worth noting for future ARTIST_PHOTO enhancement.

**ListenBrainz:**
- Requires MBID for all endpoints — no text search. SIMILAR_ARTISTS from ListenBrainz will only contribute to merge results when identity resolution has run (which is the normal path).
- Returns bare JSON arrays for some endpoints (uses `fetchJsonArray()` not `fetchJson()`).
- The `/explore/lb-radio/artist/{mbid}/similar` endpoint (already implemented) is the SIMILAR_ARTISTS source. No new ListenBrainz endpoints needed for v0.6.0.

**Last.fm:**
- SIMILAR_ARTISTS uses `artist.getSimilar` (already implemented, priority 100).
- No new Last.fm endpoints needed for v0.6.0.

### What Was Considered and Rejected

1. **Promoting `deezerId` to a first-class field in `EnrichmentIdentifiers`** — Rejected because adding every provider's internal ID to the data class violates open/closed. The `extra: Map<String, String>` pattern is correct. The real fix for the performance issue (re-searching each session) is that cached `resolvedIdentifiers` already persist the deezerId between enrichment calls within a session.

2. **Using Deezer `/artist/{id}/radio` for SIMILAR_TRACKS** — Rejected because radio is artist-seeded (not track-seeded) and returns playlist entries without match scores. Semantically different from "tracks similar to THIS specific track." Would confuse consumers expecting track-level similarity.

3. **SIMILAR_ALBUMS as a composite type** — Rejected because composites can only use data already resolved in the same request. To get albums for similar artists, we'd need to issue new enrichment requests from within the synthesizer, breaking the pure-function synthesis pattern.

4. **Dynamic genre taxonomy from Wikidata/MusicBrainz** — Rejected for v0.6.0. Would require new API calls, complex parsing of genre claim hierarchies, and a caching strategy for the taxonomy itself. Static taxonomy covers the 80% case and can be swapped later.

5. **Making SIMILAR_TRACKS mergeable** — Not enough providers to justify it. Last.fm is the only SIMILAR_TRACKS provider and v0.6.0 doesn't add a second. Deezer radio is a different type (ARTIST_RADIO). Revisit when/if a second track-level similarity source appears.

6. **Adding SimilarAlbumsProvider inside DeezerProvider** — Rejected because DeezerProvider handles ForAlbum/ForArtist enrichment for its own types. SimilarAlbumsProvider has a distinct capability (SIMILAR_ALBUMS at priority 100) and a different entity-type focus. Keeping it as a separate provider class is cleaner and follows single-responsibility.

## Verification

1. **Unit tests** — `./gradlew :musicmeta-core:test` — all new + existing tests pass
2. **E2E showcase** — `./gradlew :musicmeta-core:test -Dinclude.e2e=true --tests "*.EnrichmentShowcaseTest"` — verify new types appear in coverage matrix
3. **Manual verification** — Enrich "Radiohead" ForArtist with `{SIMILAR_ARTISTS, ARTIST_RADIO, GENRE_DISCOVERY}`:
   - SIMILAR_ARTISTS shows provider="similar_artist_merger" with artists from 3 sources
   - ARTIST_RADIO returns ~25 radio tracks
   - GENRE_DISCOVERY returns related genres (indie rock → shoegaze, britpop, post-punk, etc.)
4. **Similar Albums** — Enrich "OK Computer" ForAlbum with `{SIMILAR_ALBUMS}`:
   - Returns albums by related artists (Muse, Placebo, etc.) with year + thumbnails
5. **Build** — `./gradlew :musicmeta-core:build` passes (includes lint, compile, test)
