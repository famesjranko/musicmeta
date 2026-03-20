# music-enrichment

A Kotlin library that enriches music metadata from multiple public APIs. Give it an album, artist, or track name and get back artwork, genres, lyrics, biographies, and more.

Built for music player apps that want Spotify-quality metadata without a commercial API. Works on JVM and Android.

## What it does

```
"OK Computer" by Radiohead
         │
         ▼
┌─────────────────────┐
│  EnrichmentEngine    │
│                      │
│  MusicBrainz ────────┼──▶ MBID, genre, label, release date
│  Cover Art Archive ──┼──▶ Album artwork (via MBID)
│  Wikidata ───────────┼──▶ Artist photo (via Wikidata ID)
│  Wikipedia ──────────┼──▶ Artist biography
│  LRCLIB ─────────────┼──▶ Synced + plain lyrics
│  Deezer ─────────────┼──▶ Album art (fallback)
│  iTunes ─────────────┼──▶ Album art (fallback)
│  Last.fm ────────────┼──▶ Tags, similar artists
│  ListenBrainz ───────┼──▶ Popularity, listen counts
│  Fanart.tv ──────────┼──▶ Artist backgrounds, logos, CD art
│  Discogs ────────────┼──▶ Label, release info
└─────────────────────┘
```

The engine handles the hard parts: MusicBrainz resolves identifiers first, then downstream providers use those IDs for precise lookups. Rate limiting, circuit breaking, confidence scoring, and caching are all built in.

## Quick start

### JVM (enrichment-core)

```kotlin
val httpClient = DefaultHttpClient("MyApp/1.0 (myapp.example.com)")

val engine = EnrichmentEngine.Builder()
    .addProvider(MusicBrainzProvider(httpClient, RateLimiter(1100)))
    .addProvider(CoverArtArchiveProvider(httpClient, RateLimiter(100)))
    .addProvider(DeezerProvider(httpClient, RateLimiter(100)))
    .addProvider(ITunesProvider(httpClient, RateLimiter(3000)))
    .addProvider(LrcLibProvider(httpClient, RateLimiter(200)))
    .build()

// Enrich an album
val results = engine.enrich(
    EnrichmentRequest.forAlbum("OK Computer", "Radiohead"),
    setOf(EnrichmentType.ALBUM_ART, EnrichmentType.GENRE),
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

// Get lyrics for a track
val lyrics = engine.enrich(
    EnrichmentRequest.forTrack("Creep", "Radiohead", album = "Pablo Honey"),
    setOf(EnrichmentType.LYRICS_SYNCED),
)

// Search for candidates (for manual disambiguation UI)
val candidates = engine.search(
    EnrichmentRequest.forAlbum("Dark Side", "Pink Floyd"),
    limit = 5,
)
candidates.forEach { println("${it.title} (${it.year}) — score ${it.score}") }
```

### Android (enrichment-android)

The `enrichment-android` module adds Room-backed persistent caching, a Hilt DI module, and a WorkManager base worker.

```kotlin
// build.gradle.kts
dependencies {
    implementation(project(":enrichment-core"))
    implementation(project(":enrichment-android"))
}
```

```kotlin
// Hilt wiring — HiltEnrichmentModule auto-provides RoomEnrichmentCache
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
| `enrichment-core` | Pure JVM/Kotlin | coroutines, org.json, kotlinx.serialization | Engine, providers, HTTP, caching interface |
| `enrichment-android` | Android library | Room, Hilt, WorkManager | Persistent cache, DI wiring, batch worker |

`enrichment-core` has zero Android dependencies. Use it in backend services, CLI tools, or desktop apps.

## Providers

| Provider | Data | API Key | Rate Limit |
|----------|------|---------|------------|
| MusicBrainz | Identity resolution (MBID), genre, label, release info | No | 1 req/sec |
| Cover Art Archive | Album artwork (via MBID) | No | None |
| Wikidata | Artist photos (P18 property) | No | None |
| Wikipedia | Artist biographies | No | None |
| LRCLIB | Synced + plain lyrics | No | None |
| Deezer | Album artwork (search-based fallback) | No | None |
| iTunes | Album artwork (search-based fallback) | No | ~1 req/3sec |
| Last.fm | Tags, similar artists | Yes | None |
| ListenBrainz | Listen counts, popularity | No | None |
| Fanart.tv | Artist backgrounds, logos, CD art | Yes | None |
| Discogs | Label, release details | Yes | None |

Providers that require API keys report `isAvailable = false` until configured. The engine skips unavailable providers automatically.

## How it works

1. **Identity resolution** — MusicBrainz searches by title/artist, returns a MusicBrainz ID (MBID) plus Wikidata/Wikipedia links. This step is optional but dramatically improves downstream accuracy.

2. **Fan-out** — The engine sends the enriched request (now with IDs) to provider chains for each requested type. Providers are tried in priority order; if one returns `NotFound`, the next is tried.

3. **Confidence filtering** — Results below `minConfidence` (default 0.5) are discarded. MusicBrainz scores map directly to confidence. Search-based providers (Deezer, iTunes) get lower confidence.

4. **Caching** — Successful results are cached (default 30-day TTL). The `InMemoryEnrichmentCache` is LRU-based; `RoomEnrichmentCache` persists to SQLite.

## Configuration

```kotlin
val engine = EnrichmentEngine.Builder()
    .config(EnrichmentConfig(
        minConfidence = 0.6f,           // Stricter matching
        preferredArtworkSize = 600,     // Smaller artwork
        userAgent = "MyApp/1.0 (contact@example.com)",
        enableIdentityResolution = true,
        enrichTimeoutMs = 15_000,
        confidenceOverrides = mapOf(    // Tune per-provider
            "deezer" to 0.8f,
            "itunes" to 0.6f,
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

        // Your API call here
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
class OkHttpEnrichmentClient(private val client: OkHttpClient) : HttpClient {
    override suspend fun fetchJson(url: String): JSONObject? { /* ... */ }
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

### Option 1: Git submodule

```bash
git submodule add https://github.com/youruser/music-enrichment.git
```

```kotlin
// settings.gradle.kts
includeBuild("music-enrichment") {
    dependencySubstitution {
        substitute(module("com.cascade:enrichment-core")).using(project(":enrichment-core"))
        substitute(module("com.cascade:enrichment-android")).using(project(":enrichment-android"))
    }
}
```

### Option 2: Composite build

Clone the repo alongside your project and use Gradle composite builds:

```kotlin
// settings.gradle.kts
includeBuild("../music-enrichment")
```

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.cascade:enrichment-core")
    implementation("com.cascade:enrichment-android") // Android only
}
```

For this to work, add `group` to each module's build.gradle.kts:

```kotlin
// enrichment-core/build.gradle.kts
group = "com.cascade"

// enrichment-android/build.gradle.kts  (inside android { } block)
group = "com.cascade"
```

### Option 3: Publish to Maven Local

```bash
./gradlew publishToMavenLocal
```

Then consume like any Maven dependency. (Publishing configuration not yet included — add the `maven-publish` plugin to each module's build.gradle.kts.)

### Option 4: JitPack

Push to GitHub and add JitPack as a repository. No publishing configuration needed — JitPack builds from source:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven("https://jitpack.io")
    }
}
```

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.github.youruser.music-enrichment:enrichment-core:main-SNAPSHOT")
}
```

## Requirements

- **JVM**: Java 17+, Kotlin 2.1+
- **Android**: Min SDK 26 (Android 8.0) for `enrichment-android`
- **User-Agent**: MusicBrainz requires a descriptive User-Agent string. Set it via `EnrichmentConfig.userAgent`.

## License

TBD
