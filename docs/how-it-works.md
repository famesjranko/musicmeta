# How musicmeta Works

> A complete guide to the enrichment pipeline — from `enrich()` call to results.

## What It Does

musicmeta is a drop-in library that gives any music app **everything** about an artist, album, or track from a single call. You pass a title and artist name, and get back artwork, genres, bios, lyrics, credits, similar artists, popularity stats, and more — aggregated from 11 public APIs behind the scenes.

```kotlin
val results = engine.enrich(
    EnrichmentRequest.forAlbum("OK Computer", "Radiohead"),
    setOf(EnrichmentType.ALBUM_ART, EnrichmentType.GENRE, EnrichmentType.ALBUM_TRACKS),
)

val art = (results[EnrichmentType.ALBUM_ART] as? EnrichmentResult.Success)
    ?.data as? EnrichmentData.Artwork
println(art?.url)  // https://archive.org/download/mbid-...
```

The consumer never needs to know which APIs exist, how they authenticate, or how to correlate identifiers across services.

---

## The Pipeline

```
enrich(request, types)
    │
    ▼
┌─────────────┐
│ Cache Check  │──── hit ──→ return cached
└──────┬──────┘
       │ miss
       ▼
┌──────────────────┐
│ Identity Resolve  │  MusicBrainz searches title/artist
│ (MusicBrainz)    │  → MBID, Wikidata ID, Wikipedia title
└──────┬───────────┘  → enriches request with resolved IDs
       │
       ▼
┌──────────────────────────────────────────────┐
│ Concurrent Type Resolution (fan-out)         │
│                                              │
│  Standard ──→ chain.resolve() (first wins)   │
│  Mergeable ──→ chain.resolveAll() (all win)  │
│  Composite ──→ resolve deps → synthesize     │
└──────┬───────────────────────────────────────┘
       │
       ▼
┌──────────────────┐
│ Confidence Filter │  drop results below threshold
└──────┬───────────┘
       │
       ▼
┌─────────────┐
│ Cache Store  │  save successful results with TTL
└──────┬──────┘
       │
       ▼
  return Map<EnrichmentType, EnrichmentResult>
```

### Step 1: Cache Check

Before any API calls, the engine checks the in-memory cache for each requested type. Cache keys are based on the request identity (MBID if available, otherwise title+artist). Hits are returned immediately. Only cache misses proceed to resolution.

### Step 2: Identity Resolution

The key insight: most providers need identifiers (MusicBrainz ID, Wikidata ID) for precise lookups, but the consumer only has a title and artist name.

**MusicBrainz acts as the identity backbone.** It searches by title/artist and returns:
- `musicBrainzId` (MBID) — the universal music identifier
- `musicBrainzReleaseGroupId` — for album editions
- `wikidataId` — extracted from MusicBrainz URL relations
- `wikipediaTitle` — extracted from MusicBrainz URL relations
- `discogsReleaseId` / `discogsMasterId` — stored in the `extra` map

These identifiers are merged into the request via `request.withIdentifiers(mergedIds)`. All downstream providers then use these IDs for precise lookups instead of fuzzy search.

### Step 3: Concurrent Type Resolution

All requested types resolve **concurrently** via `coroutineScope { async {} }`. Three resolution modes:

#### Standard (short-circuit)
Most types. A `ProviderChain` tries providers in priority order:
- Priority 100 (primary) → 50 (fallback) → 30 (tertiary)
- First `Success` wins, remaining providers skipped
- `NotFound` falls through to next provider
- Circuit-broken or unavailable providers skipped

#### Mergeable (collect-all)
Currently just `GENRE`. The chain calls **every** provider and collects all `Success` results. `GenreMerger` then:
1. Normalizes tag names (lowercase, alias mapping)
2. Deduplicates by normalized name
3. Sums confidence from each source (capped at 1.0)
4. Sorts by confidence descending

Result: `alternative rock(0.70, [musicbrainz, lastfm])` — higher confidence when multiple providers agree.

#### Composite (synthesize from sub-types)
Currently just `ARTIST_TIMELINE`. The engine:
1. Identifies dependencies (ARTIST_DISCOGRAPHY + BAND_MEMBERS)
2. Resolves those sub-types first (standard rules)
3. Passes results + identity metadata to a synthesizer
4. `TimelineSynthesizer` produces chronological events (formed, albums, member changes)
5. Sub-types excluded from returned results unless caller explicitly requested them

### Step 4: Confidence Filtering

Each provider returns a confidence score (0.0–1.0):

| Score | Meaning | Example |
|-------|---------|---------|
| 1.0 | Exact ID lookup | MusicBrainz by MBID, CAA by MBID |
| 0.95 | Authoritative source | Wikipedia bio, Wikidata properties |
| 0.80 | Good fuzzy match | Deezer search with artist confirmation |
| 0.60 | Weak fuzzy match | iTunes search |
| < 0.50 | Filtered out by default | — |

Results below `config.minConfidence` (default 0.5) are treated as NotFound.

### Step 5: Cache Store

Successful results are cached with per-type TTLs:
- Artwork: 90 days
- Genres/labels/metadata: 90–365 days
- Popularity stats: 7 days
- Credits: 30 days
- Editions: 365 days (rarely change)

---

## The 11 Providers

| Provider | Auth | What it provides | Role |
|----------|------|-----------------|------|
| **MusicBrainz** | None (1 req/sec) | Identity, genres, labels, dates, members, discography, tracks, links, credits, editions | Identity backbone + primary for most metadata |
| **Cover Art Archive** | None | Album front/back/booklet art with multiple sizes | Primary artwork |
| **Wikidata** | None | Artist photo, country, birth/death dates, occupation | Structured data supplement |
| **Wikipedia** | None | Artist bio, supplemental photos | Text content |
| **LRCLIB** | None | Synced + plain lyrics | Only lyrics source |
| **Deezer** | None | Album art, discography, tracklists, album metadata | Fallback for art + metadata |
| **iTunes** | None (rate sensitive) | Album art, album metadata, tracklists, discography | Tertiary fallback |
| **ListenBrainz** | None | Artist/track popularity, discography, similar artists | Listening-based stats |
| **Last.fm** | API key | Similar artists/tracks, genres, bios, popularity, album metadata | Social/scrobble data |
| **Fanart.tv** | API key | Artist photos/backgrounds/logos/banners, CD art, album art | High-quality fan artwork |
| **Discogs** | Token | Labels, release types, band members, credits, editions, community data | Physical release metadata |

8 of 11 providers work without any API keys. The 3 key-requiring providers (Last.fm, Fanart.tv, Discogs) gracefully degrade — their types are served by other providers at lower priority.

---

## The 28 Enrichment Types

### Artwork (8 types)
| Type | Providers | Notes |
|------|-----------|-------|
| ALBUM_ART | CAA, Fanart.tv, Deezer, iTunes, Discogs | Multi-size support |
| ALBUM_ART_BACK | CAA | Via JSON metadata endpoint |
| ALBUM_BOOKLET | CAA | Via JSON metadata endpoint |
| ARTIST_PHOTO | Wikidata, Fanart.tv, Wikipedia | 3 sources |
| ARTIST_BACKGROUND | Fanart.tv | Requires key + MBID |
| ARTIST_LOGO | Fanart.tv | Requires key + MBID |
| ARTIST_BANNER | Fanart.tv | Requires key + MBID |
| CD_ART | Fanart.tv | Requires key + MBID |

### Metadata (9 types)
| Type | Providers | Notes |
|------|-----------|-------|
| GENRE | MusicBrainz, Last.fm | **Mergeable** — multi-provider with GenreTag confidence |
| LABEL | MusicBrainz, Discogs | |
| RELEASE_DATE | MusicBrainz | |
| RELEASE_TYPE | MusicBrainz, Discogs | |
| COUNTRY | MusicBrainz, Wikidata | |
| BAND_MEMBERS | MusicBrainz, Discogs | From artist-rels |
| ARTIST_DISCOGRAPHY | MusicBrainz, Deezer, ListenBrainz, iTunes | 4 providers |
| ALBUM_TRACKS | MusicBrainz, Deezer, iTunes | 3 providers |
| ALBUM_METADATA | Deezer, Last.fm, Discogs, iTunes | 4 providers, community ratings |

### New in v0.5.0 (3 types)
| Type | Providers | Notes |
|------|-----------|-------|
| CREDITS | MusicBrainz, Discogs | Recording rels + extraartists, roleCategory grouping |
| RELEASE_EDITIONS | MusicBrainz, Discogs | Release-group releases + master versions |
| ARTIST_TIMELINE | TimelineSynthesizer | **Composite** — auto-resolves discography + members |

### Text (3 types)
| Type | Providers | Notes |
|------|-----------|-------|
| ARTIST_BIO | Wikipedia, Last.fm | |
| LYRICS_SYNCED | LRCLIB | Timestamped lines |
| LYRICS_PLAIN | LRCLIB | Plain text |

### Relationships (3 types)
| Type | Providers | Notes |
|------|-----------|-------|
| SIMILAR_ARTISTS | Last.fm, ListenBrainz | 2 sources |
| SIMILAR_TRACKS | Last.fm | Match scores |
| ARTIST_LINKS | MusicBrainz | All URL relation types |

### Statistics (2 types)
| Type | Providers | Notes |
|------|-----------|-------|
| ARTIST_POPULARITY | Last.fm, ListenBrainz | Listeners + play counts |
| TRACK_POPULARITY | Last.fm, ListenBrainz | Per-track stats |

---

## Resilience

### Rate Limiting
Each provider has its own `RateLimiter(intervalMs)`. MusicBrainz requires 1 req/sec (we use 1.1s). Others range from 100ms to 3000ms (iTunes). The limiter delays requests to stay within bounds.

### Circuit Breaker
Per-provider. Tracks consecutive failures:
- **CLOSED** — normal, requests pass through
- **OPEN** — 5+ consecutive failures, rejects instantly for 60 seconds
- **HALF_OPEN** — after cooldown, one test request allowed

Prevents hammering a down provider and slowing the entire pipeline.

### Graceful Degradation
- Missing API key → provider returns `isAvailable = false` → skipped in chain
- Provider failure → chain tries next provider at lower priority
- Timeout → returns partial results (whatever finished)
- Individual type failure → other types still resolve

---

## Consumer Patterns

### Basic: Get everything for an artist
```kotlin
val results = engine.enrich(
    EnrichmentRequest.forArtist("Radiohead"),
    EnrichmentType.entries.toSet(),  // all types
)
// 21/28 types return data for a well-known artist
```

### Targeted: Get specific data
```kotlin
val results = engine.enrich(
    EnrichmentRequest.forTrack("Bohemian Rhapsody", "Queen", album = "A Night at the Opera"),
    setOf(EnrichmentType.LYRICS_SYNCED, EnrichmentType.CREDITS, EnrichmentType.ALBUM_ART),
)
```

### Search + Enrich: Manual disambiguation
```kotlin
val candidates = engine.search(EnrichmentRequest.forAlbum("Homesick", ""), limit = 10)
// User picks the right match
val chosen = candidates[2]
val results = engine.enrich(
    EnrichmentRequest.forAlbum(chosen.title, chosen.artist)
        .withIdentifiers(chosen.identifiers),
    setOf(EnrichmentType.ALBUM_ART, EnrichmentType.ALBUM_TRACKS),
)
```

### Result handling
```kotlin
results.forEach { (type, result) ->
    when (result) {
        is EnrichmentResult.Success -> {
            println("${type.name}: ${result.provider} (conf=${result.confidence})")
            when (val data = result.data) {
                is EnrichmentData.Artwork -> println("  ${data.url}")
                is EnrichmentData.Credits -> {
                    data.credits.groupBy { it.roleCategory }.forEach { (cat, members) ->
                        println("  [$cat] ${members.joinToString { it.name }}")
                    }
                }
                is EnrichmentData.ArtistTimeline -> {
                    data.events.forEach { println("  ${it.date} ${it.type}: ${it.description}") }
                }
                // ... other types
            }
        }
        is EnrichmentResult.NotFound -> {} // no data available
        is EnrichmentResult.Error -> println("${type.name}: Error(${result.errorKind})")
        is EnrichmentResult.RateLimited -> {} // try again later
    }
}
```

---

## Architecture

```
musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/
├── EnrichmentEngine.kt          # Public interface + Builder
├── EnrichmentRequest.kt         # ForAlbum / ForArtist / ForTrack
├── EnrichmentResult.kt          # Success / NotFound / Error / RateLimited
├── EnrichmentData.kt            # 14 sealed subtypes (Artwork, Credits, etc.)
├── EnrichmentType.kt            # 28 enum values with TTLs
├── EnrichmentConfig.kt          # Configuration (confidence, timeouts, overrides)
├── EnrichmentProvider.kt        # Provider interface
├── engine/
│   ├── DefaultEnrichmentEngine.kt  # Pipeline orchestration
│   ├── ProviderRegistry.kt         # Registers providers, builds chains
│   ├── ProviderChain.kt            # resolve() + resolveAll()
│   ├── GenreMerger.kt              # Multi-provider genre merge
│   ├── TimelineSynthesizer.kt      # Composite timeline synthesis
│   ├── ConfidenceCalculator.kt     # Standardized scoring
│   └── ArtistMatcher.kt            # Fuzzy name matching
├── http/
│   ├── HttpClient.kt               # Abstract HTTP interface
│   ├── DefaultHttpClient.kt        # java.net implementation
│   ├── HttpResult.kt               # Ok / ClientError / ServerError / RateLimited / NetworkError
│   ├── RateLimiter.kt              # Per-provider delay
│   └── CircuitBreaker.kt           # Failure tracking
├── cache/
│   └── InMemoryEnrichmentCache.kt  # LRU cache with TTL
└── provider/
    ├── musicbrainz/                 # Identity backbone + primary metadata
    │   ├── MusicBrainzApi.kt        # HTTP calls
    │   ├── MusicBrainzModels.kt     # Response DTOs
    │   ├── MusicBrainzParser.kt     # JSON → DTOs
    │   ├── MusicBrainzMapper.kt     # DTOs → EnrichmentData
    │   └── MusicBrainzProvider.kt   # EnrichmentProvider implementation
    ├── coverartarchive/             # Same 4-file pattern
    ├── wikidata/
    ├── wikipedia/
    ├── lrclib/
    ├── deezer/
    ├── itunes/
    ├── listenbrainz/
    ├── lastfm/
    ├── fanarttv/
    └── discogs/
```

Each provider follows the same pattern:
- `*Api.kt` — raw HTTP calls, returns parsed models
- `*Models.kt` — data classes for API responses
- `*Mapper.kt` — maps API models → `EnrichmentData` (pure functions)
- `*Provider.kt` — implements `EnrichmentProvider`, orchestrates Api → Mapper

This isolation means changes to the public API only touch mappers, and changes to a provider's API only touch its Api class.
