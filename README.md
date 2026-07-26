![Musicmeta banner](docs/musicmeta-banner.png)

<div align="center">
         
[![Maven Central](https://img.shields.io/maven-central/v/io.github.famesjranko/musicmeta-core)](https://central.sonatype.com/artifact/io.github.famesjranko/musicmeta-core)
[![JitPack](https://jitpack.io/v/famesjranko/musicmeta.svg)](https://jitpack.io/#famesjranko/musicmeta)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-%237F52FF?logo=kotlin)](https://kotlinlang.org)

</div>

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
|  Wikidata ------------------+--> Artist photo, country of origin
|  Wikipedia -----------------+--> Artist biography, supplemental photos
|  LRCLIB --------------------+--> Synced + plain lyrics
|  Deezer --------------------+--> Artist photos, album art, discography, tracklists, similar artists, radio, similar albums
|  iTunes --------------------+--> Album art, tracklists, discography
|  Last.fm -------------------+--> Genres, similar artists/tracks, album metadata
|  ListenBrainz --------------+--> Popularity, discography, similar artists, radio discovery (with token)
|  Fanart.tv -----------------+--> Backgrounds, logos, banners, CD art
|  Discogs -------------------+--> Credits, editions, labels, artwork, community data
+-----------------------------+
         |
         v
  ArtistProfile / AlbumProfile / TrackProfile
    profile.photo?.url           -> artist photo from Wikidata
    profile.bio?.text            -> biography from Wikipedia
    profile.genres               -> [GenreTag("alternative rock", 0.70)]
    profile.discography          -> 9 studio albums
    profile.similarArtists       -> merged from Last.fm + ListenBrainz + Deezer
    ...
```

The engine handles the hard parts: MusicBrainz resolves identifiers first, then downstream providers use those IDs for precise lookups. Rate limiting, circuit breaking, confidence scoring, and caching are all built in. 8 of 11 providers work without API keys.

## Quick start

```kotlin
val engine = EnrichmentEngine.Builder()
    .withDefaultProviders()
    .build()

// Artist profile -- photo, bio, genres, members, discography, similar artists, ...
val profile = engine.artistProfile("Radiohead")

println(profile.photo?.url)
println(profile.bio?.text)
profile.genres.forEach { println("${it.name} (${it.confidence})") }
profile.discography.forEach { println("${it.title} (${it.year})") }
```

`albumProfile()` and `trackProfile()` are the same shape. Each picks sensible default types for its
entity kind; pass `types` to request less and skip the API calls you do not need:

```kotlin
val minimal = engine.artistProfile("Radiohead", types = setOf(
    EnrichmentType.GENRE, EnrichmentType.ARTIST_PHOTO,
))
```

The engine resolves every type independently and `enrich()` never throws — a provider that fails,
rate limits or times out yields a typed result on that one type, and the rest of the profile is
unaffected. `profile.results` carries the per-type outcome when you need to tell "no data" from
"could not fetch".

For the full API — pre-resolved identifiers, named accessors, the raw result map, disambiguation,
and the failure-isolation guarantees — see the [developer guides](docs/guides/README.md).

## Providers

| Provider | Data | API Key | Rate Limit |
|----------|------|---------|------------|
| MusicBrainz | Identity (MBID), genre, label, dates, members, discography, tracks, links, credits, editions | No | 1 req/sec |
| Cover Art Archive | Album art front/back/booklet (multi-size), CD art | No | None |
| Wikidata | Artist photo, country of origin | No | None |
| Wikipedia | Artist biography, supplemental photos | No | None |
| LRCLIB | Synced + plain lyrics | No | None |
| Deezer | Artist photos, album art, discography, tracklists, album metadata, similar artists/tracks, artist radio, top tracks, similar albums, track previews | No | None |
| iTunes | Album art, tracklists, discography, album metadata | No | ~1 req/3sec |
| ListenBrainz | Popularity, listen counts, discography, similar artists, top tracks, radio discovery (optional token) | Optional | None |
| Last.fm | Genres, similar artists/tracks, bios, popularity, album metadata | Yes | None |
| Fanart.tv | Artist photos/backgrounds/logos/banners, CD art, album art | Yes | None |
| Discogs | Labels, members, credits, editions, artwork, album metadata, community ratings | Yes | None |

8 of 11 providers work without API keys. `withDefaultProviders()` registers a key-requiring provider
only when you supply its key, so the types it would have served simply fall through to the providers
you do have.

**Getting API keys (all free):**
- Last.fm: https://www.last.fm/api/account/create
- Fanart.tv: https://fanart.tv/get-an-api-key/
- Discogs: https://www.discogs.com/settings/developers -> "Generate new token"

Pass keys via `ApiKeyConfig`:

```kotlin
val engine = EnrichmentEngine.Builder()
    .apiKeys(ApiKeyConfig(
        lastFmKey = "...", fanartTvProjectKey = "...", discogsPersonalToken = "...",
        listenBrainzToken = "...",  // Optional — unlocks ARTIST_RADIO_DISCOVERY
    ))
    .withDefaultProviders()
    .build()
```

## Enrichment types (34)

| Category | Types | Multi-provider |
|----------|-------|----------------|
| **Artwork** | ALBUM_ART, ALBUM_ART_BACK, ALBUM_BOOKLET, ARTIST_PHOTO, ARTIST_BACKGROUND, ARTIST_LOGO, ARTIST_BANNER, CD_ART | ALBUM_ART merged (5 via ArtworkMerger), ARTIST_PHOTO merged (5: Wikidata, Fanart.tv, Deezer, Discogs, Wikipedia), CD_ART (2) |
| **Metadata** | GENRE, LABEL, RELEASE_DATE, RELEASE_TYPE, COUNTRY, BAND_MEMBERS, ARTIST_DISCOGRAPHY, ALBUM_TRACKS, ALBUM_METADATA | DISCOGRAPHY (4), METADATA (4), TRACKS (3), GENRE (2), LABEL (2), RELEASE_TYPE (2), COUNTRY (2), BAND_MEMBERS (2) |
| **Credits** | CREDITS | MusicBrainz (recording rels) + Discogs (extraartists) |
| **Editions** | RELEASE_EDITIONS | MusicBrainz (release-group) + Discogs (master versions) |
| **Text** | ARTIST_BIO, LYRICS_SYNCED, LYRICS_PLAIN | BIO (2) |
| **Relationships** | SIMILAR_ARTISTS, SIMILAR_TRACKS, ARTIST_LINKS | SIMILAR_ARTISTS (3: Last.fm, ListenBrainz, Deezer), SIMILAR_TRACKS (2) |
| **Top Tracks** | ARTIST_TOP_TRACKS | Merged from 3 (Last.fm, ListenBrainz, Deezer) via TopTrackMerger |
| **Statistics** | ARTIST_POPULARITY, TRACK_POPULARITY | Both from 2 providers |
| **Composite** | ARTIST_TIMELINE, GENRE_DISCOVERY | ARTIST_TIMELINE: discography + members + life-span; GENRE_DISCOVERY: static affinity taxonomy |
| **Radio** | ARTIST_RADIO, ARTIST_RADIO_DISCOVERY | ARTIST_RADIO: Deezer curated playlist; ARTIST_RADIO_DISCOVERY: ListenBrainz LB Radio (easy/medium/hard modes, optional token) |
| **Preview** | TRACK_PREVIEW | Deezer 30-second MP3 preview URL (on-demand, not in default types) |
| **Discovery** | SIMILAR_ALBUMS | Deezer related artists + era scoring |

19 of 34 types have multi-provider coverage with automatic fallback. Artwork types (ALBUM_ART, ARTIST_PHOTO) are merged rather than first-wins -- the best image is primary, alternatives are available via `Artwork.alternatives`.

## Installation

### Maven Central (recommended)

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.famesjranko:musicmeta-core:0.10.1")
    implementation("io.github.famesjranko:musicmeta-okhttp:0.10.1")   // Optional: OkHttp adapter
    implementation("io.github.famesjranko:musicmeta-android:0.10.1")  // Optional: Android (Room cache, Hilt, WorkManager)
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
    implementation("com.github.famesjranko.musicmeta:musicmeta-core:v0.10.1")
    implementation("com.github.famesjranko.musicmeta:musicmeta-okhttp:v0.10.1")   // Optional: OkHttp adapter
    implementation("com.github.famesjranko.musicmeta:musicmeta-android:v0.10.1")  // Optional: Android
}
```

To consume a local checkout instead, see [docs/project/workflow.md](docs/project/workflow.md).

## Requirements

- **JVM**: Java 17+, Kotlin 2.1+
- **Android**: Min SDK 21 (Android 5.0) for `musicmeta-android`
- **User-Agent**: MusicBrainz and Wikimedia APIs require a descriptive User-Agent string. Set it via `EnrichmentConfig.userAgent` or the `DefaultHttpClient` constructor.

## Documentation

| Document | Purpose |
|----------|---------|
| [docs/guides/](docs/guides/README.md) | Developer guides — quick start, identity resolution, results & errors, cache management, configuration, extension points, Android |
| [docs/how-it-works.md](docs/how-it-works.md) | Complete pipeline trace -- from `enrich()` call to results |
| [docs/providers/](docs/providers/README.md) | Per-provider feature docs — what our code takes from each provider, what it leaves, and where it departs from the house pattern |
| [docs/project/workflow.md](docs/project/workflow.md) | Branch topology, issue lifecycle, worktrees, and verification selection |
| [docs/project/release.md](docs/project/release.md) | Release preparation, tagging, and publication |
| [CHANGELOG.md](CHANGELOG.md) | Release history |
| [ARCHITECTURE.md](ARCHITECTURE.md) | What is enforced, and what is admitted as unenforced |

## Interactive demo

The `demo/` module is a standalone CLI that showcases all three API tiers (profiles, named accessors, raw results), cache management, and the disambiguation flow. To enable the key-requiring providers, create a `secrets.properties` file or set environment variables (`LASTFM_API_KEY`, `FANARTTV_API_KEY`, `DISCOGS_TOKEN`, `LISTENBRAINZ_TOKEN`).

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

## License

Licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).
