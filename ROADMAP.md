# musicmeta — Gap Analysis & Roadmap

> Where we are, where we're going, and what it takes to get there.

---

## The Goal

Give Android and JVM music app developers a flexible, unopinionated engine for getting rich metadata, artwork, and discovery data from public APIs — so they can build polished UI/UX without becoming experts in MusicBrainz, Wikidata, Deezer, and half a dozen other services.

The library is a tool for developers to wield for their needs, not a framework that dictates how to use it. Request all 36 types at once or just the one you need. Use the merged result or pick from alternatives. Cache everything or nothing. The engine adapts to how you want to work.

### Core principles

- **Maximize data, never discard.** If three providers return artist photos, return all three with alternatives. Every piece of data an API returns should reach the developer — they decide what to show their users.
- **Unopinionated and flexible.** No prescribed usage patterns. Developers choose what to request, when to request it, and how to present results. The library provides data and lets the app make decisions.
- **Resilient by default.** Rate limiting, circuit breaking, timeout handling (with explicit `ErrorKind.TIMEOUT`), and caching are built in. Individual provider failures don't break the request.
- **Works without API keys.** 8 of 11 providers need no authentication. The library returns useful results out of the box; adding API keys unlocks more coverage, not basic functionality.

### What the library provides

- **Artwork**: all types (album front/back, artist photo, banner, logo, fanart, CD art, booklet) at all sizes (thumb → hero), merged from all providers via ArtworkMerger with alternatives
- **Metadata**: artist → members → discography → albums → tracks, with labels, dates, countries, genres
- **Text content**: artist biographies, album descriptions, lyrics (synced + plain)
- **Relationships**: similar artists, similar tracks
- **Statistics**: popularity scores, rankings, listen counts — each source kept in its own unit, never summed
- **Links**: social media, websites, streaming profiles
- **Credits**: producers, performers, composers, engineers
- **Recommendations**: discovery and radio features built on enrichment data
  - *Similar Artists* — multi-provider merge (Last.fm, Deezer) with source attribution
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

## Where We Are (v0.12.0)

v0.10.1 is published to Maven Central and JitPack. Everything below the *Unreleased* block has
shipped; the version is declared once, in root `gradle.properties`.

### Unreleased — lands in the next release

Library code, including breaking changes: see the `[Unreleased]` block in `CHANGELOG.md`, which is
the list. The published 0.10.1 artifact carries none of it.

### Current Coverage (36 enrichment types)

Which providers serve each type, and in what priority order, is in
[docs/how-it-works.md](docs/how-it-works.md); what each provider takes from its upstream and what it
leaves is [docs/providers.md](docs/providers.md). This table is the assessment — a judgement, not a
measurement — of how well each type is covered.

| Category | Type | Depth |
|----------|------|-------|
| **Artwork** | ALBUM_ART | **Excellent** — 5 providers merged via ArtworkMerger, alternatives preserved |
| | ALBUM_ART_BACK | Good — via JSON metadata endpoint |
| | ALBUM_BOOKLET | Good — via JSON metadata endpoint |
| | ARTIST_PHOTO | **Excellent** — 5 providers merged via ArtworkMerger, covers niche artists |
| | ARTIST_BACKGROUND | Thin — requires API key + MBID |
| | ARTIST_LOGO | Thin — requires API key + MBID |
| | ARTIST_BANNER | OK — requires API key + MBID |
| | CD_ART | Good — 2 providers |
| **Metadata** | GENRE | **Excellent** — MusicBrainz's curated vocabulary marked and ranked ahead of Last.fm's vote-weighted tags, confidence summed where they agree. Deezer publishes one coarse editorial tag and declares no capability |
| | LABEL | Good |
| | RELEASE_DATE | OK |
| | RELEASE_TYPE | OK |
| | COUNTRY | Good |
| | BAND_MEMBERS | Good — deduplicated by MBID with roles merged; a solo (Person) artist returns themselves |
| | ARTIST_DISCOGRAPHY | Good — 4 providers, 3 of them live: ListenBrainz's route is disabled upstream ([docs/providers.md](docs/providers.md)) |
| | ALBUM_TRACKS | Good — 3 providers |
| | ALBUM_METADATA | **Excellent** — 4 providers |
| | CREDITS | Good — recording rels + extraartists with roleCategory |
| | RELEASE_EDITIONS | Good — release-group releases + master versions |
| | TRACK_METADATA | Good — 3 providers, from responses already fetched |
| **Text** | ARTIST_BIO | Good |
| | ALBUM_DESCRIPTION | Good — 2 providers, keyless top source |
| | LYRICS_SYNCED | Good |
| | LYRICS_PLAIN | Good |
| **Relationships** | SIMILAR_ARTISTS | Good — 2 providers merged via SimilarArtistMerger |
| | SIMILAR_TRACKS | Good — 2 providers merged via SimilarTrackMerger |
| | ARTIST_LINKS | Good — every MusicBrainz URL relation type, plus Wikidata's official website (P856) as a second source |
| **Statistics** | ARTIST_POPULARITY | Good — 3 providers merged; each source's claim kept in its own unit as a `PopularitySignal`, MusicBrainz's community rating among them |
| | TRACK_POPULARITY | Good — merged the same way |
| **Composite** | ARTIST_TIMELINE | Good — auto-resolves sub-types, synthesizes chronological events |
| | GENRE_DISCOVERY | **v0.6.0** — static taxonomy, 189 genre relationships |
| **Top Tracks** | ARTIST_TOP_TRACKS | Good — 3 providers merged via TopTrackMerger, 2 of them live (ListenBrainz's route is disabled upstream), fetches API max, no artificial cap |
| **Recommendations** | ARTIST_RADIO | **v0.6.0** — ordered playlist (default 50 tracks, configurable), 7-day TTL. For community-driven discovery, see ARTIST_RADIO_DISCOVERY |
| | ARTIST_RADIO_DISCOVERY | **Dark** — community-driven discovery radio (configurable depth, free user token, catalog-filtered), but its only provider's route `/1/explore/lb-radio` has returned 500 since ListenBrainz disabled it around 2026-06-30, with no re-enable date |
| | SIMILAR_ALBUMS | **v0.6.0** — era-proximity scored, 30-day TTL |
| **Preview** | TRACK_PREVIEW | **v0.9.0** — 30-second MP3 preview URL, on-demand (not in DEFAULT_TRACK_TYPES), 24-hour TTL |

### Provider Surface Used

Distinct upstream paths each provider calls, counted in `provider/*/*Api.kt` on **2026-08-12**.
Parameter variants fold into one path: Last.fm's six `method=` calls are one path, iTunes' three
`lookup` forms are one, and a search and a lookup on the same entity are two.

| Provider | Paths called |
|----------|--------------|
| **MusicBrainz** | 8 — search + lookup for artist, recording and release; browse + lookup for release-group |
| **Deezer** | 10 |
| **ListenBrainz** | 5 — 2 of them 500 upstream, see [docs/providers.md](docs/providers.md) |
| **Discogs** | 4 |
| **Cover Art Archive** | 3 |
| **Fanart.tv** | 2 |
| **iTunes** | 2 |
| **LRCLIB** | 2 |
| **Wikipedia** | 2 |
| **Last.fm** | 1 path, 6 methods |
| **Wikidata** | 1 (10 claims read) |

No percentage of each upstream's total surface appears here. What a provider leaves unused is a
per-field judgement, not a ratio — a write endpoint, a user-scoped route and a field we already
fetch and drop are not one unit — and that judgement is kept, hand-verified and dated, in
[docs/providers.md](docs/providers.md) § What we don't extract.

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

This two-step flow is the right answer for the unopinionated principle: the library provides candidates, the app decides. When an MBID is provided, the engine skips fuzzy matching entirely and does precise ID-based lookups across all providers — including a request that carries *only* an MBID (`forArtistByMbid` and its track and album siblings), where identity resolution supplies the names the other providers search by. `discoverMbidEntityType` answers what a bare MBID names when the caller does not know.

Name resolution reaches an artist's MusicBrainz aliases, and `identityMatchScore` says which of the two matched — a hit on the artist's own name scores above a hit on an alias, so a caller can tell a confident match from a plausible one.

The `SearchCandidate` fields this flow relies on are documented in
[docs/guides/identity-resolution.md](docs/guides/identity-resolution.md).

**What still needs improvement:**

| Gap | Problem | Impact | Status |
|-----|---------|--------|--------|
| **Provider factual conflicts not surfaced** | If MusicBrainz says country=UK and Wikidata says country=GB, first provider wins silently | Minor — most factual conflicts are equivalent representations, not real disagreements | Open |

**Remaining design question — strict mode**: Some apps want to never show wrong data (e.g., a metadata editor). A mode where `enrich()` refuses to auto-pick below a configurable threshold and returns candidates instead would serve this use case without changing the default behavior.

**Principle: the library should never silently guess wrong.** When the engine isn't confident, it should give the developer enough information to involve their user. The two-step search→enrich flow is the primary tool for this.

### Remaining Gaps (no planned milestone)

- **ForAlbum credits aggregation** — CREDITS only supports ForTrack; aggregating per-track credits for an album deferred
- **Credit-Based Discovery** — "more from this producer/composer" via CREDITS data; a cross-entity query pattern the engine has no shape for
- **ListenBrainz collaborative filtering** — user-scoped recommendations; needs a user identity concept in `EnrichmentRequest`
- **A second ARTIST_RADIO_DISCOVERY source** — the type has one provider and that provider's route is
  disabled upstream. `/1/lb-radio/artist/{mbid}` answers, but it is a candidate pool rather than a
  playlist ([docs/providers.md](docs/providers.md) has why serving the type from it is not free)
- **Flow-based progressive API** — assessed and deferred: marginal benefit against the complexity, and a caller who wants results in stages can split their `enrich()` calls

### Catalog Awareness — Interface Shipped, Implementations Remaining

The `CatalogProvider` interface shipped in v0.6.0 with three filtering modes (UNFILTERED, AVAILABLE_ONLY, AVAILABLE_FIRST). The engine applies filtering post-resolution to recommendation-type results. Consumers implement `CatalogProvider.checkAvailability()` for their catalog.

**What's left (v0.8.0+):**
- `LocalLibraryCatalog` — scan local files, match by title/artist/fingerprint
- `SpotifyCatalog` / `YouTubeMusicCatalog` — streaming service availability checks (requires OAuth)
- Fingerprint-based matching (AcoustID/Chromaprint) for local library
- Availability scoring — ranking by how accessible items are

### Artwork Mergeability — Mostly Resolved

**Principle: never discard data the API already returns.** If multiple providers have images for an artist, return all of them. The consumer decides which to display.

Which types merge, and from how many providers, is in the Current Coverage table above.

**Remaining:**
- **ARTIST_BACKGROUND/LOGO/BANNER** — still single-provider (Fanart.tv only). No other API provides these semantic image types.

### Provider Coverage Gaps

Left deliberately, with the reason. The full per-field list is in
[docs/providers.md](docs/providers.md) § What we don't extract; these are the ones that shape a
milestone:

- Wikidata: P136 genre and P264 label arrive as Q-ids, so either would need a label lookup we
  currently hand-roll per map
- ListenBrainz: collaborative-filtering recommendations are user-scoped (above); `/1/stats/**`
  and the submit endpoints are out of scope for a read library
- LRCLIB: the publish path is a write API needing a proof-of-work solution
- Wikipedia: `/page/media-list` is the last dependency on the legacy `rest_v1` surface, and nothing
  else lists an article's images with a lead-image flag

---

## Summary

**The metadata + recommendations story is nearly complete.** A music app using musicmeta now gets metadata, discovery, and radio features from a single `enrich()` call. The architecture supports four enrichment patterns: standard provider chains, composite synthesis, multi-provider merging, and catalog-aware filtering.

### How close to the goal?

| Goal Category | Coverage | Assessment |
|--------------|----------|------------|
| Artwork | 8 types, ALBUM_ART (5 merged) + ARTIST_PHOTO (5 merged) + CD_ART (2) | ✅ **Complete** — ArtworkMerger collects from all providers, alternatives preserved |
| Metadata | 12 types including credits + editions | ✅ **Complete** |
| Text content | Artist bios, album descriptions, synced/plain lyrics | ✅ **Complete** |
| Relationships | Similar artists (2 merged), similar tracks (2 merged), links (2 sources) | ✅ **Complete** |
| Statistics | Artist + track popularity merged from 3 sources as per-source signals, top tracks (3 merged, 2 live) | ✅ **Complete** |
| Links | All MusicBrainz URL relation types, plus Wikidata P856 | ✅ **Complete** |
| Credits | Performers, producers, composers, engineers | ✅ **Complete** |
| Recommendations | 6 modules shipped, one dark on a disabled upstream route; credit discovery + CF deferred | 🟡 **Mostly complete** |
| Developer Experience | EnrichmentResults wrapper, profiles, default type sets, identity resolution, cache management | ✅ **Complete** |
| Catalog Awareness | Interface shipped; implementations deferred | 🟡 **Interface only** |

**9/10 goal categories complete or mostly complete** (8 ✅ complete, plus Recommendations 🟡 mostly complete; Catalog Awareness is interface-only). Remaining: Catalog Awareness implementations (interface shipped, concrete implementations deferred).

---

## Planned Milestones

Shipped milestones are in `CHANGELOG.md`, one section per release.

### v1.0.0 — API Stability
Freeze the public API surface. Semantic versioning guarantees from this point forward. Migration guide from pre-1.0. All deprecated APIs removed. Published to Maven Central with stable coordinates.

**What it still waits on:** a per-class pass on the `(root)` package — 55 top-level types, 180
declarations in the dump counting nested (`musicmeta-core/api/musicmeta-core.api`, 2026-08-17) —
which has never been audited, and `1.0.0` makes every one of them permanent. Then the freeze.
Enforcement itself is already in place (`api/*.api` baselines and `apiCheck`), and `provider/`,
`http/` and `engine/` have had their pass.
