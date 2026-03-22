# musicmeta

A Kotlin library that looks up music metadata from multiple public APIs. Give it an album, artist, or track name and get back artwork, genres, lyrics, biographies, credits, timelines, and more.

Built for music player apps that want Spotify-quality metadata without a commercial API. Works on JVM and Android.

## What it does

```
"OK Computer" by Radiohead
         |
         v
+-----------------------------+
|  EnrichmentEngine           |  28 enrichment types
|                             |  11 providers
|  MusicBrainz ---------------+--> Identity (MBID), genre, label, credits, editions
|  Cover Art Archive ---------+--> Album art front/back/booklet (multi-size)
|  Wikidata ------------------+--> Artist photo, country, dates
|  Wikipedia -----------------+--> Artist biography, supplemental photos
|  LRCLIB --------------------+--> Synced + plain lyrics
|  Deezer --------------------+--> Album art, discography, tracklists
|  iTunes --------------------+--> Album art, tracklists, discography
|  Last.fm -------------------+--> Genres, similar artists/tracks, album metadata
|  ListenBrainz --------------+--> Popularity, discography, similar artists
|  Fanart.tv -----------------+--> Backgrounds, logos, banners, CD art
|  Discogs -------------------+--> Credits, editions, labels, community data
+-----------------------------+
         |
         v
  Map<EnrichmentType, EnrichmentResult>
    ALBUM_ART        -> 1200px cover from Cover Art Archive (conf=1.0)
    GENRE            -> "alternative rock" (0.70, [musicbrainz, lastfm]) via GenreMerger
    CREDITS          -> 4 performance, 2 production, 1 songwriting
    RELEASE_EDITIONS -> 25 editions across 11 countries
    ARTIST_TIMELINE  -> 21 events: formed(1991) → latest(2025)
    ALBUM_TRACKS     -> 12 tracks with durations
    ...
```

The engine handles the hard parts: MusicBrainz resolves identifiers first, then downstream providers use those IDs for precise lookups. Rate limiting, circuit breaking, confidence scoring, and caching are all built in. 8 of 11 providers work without API keys.

## Quick start

### JVM (musicmeta-core)

```kotlin
val httpClient = DefaultHttpClient("MyApp/1.0 (myapp.example.com)")

val engine = EnrichmentEngine.Builder()
    .addProvider(MusicBrainzProvider(httpClient, RateLimiter(1100)))
    .addProvider(CoverArtArchiveProvider(httpClient, RateLimiter(100)))
    .addProvider(WikidataProvider(httpClient, RateLimiter(100)))
    .addProvider(WikipediaProvider(httpClient, RateLimiter(100)))
    .addProvider(DeezerProvider(httpClient, RateLimiter(100)))
    .addProvider(ITunesProvider(httpClient, RateLimiter(3000)))
    .addProvider(LrcLibProvider(httpClient, RateLimiter(200)))
    .addProvider(ListenBrainzProvider(httpClient, RateLimiter(100)))
    // Optional: add API-key providers for more coverage
    .addProvider(LastFmProvider("YOUR_LASTFM_KEY", httpClient, RateLimiter(200)))
    .addProvider(FanartTvProvider("YOUR_FANARTTV_KEY", httpClient, RateLimiter(100)))
    .addProvider(DiscogsProvider("YOUR_DISCOGS_TOKEN", httpClient, RateLimiter(100)))
    .build()

// Look up an album — get artwork, genres, tracklist, credits, editions
val results = engine.enrich(
    EnrichmentRequest.forAlbum("OK Computer", "Radiohead"),
    setOf(
        EnrichmentType.ALBUM_ART, EnrichmentType.GENRE,
        EnrichmentType.ALBUM_TRACKS, EnrichmentType.CREDITS,
        EnrichmentType.RELEASE_EDITIONS,
    ),
)

when (val art = results[EnrichmentType.ALBUM_ART]) {
    is EnrichmentResult.Success -> {
        val artwork = art.data as EnrichmentData.Artwork
        println("Cover: ${artwork.url}")           // 1200px artwork URL
        println("Thumb: ${artwork.thumbnailUrl}")   // 250px thumbnail
        println("Confidence: ${art.confidence}")    // 0.0-1.0
    }
    is EnrichmentResult.NotFound -> println("No art found")
    is EnrichmentResult.RateLimited -> println("Try again later")
    is EnrichmentResult.Error -> println("Error: ${art.message}")
}

// Get credits for a track
val credits = engine.enrich(
    EnrichmentRequest.forTrack("Bohemian Rhapsody", "Queen", album = "A Night at the Opera"),
    setOf(EnrichmentType.CREDITS),
)
val creditData = (credits[EnrichmentType.CREDITS] as? EnrichmentResult.Success)
    ?.data as? EnrichmentData.Credits
creditData?.credits?.groupBy { it.roleCategory }?.forEach { (category, members) ->
    println("[$category] ${members.joinToString { it.name }}")
}
// [performance] Freddie Mercury, Brian May, Roger Taylor, John Deacon
// [production] Roy Thomas Baker, Mike Stone

// Get an artist timeline
val timeline = engine.enrich(
    EnrichmentRequest.forArtist("Radiohead"),
    setOf(EnrichmentType.ARTIST_TIMELINE),
)
val events = (timeline[EnrichmentType.ARTIST_TIMELINE] as? EnrichmentResult.Success)
    ?.data as? EnrichmentData.ArtistTimeline
events?.events?.forEach { println("${it.date} ${it.type}: ${it.description}") }
// 1991 formed: Band formed
// 1993 first_album: Pablo Honey
// 1997 album_release: OK Computer
// ...

// Search for candidates (for manual disambiguation UI)
val candidates = engine.search(
    EnrichmentRequest.forAlbum("Dark Side", "Pink Floyd"),
    limit = 5,
)
candidates.forEach { println("${it.title} (${it.year}) -- score ${it.score}") }
```

### Android (musicmeta-android)

The `musicmeta-android` module adds Room-backed persistent caching, a Hilt DI module, and a WorkManager base worker.

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.github.famesjranko.musicmeta:musicmeta-core:v0.5.0")
    implementation("com.github.famesjranko.musicmeta:musicmeta-android:v0.5.0")
}
```

```kotlin
// Hilt wiring -- HiltEnrichmentModule auto-provides RoomEnrichmentCache
@Module
@InstallIn(SingletonComponent::class)
object MyEnrichmentModule {

    @Provides @Singleton
    fun provideEngine(
        roomCache: RoomEnrichmentCache,
    ): EnrichmentEngine {
        val http = DefaultHttpClient("MyApp/1.0 (myapp.example.com)")
        return EnrichmentEngine.Builder()
            .cache(roomCache)  // Persistent Room cache instead of in-memory
            .addProvider(MusicBrainzProvider(http, RateLimiter(1100)))
            .addProvider(CoverArtArchiveProvider(http, RateLimiter(100)))
            .addProvider(DeezerProvider(http, RateLimiter(100)))
            .build()
    }
}
```

## Modules

| Module | Type | Dependencies | Purpose |
|--------|------|-------------|---------|
| `musicmeta-core` | Pure JVM/Kotlin | coroutines, org.json, kotlinx.serialization | Engine, providers, HTTP, caching interface |
| `musicmeta-android` | Android library | Room, Hilt, WorkManager | Persistent cache, DI wiring, batch worker |

`musicmeta-core` has zero Android dependencies. Use it in backend services, CLI tools, or desktop apps.

## Providers

| Provider | Data | API Key | Rate Limit |
|----------|------|---------|------------|
| MusicBrainz | Identity (MBID), genre, label, dates, members, discography, tracks, links, credits, editions | No | 1 req/sec |
| Cover Art Archive | Album art front/back/booklet (multi-size) | No | None |
| Wikidata | Artist photo, country, dates, occupation | No | None |
| Wikipedia | Artist biography, supplemental photos | No | None |
| LRCLIB | Synced + plain lyrics | No | None |
| Deezer | Album art, discography, tracklists, album metadata | No | None |
| iTunes | Album art, tracklists, discography, album metadata | No | ~1 req/3sec |
| ListenBrainz | Popularity, listen counts, discography, similar artists | No | None |
| Last.fm | Genres, similar artists/tracks, bios, popularity, album metadata | Yes | None |
| Fanart.tv | Artist photos/backgrounds/logos/banners, CD art, album art | Yes | None |
| Discogs | Labels, members, credits, editions, community ratings | Yes | None |

8 of 11 providers work without API keys. Providers with missing keys report `isAvailable = false` and are skipped automatically — their types fall through to other providers.

**Getting API keys (all free):**
- Last.fm: https://www.last.fm/api/account/create
- Fanart.tv: https://fanart.tv/get-an-api-key/
- Discogs: https://www.discogs.com/settings/developers → "Generate new token"

## Enrichment types (28)

| Category | Types | Multi-provider |
|----------|-------|----------------|
| **Artwork** | ALBUM_ART, ALBUM_ART_BACK, ALBUM_BOOKLET, ARTIST_PHOTO, ARTIST_BACKGROUND, ARTIST_LOGO, ARTIST_BANNER, CD_ART | ALBUM_ART (5), ARTIST_PHOTO (3) |
| **Metadata** | GENRE, LABEL, RELEASE_DATE, RELEASE_TYPE, COUNTRY, BAND_MEMBERS, ARTIST_DISCOGRAPHY, ALBUM_TRACKS, ALBUM_METADATA | GENRE merged from 2+, DISCOGRAPHY (4), TRACKS (3), METADATA (4) |
| **Credits** | CREDITS | MusicBrainz (recording rels) + Discogs (extraartists) |
| **Editions** | RELEASE_EDITIONS | MusicBrainz (release-group) + Discogs (master versions) |
| **Text** | ARTIST_BIO, LYRICS_SYNCED, LYRICS_PLAIN | BIO (2) |
| **Relationships** | SIMILAR_ARTISTS, SIMILAR_TRACKS, ARTIST_LINKS | SIMILAR_ARTISTS (2) |
| **Statistics** | ARTIST_POPULARITY, TRACK_POPULARITY | Both from 2 providers |
| **Composite** | ARTIST_TIMELINE | Synthesized from discography + members + life-span |

16 of 28 types have multi-provider coverage with automatic fallback.

## How it works

1. **Identity resolution** — MusicBrainz searches by title/artist, returns a MusicBrainz ID (MBID) plus Wikidata/Wikipedia links. Downstream providers use these IDs for precise lookups instead of fuzzy search.

2. **Fan-out** — The engine sends the enriched request to provider chains for each type, concurrently. Three resolution modes:
   - **Standard** — providers tried in priority order, first `Success` wins
   - **Mergeable** — all providers contribute (GENRE: tags merged with additive confidence scoring)
   - **Composite** — sub-types resolved first, then synthesized (ARTIST_TIMELINE)

3. **Confidence filtering** — Results below `minConfidence` (default 0.5) are discarded. ID-based lookups score 1.0, fuzzy searches score 0.6-0.8.

4. **Resilience** — Per-provider rate limiting, circuit breakers (5 failures → 60s cooldown), overall timeout (30s default). Individual failures don't block other types.

5. **Caching** — Successful results cached with per-type TTLs (7 days for stats, 90 days for artwork, 365 days for editions). `InMemoryEnrichmentCache` (LRU) or `RoomEnrichmentCache` (SQLite).

For a detailed pipeline trace, see [docs/how-it-works.md](docs/how-it-works.md).

## Configuration

```kotlin
val engine = EnrichmentEngine.Builder()
    .addProvider(MusicBrainzProvider(httpClient, RateLimiter(1100)))
    .addProvider(CoverArtArchiveProvider(httpClient, RateLimiter(100),
        artworkSize = 600, thumbnailSize = 250))     // Custom artwork sizes
    .addProvider(ITunesProvider(httpClient, RateLimiter(3000),
        artworkSize = 600))                           // Per-provider artwork size
    .config(EnrichmentConfig(
        minConfidence = 0.6f,           // Stricter matching
        userAgent = "MyApp/1.0 (contact@example.com)",
        enableIdentityResolution = true,
        enrichTimeoutMs = 15_000,
        confidenceOverrides = mapOf(    // Tune per-provider
            "deezer" to 0.8f,
            "itunes" to 0.6f,
        ),
        priorityOverrides = mapOf(      // Reorder provider chains
            "deezer" to mapOf(EnrichmentType.ALBUM_ART to 90),
        ),
    ))
    .logger(object : EnrichmentLogger {
        override fun debug(tag: String, message: String) = Log.d(tag, message)
        override fun warn(tag: String, message: String, throwable: Throwable?) = Log.w(tag, message, throwable)
    })
    .build()
```

## Extension points

### Custom provider

Implement `EnrichmentProvider` to add a new data source:

```kotlin
class MyProvider(
    private val httpClient: HttpClient,
    private val rateLimiter: RateLimiter,
) : EnrichmentProvider {
    override val id = "myprovider"
    override val displayName = "My Provider"
    override val capabilities = listOf(
        ProviderCapability(EnrichmentType.GENRE, priority = 60),
    )
    override val requiresApiKey = false
    override val isAvailable = true

    override suspend fun enrich(
        request: EnrichmentRequest,
        type: EnrichmentType,
    ): EnrichmentResult {
        rateLimiter.acquire()
        val album = request as? EnrichmentRequest.ForAlbum
            ?: return EnrichmentResult.NotFound(type, id)

        val json = httpClient.fetchJson("https://api.example.com/search?q=${album.title}")
            ?: return EnrichmentResult.RateLimited(type, id)

        return EnrichmentResult.Success(
            type = type,
            data = EnrichmentData.Metadata(genres = listOf("rock")),
            provider = id,
            confidence = 0.75f,
        )
    }
}
```

### Custom HTTP client

Replace `DefaultHttpClient` (which uses `java.net.HttpURLConnection`) with OkHttp, Ktor, or anything else:

```kotlin
class OkHttpMusicMetaClient(private val client: OkHttpClient) : HttpClient {
    override suspend fun fetchJson(url: String): JSONObject? { /* ... */ }
    override suspend fun fetchJsonResult(url: String): HttpResult<JSONObject> { /* ... */ }
    override suspend fun fetchJsonArray(url: String): JSONArray? { /* ... */ }
    override suspend fun fetchBody(url: String): String? { /* ... */ }
    override suspend fun fetchRedirectUrl(url: String): String? { /* ... */ }
}
```

### Custom cache

Implement `EnrichmentCache` for your storage backend:

```kotlin
class RedisEnrichmentCache(private val redis: RedisClient) : EnrichmentCache {
    override suspend fun get(entityKey: String, type: EnrichmentType) = /* ... */
    override suspend fun put(entityKey: String, type: EnrichmentType, result: EnrichmentResult.Success, ttlMs: Long) = /* ... */
    // ...
}
```

## Using in your project

### JitPack (recommended)

Add JitPack as a repository and pull the dependency:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.github.famesjranko.musicmeta:musicmeta-core:v0.5.0")
    implementation("com.github.famesjranko.musicmeta:musicmeta-android:v0.5.0") // Android only
}
```

### Composite build

Clone the repo alongside your project:

```kotlin
// settings.gradle.kts
includeBuild("../musicmeta")
```

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.landofoz:musicmeta-core")
    implementation("com.landofoz:musicmeta-android")
}
```

### Maven Local

```bash
./gradlew publishToMavenLocal
```

Then consume as `com.landofoz:musicmeta-core:0.5.0` from `mavenLocal()`.

## Running tests

```bash
# Unit tests (no API keys needed, no network)
./gradlew :musicmeta-core:test

# E2E showcase — readable diagnostic report across diverse queries
./gradlew :musicmeta-core:test -Dinclude.e2e=true \
  --tests "*.EnrichmentShowcaseTest"

# E2E with all 11 providers active (pass API keys)
./gradlew :musicmeta-core:test -Dinclude.e2e=true \
  -Dlastfm.apikey=YOUR_KEY \
  -Dfanarttv.apikey=YOUR_KEY \
  -Ddiscogs.token=YOUR_TOKEN \
  --tests "*.EnrichmentShowcaseTest"

# Edge analysis — boundary testing, special characters, error handling
./gradlew :musicmeta-core:test -Dinclude.e2e=true \
  -Dlastfm.apikey=YOUR_KEY \
  -Dfanarttv.apikey=YOUR_KEY \
  -Ddiscogs.token=YOUR_TOKEN \
  --tests "*.EdgeAnalysisTest"
```

Or set environment variables: `LASTFM_API_KEY`, `FANARTTV_API_KEY`, `DISCOGS_TOKEN`.

The showcase report includes:
- Provider availability (which of 11 are active)
- Deep dives: artist (Radiohead), album (OK Computer), track (Bohemian Rhapsody)
- Cross-genre test (Kendrick Lamar)
- Edge cases (AC/DC, Bjork, instrumental tracks, obscure artists)
- Dynamic coverage matrix (which types have multi-provider support)
- v0.5.0 feature spotlight (credits with roleCategory, release editions, artist timeline, genre merge with confidence)

## Documentation

| Document | Purpose |
|----------|---------|
| [docs/how-it-works.md](docs/how-it-works.md) | Complete pipeline trace — from `enrich()` call to results |
| [docs/v0.5.0-edge-analysis.md](docs/v0.5.0-edge-analysis.md) | Edge testing report — bugs, findings, methodology |
| [docs/providers/](docs/providers/) | Per-provider API documentation and endpoint inventory |
| [CHANGELOG.md](CHANGELOG.md) | Release history |
| [STORIES.md](STORIES.md) | Architectural decisions and rationale |

## Requirements

- **JVM**: Java 17+, Kotlin 2.1+
- **Android**: Min SDK 26 (Android 8.0) for `musicmeta-android`
- **User-Agent**: MusicBrainz and Wikimedia APIs require a descriptive User-Agent string. Set it via the `DefaultHttpClient` constructor.

## License

TBD
