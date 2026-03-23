# musicmeta — Gap Analysis & Roadmap

> Where we are, where we're going, and what it takes to get there.

---

## The Goal

Give Android and JVM music app developers a flexible, unopinionated engine for getting rich metadata, artwork, and discovery data from public APIs — so they can build polished UI/UX without becoming experts in MusicBrainz, Wikidata, Deezer, and half a dozen other services.

The library is a tool for developers to wield for their needs, not a framework that dictates how to use it. Request all 31 types at once or just the one you need. Use the merged result or pick from alternatives. Cache everything or nothing. The engine adapts to how you want to work.

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
  - *Profile methods* — `engine.artistProfile("Radiohead")` returning a structured object (planned)
  - *Type-safe requests* — only valid types for each entity kind, no wasted calls (planned)
  - *Smart defaults* — request the right types automatically based on entity kind (planned)

---

## Where We Are (v0.6.0)

### What Changed Since v0.5.0

v0.6.0 was **Recommendations Engine** — 7 phases, 14 plans, shipped 2026-03-23.

Key additions:
- **SIMILAR_ARTISTS multi-provider merge** — Deezer `/artist/{id}/related` added as third provider. SIMILAR_ARTISTS promoted to mergeable type (like GENRE). `SimilarArtistMerger` deduplicates by name, uses additive scoring capped at 1.0, and tracks contributing sources per artist.
- **ARTIST_RADIO** — New enrichment type backed by Deezer `/artist/{id}/radio`. Returns ordered `RadioPlaylist` with up to 25 tracks. 7-day TTL.
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
| | ARTIST_PHOTO | Wikidata, Fanart.tv, Deezer, Wikipedia | **Excellent** — 4 providers merged via ArtworkMerger, covers niche artists |
| | ARTIST_BACKGROUND | Fanart.tv | Thin — requires API key + MBID |
| | ARTIST_LOGO | Fanart.tv | Thin — requires API key + MBID |
| | ARTIST_BANNER | Fanart.tv | OK — requires API key + MBID |
| | CD_ART | Fanart.tv | OK — album-specific endpoint added |
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
| **Recommendations** | ARTIST_RADIO | Deezer | **v0.6.0** — ordered playlist of ~25 tracks, 7-day TTL |
| | SIMILAR_ALBUMS | Deezer (SimilarAlbumsProvider) | **v0.6.0** — era-proximity scored, 30-day TTL |

### Provider Utilization

| Provider | Endpoints Used | Utilization | Change from v0.5.0 |
|----------|---------------|-------------|---------------------|
| **MusicBrainz** | 11 | ~85% | — |
| **Last.fm** | 5 methods | ~50% | — |
| **Fanart.tv** | 2 | ~67% | — |
| **Deezer** | 10 | ~100% | +6 (getRelatedArtists, getArtistRadio, SimilarAlbumsProvider, searchTrack, getTrackRadio, artist photos) |
| **Discogs** | 5 | ~65% | — |
| **Cover Art Archive** | 2 | ~60% | — |
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

| Gap | Problem | Impact |
|-----|---------|--------|
| **No match quality indicator on `enrich()`** | Auto mode returns results with no indication of how confident the identity match was | Developer can't detect ambiguous matches to prompt for disambiguation |
| **No "did you mean?" on NotFound** | When search finds no match above threshold, returns empty — no close matches suggested | Developer can't help users fix typos |
| **Provider factual conflicts not surfaced** | If MusicBrainz says country=UK and Wikidata says country=GB, first provider wins silently | Minor — most factual conflicts are equivalent representations, not real disagreements |

**Design decisions needed:**

1. **Should `enrich()` return match quality?** A `matchConfidence` or `identityScore` field on the result would let developers decide: high score → show immediately, low score → prompt user with `search()` candidates. This keeps auto mode working but gives the developer a signal.

2. **Should there be a strict mode?** Some apps want to never show wrong data (e.g., a metadata editor). A mode where `enrich()` refuses to auto-pick below a configurable threshold and returns candidates instead would serve this use case without changing the default behavior.

**Principle: the library should never silently guess wrong.** When the engine isn't confident, it should give the developer enough information to involve their user. The two-step search→enrich flow is the primary tool for this — it just needs better candidate data and a bridge from auto mode.

### Remaining Gaps (no planned milestone)

- **itunesArtistId** not stored in resolvedIdentifiers — re-searches on every discography call (minor perf tech debt from v0.5.0)
- **ForAlbum credits aggregation** — CREDITS only supports ForTrack; aggregating per-track credits for an album deferred
- **Credit-Based Discovery** — "more from this producer/composer" via CREDITS data; cross-entity query pattern, deferred to v0.7.0+
- **ListenBrainz collaborative filtering** — user-scoped recommendations; needs user identity concept in EnrichmentRequest, deferred to v0.8.0+
- **Last.fm artist.gettoptracks** — could enhance ARTIST_POPULARITY with top track names/scrobble counts
- **Discogs artist images** — `getArtist()` response includes `images[]` array, already called for BAND_MEMBERS but images are not parsed. Would add another ARTIST_PHOTO source.

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
- **ErrorKind.TIMEOUT** — timed-out types get explicit `Error(TIMEOUT)` instead of being silently missing.
- **Band member deduplication** — MusicBrainz members deduplicated by ID, roles merged. Solo (Person-type) artists return themselves as the sole member.
- **Performance** — MusicBrainz lookup caching eliminates redundant API calls (~2s saved). `resolveAll` runs providers concurrently for mergeable types.

**Remaining gap:**
- **Discogs artist images** — `getArtist()` response includes `images[]` array but not parsed yet. Would add a 5th ARTIST_PHOTO source.
- **ARTIST_BACKGROUND/LOGO/BANNER** — still single-provider (Fanart.tv only). Could be made mergeable if additional sources are added.

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

---

## Summary

| Dimension | v0.1.0 | v0.4.0 | v0.5.0 | v0.6.0 |
|-----------|--------|--------|--------|--------|
| Enrichment types | 16 | 25 (+9) | 28 (+3) | 32 (+4) |
| Provider utilization | ~30% avg | ~48% avg | ~57% avg | ~60% avg |
| Engine concepts | Provider chains | + Typed identifiers, mapper pattern, confidence calculator | + Composite types, mergeable types, GenreMerger | + ResultMerger/CompositeSynthesizer interfaces, CatalogProvider filtering, ArtworkMerger, ErrorKind.TIMEOUT |
| "App-ready" artist page | Partial | Full | **Complete** | **Complete** + radio, genre discovery, merged similar artists |
| "App-ready" album page | Partial | Full | **Complete** | **Complete** + similar albums with era scoring |
| "App-ready" now-playing | Partial | Rich | **Complete** | **Complete** + catalog-aware filtering |

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
| Developer Experience | Engine works, consumer API is power-user only | ❌ **Not started** |
| Catalog Awareness | Interface shipped; implementations deferred | 🟡 **Interface only** |

**8/10 goal categories complete or mostly complete.** One category remains: Developer Experience.

#### Recommendation Module Status

| Module | Status | Details |
|--------|--------|---------|
| Similar Artists | ✅ **Shipped (v0.6.0)** | 3-provider merge (Last.fm + ListenBrainz + Deezer) via SimilarArtistMerger |
| Similar Tracks | ✅ **Shipped** | 2-provider merge (Last.fm + Deezer track radio) via SimilarTrackMerger |
| Similar Albums | ✅ **Shipped (v0.6.0)** | SimilarAlbumsProvider with era-proximity scoring |
| Radio/Mix | ✅ **Shipped (v0.6.0)** | ARTIST_RADIO via Deezer `/artist/{id}/radio` |
| Top Tracks | ✅ **Shipped** | 3-provider merge (Last.fm + ListenBrainz + Deezer) via TopTrackMerger. Fetches API max, no artificial cap. |
| Credit-Based Discovery | ❌ Deferred (v0.7.0+) | Cross-entity query pattern; CREDITS data exists |
| Genre Discovery | ✅ **Shipped (v0.6.0)** | GenreAffinityMatcher with ~70-relationship static taxonomy |
| Listening-Based | ❌ Deferred (v0.8.0+) | User-scoped; needs user identity in EnrichmentRequest |

#### Developer Experience Status

The engine works well, but the consumer API is a power-user API. Developers building music apps have to manually request types, cast results, and handle the Map pattern. A convenience layer would make the library feel effortless for the 80% use case.

| Issue | Impact | Status |
|-------|--------|--------|
| Verbose result handling (Map + casting) | High — every consumer writes boilerplate | ❌ Not started |
| No high-level profile methods | High — "give me an artist page" is the #1 use case | ❌ Not started |
| No type safety per request kind | Medium — ForArtist + LYRICS_SYNCED = wasted call | ❌ Not started |
| Metadata class overloaded | Medium — 15 nullable fields, unclear which are populated | ❌ Not started |
| "All types" returns noisy NotFounds | Low — 11 NotFounds for ForAlbum is confusing | ❌ Not started |

---

## Planned Milestones

### ✅ v0.6.0 — Recommendations Engine — SHIPPED 2026-03-23
Built discovery features on top of the enrichment data: multi-provider SIMILAR_ARTISTS merge (Last.fm + ListenBrainz + Deezer), ARTIST_RADIO (Deezer radio endpoint), SIMILAR_ALBUMS (synthesized from related artists + era scoring), GENRE_DISCOVERY (static genre affinity taxonomy), and CatalogProvider interface for library-aware filtering. 7 phases, 14 plans, 32 enrichment types.

### v0.7.0 — Developer Experience
Add a convenience layer that makes musicmeta feel effortless for the 80% use case. High-level `artistProfile()` / `albumProfile()` / `trackProfile()` methods returning structured objects with all fields pre-extracted. Type-safe request scoping. Cleaner data model. The low-level `enrich()` API stays for power users. This is a thin layer on top of what already works — no engine changes needed.

### v0.8.0+ — Catalog Implementations & User-Scoped Features
`CatalogProvider` interface and filtering modes shipped in v0.6.0. This milestone adds concrete implementations: `LocalLibraryCatalog` (file scanning + fingerprint matching), `SpotifyCatalog` (OAuth + catalog check), `YouTubeMusicCatalog`. Also adds ListenBrainz collaborative filtering (user-scoped recommendations requiring username in request). May introduce `ForUser` request variant or dedicated engine method.
