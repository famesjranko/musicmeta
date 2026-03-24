# musicmeta — Gap Analysis & Roadmap

> Where we are, where we're going, and what it takes to get there.

---

## The Goal

Give Android and JVM music app developers a flexible, unopinionated engine for getting rich metadata, artwork, and discovery data from public APIs — so they can build polished UI/UX without becoming experts in MusicBrainz, Wikidata, Deezer, and half a dozen other services.

The library is a tool for developers to wield for their needs, not a framework that dictates how to use it. Request all 32 types at once or just the one you need. Use the merged result or pick from alternatives. Cache everything or nothing. The engine adapts to how you want to work.

### Core principles

- **Maximize data, never discard.** If three providers return artist photos, return all three with alternatives. Every piece of data an API returns should reach the developer — they decide what to show their users.
- **Unopinionated and flexible.** No prescribed usage patterns. Developers choose what to request, when to request it, and how to present results. The library provides data and lets the app make decisions.
- **Resilient by default.** Rate limiting, circuit breaking, timeout handling (with explicit `ErrorKind.TIMEOUT`), and caching are built in. Individual provider failures don't break the request.
- **Works without API keys.** 8 of 11 providers need no authentication. The library returns useful results out of the box; adding API keys unlocks more coverage, not basic functionality.

### What the library provides

- **Artwork**: all types (album front/back, artist photo, banner, logo, fanart, CD art, booklet) at all sizes (thumb → hero), merged from all providers via ArtworkMerger with alternatives
- **Metadata**: artist → members → discography → albums → tracks, with labels, dates, countries, genres
- **Text content**: biographies, lyrics (synced + plain)
- **Relationships**: similar artists, similar tracks
- **Statistics**: popularity scores, rankings, listen counts
- **Links**: social media, websites, streaming profiles
- **Credits**: producers, performers, composers, engineers
- **Recommendations**: discovery and radio features built on enrichment data
  - *Similar Artists* — multi-provider merge (Last.fm, ListenBrainz, Deezer) with source attribution
  - *Similar Tracks* — track-level "you might also like"
  - *Similar Albums* — synthesized from similar artists + genre + era proximity
  - *Radio/Mix* — seed-based playlist generation (Deezer radio)
  - *Genre Discovery* — genre affinity neighbors via confidence-scored taxonomy
  - *Credit-Based Discovery* — "more from this producer/composer" via credits data (planned)
  - *Listening-Based* — collaborative filtering recommendations (planned, user-scoped)
- **Catalog Awareness**: filter recommendations by what the user can actually play
  - *CatalogProvider interface* — consumers implement to answer "does the user have access to X?"
  - *Filtering Modes* — unfiltered (pure discovery), available-only, available-first (ranked)
- **Developer Experience**: make integration as simple as possible
  - *Profile methods* — `engine.artistProfile("Radiohead")` returning a structured object
  - *Type-safe requests* — only valid types for each entity kind, no wasted calls
  - *Smart defaults* — request the right types automatically based on entity kind
  - *Cache management* — `engine.invalidate(request)`, `forceRefresh`, manual selection without cache key knowledge

---

## Where We Are (v0.7.0)

### What Changed Since v0.6.0

v0.7.0 was **Developer Experience** — shipped 2026-03-24.

Key additions:
- **Profile methods** — `engine.artistProfile("Radiohead")`, `engine.albumProfile(...)`, `engine.trackProfile(...)` returning structured data classes with computed properties
- **`EnrichmentResults` wrapper** — 19 named accessors, top-level `IdentityResolution`, `wasRequested()`/`result()` diagnostics, generic `get<T>()`
- **Default type sets** — `DEFAULT_ARTIST_TYPES` (15), `DEFAULT_ALBUM_TYPES` (14), `DEFAULT_TRACK_TYPES` (8), composable via set algebra
- **Cache management API** — `engine.invalidate(request, type?)`, `forceRefresh` parameter on `enrich()` and all profile extensions, `engine.isManuallySelected()`/`markManuallySelected()` without cache key knowledge
- **Bug fixes** — ProviderChain failure preservation, Room cache identity round-tripping, cache key convergence after disambiguation

### What Changed in v0.6.0

v0.6.0 was **Recommendations Engine** — 7 phases, 14 plans, shipped 2026-03-23.

Key additions:
- **SIMILAR_ARTISTS multi-provider merge** — Deezer `/artist/{id}/related` added as third provider. SIMILAR_ARTISTS promoted to mergeable type (like GENRE). `SimilarArtistMerger` deduplicates by name, uses additive scoring capped at 1.0, and tracks contributing sources per artist.
- **ARTIST_RADIO** — New enrichment type backed by Deezer `/artist/{id}/radio`. Returns ordered `RadioPlaylist` (default 50 tracks, configurable via `radioLimit`). 7-day TTL.
- **SIMILAR_ALBUMS** — New enrichment type via standalone `SimilarAlbumsProvider`. Fetches Deezer related artists + their top albums, scored by artist similarity and era proximity (±5yr = 1.2x multiplier). 30-day TTL.
- **GENRE_DISCOVERY** — New composite type via `GenreAffinityMatcher`. Static genre taxonomy of ~70 relationships across 12 families. Produces affinity-scored genre neighbors with relationship type (parent/child/sibling).
- **CatalogProvider interface** — Consumers implement `checkAvailability()` to filter recommendation results by availability. Three modes: UNFILTERED (default), AVAILABLE_ONLY, AVAILABLE_FIRST.
- **Engine extensibility** — `ResultMerger` and `CompositeSynthesizer` interfaces extracted from engine. New mergeable types and composite types plug in without modifying `DefaultEnrichmentEngine`.

### Current Coverage (32 enrichment types)

| Category | Type | Providers | Depth |
|----------|------|-----------|-------|
| **Artwork** | ALBUM_ART | CAA, Fanart.tv, Deezer, iTunes, Wikipedia | **Excellent** — 5 providers merged via ArtworkMerger, alternatives preserved |
| | ALBUM_ART_BACK | CAA | Good — via JSON metadata endpoint |
| | ALBUM_BOOKLET | CAA | Good — via JSON metadata endpoint |
| | ARTIST_PHOTO | Wikidata, Fanart.tv, Deezer, Discogs, Wikipedia | **Excellent** — 5 providers merged via ArtworkMerger, covers niche artists |
| | ARTIST_BACKGROUND | Fanart.tv | Thin — requires API key + MBID |
| | ARTIST_LOGO | Fanart.tv | Thin — requires API key + MBID |
| | ARTIST_BANNER | Fanart.tv | OK — requires API key + MBID |
| | CD_ART | Fanart.tv, CAA | Good — 2 providers |
| **Metadata** | GENRE | MusicBrainz, Last.fm, Discogs, iTunes | **Excellent** — 4 providers merged via GenreMerger |
| | LABEL | MusicBrainz, Discogs | Good |
| | RELEASE_DATE | MusicBrainz | OK |
| | RELEASE_TYPE | MusicBrainz, Discogs | OK |
| | COUNTRY | MusicBrainz, Wikidata | Good |
| | BAND_MEMBERS | MusicBrainz, Discogs | Good |
| | ARTIST_DISCOGRAPHY | MusicBrainz, Deezer, ListenBrainz, iTunes | **Excellent** — 4 providers |
| | ALBUM_TRACKS | MusicBrainz, Deezer, iTunes | Good — 3 providers |
| | ALBUM_METADATA | Deezer, Last.fm, Discogs, iTunes | **Excellent** — 4 providers |
| | CREDITS | MusicBrainz, Discogs | Good — recording rels + extraartists with roleCategory |
| | RELEASE_EDITIONS | MusicBrainz, Discogs | Good — release-group releases + master versions |
| **Text** | ARTIST_BIO | Wikipedia, Last.fm | Good |
| | LYRICS_SYNCED | LRCLIB | Good |
| | LYRICS_PLAIN | LRCLIB | Good |
| **Relationships** | SIMILAR_ARTISTS | Last.fm, ListenBrainz, Deezer | **Excellent** — 3 providers merged via SimilarArtistMerger |
| | SIMILAR_TRACKS | Last.fm, Deezer | Good — 2 providers merged via SimilarTrackMerger |
| | ARTIST_LINKS | MusicBrainz | Good — all URL relation types parsed |
| **Statistics** | ARTIST_POPULARITY | Last.fm, ListenBrainz | Good — batch endpoints available |
| | TRACK_POPULARITY | Last.fm, ListenBrainz | Good |
| **Composite** | ARTIST_TIMELINE | TimelineSynthesizer | Good — auto-resolves sub-types, synthesizes chronological events |
| | GENRE_DISCOVERY | GenreAffinityMatcher | **v0.6.0** — static taxonomy, ~70 genre relationships |
| **Top Tracks** | ARTIST_TOP_TRACKS | Last.fm, ListenBrainz, Deezer | **Excellent** — 3 providers merged via TopTrackMerger, fetches API max, no artificial cap |
| **Recommendations** | ARTIST_RADIO | Deezer | **v0.6.0** — ordered playlist (default 50 tracks, configurable), 7-day TTL |
| | SIMILAR_ALBUMS | Deezer (SimilarAlbumsProvider) | **v0.6.0** — era-proximity scored, 30-day TTL |

### Provider Utilization

| Provider | Endpoints Used | Utilization | Change from v0.5.0 |
|----------|---------------|-------------|---------------------|
| **MusicBrainz** | 11 | ~85% | — |
| **Last.fm** | 6 methods | ~55% | +1 (artist.gettoptracks) |
| **Fanart.tv** | 2 | ~67% | — |
| **Deezer** | 10 | ~100% | +6 (getRelatedArtists, getArtistRadio, SimilarAlbumsProvider, searchTrack, getTrackRadio, artist photos) |
| **Discogs** | 5 | ~70% | +artist images from existing getArtist call |
| **Cover Art Archive** | 2 | ~70% | +CD_ART from existing metadata endpoint |
| **ListenBrainz** | 6 | ~43% | — |
| **iTunes** | 3 | ~43% | — |
| **Wikidata** | 1 (5 properties) | ~15% | — |
| **Wikipedia** | 2 | ~50% | — |
| **LRCLIB** | 2 | ~100% | — |

**Average utilization: ~61%** (up from ~57% in v0.5.0, ~60% in v0.6.0)

---

## What's Left

### Ambiguity & Disambiguation

The engine supports two usage patterns for identity resolution:

**Auto mode** (current default) — pass a name, engine picks the best MusicBrainz match and enriches it. Simple but opaque — the developer doesn't know if the match was confident or a coin flip between two similar artists.

**Manual disambiguation** (already supported) — the developer controls the flow:
```kotlin
// 1. Search — returns candidates with MBIDs, scores, and metadata
val candidates = engine.search(EnrichmentRequest.forArtist("Bush"), limit = 5)
// → "Bush" (British rock, score=100, mbid=abc), "Bush" (Canadian, score=95, mbid=def)

// 2. Developer/user picks the right one

// 3. Enrich with the chosen MBID — skips search, goes straight to ID-based lookup
val results = engine.enrich(
    EnrichmentRequest.forArtist("Bush", mbid = chosen.identifiers.musicBrainzId),
    types,
)
```

This two-step flow is the right answer for the unopinionated principle: the library provides candidates, the app decides. When an MBID is provided, the engine skips fuzzy matching entirely and does precise ID-based lookups across all providers.

**Shipped:**
- `SearchCandidate.disambiguation` — MusicBrainz disambiguation text (e.g., "British rock band" vs "Canadian band") included in search results so developers can show users meaningful choices
- `SearchCandidate.releaseType` already carries artist type ("Group", "Person") and release type for albums
- `SearchCandidate.identifiers` carries MBIDs for the pick-and-enrich flow

**What still needs improvement:**

| Gap | Problem | Impact | Status |
|-----|---------|--------|--------|
| **Match quality indicator on `enrich()`** | Auto mode returns results with no indication of how confident the identity match was | Developer can't detect ambiguous matches to prompt for disambiguation | ✅ Shipped — `identityMatchScore` on `Success` |
| **"Did you mean?" on NotFound** | When search finds no match above threshold, returns empty — no close matches suggested | Developer can't help users fix typos | ✅ Shipped — `NotFound.suggestions` |
| **Provider factual conflicts not surfaced** | If MusicBrainz says country=UK and Wikidata says country=GB, first provider wins silently | Minor — most factual conflicts are equivalent representations, not real disagreements | Open |

**Shipped — `identityMatchScore` on `EnrichmentResult.Success`**: Each `Success` result now carries `identityMatchScore: Int?` (0-100, same scale as `SearchCandidate.score`). `null` when identity was pre-resolved (MBID provided) or result was cached. The engine stamps the MusicBrainz search score onto all results after identity resolution. Developers can use this to decide: high score → show immediately, low score → prompt user with `search()` candidates for disambiguation.

**Remaining design question — strict mode**: Some apps want to never show wrong data (e.g., a metadata editor). A mode where `enrich()` refuses to auto-pick below a configurable threshold and returns candidates instead would serve this use case without changing the default behavior.

**Principle: the library should never silently guess wrong.** When the engine isn't confident, it should give the developer enough information to involve their user. The two-step search→enrich flow is the primary tool for this.

### Remaining Gaps (no planned milestone)

- ~~**itunesArtistId** not stored in resolvedIdentifiers~~ — ✅ Fixed: iTunes provider now stores `itunesArtistId` after artist search
- **ForAlbum credits aggregation** — CREDITS only supports ForTrack; aggregating per-track credits for an album deferred
- **Credit-Based Discovery** — "more from this producer/composer" via CREDITS data; cross-entity query pattern, deferred to v0.7.0+
- **ListenBrainz collaborative filtering** — user-scoped recommendations; needs user identity concept in EnrichmentRequest, deferred to v0.8.0+

### Catalog Awareness — Interface Shipped, Implementations Remaining

The `CatalogProvider` interface shipped in v0.6.0 with three filtering modes (UNFILTERED, AVAILABLE_ONLY, AVAILABLE_FIRST). The engine applies filtering post-resolution to recommendation-type results. Consumers implement `CatalogProvider.checkAvailability()` for their catalog.

**What's left (v0.8.0+):**
- `LocalLibraryCatalog` — scan local files, match by title/artist/fingerprint
- `SpotifyCatalog` / `YouTubeMusicCatalog` — streaming service availability checks (requires OAuth)
- Fingerprint-based matching (AcoustID/Chromaprint) for local library
- Availability scoring — ranking by how accessible items are

### Artwork Mergeability — Mostly Resolved

**Principle: never discard data the API already returns.** If multiple providers have images for an artist, return all of them. The consumer decides which to display.

**Shipped:**
- **ArtworkMerger** — ARTIST_PHOTO and ALBUM_ART are now mergeable types. All providers are queried and results merged: highest-confidence image is primary, others are available via `Artwork.alternatives: List<ArtworkSource>`.
- **Deezer artist photos** — `picture_small/medium/big/xl` (4 sizes up to 1000x1000) now parsed and returned. Gives artist photos for niche artists like Ochre that have no Wikidata/Fanart.tv coverage.
- **Discogs artist photos** — `images[]` array from existing `getArtist()` response now parsed. ARTIST_PHOTO has 5 providers.
- **CD_ART from Cover Art Archive** — CAA's "Medium" image type was already in the API response but the capability wasn't registered.
- **ErrorKind.TIMEOUT** — timed-out types get explicit `Error(TIMEOUT)` instead of being silently missing.
- **Band member deduplication** — MusicBrainz members deduplicated by ID, roles merged. Solo (Person-type) artists return themselves as the sole member.
- **Performance** — MusicBrainz lookup caching eliminates redundant API calls (~2s saved). `resolveAll` runs providers concurrently for mergeable types.

**Remaining:**
- **ARTIST_BACKGROUND/LOGO/BANNER** — still single-provider (Fanart.tv only). No other API provides these semantic image types.

### Provider Coverage Gaps

Endpoints with diminishing returns (niche, write APIs, deprecated):
- Wikidata: ~85% unused but remaining properties are niche (occupation subtypes, genre claims)
- ListenBrainz: ~57% unused but remaining are CF recommendations, charts, sitewide stats
- LRCLIB: publish endpoint (write API, not relevant)
- Wikipedia: deprecated mobile-sections endpoint

---

## Priority Scorecard

Ranked by **impact to consumers × implementation effort**:

| # | Feature | New Types | Impact | Effort | Status |
|---|---------|-----------|--------|--------|--------|
| 1 | Multiple art sizes | Enhancement | High | Medium | ✅ Done (v0.4.0) |
| 2 | Album tracklist | ALBUM_TRACKS | High | Low | ✅ Done (v0.4.0) |
| 3 | Artist discography | ARTIST_DISCOGRAPHY | High | Medium | ✅ Done (v0.4.0) |
| 4 | Band members | BAND_MEMBERS | High | Medium | ✅ Done (v0.4.0) |
| 5 | Artist links | ARTIST_LINKS | Medium | Low | ✅ Done (v0.4.0) |
| 6 | Artist banner | ARTIST_BANNER | Medium | Very low | ✅ Done (v0.4.0) |
| 7 | Similar tracks | SIMILAR_TRACKS | Medium | Low | ✅ Done (v0.4.0) — Last.fm only |
| 8 | Wikidata enrichment | Enhancement | Medium | Low | ✅ Done (v0.4.0) |
| 9 | Album back/booklet art | Enhancement | Medium | Low | ✅ Done (v0.4.0) |
| 10 | Credits & personnel | CREDITS | High | High | ✅ Done (v0.5.0) — MB + Discogs |
| 11 | Wikipedia media | Enhancement | Low | Medium | ✅ Done (v0.4.0) |
| 12 | Release editions | RELEASE_EDITIONS | Low | Medium | ✅ Done (v0.5.0) — MB + Discogs |
| 13 | Artist timeline | ARTIST_TIMELINE | Medium | Medium | ✅ Done (v0.5.0) — Composite type |
| 14 | Genre confidence scores | Enhancement | Medium | Medium | ✅ Done (v0.5.0) — GenreMerger |
| 15 | Provider coverage expansion | Enhancement | Medium | Low | ✅ Done (v0.5.0) — 5 providers deepened |
| 16 | HttpResult/ErrorKind adoption | Enhancement | Low | Medium | ✅ Done (v0.5.0) — all 11 providers |
| 17 | Similar artists merge | SIMILAR_ARTISTS | High | Medium | ✅ Done (v0.6.0) — 3 providers via SimilarArtistMerger |
| 18 | Artist radio | ARTIST_RADIO | Medium | Low | ✅ Done (v0.6.0) — Deezer radio endpoint |
| 19 | Similar albums | SIMILAR_ALBUMS | Medium | Medium | ✅ Done (v0.6.0) — era-proximity scoring |
| 20 | Genre discovery | GENRE_DISCOVERY | Medium | Medium | ✅ Done (v0.6.0) — GenreAffinityMatcher |
| 21 | Catalog filtering | Enhancement | High | Medium | ✅ Done (v0.6.0) — CatalogProvider interface + 3 modes |
| 22 | Artwork merging | Enhancement | High | Medium | ✅ Done — ArtworkMerger, ARTIST_PHOTO (5 providers), ALBUM_ART (5 merged) |
| 23 | Deezer + Discogs artist photos | Enhancement | Medium | Low | ✅ Done — stopped discarding image data from existing API calls |
| 24 | Artist top tracks | ARTIST_TOP_TRACKS | High | Medium | ✅ Done — 3 providers merged, no artificial cap |
| 25 | ErrorKind.TIMEOUT | Enhancement | Medium | Low | ✅ Done — timed-out types get explicit errors |
| 26 | Band member dedup + solo artists | Enhancement | Medium | Low | ✅ Done — MBID dedup, Person-type returns sole member |
| 27 | Performance (MB caching + parallel resolveAll) | Enhancement | Medium | Medium | ✅ Done — ~6s faster for artist enrichment |
| 28 | Search disambiguation | Enhancement | Medium | Low | ✅ Done — SearchCandidate.disambiguation + pick-and-enrich flow |
| 29 | OkHttp adapter | Enhancement | High | Low | Planned (v0.8.0) — `musicmeta-okhttp` module, ~1 day |
| 30 | Stale-while-revalidate cache | Enhancement | High | Medium | Planned (v0.8.0) — `CacheMode` enum, offline fallback, ~2-3 days |
| 31 | Bulk enrichment (simple) | Enhancement | High | Low | Planned (v0.8.0) — sequential `enrichBatch()` with Flow emission, ~1 day |
| 32 | Maven Central publishing | Enhancement | High | Medium | Planned (v0.8.0) — Sonatype config + BOM, ~1 day code + ops |
| 33 | API stability (v1.0.0) | Milestone | High | Low | Planned (v1.0.0) — semver guarantees, freeze public API |
| — | ~~Flow-based progressive API~~ | Enhancement | Medium | High | Deferred — marginal benefit vs complexity; callers can split enrich() calls |

---

## Summary

| Dimension | v0.1.0 | v0.4.0 | v0.5.0 | v0.6.0 | v0.7.0 | v0.8.0 (planned) | v1.0.0 (planned) |
|-----------|--------|--------|--------|--------|--------|------------------|------------------|
| Enrichment types | 16 | 25 (+9) | 28 (+3) | 32 (+4) | 32 | 32 | 32 |
| Provider utilization | ~30% avg | ~48% avg | ~57% avg | ~60% avg | ~60% avg | ~60% avg | ~60% avg |
| Engine concepts | Provider chains | + Typed identifiers, mapper pattern, confidence calculator | + Composite types, mergeable types, GenreMerger | + ResultMerger/CompositeSynthesizer interfaces, CatalogProvider filtering, ArtworkMerger | + EnrichmentResults wrapper, IdentityResolution, profiles, default type sets | + OkHttp adapter, stale cache, bulk enrichment | API freeze, semver |
| "App-ready" artist page | Partial | Full | **Complete** | **Complete** + radio, genre discovery | `engine.artistProfile("Radiohead")` — one call, structured result | + offline support, bulk loading | Stable |
| "App-ready" album page | Partial | Full | **Complete** | **Complete** + similar albums | `engine.albumProfile("OK Computer", "Radiohead")` | + offline support, bulk loading | Stable |
| "App-ready" now-playing | Partial | Rich | **Complete** | **Complete** + catalog-aware | `engine.trackProfile("Creep", "Radiohead")` | + offline support | Stable |
| Android integration | Room cache | + Hilt module | + WorkManager | + WorkManager | + WorkManager | + OkHttp adapter | Stable |
| Distribution | JitPack | JitPack | JitPack | JitPack | JitPack | + Maven Central | Maven Central |

**The metadata + recommendations story is nearly complete.** A music app using musicmeta now gets metadata, discovery, and radio features from a single `enrich()` call. The architecture supports four enrichment patterns: standard provider chains, composite synthesis, multi-provider merging, and catalog-aware filtering.

### How close to the goal?

| Goal Category | Coverage | Assessment |
|--------------|----------|------------|
| Artwork | 8 types, ALBUM_ART (5 merged) + ARTIST_PHOTO (5 merged) + CD_ART (2) | ✅ **Complete** — ArtworkMerger collects from all providers with alternatives. Deezer + Discogs artist photos added for niche coverage. |
| Metadata | 9 types including credits + editions | ✅ **Complete** |
| Text content | Bios + synced/plain lyrics | ✅ **Complete** |
| Relationships | Similar artists (3 merged), similar tracks, links | ✅ **Complete** |
| Statistics | Artist + track popularity from 2 sources, top tracks (3 merged) | ✅ **Complete** |
| Links | All MusicBrainz URL relation types | ✅ **Complete** |
| Credits | Performers, producers, composers, engineers | ✅ **Complete** |
| Recommendations | 6 modules shipped; credit discovery + CF deferred | 🟡 **Mostly complete** |
| Developer Experience | EnrichmentResults wrapper, profiles, default type sets, identity resolution, cache management | ✅ **Complete** — `artistProfile()`, `albumProfile()`, `trackProfile()` with computed properties, 19 named accessors, `wasRequested()`/`result()` diagnostics, `invalidate()`, `forceRefresh`, manual selection |
| Catalog Awareness | Interface shipped; implementations deferred | 🟡 **Interface only** |

**9/10 goal categories complete or mostly complete.** Remaining: Catalog Awareness implementations (interface shipped, concrete implementations deferred).

**Production readiness gaps identified by external review:** HttpURLConnection-only HTTP stack (no OkHttp adapter), JitPack-only distribution, no offline/stale cache mode, no bulk enrichment API. Addressed in v0.8.0 milestone (~5-6 days estimated). Flow-based progressive API assessed and deferred — marginal benefit vs complexity.

#### Recommendation Module Status

| Module | Status | Details |
|--------|--------|---------|
| Similar Artists | ✅ **Shipped (v0.6.0)** | 3-provider merge (Last.fm + ListenBrainz + Deezer) via SimilarArtistMerger |
| Similar Tracks | ✅ **Shipped** | 2-provider merge (Last.fm + Deezer track radio) via SimilarTrackMerger |
| Similar Albums | ✅ **Shipped (v0.6.0)** | SimilarAlbumsProvider with era-proximity scoring |
| Radio/Mix | ✅ **Shipped (v0.6.0)** | ARTIST_RADIO via Deezer `/artist/{id}/radio` |
| Top Tracks | ✅ **Shipped** | 3-provider merge (Last.fm + ListenBrainz + Deezer) via TopTrackMerger. Fetches API max, no artificial cap. |
| Credit-Based Discovery | ❌ Deferred (v0.8.0+) | Cross-entity query pattern; CREDITS data exists |
| Genre Discovery | ✅ **Shipped (v0.6.0)** | GenreAffinityMatcher with ~70-relationship static taxonomy |
| Listening-Based | ❌ Deferred (v0.8.0+) | User-scoped; needs user identity in EnrichmentRequest |

#### Developer Experience Status

The consumer API now covers the full developer journey: build → get → update → refresh. Profile methods handle the 80% case; `EnrichmentResults` accessors handle the rest.

| Issue | Impact | Status |
|-------|--------|--------|
| Verbose result handling (Map + casting) | High — every consumer writes boilerplate | ✅ Done — `EnrichmentResults` wrapper with 19 named accessors + generic `get<T>()` |
| No high-level profile methods | High — "give me an artist page" is the #1 use case | ✅ Done — `artistProfile()`, `albumProfile()`, `trackProfile()` extension functions |
| No type safety per request kind | Medium — ForArtist + LYRICS_SYNCED = wasted call | ✅ Done — `DEFAULT_ARTIST_TYPES`, `DEFAULT_ALBUM_TYPES`, `DEFAULT_TRACK_TYPES` with `defaultTypesFor()` |
| Cache management requires internal keys | High — consumers can't invalidate or refresh cleanly | ✅ Done — `engine.invalidate(request)`, `forceRefresh` param, `markManuallySelected()`/`isManuallySelected()` |
| Metadata class overloaded | Medium — 16 nullable fields, unclear which are populated | ✅ Resolved — profile properties and named accessors unwrap individual fields; Tier 3 power users can still access raw `Metadata` but rarely need to |
| "All types" returns noisy NotFounds | Low — 11 NotFounds for ForAlbum is confusing | ✅ Done — default type sets + `wasRequested()` distinguish requested vs unrequested |

---

## Planned Milestones

### ✅ v0.6.0 — Recommendations Engine — SHIPPED 2026-03-23
Built discovery features on top of the enrichment data: multi-provider SIMILAR_ARTISTS merge (Last.fm + ListenBrainz + Deezer), ARTIST_RADIO (Deezer radio endpoint), SIMILAR_ALBUMS (synthesized from related artists + era scoring), GENRE_DISCOVERY (static genre affinity taxonomy), and CatalogProvider interface for library-aware filtering. 7 phases, 14 plans, 32 enrichment types.

### ✅ v0.7.0 — Developer Experience — SHIPPED 2026-03-24
Added a convenience layer: `EnrichmentResults` wrapper with 19 named accessors, top-level `IdentityResolution`, `wasRequested()`/`result()` for diagnostics, default type sets per entity kind, and profile extension functions (`artistProfile()`, `albumProfile()`, `trackProfile()`) with `SearchCandidate` overloads and `forceRefresh` support. Cache management API: `engine.invalidate(request)`, `engine.isManuallySelected()`/`markManuallySelected()`. Also fixed ProviderChain failure preservation, Room cache identity round-tripping, and cache key convergence after disambiguation. Developer guide split into 7 focused pages under `docs/guides/`.

### v0.8.0 — Production Readiness

External review identified real gaps in Android integration and production readiness. This milestone addresses the four highest-impact items — chosen by value-to-effort ratio, no architectural rewrites.

**Deliberately deferred:** Flow-based progressive API (`enrichFlow()` emitting partial results). Assessed and rejected for v0.8.0 — identity resolution blocks all emission, mergeable types (artwork, genres, similar artists) can't emit until all providers finish, so the actual progressive benefit is marginal. Callers who need progressive loading can make separate `enrich()` calls with different type sets today. Revisit if real demand emerges.

#### OkHttp HttpClient adapter
**Problem**: `DefaultHttpClient` uses `java.net.HttpURLConnection`. Every Android project already has OkHttp — developers can't leverage existing interceptors, certificate pinning, proxy config, or connection pooling.

**Solution**: Ship `OkHttpEnrichmentClient` implementing `HttpClient` in a new `musicmeta-okhttp` module (optional dependency). Developers pass their existing `OkHttpClient` instance. The `HttpClient` interface already exists with 12 methods — this is a transport swap, not an architecture change. ~200 lines.

**Effort**: ~1 day.

#### Stale-while-revalidate cache mode
**Problem**: Cache returns `null` on TTL expiry. No offline fallback, no stale serving with freshness indicator. Mobile apps with intermittent connectivity get nothing when the network is down and cache is expired.

**Solution**: Add `CacheMode` enum to `EnrichmentConfig`: `NETWORK_FIRST` (current behavior), `STALE_IF_ERROR` (serve expired data when network fails, flagged with `isStale: Boolean` on results), `CACHE_FIRST` (always serve cache, refresh in background). Add `getIncludingExpired()` to `EnrichmentCache` with default implementation returning `null` (backward-compatible). Cache implementations stop deleting expired entries on read — cleanup stays explicit via `deleteExpired()`. Room migration required for `RoomEnrichmentCache`.

**Effort**: ~2-3 days.

#### Bulk enrichment (simple)
**Problem**: Enriching a library screen (20 albums) means 20 serial MusicBrainz lookups at 1 req/sec = 20+ seconds. No progress feedback.

**Solution**: Add `engine.enrichBatch(requests, types): Flow<Pair<EnrichmentRequest, EnrichmentResults>>`. Simple implementation: iterates requests sequentially, emits each result as it completes. MusicBrainz rate limiter naturally throttles. Cache hits return instantly (no queue wait). Progress is implicit in the flow (count emissions vs total). This gives 90% of the value — per-entity callbacks, cache optimization, natural backpressure — without building a pipelining scheduler. Real pipelining (concurrent identity resolution + downstream fan-out overlap) deferred until someone proves the simple version is the bottleneck.

**Effort**: ~1 day.

#### Maven Central publishing
**Problem**: JitPack-only distribution. Corporate environments often block JitPack for supply chain security reasons.

**Solution**: Add Sonatype/Maven Central publishing config (`signing`, `maven-publish` with repository URLs, POM metadata). Publish `musicmeta-core`, `musicmeta-android`, and `musicmeta-okhttp` as a BOM. JitPack remains supported as an alternative. Code changes are small (~1 day); operational setup (Sonatype account, GPG key, CI secrets) is the real work.

**Effort**: ~1 day code + ~1-2 days ops.

### v0.9.0 — Catalog Implementations & User-Scoped Features
`CatalogProvider` interface and filtering modes shipped in v0.6.0. This milestone adds concrete implementations: `LocalLibraryCatalog` (file scanning + fingerprint matching), `SpotifyCatalog` (OAuth + catalog check), `YouTubeMusicCatalog`. Also adds ListenBrainz collaborative filtering (user-scoped recommendations requiring username in request). May introduce `ForUser` request variant or dedicated engine method.

### v1.0.0 — API Stability
Freeze the public API surface. Semantic versioning guarantees from this point forward. Migration guide from pre-1.0. All deprecated APIs removed. Published to Maven Central with stable coordinates.
