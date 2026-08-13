# How musicmeta Works

> A complete guide to the enrichment pipeline — from `enrich()` call to results.

## What It Does

musicmeta is a drop-in library that gives any music app **everything** about an artist, album, or track from a single call. You pass a title and artist name, and get back artwork, genres, bios, lyrics, credits, similar artists, popularity stats, and more — aggregated from 11 public APIs behind the scenes.

```kotlin
val artist = engine.artistProfile("Radiohead")

println(artist.bio?.text)          // "Radiohead are an English rock band..."
println(artist.genres.map { it.name }) // [alternative rock, art rock, electronic]
println(artist.photo?.url)         // https://upload.wikimedia.org/...
println(artist.topTracks)          // Top tracks with listen counts
println(artist.similarArtists)     // Similar artists from multiple sources
```

The consumer never needs to know which APIs exist, how they authenticate, or how to correlate identifiers across services.

This document traces the pipeline. How to *call* it — the three API tiers, error handling,
disambiguation, caching, batching and the OkHttp adapter — is in [guides/](guides/README.md).

---

## The Pipeline

```
enrich(request, types, forceRefresh)
      │
      ▼
┌────────────────────────────┐
│ 1. Force Invalidate        │── forceRefresh=false ──→ skip
└─────────────┬──────────────┘
              │ forceRefresh=true → invalidate MBID key + name alias key
              ▼
┌────────────────────────────┐
│ 2. Cache Check             │── all hit ──→ return cached
└─────────────┬──────────────┘
              │ miss (uncached types only proceed)
              ▼
┌──────────────────────────────────────────┐
│ 3. Identity Resolve (MusicBrainz)        │
│    searches title/artist                 │
│    → MBID, Wikidata ID, Wikipedia title  │
└─────────────┬────────────────────────────┘
              │ outcomes:
              │   success → merge IDs into request, continue
              │   suggestions → kept at the top level; fan-out still runs (step 4)
              │   not needed → skip (MBID already provided)
              ▼
┌────────────────────────────────────────────────────┐
│ 4. Concurrent Type Resolution (fan-out)            │
│                                                    │
│  Standard  ──→ chain.resolve()   (first wins)      │
│  Mergeable ──→ chain.resolveAll() (all win)        │
│  Composite ──→ resolve deps → synthesize           │
└─────────────┬──────────────────────────────────────┘
              │
              ▼
┌────────────────────────────┐
│ 5. Confidence Filter       │── drop below threshold (default 0.5)
└─────────────┬──────────────┘
              │
              ▼
┌────────────────────────────┐
│ 6. Catalog Filter          │── reorder/filter recommendations
└─────────────┬──────────────┘
              │
              ▼
┌────────────────────────────┐
│ 7. Stale Fallback          │── STALE_IF_ERROR: serve expired cache on Error/RateLimited
└─────────────┬──────────────┘
              │
              ▼
┌────────────────────────────┐
│ 8. Provenance Stamp        │── mark provider results with LookupProvenance
└─────────────┬──────────────┘
              │
              ▼
┌────────────────────────────┐
│ 9. Cache Store + Alias     │── save with TTL + name alias (skip stale results)
└─────────────┬──────────────┘
              │
              ▼
  return EnrichmentResults(raw, requestedTypes, identity)
```

### Step 1: Force Refresh Invalidation

When `forceRefresh=true`, the engine invalidates cache entries for all requested types before proceeding. Both the primary key (MBID-based when available) and the name alias key (title+artist) are invalidated so stale data can't survive under either key.

### Step 2: Cache Check

For each requested type, the engine checks the cache using the primary entity key (MBID if available, otherwise title+artist). Cache hits go directly into the result map. Only cache misses proceed to resolution. If every type is a cache hit, the engine returns immediately — no identity resolution or API calls needed.

### Step 3: Identity Resolution

The key insight: most providers need identifiers (MusicBrainz ID, Wikidata ID) for precise lookups, but the consumer only has a title and artist name.

**MusicBrainz acts as the identity backbone.** The engine first checks whether identity resolution is needed — it scans all uncached types and their provider chains' identifier requirements. If no provider needs an identifier the request lacks, identity resolution is skipped entirely.

A track request is the one exception, and it is a consequence of what `musicBrainzId` means. The field is polymorphic — a release id on an album request, an artist id on an artist request, a *recording* id on a track request — so a capability declaring `MUSICBRAINZ_ID` is declaring "an id of the request's own kind", not which kind it can serve. A recording id cannot stand in for the release-group id cover art is keyed on, so a track request carrying no `musicBrainzReleaseGroupId` still resolves identity even when it carries a recording id.

When needed, MusicBrainz searches by title/artist and returns:
- `musicBrainzId` (MBID) — the universal music identifier
- `musicBrainzReleaseGroupId` — for album editions
- `wikidataId` — extracted from MusicBrainz URL relations
- `wikipediaTitle` — extracted from MusicBrainz URL relations, English only: a relation pointing at
  another language wiki is ignored, leaving this null so `WikipediaProvider` falls back to the
  Wikidata `enwiki` sitelink
- Provider-specific IDs (Discogs, etc.) — stored in the `extra` map

These identifiers are merged into the request via `request.withIdentifiers(mergedIds)`. All downstream providers then use these IDs for precise lookups instead of fuzzy search.

**Name backfill, into a blank field only.** A request from `EnrichmentRequest.forTrackByMbid` (or its album/artist siblings) names an identifier and nothing else, and every provider but MusicBrainz searches by name. After the merge, the engine fills each blank name field from the entity identity resolution settled on — the recording or release title, and the `artist-credit` joined with MusicBrainz's own join phrases ("Queen & David Bowie"). A name the caller supplied is never overwritten: MusicBrainz keeps a variant like "Comfortably Numb (Live at Earls Court)" in the disambiguation rather than the title, so replacing the caller's string would send Deezer and LRCLIB after the studio take. An identifier MusicBrainz holds nothing under leaves the names blank — roughly seven in ten real third-party recording MBIDs, so this is the common path and not a corner. A request still naming no entity after the backfill is not fanned out to the providers that search by name: for each type, only providers whose capability is keyed on an identifier are asked, and a type none of them can serve is an honest `NotFound` from the chain. Nothing is asked to search for the empty string, on any provider — the engine enforces that, not MusicBrainz alone (`NamelessRequestFanOutTest`).

### Type discovery: what a bare MBID names

`EnrichmentEngine.discoverMbidEntityType(mbid)` answers `RECORDING`, `RELEASE`, `ARTIST` or nothing, so a consumer holding an identifier can build the right request. MusicBrainz has no endpoint that takes an id without its type — a wrong-type lookup answers 404, exactly as the right type does for an id it no longer holds — so the answer is a probe of the three types in order: **recording, then release, then artist**. That costs 1 request for a recording, 2 for a release, 3 for an artist and 3 for a dead id, on a 1 req/s limiter; the counts are asserted in `MusicBrainzEntityTypeDiscoveryTest`. Recording leads because that is where third-party identifiers come from. Each probe shares the enricher's per-call memo, so discovery inside an `enrich()` that already looked the entity up is free, and a miss is paid for once per call.

**ListenBrainz was measured against this and lost** (2026-08-12). `GET /1/metadata/recording/` is keyless, on a 2.5/s limiter, and answers a recording MBID with its name in one request — but a *non*-recording MBID and a dead one both come back `{}`, so it cannot tell "not a recording" from "not held". A miss therefore still pays the MusicBrainz release and artist lookups, making LB-first 1 request cheaper only when the id is a recording and 1 request dearer on every other outcome — including the dead case, which was seven in ten of a real third-party population (1212 of 1710, 2026-08-12). It would also add a failure mode this has none of: an LB 5xx is an outage and must never read as absence. Recorded so it is not re-proposed.

**Cache alias:** a result the engine resolved from an identifier is also written under the name key, so a later name-only lookup finds it. For an identifier-only request that key carries MusicBrainz's canonical name — there is no caller name to alias under, and the canonical one is what a later name lookup would ask with.

**Suggestions do not veto the fan-out:** If MusicBrainz can't find an exact match but has near-miss candidates, that is a statement about MusicBrainz's own lookup, not a global "nothing can be fetched" decision. Every uncached type still resolves through step 4 exactly as it would under a plain unresolved identity — each provider's own `ProviderChain` eligibility (availability, identifier requirements, circuit breaker) decides whether it runs. A `NONE`-identifier provider like Deezer's track preview search still answers; an MBID-only provider without an MBID still doesn't. Surviving `Success` results carry a `LookupProvenance` reflecting the fuzzy search that produced them (step 8), while the call's `CanonicalStatus` stays `AMBIGUOUS`. The suggestion list itself is attached once, to `EnrichmentResults.identity`, never copied onto a per-type result — the consumer can present it as "Did you mean?" and re-enrich with the selected candidate.

### Step 4: Concurrent Type Resolution

All requested types resolve **concurrently** via `coroutineScope { async {} }`. Three resolution modes:

#### Standard (short-circuit)
Most types. A `ProviderChain` tries providers in priority order:
- Priority 100 (primary) → 50 (fallback) → 30 (tertiary)
- First `Success` wins, remaining providers skipped
- `NotFound` and `Error` both fall through to the next provider
- If no provider succeeds, the last failure (`Error` or `RateLimited`) is returned; only if there was none is the outcome `NotFound`
- Circuit-broken or unavailable providers skipped
- Providers whose identifier requirements aren't met are skipped

#### Mergeable (collect-all)
Types where multiple providers contribute complementary data. The chain calls **every** eligible provider concurrently and collects all `Success` results. A type-specific `ResultMerger` then combines them:

| Merger | Type(s) | Strategy |
|--------|---------|----------|
| `GenreMerger` | GENRE | Normalizes tags, deduplicates, sums confidence (capped 1.0), merges sources; curated genres rank ahead of community tags |
| `ArtworkMerger` | All 8 artwork types | Highest-confidence as primary, others as `alternatives` |
| `SimilarArtistMerger` | SIMILAR_ARTISTS | Deduplicates by name, sums matchScores, merges sources |
| `SimilarTrackMerger` | SIMILAR_TRACKS | Deduplicates by name, sums matchScores, merges sources |
| `TopTrackMerger` | ARTIST_TOP_TRACKS | Deduplicates by MBID or title, sums listen counts |

Example merged genre result: `alternative rock (0.70, [musicbrainz, lastfm])` — higher confidence when multiple providers agree.

#### Composite (synthesize from sub-types)
Types that are synthesized from other resolved types rather than fetched from a provider:

| Synthesizer | Type | Dependencies | Strategy |
|-------------|------|-------------|----------|
| `TimelineSynthesizer` | ARTIST_TIMELINE | ARTIST_DISCOGRAPHY + BAND_MEMBERS | Extracts chronological events (formed, albums, member changes) from identity metadata + sub-type results |
| `GenreAffinityMatcher` | GENRE_DISCOVERY | GENRE | Looks up each input genre tag in a static taxonomy (189 relationships across 12 genre families), scores neighbors by `inputConfidence * relationshipWeight` |

The engine resolves dependencies first (standard rules), then passes results + identity metadata to the synthesizer. Sub-types are excluded from returned results unless the caller explicitly requested them.

### Step 5: Confidence Filtering

Each provider returns a confidence score (0.0–1.0):

| Score | Meaning | Example |
|-------|---------|---------|
| 1.0 | Exact ID lookup | MusicBrainz by MBID, CAA by MBID |
| 0.95 | Authoritative source | Wikipedia bio, Wikidata properties |
| 0.80 | Good fuzzy match | Deezer search with artist confirmation |
| 0.60 | Weak fuzzy match | iTunes search |
| < 0.50 | Filtered out by default | — |

Results below `config.minConfidence` (default 0.5) are converted to NotFound. Per-provider confidence overrides let you tune thresholds at runtime without code changes.

### Step 6: Catalog Filtering

For recommendation types only (SIMILAR_ARTISTS, SIMILAR_ALBUMS, ARTIST_RADIO, ARTIST_RADIO_DISCOVERY, SIMILAR_TRACKS, ARTIST_TOP_TRACKS). When a `CatalogProvider` is configured, the engine checks which recommended items are available in the consumer's music catalog:

- **AVAILABLE_ONLY** — remove unavailable items; NotFound if none remain
- **AVAILABLE_FIRST** — reorder: available items first, then unavailable
- **UNFILTERED** (default) — no filtering

This lets a music player show only recommendations the user can actually play.

### Step 7: Identity Model

Two independent facts describe an `enrich()` call, never one merged value:

- `EnrichmentResults.identity.status: CanonicalStatus` — the MusicBrainz canonical resolution
  outcome for this call, set exactly once. Never `null`: every reason resolution did not run has
  its own explicit state, so a consumer can never mistake "not attempted" for "confident".
- `EnrichmentResult.Success.provenance: LookupProvenance` — how that specific provider selected
  the entity behind its own result. This describes that provider's own lookup, not whether
  MusicBrainz agreed.

| `CanonicalStatus` | Meaning |
|---|---|
| `RESOLVED` | MusicBrainz confirmed the entity. `identity.matchScore` (0–100) indicates match quality. |
| `AMBIGUOUS` | MusicBrainz found no confident match, but offered candidates. See `identity.suggestions`. |
| `UNRESOLVED` | MusicBrainz searched and found neither a match nor candidates. |
| `FAILED` | The identity provider errored (usually transient); a retry may resolve. |
| `NOT_ATTEMPTED_DISABLED` | `EnrichmentConfig.enableIdentityResolution` is `false`. |
| `NOT_ATTEMPTED_NOT_REQUIRED` | The request already carried every identifier the requested types needed. |
| `NOT_ATTEMPTED_CACHE_HIT` | Every requested type was served from cache; no live attempt ran this call. |
| `NOT_ATTEMPTED_NO_PROVIDER` | Resolution was needed, but no identity provider is registered. |

| `LookupProvenance` | Meaning |
|---|---|
| `CANONICAL_ID` | Looked up directly by a MusicBrainz canonical id. |
| `PROVIDER_NATIVE_ID` | Looked up directly by a provider-native id supplied on the request. |
| `EXACT_NAME` | Selected by a name search MusicBrainz canonically confirmed this call. |
| `QUALIFIER_FALLBACK_NAME` | Selected after normalization or qualifier-fallback stripping. |
| `FUZZY_NAME` | Selected by an unverified fuzzy name search; MusicBrainz did not confirm this call. |
| `CACHE` | Served from cache by an implementation that could not recover the original provenance. |

A consumer deciding how much to trust results reads both: `status == RESOLVED` with a high
`matchScore` is confident; any `AMBIGUOUS`/`UNRESOLVED`/`FAILED` status means every result this
call produced is a fuzzy or ambiguous guess, whatever `provenance` an individual result carries.

### Step 8: Cache Store

No fresh result — success or `NotFound` — is cached for a call whose canonical status is
`AMBIGUOUS`, `UNRESOLVED`, or `FAILED`: the entry would read back as a cache hit indistinguishable
from a confident one, losing the ambiguity or outage. A `NotFound` is also never negative-cached
when its own chain skipped a provider for a missing identifier, resolved identity or not — a
provider that was never asked cannot speak for "nothing found". Successful results reached under
`RESOLVED` (or any `NOT_ATTEMPTED_*` status) are cached with per-type TTLs, and a cache hit's
`Success.provenance` always replays the original live lookup's value rather than a generic `CACHE`:
- Artwork: 30–90 days (photos 30d, album art 90d)
- Genres/labels/metadata: 90–365 days
- Popularity/stats: 7 days
- Recommendations: 7–30 days
- Credits/members: 30 days
- Editions/tracks: 365 days (rarely change)

**Cache aliasing:** When identity resolution discovered a new MBID (not pre-provided in the request), the result is also cached under the name-only key. This means future name-only lookups find the MBID-resolved data without re-running identity resolution.

---

## The 11 Providers

| Provider | Auth | What it provides | Role |
|----------|------|-----------------|------|
| **MusicBrainz** | None (1 req/sec) | Identity, genres, labels, dates, members, discography, tracks, links, credits, editions | Identity backbone + primary for most metadata |
| **Cover Art Archive** | None | Album front/back/booklet art, CD art (multiple sizes) | Primary artwork |
| **Wikidata** | None | Artist photo, country, official website | Structured data supplement |
| **Wikipedia** | None | Artist bio, supplemental photos | Text content |
| **LRCLIB** | None | Synced + plain lyrics | Only lyrics source |
| **Deezer** | None | Album art, artist photos, discography, tracklists, album metadata, similar artists/tracks, artist radio, top tracks, similar albums, track previews | Fallback metadata + primary radio/similar albums |
| **iTunes** | None (rate sensitive) | Album art, album metadata, tracklists, discography | Tertiary fallback |
| **ListenBrainz** | None | Artist/track popularity, discography, top tracks, radio discovery | Listening-based stats + community radio (token for radio) |
| **Last.fm** | API key | Similar artists/tracks, genres, bios, popularity, album metadata, top tracks | Social/scrobble data |
| **Fanart.tv** | API key | Artist photos/backgrounds/logos/banners, CD art, album art | High-quality fan artwork |
| **Discogs** | Token | Artist photos, album art, labels, release types, band members, album metadata, credits, editions | Physical release metadata |

8 of 11 providers work without any API keys. The 3 key-requiring providers (Last.fm, Fanart.tv, Discogs) gracefully degrade — their types are served by other providers at lower priority.

---

## The 36 Enrichment Types

### Artwork (8 types — all mergeable)
| Type | Providers (by priority) | Notes |
|------|------------------------|-------|
| ALBUM_ART | CAA(100), Deezer(50), iTunes(40), Fanart.tv(30), Discogs(20) | Multi-size + alternatives from all sources |
| ARTIST_PHOTO | Wikidata(100), Fanart.tv(80), Deezer(60), Discogs(40), Wikipedia(30) | 5 sources, merged via ArtworkMerger |
| ARTIST_BACKGROUND | Fanart.tv(100) | Requires key + MBID |
| ARTIST_LOGO | Fanart.tv(100) | Requires key + MBID |
| CD_ART | Fanart.tv(100), CAA(50) | 2 sources |
| ARTIST_BANNER | Fanart.tv(100) | Requires key + MBID |
| ALBUM_ART_BACK | CAA(100) | Via JSON metadata endpoint |
| ALBUM_BOOKLET | CAA(100) | Via JSON metadata endpoint |

`ArtworkMerger` is registered for **ALBUM_ART and ARTIST_PHOTO only** — the two types with more than
two sources. For those, the highest-confidence image becomes primary and the rest become
`alternatives`, so consumers get every available image from every provider in one result. The other
six artwork types resolve first-wins down the priority chain, including CD_ART: Fanart.tv answers it
when a key is present, and CAA is a fallback, not a second entry in the same result.

### Metadata (6 types)
| Type | Providers (by priority) | Notes |
|------|------------------------|-------|
| GENRE | MusicBrainz(100), Last.fm(100) | **Mergeable** — GenreTag with confidence, sources and `curated` (MusicBrainz's controlled vocabulary vs free-text tags; `null` where nobody could tell — an entry cached before the marking, or tags read off a search hit — which reads as a cache miss and refetches). Only these two serve the type; `genres()`/`genreTags()` additionally fall back to genre strings carried inside ALBUM_METADATA, which Discogs and iTunes also populate |
| LABEL | MusicBrainz(100), Discogs(50) | |
| RELEASE_DATE | MusicBrainz(100) | |
| RELEASE_TYPE | MusicBrainz(100), Discogs(50) | |
| COUNTRY | MusicBrainz(100), Wikidata(50) | |
| ALBUM_METADATA | Deezer(50), Discogs(40), Last.fm(40), iTunes(30) | Community ratings, barcode, etc. |

### Text (4 types)
| Type | Providers (by priority) | Notes |
|------|------------------------|-------|
| ARTIST_BIO | Wikipedia(100), Last.fm(50) | |
| LYRICS_SYNCED | LRCLIB(100) | Timestamped lines |
| LYRICS_PLAIN | LRCLIB(100) | Plain text |
| ALBUM_DESCRIPTION | Wikipedia(100), Last.fm(50) | Wikipedia extract, Last.fm `wiki` block |

### Relationships (5 types)
| Type | Providers (by priority) | Notes |
|------|------------------------|-------|
| SIMILAR_ARTISTS | Last.fm(100), Deezer(30) | **Mergeable** — deduplicates, sums matchScores |
| SIMILAR_TRACKS | Last.fm(100), Deezer(50) | **Mergeable** — deduplicates, sums matchScores |
| BAND_MEMBERS | MusicBrainz(100), Discogs(50) | From artist-rels |
| ARTIST_LINKS | MusicBrainz(100), Wikidata(50) | All URL relation types; Wikidata contributes P856 only |
| CREDITS | MusicBrainz(100), Discogs(50) | Recording rels + extraartists, roleCategory grouping |

### Additional Data (4 types)
| Type | Providers (by priority) | Notes |
|------|------------------------|-------|
| ARTIST_DISCOGRAPHY | MusicBrainz(100), Deezer(50), ListenBrainz(50), iTunes(30) | 4 providers, 3 answering — ListenBrainz's route is disabled upstream, see [providers.md](providers.md) § Routes disabled upstream |
| ALBUM_TRACKS | MusicBrainz(100), Deezer(50), iTunes(30) | 3 providers |
| RELEASE_EDITIONS | MusicBrainz(100), Discogs(50) | Release-group releases + master versions |
| TRACK_METADATA | MusicBrainz(100), Deezer(70), LRCLIB(40) | Duration, album title, disambiguation — all off responses already fetched |

### Statistics (2 types)
| Type | Providers (by priority) | Notes |
|------|------------------------|-------|
| ARTIST_POPULARITY | Last.fm(100), ListenBrainz(100), MusicBrainz(20) | **Mergeable** — each source's claim kept in its own unit as a `PopularitySignal`, never summed; MusicBrainz contributes its community rating |
| TRACK_POPULARITY | Last.fm(100), ListenBrainz(50), MusicBrainz(20) | **Mergeable** — same shape |

### Recommendations (5 types)
| Type | Providers (by priority) | Notes |
|------|------------------------|-------|
| ARTIST_RADIO | Deezer(100) | Tracks for a "radio station" seeded by artist |
| ARTIST_RADIO_DISCOVERY | ListenBrainz(100) | Community-driven radio via LB Radio; requires `listenBrainzToken`. Its route is disabled upstream — see [providers.md](providers.md) § Routes disabled upstream |
| ARTIST_TOP_TRACKS | Last.fm(100), ListenBrainz(100), Deezer(50) | **Mergeable** — deduplicates, sums listen counts. ListenBrainz's route is disabled upstream, so two of the three answer today |
| SIMILAR_ALBUMS | Deezer(100) | **Artist-derived** — Deezer has no album-similarity endpoint, so this is albums by artists related to the seed *artist*, era-weighted (re-ranked, nothing dropped). Two albums by one artist give near-identical lists |
| GENRE_DISCOVERY | GenreAffinityMatcher | **Composite** — taxonomy lookup from resolved GENRE tags |

### Preview (1 type)
| Type | Providers (by priority) | Notes |
|------|------------------------|-------|
| TRACK_PREVIEW | Deezer(100) | 30-second MP3 preview URL; on-demand (not in default types) |

### Composite (1 type)
| Type | Dependencies | Notes |
|------|-------------|-------|
| ARTIST_TIMELINE | ARTIST_DISCOGRAPHY + BAND_MEMBERS | Chronological events: formed, albums, member changes |

---

## Resilience

### Rate Limiting
Each host has its own `RateLimiter`, which delays requests to stay within bounds. The intervals and the basis for each are in [providers.md](providers.md) §Rate limiting.

### Circuit Breaker
Per-provider. Tracks consecutive failures:
- **CLOSED** — normal, requests pass through
- **OPEN** — 5+ consecutive failures, rejects instantly for 60 seconds
- **HALF_OPEN** — after cooldown, one test request allowed

Prevents hammering a down provider and slowing the entire pipeline.

### Graceful Degradation
- Missing API key → provider returns `isAvailable = false` → skipped in chain
- Provider failure → chain tries next provider at lower priority
- Timeout → returns partial results (whatever finished within `enrichTimeoutMs`)
- Individual type failure → other types still resolve
- Identity resolution found no match → results continue under `CanonicalStatus.UNRESOLVED`/`AMBIGUOUS`
- Identity provider errored → results continue under `CanonicalStatus.FAILED`, uncached so a retry can heal
- Catalog provider unavailable → recommendations returned unfiltered
