# musicmeta

[![Maven Central](https://img.shields.io/maven-central/v/io.github.famesjranko/musicmeta-core)](https://central.sonatype.com/artifact/io.github.famesjranko/musicmeta-core)
[![JitPack](https://jitpack.io/v/famesjranko/musicmeta.svg)](https://jitpack.io/#famesjranko/musicmeta)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-%237F52FF?logo=kotlin)](https://kotlinlang.org)

A Kotlin library that gives Android and JVM music apps access to rich metadata, artwork, and discovery features -- without a commercial API. Ask for as much or as little as you need: all 34 enrichment types at once, a single artist photo, just lyrics, or anything in between.

11 public music APIs behind one engine. You choose what to request, how to use it, and what to show your users. The library handles the plumbing -- identity resolution, multi-provider merging, confidence scoring, rate limiting, caching -- so you can focus on building your app.

## What it does

```
"OK Computer" by Radiohead
         |
         v
+-----------------------------+
|  EnrichmentEngine           |  34 enrichment types
|                             |  11 providers
|  MusicBrainz ---------------+--> Identity (MBID), genre, label, credits, editions
|  Cover Art Archive ---------+--> Album art front/back/booklet (multi-size)
|  Wikidata ------------------+--> Artist photo, country, dates
|  Wikipedia -----------------+--> Artist biography, supplemental photos
|  LRCLIB --------------------+--> Synced + plain lyrics
|  Deezer --------------------+--> Artist photos, album art, discography, tracklists, similar artists, radio, similar albums
|  iTunes --------------------+--> Album art, tracklists, discography
|  Last.fm -------------------+--> Genres, similar artists/tracks, album metadata
|  ListenBrainz --------------+--> Popularity, discography, similar artists, radio discovery (with token)
|  Fanart.tv -----------------+--> Backgrounds, logos, banners, CD art
|  Discogs -------------------+--> Credits, editions, labels, community data
+-----------------------------+
         |
         v
  ArtistProfile / AlbumProfile / TrackProfile
    profile.photo?.url           -> 600px photo from Wikidata (conf=1.0)
    profile.bio?.text            -> biography from Wikipedia
    profile.genres               -> [GenreTag("alternative rock", 0.70)]
    profile.discography          -> 9 studio albums
    profile.similarArtists       -> merged from Last.fm + ListenBrainz + Deezer
    ...
```

The engine handles the hard parts: MusicBrainz resolves identifiers first, then downstream providers use those IDs for precise lookups. Rate limiting, circuit breaking, confidence scoring, and caching are all built in. 8 of 11 providers work without API keys.

## Quick start

### Simple: profile methods

One call, structured result. The engine picks sensible default types for the entity kind.

```kotlin
val engine = EnrichmentEngine.Builder()
    .withDefaultProviders()
    .build()

// Artist profile -- photo, bio, genres, members, discography, similar artists, ...
val profile = engine.artistProfile("Radiohead")

println(profile.photo?.url)
println(profile.bio?.text)
profile.genres.forEach { println("${it.name} (${it.confidence})") }
profile.members.forEach { println("${it.name} — ${it.role}") }
profile.discography.forEach { println("${it.title} (${it.year})") }

// Album profile -- artwork, tracklist, genres, editions, ...
val album = engine.albumProfile("OK Computer", "Radiohead")
println(album.artwork?.url)
album.tracks.forEach { println("${it.position}. ${it.title} (${it.durationMs}ms)") }

// Track profile -- lyrics, credits, genres, popularity, ...
val track = engine.trackProfile("Bohemian Rhapsody", "Queen")
println(track.lyrics?.syncedLyrics ?: track.lyrics?.plainLyrics)
track.credits?.credits?.groupBy { it.roleCategory }?.forEach { (cat, members) ->
    println("[$cat] ${members.joinToString { it.name }}")
}
```

Request only the types you need to skip unnecessary API calls:

```kotlin
val minimal = engine.artistProfile("Radiohead", types = setOf(
    EnrichmentType.GENRE, EnrichmentType.ARTIST_PHOTO,
))
```

### Flexible: enrich + named accessors

For full control over the request and per-type diagnostics:

```kotlin
val results = engine.enrich(
    EnrichmentRequest.forAlbum("OK Computer", "Radiohead"),
    setOf(EnrichmentType.ALBUM_ART, EnrichmentType.GENRE, EnrichmentType.ALBUM_TRACKS),
)

// Named accessors -- no casting needed
val art = results.albumArt()
val genres = results.genreTags()
val tracks = results.get<EnrichmentData.Tracklist>(EnrichmentType.ALBUM_TRACKS)

// Diagnostics: was this type requested? What happened?
if (results.wasRequested(EnrichmentType.ALBUM_ART)) {
    when (val raw = results.result(EnrichmentType.ALBUM_ART)) {
        is EnrichmentResult.Success -> println("Cover: ${art?.url}")
        is EnrichmentResult.RateLimited -> println("Rate limited, try later")
        is EnrichmentResult.Error -> println("Error: ${raw.message}")
        is EnrichmentResult.NotFound -> println("No art found")
        null -> {}
    }
}

// Identity resolution is on the result, not buried in individual results
val identity = results.identity
println("Match: ${identity?.match}, score: ${identity?.matchScore}%")
```

Power users can access the raw result map via `results.raw[EnrichmentType.GENRE]`.

### Disambiguation

```kotlin
// Search first, then enrich with the chosen candidate
val candidates = engine.search(EnrichmentRequest.forArtist("Bush"), limit = 5)
// -> Show to user, they pick one...
val profile = engine.artistProfile(candidates.first())
```

### Android (musicmeta-android)

The `musicmeta-android` module adds Room-backed persistent caching, a Hilt DI module, and a WorkManager base worker.

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.famesjranko:musicmeta-core:0.8.2")
    implementation("io.github.famesjranko:musicmeta-android:0.8.2") // Android only
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
        return EnrichmentEngine.Builder()
            .cache(roomCache)  // Persistent Room cache instead of in-memory
            .withDefaultProviders()
            .build()
    }
}
```

## Providers

| Provider | Data | API Key | Rate Limit |
|----------|------|---------|------------|
| MusicBrainz | Identity (MBID), genre, label, dates, members, discography, tracks, links, credits, editions | No | 1 req/sec |
| Cover Art Archive | Album art front/back/booklet (multi-size) | No | None |
| Wikidata | Artist photo, country, dates, occupation | No | None |
| Wikipedia | Artist biography, supplemental photos | No | None |
| LRCLIB | Synced + plain lyrics | No | None |
| Deezer | Artist photos, album art, discography, tracklists, album metadata, similar artists, artist radio, similar albums | No | None |
| iTunes | Album art, tracklists, discography, album metadata | No | ~1 req/3sec |
| ListenBrainz | Popularity, listen counts, discography, similar artists | No | None |
| Last.fm | Genres, similar artists/tracks, bios, popularity, album metadata | Yes | None |
| Fanart.tv | Artist photos/backgrounds/logos/banners, CD art, album art | Yes | None |
| Discogs | Labels, members, credits, editions, community ratings | Yes | None |

8 of 11 providers work without API keys. Providers with missing keys report `isAvailable = false` and are skipped automatically.

**Getting API keys (all free):**
- Last.fm: https://www.last.fm/api/account/create
- Fanart.tv: https://fanart.tv/get-an-api-key/
- Discogs: https://www.discogs.com/settings/developers -> "Generate new token"

Pass keys via `ApiKeyConfig`:

```kotlin
val engine = EnrichmentEngine.Builder()
    .apiKeys(ApiKeyConfig(lastFmKey = "...", fanartTvProjectKey = "...", discogsPersonalToken = "..."))
    .withDefaultProviders()
    .build()
```

## Enrichment types (32)

| Category | Types | Multi-provider |
|----------|-------|----------------|
| **Artwork** | ALBUM_ART, ALBUM_ART_BACK, ALBUM_BOOKLET, ARTIST_PHOTO, ARTIST_BACKGROUND, ARTIST_LOGO, ARTIST_BANNER, CD_ART | ALBUM_ART merged (5 providers via ArtworkMerger), ARTIST_PHOTO merged (4: Wikidata, Fanart.tv, Deezer, Wikipedia) |
| **Metadata** | GENRE, LABEL, RELEASE_DATE, RELEASE_TYPE, COUNTRY, BAND_MEMBERS, ARTIST_DISCOGRAPHY, ALBUM_TRACKS, ALBUM_METADATA | GENRE merged from 2+, DISCOGRAPHY (4), TRACKS (3), METADATA (4) |
| **Credits** | CREDITS | MusicBrainz (recording rels) + Discogs (extraartists) |
| **Editions** | RELEASE_EDITIONS | MusicBrainz (release-group) + Discogs (master versions) |
| **Text** | ARTIST_BIO, LYRICS_SYNCED, LYRICS_PLAIN | BIO (2) |
| **Relationships** | SIMILAR_ARTISTS, SIMILAR_TRACKS, ARTIST_LINKS | SIMILAR_ARTISTS merged (3: Last.fm, ListenBrainz, Deezer) |
| **Top Tracks** | ARTIST_TOP_TRACKS | Merged from 3 providers (Last.fm, ListenBrainz, Deezer) via TopTrackMerger |
| **Statistics** | ARTIST_POPULARITY, TRACK_POPULARITY | Both from 2 providers |
| **Composite** | ARTIST_TIMELINE, GENRE_DISCOVERY | ARTIST_TIMELINE: discography + members + life-span; GENRE_DISCOVERY: static affinity taxonomy |
| **Radio** | ARTIST_RADIO | Deezer /artist/{id}/radio, ordered playlist |
| **Discovery** | SIMILAR_ALBUMS | Deezer related artists + era scoring |

18 of 32 types have multi-provider coverage with automatic fallback. Artwork types (ALBUM_ART, ARTIST_PHOTO) are merged from all providers -- the best image is primary, alternatives are available via `Artwork.alternatives`.

## Installation

### Maven Central (recommended)

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.famesjranko:musicmeta-core:0.8.2")
    implementation("io.github.famesjranko:musicmeta-okhttp:0.8.2")   // Optional: OkHttp adapter
    implementation("io.github.famesjranko:musicmeta-android:0.8.2")  // Optional: Android (Room cache, Hilt, WorkManager)
}
```

### JitPack

For projects already using JitPack — existing coordinates remain unchanged.

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
    implementation("com.github.famesjranko.musicmeta:musicmeta-core:v0.8.2")
    implementation("com.github.famesjranko.musicmeta:musicmeta-okhttp:v0.8.2")   // Optional: OkHttp adapter
    implementation("com.github.famesjranko.musicmeta:musicmeta-android:v0.8.2")  // Optional: Android
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
    implementation("io.github.famesjranko:musicmeta-core")
    implementation("io.github.famesjranko:musicmeta-android")
}
```

### Maven Local

```bash
./gradlew publishToMavenLocal
```

Then consume as `io.github.famesjranko:musicmeta-core:0.8.2` from `mavenLocal()`.

## Documentation

| Document | Purpose |
|----------|---------|
| [docs/guides/](docs/guides/README.md) | Developer guides — quick start, identity resolution, results & errors, cache management, configuration, extension points, Android |
| [docs/how-it-works.md](docs/how-it-works.md) | Complete pipeline trace -- from `enrich()` call to results |
| [docs/providers/](docs/providers/) | Per-provider API documentation and endpoint inventory |
| [CHANGELOG.md](CHANGELOG.md) | Release history |
| [STORIES.md](STORIES.md) | Architectural decisions and rationale |

## Interactive demo

The `demo/` module is a standalone CLI that showcases all three API tiers (profiles, named accessors, raw results), cache management, and the disambiguation flow. 8 of 11 providers work without API keys. To enable all providers, create a `secrets.properties` file or set environment variables (`LASTFM_API_KEY`, `FANARTTV_API_KEY`, `DISCOGS_TOKEN`).

```bash
cd demo && ../gradlew run -q --console=plain
```

```
musicmeta> artist radiohead
musicmeta> album OK Computer by Radiohead
musicmeta> track Paranoid Android by Radiohead --types lyrics,credits
musicmeta> search artist pink floyd
musicmeta> pick 1
musicmeta> refresh artist radiohead
musicmeta> invalidate artist radiohead
```

## Running tests

```bash
# Unit tests (no API keys needed, no network)
./gradlew :musicmeta-core:test

# E2E showcase -- readable diagnostic report across diverse queries
./gradlew :musicmeta-core:test -Dinclude.e2e=true \
  --tests "*.EnrichmentShowcaseTest"

# E2E with all 11 providers active (pass API keys)
./gradlew :musicmeta-core:test -Dinclude.e2e=true \
  -Dlastfm.apikey=YOUR_KEY \
  -Dfanarttv.apikey=YOUR_KEY \
  -Ddiscogs.token=YOUR_TOKEN \
  --tests "*.EnrichmentShowcaseTest"
```

## Requirements

- **JVM**: Java 17+, Kotlin 2.1+
- **Android**: Min SDK 26 (Android 8.0) for `musicmeta-android`
- **User-Agent**: MusicBrainz and Wikimedia APIs require a descriptive User-Agent string. Set it via `EnrichmentConfig.userAgent` or the `DefaultHttpClient` constructor.

## License

Licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).
