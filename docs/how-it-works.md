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

### Three API Tiers

musicmeta offers three levels of access — use whichever fits your use case:

| Tier | Entry Point | Best For |
|------|------------|----------|
| **1. Profiles** | `engine.artistProfile()` | Structured result, sensible defaults, zero casting |
| **2. Accessors** | `results.albumArt()`, `results.genres()` | Per-type data without casting, custom type sets |
| **3. Raw Map** | `results.raw[type]` | Error diagnostics, full control, custom logic |

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
              │   suggestions → short-circuit: all types → NotFound
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
│ 7. Identity Stamp          │── mark RESOLVED/BEST_EFFORT + score
└─────────────┬──────────────┘
              │
              ▼
┌────────────────────────────┐
│ 8. Cache Store + Alias     │── save with TTL + name alias
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

When needed, MusicBrainz searches by title/artist and returns:
- `musicBrainzId` (MBID) — the universal music identifier
- `musicBrainzReleaseGroupId` — for album editions
- `wikidataId` — extracted from MusicBrainz URL relations
- `wikipediaTitle` — extracted from MusicBrainz URL relations
- Provider-specific IDs (Discogs, etc.) — stored in the `extra` map

These identifiers are merged into the request via `request.withIdentifiers(mergedIds)`. All downstream providers then use these IDs for precise lookups instead of fuzzy search.

**Suggestions short-circuit:** If MusicBrainz can't find an exact match but has near-miss candidates, all uncached types are immediately set to `NotFound` with the suggestion list attached. The consumer can present these as "Did you mean?" choices and re-enrich with the selected candidate.

### Step 4: Concurrent Type Resolution

All requested types resolve **concurrently** via `coroutineScope { async {} }`. Three resolution modes:

#### Standard (short-circuit)
Most types. A `ProviderChain` tries providers in priority order:
- Priority 100 (primary) → 50 (fallback) → 30 (tertiary)
- First `Success` wins, remaining providers skipped
- `NotFound` falls through to next provider
- Circuit-broken or unavailable providers skipped
- Providers whose identifier requirements aren't met are skipped

#### Mergeable (collect-all)
Types where multiple providers contribute complementary data. The chain calls **every** eligible provider concurrently and collects all `Success` results. A type-specific `ResultMerger` then combines them:

| Merger | Type(s) | Strategy |
|--------|---------|----------|
| `GenreMerger` | GENRE | Normalizes tags, deduplicates, sums confidence (capped 1.0), merges sources |
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
| `GenreAffinityMatcher` | GENRE_DISCOVERY | GENRE | Looks up each input genre tag in a static taxonomy (~70 relationships across 12 genre families), scores neighbors by `inputConfidence * relationshipWeight` |

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

For recommendation types only (SIMILAR_ARTISTS, SIMILAR_ALBUMS, ARTIST_RADIO, SIMILAR_TRACKS, ARTIST_TOP_TRACKS). When a `CatalogProvider` is configured, the engine checks which recommended items are available in the consumer's music catalog:

- **AVAILABLE_ONLY** — remove unavailable items; NotFound if none remain
- **AVAILABLE_FIRST** — reorder: available items first, then unavailable
- **UNFILTERED** (default) — no filtering

This lets a music player show only recommendations the user can actually play.

### Step 7: Identity Match Stamping

Each Success result is stamped with identity resolution metadata:

| `identityMatch` | Meaning |
|-----------------|---------|
| `RESOLVED` | MusicBrainz found a confident match. `identityMatchScore` (0–100) indicates match quality. |
| `BEST_EFFORT` | Identity resolution failed. Results came from unverified fuzzy searches. |
| `null` | Identity resolution wasn't needed (MBID pre-provided, cached, or disabled). |

This lets consumers decide how much to trust results — a `RESOLVED` match with score 95 is much more reliable than `BEST_EFFORT`.

### Step 8: Cache Store

Successful results are cached with per-type TTLs:
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
| **Wikidata** | None | Artist photo, country | Structured data supplement |
| **Wikipedia** | None | Artist bio, supplemental photos | Text content |
| **LRCLIB** | None | Synced + plain lyrics | Only lyrics source |
| **Deezer** | None | Album art, artist photos, discography, tracklists, album metadata, similar artists/tracks, artist radio, top tracks, similar albums | Fallback metadata + primary radio/similar albums |
| **iTunes** | None (rate sensitive) | Album art, album metadata, tracklists, discography | Tertiary fallback |
| **ListenBrainz** | None | Artist/track popularity, discography, similar artists, top tracks | Listening-based stats |
| **Last.fm** | API key | Similar artists/tracks, genres, bios, popularity, album metadata, top tracks | Social/scrobble data |
| **Fanart.tv** | API key | Artist photos/backgrounds/logos/banners, CD art, album art | High-quality fan artwork |
| **Discogs** | Token | Artist photos, album art, labels, release types, band members, album metadata, credits, editions | Physical release metadata |

8 of 11 providers work without any API keys. The 3 key-requiring providers (Last.fm, Fanart.tv, Discogs) gracefully degrade — their types are served by other providers at lower priority.

---

## The 32 Enrichment Types

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

All artwork types use `ArtworkMerger`: highest-confidence image becomes primary, others become `alternatives` — consumers get every available image from every provider in one result.

### Metadata (6 types)
| Type | Providers (by priority) | Notes |
|------|------------------------|-------|
| GENRE | MusicBrainz(100), Last.fm(100) | **Mergeable** — GenreTag with confidence + sources |
| LABEL | MusicBrainz(100), Discogs(50) | |
| RELEASE_DATE | MusicBrainz(100) | |
| RELEASE_TYPE | MusicBrainz(100), Discogs(50) | |
| COUNTRY | MusicBrainz(100), Wikidata(50) | |
| ALBUM_METADATA | Deezer(50), Discogs(40), Last.fm(40), iTunes(30) | Community ratings, barcode, etc. |

### Text (3 types)
| Type | Providers (by priority) | Notes |
|------|------------------------|-------|
| ARTIST_BIO | Wikipedia(100), Last.fm(50) | |
| LYRICS_SYNCED | LRCLIB(100) | Timestamped lines |
| LYRICS_PLAIN | LRCLIB(100) | Plain text |

### Relationships (5 types)
| Type | Providers (by priority) | Notes |
|------|------------------------|-------|
| SIMILAR_ARTISTS | Last.fm(100), ListenBrainz(50), Deezer(30) | **Mergeable** — deduplicates, sums matchScores |
| SIMILAR_TRACKS | Last.fm(100), Deezer(50) | **Mergeable** — deduplicates, sums matchScores |
| BAND_MEMBERS | MusicBrainz(100), Discogs(50) | From artist-rels |
| ARTIST_LINKS | MusicBrainz(100) | All URL relation types |
| CREDITS | MusicBrainz(100), Discogs(50) | Recording rels + extraartists, roleCategory grouping |

### Additional Data (3 types)
| Type | Providers (by priority) | Notes |
|------|------------------------|-------|
| ARTIST_DISCOGRAPHY | MusicBrainz(100), Deezer(50), ListenBrainz(50), iTunes(30) | 4 providers |
| ALBUM_TRACKS | MusicBrainz(100), Deezer(50), iTunes(30) | 3 providers |
| RELEASE_EDITIONS | MusicBrainz(100), Discogs(50) | Release-group releases + master versions |

### Statistics (2 types)
| Type | Providers (by priority) | Notes |
|------|------------------------|-------|
| ARTIST_POPULARITY | Last.fm(100), ListenBrainz(100) | Listeners + play counts |
| TRACK_POPULARITY | Last.fm(100), ListenBrainz(50) | Per-track stats |

### Recommendations (4 types)
| Type | Providers (by priority) | Notes |
|------|------------------------|-------|
| ARTIST_RADIO | Deezer(100) | Tracks for a "radio station" seeded by artist |
| ARTIST_TOP_TRACKS | Last.fm(100), ListenBrainz(100), Deezer(50) | **Mergeable** — deduplicates, sums listen counts |
| SIMILAR_ALBUMS | Deezer(100) | Albums similar to the queried album |
| GENRE_DISCOVERY | GenreAffinityMatcher | **Composite** — taxonomy lookup from resolved GENRE tags |

### Composite (1 type)
| Type | Dependencies | Notes |
|------|-------------|-------|
| ARTIST_TIMELINE | ARTIST_DISCOGRAPHY + BAND_MEMBERS | Chronological events: formed, albums, member changes |

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
- Timeout → returns partial results (whatever finished within `enrichTimeoutMs`)
- Individual type failure → other types still resolve
- Identity resolution failure → results continue with `BEST_EFFORT` match quality
- Catalog provider unavailable → recommendations returned unfiltered

---

## Consumer Patterns

### Tier 1: Profiles (simplest)
```kotlin
// One call, sensible defaults, zero casting
val artist = engine.artistProfile("Radiohead")

println(artist.photo?.url)
println(artist.bio?.text)
println(artist.genres.map { "${it.name} (${it.confidence})" })
println(artist.similarArtists?.artists?.map { it.name })
println(artist.topTracks?.tracks?.map { it.title })

// Albums and tracks have profiles too
val album = engine.albumProfile("OK Computer", "Radiohead")
println(album.artwork?.url)
println(album.tracks)
println(album.genres)

val track = engine.trackProfile("Paranoid Android", "Radiohead")
println(track.lyrics?.syncedLyrics)
println(track.credits?.credits?.groupBy { it.roleCategory })
```

### Tier 2: Named Accessors (mid-level)
```kotlin
// Custom type set, type-safe accessors, no casting
val results = engine.enrich(
    EnrichmentRequest.forAlbum("OK Computer", "Radiohead"),
    setOf(EnrichmentType.ALBUM_ART, EnrichmentType.GENRE, EnrichmentType.ALBUM_TRACKS),
)

println(results.albumArt()?.url)
println(results.genres())           // List<String> with GENRE→ALBUM_METADATA fallback
println(results.genreTags())        // List<GenreTag> with confidence + sources
println(results.lyrics())           // synced → plain fallback
```

### Tier 3: Raw Map (full control)
```kotlin
results.raw.forEach { (type, result) ->
    when (result) {
        is EnrichmentResult.Success -> {
            println("${type.name}: ${result.provider} (conf=${result.confidence})")
            println("  identityMatch=${result.identityMatch}, score=${result.identityMatchScore}")
        }
        is EnrichmentResult.NotFound -> {
            result.suggestions?.let { println("  Did you mean: ${it.map { s -> s.title }}") }
        }
        is EnrichmentResult.Error -> println("${type.name}: Error(${result.errorKind})")
        is EnrichmentResult.RateLimited -> println("${type.name}: retry after ${result.retryAfterMs}ms")
    }
}
```

### Search + Enrich: Manual Disambiguation
```kotlin
val candidates = engine.search(EnrichmentRequest.forAlbum("Homesick", ""), limit = 10)
// User picks the right match
val chosen = candidates[2]
val album = engine.albumProfile(chosen)  // uses candidate's identifiers
```

### Identity Match Handling
```kotlin
val artist = engine.artistProfile("Radiohed")  // typo

when (artist.identityMatch) {
    IdentityMatch.RESOLVED -> {
        // Confident match — identityMatchScore is 0-100
        println("Match score: ${artist.identityMatchScore}")
    }
    IdentityMatch.SUGGESTIONS -> {
        // Near-miss candidates available
        println("Did you mean: ${artist.suggestions.map { it.title }}")
        // Re-enrich with the right one
        val corrected = engine.artistProfile(artist.suggestions.first())
    }
    IdentityMatch.BEST_EFFORT -> {
        // Identity failed, results from unverified fuzzy searches
        println("Results may be inaccurate")
    }
    null -> {
        // Identity resolution wasn't needed (MBID pre-provided or cached)
    }
}
```

### Force Refresh + Cache Invalidation
```kotlin
// Bypass cache for a single request
val fresh = engine.artistProfile("Radiohead", forceRefresh = true)

// Invalidate specific type
engine.invalidate(EnrichmentRequest.forArtist("Radiohead"), EnrichmentType.ARTIST_PHOTO)

// Invalidate all cached data for an entity
engine.invalidate(EnrichmentRequest.forArtist("Radiohead"))
```

---

## Architecture

```
musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/
├── EnrichmentEngine.kt              # Public interface + Builder
├── EnrichmentRequest.kt             # ForAlbum / ForArtist / ForTrack + default type sets
├── EnrichmentResult.kt              # Success / NotFound / Error / RateLimited
├── EnrichmentResults.kt             # Batch wrapper + 19 named accessors
├── EnrichmentData.kt                # 18 sealed subtypes (Artwork, Credits, TopTracks, etc.)
├── EnrichmentType.kt                # 32 enum values with TTLs
├── EnrichmentConfig.kt              # Configuration (confidence, timeouts, overrides)
├── EnrichmentProvider.kt            # Provider interface + ProviderCapability
├── EnrichmentCache.kt               # Cache interface
├── IdentityResolution.kt            # Identity outcome (match, score, suggestions)
├── CatalogProvider.kt               # Catalog availability interface
├── ArtistProfile.kt                 # Tier 1 structured view for artists
├── AlbumProfile.kt                  # Tier 1 structured view for albums
├── TrackProfile.kt                  # Tier 1 structured view for tracks
├── EnrichmentEngineExtensions.kt    # Profile builder extension functions
├── EnrichmentLogger.kt              # Logging interface
├── StringExtensions.kt              # Utility functions
├── engine/
│   ├── DefaultEnrichmentEngine.kt   # Pipeline orchestration
│   ├── ProviderRegistry.kt          # Registers providers, builds chains
│   ├── ProviderChain.kt             # resolve() + resolveAll()
│   ├── EntityKey.kt                 # Cache key computation
│   ├── IdentityHelper.kt            # Identity resolution + stamping logic
│   ├── ConfidenceCalculator.kt      # Standardized scoring
│   ├── ArtistMatcher.kt             # Fuzzy name matching
│   ├── CatalogFilter.kt             # Recommendation filtering
│   ├── ResultMerger.kt              # Merger interface
│   ├── GenreMerger.kt               # Multi-provider genre merge
│   ├── ArtworkMerger.kt             # Multi-provider artwork merge
│   ├── SimilarArtistMerger.kt       # Multi-provider similar artist merge
│   ├── SimilarTrackMerger.kt        # Multi-provider similar track merge
│   ├── TopTrackMerger.kt            # Multi-provider top track merge
│   ├── CompositeSynthesizer.kt      # Synthesizer interface
│   ├── TimelineSynthesizer.kt       # Composite timeline synthesis
│   ├── GenreAffinityMatcher.kt      # Composite genre discovery synthesis
│   └── GenreTaxonomy.kt             # Static genre relationship data
├── http/
│   ├── HttpClient.kt                # Abstract HTTP interface
│   ├── DefaultHttpClient.kt         # java.net implementation
│   ├── HttpResponse.kt              # Response model
│   ├── HttpResult.kt                # Ok / ClientError / ServerError / RateLimited / NetworkError
│   ├── RateLimiter.kt               # Per-provider delay
│   └── CircuitBreaker.kt            # Failure tracking
├── cache/
│   └── InMemoryEnrichmentCache.kt   # LRU cache with TTL
└── provider/
    ├── musicbrainz/                  # Identity backbone + primary metadata
    │   ├── MusicBrainzApi.kt
    │   ├── MusicBrainzModels.kt
    │   ├── MusicBrainzParser.kt
    │   ├── MusicBrainzMapper.kt
    │   ├── MusicBrainzEnricher.kt
    │   ├── MusicBrainzCreditParser.kt
    │   └── MusicBrainzProvider.kt
    ├── coverartarchive/              # Same 4-file pattern (Api/Models/Mapper/Provider)
    ├── wikidata/
    ├── wikipedia/
    ├── lrclib/
    ├── deezer/                       # + SimilarAlbumsProvider.kt
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
