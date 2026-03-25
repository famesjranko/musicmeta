# Configuration

## EnrichmentConfig

```kotlin
val config = EnrichmentConfig(
    // Minimum confidence to accept a result (0.0-1.0). Default: 0.5.
    // ID-based lookups score 1.0, fuzzy searches 0.6-0.8.
    minConfidence = 0.6f,

    // User-Agent header for all HTTP requests.
    // MusicBrainz and Wikimedia require a descriptive value.
    userAgent = "MyApp/1.0 (contact@example.com)",

    // Auto-resolve MBIDs via MusicBrainz before fan-out. Default: true.
    enableIdentityResolution = true,

    // Overall enrichment timeout in milliseconds. Default: 30000 (30s).
    enrichTimeoutMs = 15_000,

    // Per-provider confidence overrides. Replaces the provider's hardcoded
    // confidence with this value. Key = provider ID (e.g., "deezer", "itunes").
    confidenceOverrides = mapOf(
        "deezer" to 0.85f,
        "itunes" to 0.55f,
    ),

    // Per-provider priority overrides. Higher = tried first in the chain.
    // Outer key = provider ID, inner key = enrichment type.
    priorityOverrides = mapOf(
        "deezer" to mapOf(EnrichmentType.ALBUM_ART to 90),
    ),

    // Per-type cache TTL overrides in milliseconds.
    ttlOverrides = mapOf(
        EnrichmentType.ARTIST_POPULARITY to 3L * 24 * 60 * 60 * 1000, // 3 days
        EnrichmentType.GENRE to 180L * 24 * 60 * 60 * 1000,           // 180 days
    ),

    // Max tracks for ARTIST_RADIO (Deezer supports up to 100). Default: 50.
    radioLimit = 25,

    // Discovery mode for ARTIST_RADIO_DISCOVERY (ListenBrainz LB Radio).
    // EASY stays close to the seed artist; HARD ventures into less-related territory.
    // Default: RadioDiscoveryMode.EASY
    radioDiscoveryMode = RadioDiscoveryMode.EASY,

    // Cache fallback mode. NETWORK_FIRST (default) returns errors as-is.
    // STALE_IF_ERROR serves expired cache entries when providers return Error or RateLimited.
    cacheMode = CacheMode.STALE_IF_ERROR,
)
```

---

## ApiKeyConfig

Four providers require API keys. All are free to obtain:

```kotlin
val keys = ApiKeyConfig(
    lastFmKey = "...",             // https://www.last.fm/api/account/create
    fanartTvProjectKey = "...",    // https://fanart.tv/get-an-api-key/
    discogsPersonalToken = "...",  // https://www.discogs.com/settings/developers
    listenBrainzToken = "...",     // https://listenbrainz.org/profile/  (free account)
)
```

`listenBrainzToken` unlocks `ARTIST_RADIO_DISCOVERY` (LB Radio). All other ListenBrainz endpoints — popularity, similar artists, discography — remain keyless and continue working without a token.

Pass keys to the builder. `withDefaultProviders()` conditionally registers key-requiring providers only when their key is present:

```kotlin
val engine = EnrichmentEngine.Builder()
    .withDefaultProviders()
    .apiKeys(keys)
    .config(config)
    .build()
```

Providers with missing keys report `isAvailable = false` and are skipped. Their types fall through to other providers that can supply the same data.

---

## Confidence scoring guidelines

| Score | Match type | Examples |
|-------|------------|----------|
| 1.0 | Deterministic — looked up by exact ID | Cover Art Archive by MBID, MusicBrainz direct lookup |
| 0.90–0.99 | Authoritative source, high-quality match | Wikipedia bio, Wikidata photo, LRCLIB exact match |
| 0.70–0.89 | Good fuzzy match from a large catalog | Deezer search, Last.fm tags, ListenBrainz |
| 0.50–0.69 | Weak fuzzy match, may be wrong | iTunes search, Discogs search |
| < 0.50 | Unreliable — filtered out by `minConfidence` | |

---

## Builder: withDefaultProviders() vs manual wiring

**`withDefaultProviders()`** registers all 11 providers with sensible defaults:

```kotlin
val engine = EnrichmentEngine.Builder()
    .withDefaultProviders()
    .apiKeys(ApiKeyConfig(lastFmKey = "..."))
    .config(EnrichmentConfig(userAgent = "MyApp/1.0"))
    .build()
```

**Manual wiring** gives you full control over which providers are included and how they are configured:

```kotlin
val http = DefaultHttpClient("MyApp/1.0 (contact@example.com)")
val mbRateLimiter = RateLimiter(1100)  // MusicBrainz: max 1 req/sec
val defaultRateLimiter = RateLimiter(100)

val engine = EnrichmentEngine.Builder()
    .httpClient(http)
    .addProvider(MusicBrainzProvider(http, mbRateLimiter))
    .addProvider(CoverArtArchiveProvider(http, defaultRateLimiter))
    .addProvider(WikidataProvider(http, defaultRateLimiter))
    .addProvider(DeezerProvider(http, defaultRateLimiter))
    // Omit providers you do not need
    .config(EnrichmentConfig(userAgent = "MyApp/1.0 (contact@example.com)"))
    .build()
```

---

## Logging

The engine accepts an `EnrichmentLogger` for debug and warning output:

```kotlin
val engine = EnrichmentEngine.Builder()
    .withDefaultProviders()
    .logger(object : EnrichmentLogger {
        override fun debug(tag: String, message: String) {
            println("DEBUG [$tag] $message")
        }
        override fun warn(tag: String, message: String, throwable: Throwable?) {
            System.err.println("WARN [$tag] $message")
            throwable?.printStackTrace(System.err)
        }
    })
    .build()
```

On Android, bridge to Logcat:

```kotlin
.logger(object : EnrichmentLogger {
    override fun debug(tag: String, message: String) = Log.d(tag, message)
    override fun warn(tag: String, message: String, throwable: Throwable?) = Log.w(tag, message, throwable)
})
```

The default logger is `EnrichmentLogger.NoOp` (silent).

---

## Inspecting registered providers

```kotlin
val engine = EnrichmentEngine.Builder()
    .withDefaultProviders()
    .apiKeys(ApiKeyConfig(lastFmKey = "my-key"))
    .build()

engine.getProviders().forEach { info ->
    println("${info.displayName} (${info.id})")
    println("  available: ${info.isAvailable}, requiresKey: ${info.requiresApiKey}")
    info.capabilities.forEach { cap ->
        println("  ${cap.type} priority=${cap.priority} requires=${cap.identifierRequirement}")
    }
}
```

---

## Default type sets

Each request kind has a default set of types that covers the most common use cases.

### DEFAULT_ARTIST_TYPES (16 types)

```kotlin
EnrichmentRequest.DEFAULT_ARTIST_TYPES
// GENRE, ARTIST_BIO,
// ARTIST_PHOTO, ARTIST_BACKGROUND, ARTIST_LOGO, ARTIST_BANNER,
// ARTIST_POPULARITY, SIMILAR_ARTISTS,
// BAND_MEMBERS, ARTIST_DISCOGRAPHY, ARTIST_LINKS, ARTIST_TIMELINE,
// ARTIST_RADIO, ARTIST_RADIO_DISCOVERY, ARTIST_TOP_TRACKS,
// GENRE_DISCOVERY
```

### DEFAULT_ALBUM_TYPES (14 types)

```kotlin
EnrichmentRequest.DEFAULT_ALBUM_TYPES
// ALBUM_ART, ALBUM_ART_BACK, ALBUM_BOOKLET, CD_ART,
// GENRE, LABEL, RELEASE_DATE, RELEASE_TYPE, COUNTRY, ALBUM_METADATA,
// ALBUM_TRACKS, RELEASE_EDITIONS,
// SIMILAR_ALBUMS, GENRE_DISCOVERY
```

### DEFAULT_TRACK_TYPES (8 types)

```kotlin
EnrichmentRequest.DEFAULT_TRACK_TYPES
// GENRE, LYRICS_SYNCED, LYRICS_PLAIN,
// TRACK_POPULARITY, SIMILAR_TRACKS, CREDITS,
// ALBUM_ART, GENRE_DISCOVERY
```

### defaultTypesFor()

Returns the default set for any request variant:

```kotlin
val request = EnrichmentRequest.forArtist("Radiohead")
val types = EnrichmentRequest.defaultTypesFor(request) // returns DEFAULT_ARTIST_TYPES
```

### Set algebra composition

Kotlin sets support `+`, `-`, and `intersect`:

```kotlin
// Remove a slow type
val fast = EnrichmentRequest.DEFAULT_ARTIST_TYPES - setOf(EnrichmentType.ARTIST_TIMELINE)

// Add a type not in the default set
val extended = EnrichmentRequest.DEFAULT_ALBUM_TYPES + setOf(EnrichmentType.CREDITS)

// Request only the intersection of two sets
val common = EnrichmentRequest.DEFAULT_ARTIST_TYPES.intersect(
    setOf(EnrichmentType.GENRE, EnrichmentType.ARTIST_PHOTO, EnrichmentType.SIMILAR_ARTISTS)
)

// Build a minimal request from scratch
val minimal = setOf(EnrichmentType.ALBUM_ART, EnrichmentType.GENRE)
```

---

## Recommendations

The library includes several discovery types that go beyond basic metadata.

### Similar artists (merged from 3 providers)

Last.fm, ListenBrainz, and Deezer each return similar artists. The `SimilarArtistMerger` deduplicates them, combines scores, and tracks which providers contributed each match:

```kotlin
val results = engine.enrich(
    EnrichmentRequest.forArtist("Radiohead"),
    setOf(EnrichmentType.SIMILAR_ARTISTS),
)

results.similarArtists()?.artists?.forEach { artist ->
    println("${artist.name} (score=%.2f, sources=${artist.sources})".format(artist.matchScore))
    println("  MBID: ${artist.identifiers.musicBrainzId}")
}
// Thom Yorke (score=0.95, sources=[lastfm, listenbrainz, deezer])
// Portishead (score=0.88, sources=[lastfm, deezer])
```

### Similar tracks

```kotlin
val results = engine.enrich(
    EnrichmentRequest.forTrack("Creep", "Radiohead"),
    setOf(EnrichmentType.SIMILAR_TRACKS),
)

results.similarTracks()?.tracks?.forEach { track ->
    println("${track.title} by ${track.artist} (score=%.2f, sources=${track.sources})".format(track.matchScore))
}
```

### Similar albums

Synthesized from Deezer similar artists and their top albums. Albums from the same era as the queried album get a score multiplier:

```kotlin
val results = engine.enrich(
    EnrichmentRequest.forAlbum("OK Computer", "Radiohead"),
    setOf(EnrichmentType.SIMILAR_ALBUMS),
)

results.similarAlbums()?.albums?.forEach { album ->
    println("${album.title} by ${album.artist} (${album.year}) — score=%.2f".format(album.artistMatchScore))
    println("  thumbnail: ${album.thumbnailUrl}")
}
```

### Artist radio discovery (ListenBrainz LB Radio)

Community-driven discovery radio via ListenBrainz. Requires `listenBrainzToken` in `ApiKeyConfig`.

```kotlin
val keys = ApiKeyConfig(listenBrainzToken = "your-token")

val engine = EnrichmentEngine.Builder()
    .withDefaultProviders()
    .apiKeys(keys)
    .build()

val results = engine.enrich(
    EnrichmentRequest.forArtist("Radiohead"),
    setOf(EnrichmentType.ARTIST_RADIO_DISCOVERY),
)

results.radioDiscovery()?.tracks?.forEach { track ->
    println("${track.title} — ${track.artist} (${track.durationMs?.div(1000)}s)")
    println("  recording MBID: ${track.identifiers.musicBrainzId}")
}
```

Configure discovery depth via `radioDiscoveryMode`:

```kotlin
// Easy: familiar-adjacent (default)
val config = EnrichmentConfig(radioDiscoveryMode = RadioDiscoveryMode.EASY)

// Hard: adventurous — deeper into less-related territory
val config = EnrichmentConfig(radioDiscoveryMode = RadioDiscoveryMode.HARD)
```

Without `listenBrainzToken`, `ARTIST_RADIO_DISCOVERY` is silently absent — `NotFound` is returned and all other ListenBrainz capabilities continue working.

### Track preview

A 30-second MP3 preview URL from Deezer. On-demand type — not in `DEFAULT_TRACK_TYPES`. Request explicitly when you need it:

```kotlin
val results = engine.enrich(
    EnrichmentRequest.forTrack("Creep", "Radiohead"),
    types = setOf(EnrichmentType.TRACK_PREVIEW),
)

val preview = results.trackPreview()
println(preview?.url)        // https://cdns-preview-d.dzcdn.net/stream/...
println(preview?.durationMs) // 30000
println(preview?.source)     // "deezer"
```

Typical use: resolve a preview URL for a track the user discovered via radio or similar artists, so they can audition it before adding it to their library. See `docs/v0.9.0-lb-radio-feature-plan.md` Consumer Usage Patterns for a full discovery-with-preview example.

### Artist radio

An ordered playlist from Deezer's `/artist/{id}/radio` endpoint. Good for "radio station" features:

```kotlin
val results = engine.enrich(
    EnrichmentRequest.forArtist("Daft Punk"),
    setOf(EnrichmentType.ARTIST_RADIO),
)

results.radio()?.tracks?.forEach { track ->
    println("${track.title} — ${track.artist} (${track.durationMs?.div(1000)}s)")
}
```

The default radio limit is 50 tracks. Configure it via `EnrichmentConfig.radioLimit` (Deezer supports up to 100).

### Artist top tracks (merged from 3 providers)

Last.fm, ListenBrainz, and Deezer each provide top tracks. The `TopTrackMerger` deduplicates, combines listen counts, and tracks sources:

```kotlin
val results = engine.enrich(
    EnrichmentRequest.forArtist("Radiohead"),
    setOf(EnrichmentType.ARTIST_TOP_TRACKS),
)

results.topTracks()?.tracks?.forEach { track ->
    println("#${track.rank} ${track.title} (listens=${track.listenCount}, sources=${track.sources})")
}
```

### Genre discovery

Uses a static genre affinity taxonomy covering ~70 genre relationships. Include `GENRE` in the same request or ensure it is cached:

```kotlin
val results = engine.enrich(
    EnrichmentRequest.forArtist("Radiohead"),
    setOf(EnrichmentType.GENRE, EnrichmentType.GENRE_DISCOVERY),
)

results.get<EnrichmentData.GenreDiscovery>(EnrichmentType.GENRE_DISCOVERY)
    ?.relatedGenres?.forEach { genre ->
        println("${genre.name} (affinity=%.2f, rel=${genre.relationship}, from=${genre.sourceGenres})"
            .format(genre.affinity))
    }
// post-rock (affinity=0.90, rel=sibling, from=[alternative rock])
// shoegaze (affinity=0.80, rel=sibling, from=[alternative rock])
// art rock (affinity=0.85, rel=parent, from=[alternative rock])
```

Each `GenreAffinity` includes: `name`, `affinity` (0.0–1.0), `relationship` ("sibling", "parent", "child", etc.), and `sourceGenres` (which of the entity's genres triggered the recommendation).

---

## Catalog filtering

Plug in your music library to filter recommendation results by what the user can actually play:

```kotlin
val catalog = CatalogProvider { queries ->
    queries.map { q ->
        CatalogMatch(
            available = myLibrary.contains(q.title, q.artist),
            source = "local-library",
            uri = myLibrary.getUri(q.title, q.artist), // optional deep link
            confidence = 1.0f,
        )
    }
}

val engine = EnrichmentEngine.Builder()
    .withDefaultProviders()
    .catalog(catalog, CatalogFilterMode.AVAILABLE_FIRST)
    .build()
```

### Filter modes

| Mode | Behavior |
|------|----------|
| `UNFILTERED` | No filtering. Default when no `CatalogProvider` is configured. |
| `AVAILABLE_ONLY` | Removes items where `CatalogMatch.available == false`. |
| `AVAILABLE_FIRST` | Sorts available items before unavailable; preserves relative order within each group. |

Catalog filtering applies to: `SIMILAR_ARTISTS`, `SIMILAR_TRACKS`, `SIMILAR_ALBUMS`, `ARTIST_RADIO`, `ARTIST_RADIO_DISCOVERY`, `ARTIST_TOP_TRACKS`.

Note: `TRACK_PREVIEW` is intentionally excluded — previews are for tracks the user does not have.
