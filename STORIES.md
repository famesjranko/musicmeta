# musicmeta - Project Stories

> A living document of architectural decisions, progress, and lessons learned.
> Updated as the project evolves. Newest entries first within each section.

---

## Decisions

### 2026-03-24: v0.8.0 Production Readiness — four adoption blockers addressed

**Context**: External review identified four gaps blocking production adoption: no OkHttp adapter (every Android project has OkHttp already), no offline cache fallback, no bulk enrichment API, JitPack-only distribution. Flow-based progressive API was assessed and deliberately cut — identity resolution blocks all emission, so marginal benefit vs complexity.

**Decision**: Ship four targeted features as v0.8.0 without architectural rewrites.

**Key decisions**:
- **OkHttp 4.12.0, not 5.x**. OkHttp 5.x requires Kotlin 2.2.x stdlib as a transitive dependency, which would force consumers onto Kotlin 2.2.x even though musicmeta compiles with Kotlin 2.1.0. The upgrade belongs together with a future Kotlin 2.2 bump.
- **No `Accept-Encoding: gzip` in OkHttp adapter.** OkHttp adds this header automatically and handles transparent decompression. Setting it manually disables OkHttp's decompression and delivers raw gzip bytes to the JSON parser — a documented footgun in OkHttp's issue tracker.
- **`STALE_IF_ERROR` only, no `CACHE_FIRST`.** `CACHE_FIRST` (always serve cache, refresh in background) needs a background refresh coroutine scope, which is a different architecture. `STALE_IF_ERROR` covers the critical offline case with minimal complexity.
- **Stale only for `Error`/`RateLimited`, never `NotFound`.** `NotFound` means "the provider searched and found nothing" — serving stale data would be misleading. `Error` and `RateLimited` mean "the network failed" — stale is a reasonable fallback.
- **`enrichBatch()` is sequential, not pipelined.** Rate limiter naturally throttles at 1 req/sec for MusicBrainz. Cache hits return instantly. Real pipelining (concurrent identity resolution overlap) deferred until someone proves the simple version is a bottleneck.
- **OSSRH is dead (shut down June 2025).** The original plan targeted OSSRH URLs — research caught this before implementation. Switched to vanniktech `gradle-maven-publish-plugin` targeting `SonatypeHost.CENTRAL_PORTAL`.
- **vanniktech plugin 0.30.0, not 0.36.0.** v0.36.0 hardcodes `ANDROID_GRADLE_MIN = "8.13.0"` but the project uses AGP 8.7.3. v0.30.0 has the same `CENTRAL_PORTAL` DSL and works with AGP 8.0.0+.
- **Android javadoc jar disabled.** AGP 8.7.3 bundles Dokka 1.x (ASM8) which cannot parse Kotlin 2.1.0 sealed class metadata. Core and okhttp produce full javadoc jars. Android publishes `.aar` + `-sources.jar` + `.pom`. Re-enable with AGP 8.13.0+.

**Status**: Shipped

---

### 2026-03-24: Cache management API — closing the consumer dev map

**Context**: Audit of the v0.7.0 developer experience revealed that while build→get→search flows were clean, update/refresh operations required consumers to import `DefaultEnrichmentEngine` and hand-compute internal cache keys. This leaked an implementation detail: the entity key format (`"artist:Radiohead:GENRE"`) includes the type and uses MBID when available, with name-alias keys for cache convergence after disambiguation. No external consumer should need to know this.

**Decision**: Four new methods on `EnrichmentEngine` interface, plus `forceRefresh` on `enrich()`.

**Key design decisions**:
- **`invalidate(request, type?)` iterates all `EnrichmentType.entries` when type is null.** The cache interface's `invalidate(entityKey, null)` was designed for a different key format — entity keys embed the type, so "delete all for this entity" requires computing a key per type. 32 iterations is trivial cost for a cache operation.
- **`invalidateKeys()` private helper shared by `invalidate()` and `forceRefresh`.** Both need to clear the primary key AND the name-alias key (when MBID is present). Extracted to avoid duplication.
- **`forceRefresh` as a parameter on `enrich()`, not a separate method.** Keeps the full pipeline intact (identity resolution, provider fan-out, caching, catalog filtering). Implementation: invalidate requested types before the normal flow. Profile extensions inherit it for free via delegation.
- **Entity key functions extracted to `EntityKey.kt`.** `entityKeyFor()` and `entityKeyForName()` were `DefaultEnrichmentEngine` companion methods — internal details that tests referenced. Moved to package-internal top-level functions. Companion keeps thin delegation wrappers to avoid breaking existing test references.
- **`markManuallySelected`/`isManuallySelected` delegate directly.** These are simple key-computation wrappers — no aliasing complexity since manual selection is always on the primary key.

**Status**: Active

---

### 2026-03-24: Disambiguation signals — identity match score and "did you mean?"

**Context**: The engine's auto mode (`enrich()` with just a name) silently picks the best MusicBrainz match. Developers had no way to know if the match was confident ("Radiohead" → score 100) or ambiguous ("Bush" → score 80, could be British or Canadian band). The `search()` → pick → `enrich(mbid=...)` flow existed for manual disambiguation, but there was no signal telling developers *when* to use it. Worse, when no match met the threshold, all candidates were silently discarded — the developer got NotFound with zero context.

**Decision**: Two complementary signals on `EnrichmentResult`:
1. `Success.identityMatchScore: Int?` — stamps every result with the MusicBrainz search score (0-100) after identity resolution.
2. `NotFound.suggestions: List<SearchCandidate>?` — carries near-miss candidates when identity resolution finds results below the match threshold.

**Key design decisions**:
- **`IdentityMatch` enum** (`RESOLVED`, `BEST_EFFORT`, `SUGGESTIONS`) on both `Success` and `NotFound`. Single field to route on — no magic numbers, no checking multiple fields. `null` means identity resolution wasn't needed (MBID pre-provided or cached).
- **Short-circuit on `SUGGESTIONS`**. When identity fails with near-miss candidates, the engine returns `NotFound(SUGGESTIONS)` immediately for all types — no provider fan-out, no wasted API calls. The developer shows "did you mean?", user picks, re-enriches with the correct MBID. Saves ~14s vs the old behavior of querying all providers with a bad name.
- **`BEST_EFFORT` for unverified results**. When identity fails without candidates (truly nothing found), providers try fuzzy searches. Results are stamped `BEST_EFFORT` so the developer can show a warning.
- **`identityMatchScore` on 0-100 scale** (only when `RESOLVED`), matching `SearchCandidate.score`. Distinct from `confidence: Float` (0.0-1.0) which measures data quality, not identity quality.
- **Suggestions reuse `SearchCandidate`** — the same type returned by `engine.search()`. Contains title, artist, score, disambiguation text, and MBID.
- **Fuzzy fallback search**. MusicBrainz quoted search (`artist:"Radohead"`) returns zero results for typos. When empty, falls back to unquoted Lucene `~` fuzzy search to find near-miss candidates for suggestions. One extra API call, only in the typo case.
- **Max 3 suggestions** to keep the response lightweight. The developer can call `search()` for more.

**Status**: Active

---

### 2026-03-24: ARTIST_TOP_TRACKS — fetch everything, let devs filter

**Context**: Apps need an artist's most popular tracks for "Top Tracks" UI widgets. The data existed across three providers (Last.fm scrobble counts, ListenBrainz listen counts + duration + album, Deezer stream ranking) but wasn't surfaced as its own type — it was buried inside `ARTIST_POPULARITY.topTracks` from ListenBrainz only.

**Decision**: New `ARTIST_TOP_TRACKS` enrichment type with `TopTrackMerger`. Three providers merged: Last.fm (`artist.gettoptracks`, up to 1000), Deezer (`/artist/{id}/top`, up to 100), ListenBrainz (`/popularity/top-recordings-for-artist`, unlimited).

**Key design decisions**:
- **No artificial limit on output.** Early iterations had `topTracksLimit` in config, but this is opinionated — the developer should call `.take(n)` for their UI needs. Each provider fetches its API max; the merger returns everything deduplicated and ranked. Consistent with the "never discard data" principle.
- **New `TopTrack` data class** rather than reusing `PopularTrack`. `PopularTrack` was too thin (no album, duration, listenerCount, sources). `TopTrack` has everything an app needs for a rich track list.
- **ListenBrainz was discarding data.** The existing `getTopRecordingsForArtist` parser threw away `total_user_count`, `length`, and `release.name` — three fields the API already returned. Fixed to capture everything.
- **Merger deduplicates by normalized title + MBID.** MBID-based matching takes priority, so "Karma Police" and "Karma Police (Remastered)" merge when they share an MBID. Listen counts are summed across providers; highest listener count is kept.
- **Added to `RECOMMENDATION_TYPES`** so CatalogProvider filtering applies automatically. If a developer configures catalog awareness, top tracks are filtered by availability just like radio and similar artists.

**Status**: Active

---

### 2026-03-24: "Never discard data" — ArtworkMerger and multi-provider images

**Context**: Testing with niche artist "Ochre" revealed ARTIST_PHOTO returned NotFound despite Deezer having a profile photo. Investigation found Deezer's `searchArtist()` response includes `picture_small/medium/big/xl` but we discarded them. Same for Discogs — `getArtist()` returns `images[]` but we only parsed `members`. Broader problem: artwork types used short-circuit provider chains (first `Success` wins), so even when multiple providers had different images, only one was returned.

**Decision**: Establish "never discard data" as a core project principle. Make artwork types mergeable. Create `ArtworkMerger` following the `ResultMerger` pattern.

**Key design decisions**:
- **`ArtworkSource` model and `Artwork.alternatives` field** (backward compatible). The highest-confidence provider's image becomes the primary `url`/`thumbnailUrl`/`sizes`. Other providers' images are available via `alternatives: List<ArtworkSource>`. Consumers who only read `url` see no change; consumers who want all images check `alternatives`.
- **Not a flat `sizes` list.** Early design considered putting all provider images into one `sizes` list with provider labels. Rejected because `ArtworkSize` represents different resolutions of the *same* image — a Deezer press photo and a Wikidata editorial photo are *different images*, not size variants.
- **Deezer artist photos added** (priority 60, no API key needed). Covers niche artists that Wikidata/Fanart.tv miss.
- **Discogs artist photos added** (priority 40, requires token). Zero extra API calls — `getArtist()` was already called for BAND_MEMBERS, images were in the same response.
- **ARTIST_PHOTO now has 5 providers**: Wikidata (100), Fanart.tv (80), Deezer (60), Discogs (40), Wikipedia.
- **ALBUM_ART also made mergeable**: 5 providers (CAA, Deezer, iTunes, Fanart.tv, Wikipedia) now contribute with alternatives.
- **CD_ART added to Cover Art Archive**: The API already returned CD/Medium image types in metadata but the capability wasn't registered.

**Status**: Active

---

### 2026-03-24: Performance + quality fixes

**Context**: Artist enrichment for Radiohead took ~16 seconds. Band members showed "19 members: Colin Greenwood, Colin Greenwood, Colin Greenwood..." (duplicates from multiple MusicBrainz membership periods). Solo artist "Ochre" returned NotFound for band members despite being a known person.

**Fixes**:
- **MusicBrainz lookup caching** (`cachedArtistLookup` with Mutex). BAND_MEMBERS, ARTIST_LINKS, and GENRE all called `lookupArtist(mbid)` separately — 3 redundant API calls through the 1.1s rate limiter. Now uses `lookupArtistWithRels` (superset) cached by MBID. First call populates cache, subsequent calls return instantly. Saves ~2.2s per artist enrichment.
- **Concurrent `resolveAll`** in ProviderChain. Mergeable types (GENRE, SIMILAR_ARTISTS, ARTIST_PHOTO) previously queried providers sequentially via `forEachEligible`. Now launches each provider in its own `async` via `coroutineScope`. Combined with MB caching, reduced Radiohead from ~16s to ~10s.
- **Band member deduplication.** MusicBrainz returns multiple `member-of-band` relationships per person (different roles, time periods). Now deduplicates by member ID — merges roles (e.g., "guitar, keyboards" for Jonny Greenwood) and picks the widest date range.
- **Solo artist handling.** When MusicBrainz artist type is "Person" and no member relationships exist, returns the artist themselves as the sole member using `sort-name` for real name (e.g., "Christopher Leary (Ochre)").
- **ErrorKind.TIMEOUT.** When the engine timeout fires, timed-out types now get explicit `EnrichmentResult.Error(errorKind = ErrorKind.TIMEOUT)` instead of being silently missing from the result map.
- **SearchCandidate.disambiguation.** MusicBrainz returns disambiguation text (e.g., "British rock band" vs "Canadian band") — now included in search results so developers can show users meaningful choices for the pick-and-enrich flow.

**Status**: Active

---

### 2026-03-23: SIMILAR_TRACKS multi-provider merge via Deezer track radio

**Context**: SIMILAR_TRACKS only had Last.fm (`track.getSimilar`). The ROADMAP noted "Deezer radio is artist-seeded, different semantics" but that referred to ARTIST_RADIO (`/artist/{id}/radio`). Deezer also has `/track/{id}/radio` — a track-seeded endpoint returning ~25 similar tracks.

**Decision**: Add Deezer as a second SIMILAR_TRACKS provider with a `SimilarTrackMerger`, following the exact pattern established by `SimilarArtistMerger` in v0.6.0. The merger deduplicates by normalized title+artist, uses additive scoring capped at 1.0, and merges sources/identifiers.

**Key details**:
- `DeezerApi.searchTrack()` finds the Deezer track ID via `/search/track`, guarded by `ArtistMatcher.isMatch()`
- `DeezerApi.getTrackRadio()` calls `/track/{id}/radio`, reuses `DeezerRadioTrack` model (same response shape as artist radio)
- Position-based matchScores for Deezer results (same formula as `toSimilarArtists`)
- `SimilarTrack` gained a `sources: List<String>` field (matching `SimilarArtist` pattern)
- SIMILAR_TRACKS is now a mergeable type in the engine — all providers queried, results merged

**Status**: Active

---

### 2026-03-22: v0.6.0 Recommendations Engine — key architectural decisions

**Context**: Built four new recommendation enrichment types on top of the v0.5.0 engine. Several design decisions worth documenting.

**ResultMerger and CompositeSynthesizer extraction**: `DefaultEnrichmentEngine` was handling both mergeable-type dispatch (GENRE) and composite-type dispatch (ARTIST_TIMELINE) inline. With SIMILAR_ARTISTS becoming mergeable and GENRE_DISCOVERY becoming composite, these responsibilities were extracted into `ResultMerger` and `CompositeSynthesizer` interfaces. The engine now delegates to `ProviderRegistry` which holds lists of both. GenreMerger and TimelineSynthesizer were kept as objects (singletons) — stateless strategy pattern preserved. This allowed Phase 13 (SimilarArtistMerger) and Phase 16 (GenreAffinityMatcher) to plug in without modifying the engine.

**SimilarAlbumsProvider is standalone (not composite)**: The synthesizer interface requires pure functions with no I/O. `SIMILAR_ALBUMS` needs two Deezer API calls (related artists + their top albums). Placing this inside a synthesizer would break the no-I/O invariant. Decision: `SimilarAlbumsProvider` is a standalone `EnrichmentProvider` that does all its own I/O, identical to how every other provider works. No special treatment in the engine.

**CatalogProvider as fun interface (SAM)**: `CatalogProvider` exposes a single suspending method `checkAvailability`. Declaring it as `fun interface` allows consumers to use lambda syntax for simple implementations — `CatalogProvider { queries -> queries.map { CatalogMatch(available = true, source = "local") } }`. This is the same pattern used by Java's `Comparator` and Kotlin's `Comparator` functional interface.

**Catalog filtering extracted to CatalogFilter.kt**: `DefaultEnrichmentEngine` was approaching the 300-line file limit after adding catalog filtering. The private helpers (`applyCatalogFiltering`, `toQueries`, `applyMode`, `reorderData`) were extracted to `CatalogFilter.kt` as top-level functions. The engine imports and calls them. This preserved the 300-line constraint and made the filtering logic independently testable.

**Deezer artist ID resolution pattern**: Providers that need a Deezer artist ID (SIMILAR_ARTISTS, ARTIST_RADIO, SIMILAR_ALBUMS) all follow the same pattern: check `identifiers.extra["deezerId"]` first (cache hit path), fall back to `DeezerApi.searchArtist()` + `ArtistMatcher.isMatch()` name guard, return `NotFound` if no confident match. The guard prevents false positives on artist name searches — Deezer's fuzzy search will return results for almost any query.

**Status**: Active

---

### 2026-03-21: Showcase demo and `runTest` discovery

**Context**: Built a comprehensive E2E showcase test to exercise every enrichment type across diverse queries (Radiohead, Kendrick Lamar, AC/DC, Bjork, instrumentals, obscure artists). The goal was both to demo capabilities and to find where the library needs improvement.

**Discovery — `runTest` breaks all E2E tests**: Every `engine.enrich()` call returned empty results under `runTest`. Root cause: `runTest` uses virtual time, and the engine's `withTimeout(30_000)` fires based on virtual time. Rate limiter `delay()` calls advance virtual time instantly, causing the timeout to fire before any real HTTP response arrives. Error message from kotlinx.coroutines confirms: *"Timed out after 30s of _virtual_ (kotlinx.coroutines.test) time."* All existing E2E tests (16 tests in `RealApiEndToEndTest`) were silently broken — they were passing only because the assertions happened to pass on empty results (they didn't). Switched all E2E tests to `runBlocking`.

**Discovery — identity resolution skipped wikidata/wikipedia for artists**: When the engine called MusicBrainz for identity resolution with type=GENRE, the provider's optimization (`type in RELATION_DEPENDENT_TYPES`) skipped the full lookup that gets wikidata/wikipedia URLs. This meant ARTIST_PHOTO (Wikidata) and ARTIST_BIO (Wikipedia) always failed for artist requests. Fixed by removing the type gate — always do the full lookup when the search result is missing wikidata/wikipedia.

**Discovery — `extractResolution` mismatch**: The existing E2E tests' `extractResolution` helper looked for `IdentifierResolution` data in results, but the engine stores `Metadata` data with `resolvedIdentifiers` attached to the Success wrapper. This was a stale pattern from before the engine was refactored. Fixed to reconstruct from current data model.

**Lesson**: E2E tests against real APIs should use `runBlocking`, not `runTest`. The virtual-time model in `runTest` is designed for unit tests with faked dependencies, not for integration tests with real I/O. The `RateLimiter` is particularly problematic because it uses `System.currentTimeMillis()` for the clock but `delay()` for waiting — a mismatch that causes artificial time accumulation under virtual time.

**Status**: Active

---

### 2026-03-21: Open-source readiness — remove opinionation

**Context**: musicmeta was extracted from Cascade (Android music player) as a standalone library. A review found several hardcoded values and Cascade-specific assumptions that would limit adoption by other apps.

**Changes**:
- Provider chain priorities made configurable via `EnrichmentConfig.priorityOverrides`. Apps can reorder which provider is tried first for each enrichment type without modifying provider code.
- Artwork sizes moved from engine-level config to per-provider constructor params. Each API has different size semantics (CAA uses pixel sizes in URLs, iTunes uses string replacement, Wikidata uses width params), so a single "preferred size" in config was misleading — it wasn't wired to anything.
- MusicBrainz `minMatchScore` (was hardcoded at 80) now a constructor param. Apps with obscure catalogs or non-Latin scripts can lower it.
- `ArtistMatcher.isMatch()` token overlap threshold now configurable (was hardcoded 0.5).
- Room database name extracted to a public constant so apps know what to change.
- All silent `catch (_: Exception)` blocks now log through `EnrichmentLogger`.
- Removed all Cascade references from test User-Agent strings.

**Rationale**: A library should let consumers tune behavior without forking. Constructor params with sensible defaults preserve backwards compatibility while opening up flexibility.

**Status**: Active

---

### 2026-03-21: Extraction from Cascade

**Context**: Cascade's metadata enrichment started as in-app API clients (MusicBrainz, Wikidata, Cover Art Archive). As the provider count grew to 11, the enrichment logic outgrew the app's data layer. The engine also has value as a standalone open-source library.

**Decision**: Extract into a two-module library:
- `musicmeta-core` (pure Kotlin/JVM) — engine, providers, HTTP, caching interface. Zero Android dependencies.
- `musicmeta-android` (optional) — Room cache, Hilt DI wiring, WorkManager base worker.

**Architecture**:
- `EnrichmentEngine` orchestrates the pipeline: cache check → identity resolution → fan-out to provider chains → confidence filtering → cache store.
- `ProviderRegistry` builds a `ProviderChain` per `EnrichmentType`, ordered by `ProviderCapability.priority`. The chain tries providers in order; `Success` short-circuits, `NotFound` falls through.
- Each provider is self-contained: own `*Api.kt` (HTTP), `*Models.kt` (parsing), `*Provider.kt` (enrichment logic). Adding a provider means implementing `EnrichmentProvider` — no engine changes needed.
- `CircuitBreaker` per provider (shared across chains) protects against cascading failures from a single degraded API.

**Trade-offs**:
- **Pro**: Reusable across apps, testable in isolation, pure Kotlin core runs on any JVM
- **Pro**: Provider architecture is open/closed — add new sources without modifying engine
- **Con**: Cascade's data layer now needs a mapping layer between engine types and domain models
- **Con**: JitPack dependency for consumers (vs composite build alternative)

**Key design choice — MusicBrainz as identity backbone**: MusicBrainz resolves MBIDs, Wikidata IDs, and Wikipedia titles. Downstream providers (Cover Art Archive, Wikidata, Wikipedia, Fanart.tv) use these IDs for precise lookups instead of fuzzy search. This dramatically improves accuracy but means MusicBrainz is a soft dependency. `enableIdentityResolution = false` provides an escape hatch.

**Status**: Active
