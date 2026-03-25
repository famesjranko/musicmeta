# Quick Start

Get up and running in 5 minutes. All enrichment calls are `suspend fun` — every example below must run inside a coroutine.

## Engine setup

```kotlin
val engine = EnrichmentEngine.Builder()
    .withDefaultProviders()
    .config(EnrichmentConfig(
        userAgent = "MyApp/1.0 (contact@example.com)",
    ))
    .apiKeys(ApiKeyConfig(
        lastFmKey = "...",              // optional — enables Last.fm
        fanartTvProjectKey = "...",     // optional — enables Fanart.tv
        discogsPersonalToken = "...",   // optional — enables Discogs
        listenBrainzToken = "...",      // optional — enables LB Radio discovery
    ))
    .build()
```

8 of 11 providers work without API keys. `withDefaultProviders()` registers all of them and conditionally adds key-requiring providers only when their key is present.

### With OkHttp (recommended for Android)

```kotlin
// Add: implementation("io.github.famesjranko:musicmeta-okhttp:0.8.2")
val engine = EnrichmentEngine.Builder()
    .httpClient(OkHttpEnrichmentClient(myOkHttpClient, "MyApp/1.0"))
    .withDefaultProviders()
    .build()
```

This replaces the default `HttpURLConnection` transport with your existing `OkHttpClient` — interceptors, certificate pinning, and connection pooling all apply.

---

## Tier 1: Profile methods

Profile methods return structured data classes with named properties. No casting, no map lookups, no sealed-class matching. All are `suspend fun` — call them from a coroutine or `runBlocking`.

### Artist profile

```kotlin
// Inside a coroutine or runBlocking { }
val profile = engine.artistProfile("Radiohead")

// Identity
profile.identifiers.musicBrainzId   // "a74b1b7f-71a5-4011-9441-d0b5e4122711"
profile.identityMatch                // IdentityMatch.RESOLVED
profile.identityMatchScore           // 100

// Artwork
profile.photo?.url                   // primary artist photo URL
profile.photo?.thumbnailUrl          // smaller version
profile.photo?.alternatives          // images from other providers
profile.background?.url              // artist background image
profile.logo?.url                    // artist logo (Fanart.tv)
profile.banner?.url                  // artist banner (Fanart.tv)

// Text & metadata
profile.bio?.text                    // Wikipedia biography
profile.bio?.source                  // "wikipedia"
profile.genres                       // List<GenreTag> with name, confidence, sources
profile.country                      // "GB"

// Members & relationships
profile.members                      // List<BandMember> with name, role, activePeriod
profile.links                        // List<ExternalLink> — social media, websites
profile.discography                  // List<DiscographyAlbum> with title, year, type

// Stats & recommendations
profile.popularity?.listenCount      // total listens (ListenBrainz)
profile.popularity?.listenerCount    // unique listeners
profile.topTracks?.tracks            // List<TopTrack> merged from up to 3 providers
profile.similarArtists?.artists      // List<SimilarArtist> with matchScore and sources
profile.similarAlbums?.albums        // List<SimilarAlbum>
profile.radio?.tracks                // List<RadioTrack> — Deezer artist radio playlist
profile.radioDiscovery?.tracks       // List<RadioTrack> — ListenBrainz community radio
profile.timeline                     // List<TimelineEvent> — formed, albums, milestones
profile.genreDiscovery               // List<GenreAffinity> — related genres to explore
```

### Album profile

```kotlin
val profile = engine.albumProfile("OK Computer", "Radiohead")

// Identity
profile.identifiers.musicBrainzId
profile.identityMatch
profile.identityMatchScore
profile.suggestions                  // List<SearchCandidate> when match is SUGGESTIONS

// Artwork
profile.artwork?.url                 // front cover
profile.artwork?.sizes               // List<ArtworkSize> — multiple resolutions
profile.artwork?.alternatives        // covers from other providers
profile.artworkBack?.url             // back cover
profile.booklet?.url                 // CD booklet scan
profile.cdArt?.url                   // CD art (Fanart.tv)

// Metadata
profile.genres                       // List<GenreTag>
profile.label                        // "Parlophone"
profile.releaseDate                  // "1997-06-16"
profile.releaseType                  // "Album"
profile.country                      // "GB"

// Tracklist & editions
profile.tracks                       // List<TrackInfo> with title, position, durationMs
profile.editions                     // List<ReleaseEdition> — all pressings worldwide

// Recommendations
profile.similarAlbums                // List<SimilarAlbum>
profile.genreDiscovery               // List<GenreAffinity>
```

### Track profile

```kotlin
val profile = engine.trackProfile("Creep", "Radiohead")

// Identity
profile.identifiers
profile.identityMatch
profile.identityMatchScore
profile.suggestions

// Content
profile.lyrics?.syncedLyrics         // LRC-format synced lyrics
profile.lyrics?.plainLyrics          // plain text lyrics
profile.lyrics?.isInstrumental       // true if instrumental
profile.credits?.credits             // List<Credit> with name, role, roleCategory
profile.artwork?.url                 // album art for the track

// Metadata
profile.genres                       // List<GenreTag>

// Stats & recommendations
profile.popularity?.listenCount
profile.similarTracks?.tracks        // List<SimilarTrack> with matchScore and sources
profile.preview?.url                 // 30-second MP3 preview URL (Deezer)
profile.genreDiscovery               // List<GenreAffinity>
```

---

## Custom type sets

By default, profile methods request all types relevant to the entity (16 for artists, 14 for albums, 8 for tracks). Override to request fewer types for faster responses:

```kotlin
// Only fetch photo and genres — skips bio, discography, timeline, etc.
val profile = engine.artistProfile(
    "Radiohead",
    types = setOf(EnrichmentType.GENRE, EnrichmentType.ARTIST_PHOTO),
)
```

See [configuration.md](configuration.md) for the full default type sets and set algebra composition.

---

## forceRefresh

Bypass the cache and fetch fresh data from providers:

```kotlin
val profile = engine.artistProfile("Radiohead", forceRefresh = true)
```

Works on all three profile methods and on `engine.enrich()` directly. The forceRefresh flag clears existing cache entries (including manual selections) before fetching. See [cache-management.md](cache-management.md) for more.

---

## "Did you mean?" flow

When identity resolution is ambiguous, profile methods return `SUGGESTIONS` instead of results. Re-enrich from the chosen candidate:

```kotlin
val profile = engine.artistProfile("Bush")

if (profile.identityMatch == IdentityMatch.SUGGESTIONS) {
    println("Did you mean?")
    profile.suggestions.forEach { candidate ->
        println("  ${candidate.title} — ${candidate.disambiguation} (${candidate.score}%)")
    }

    val chosen = profile.suggestions.first()

    // Re-enrich using the candidate — its MBID skips identity resolution
    val resolved = engine.artistProfile(chosen)
    println(resolved.bio?.text)
}
```

The `SearchCandidate` overloads exist for all three profile methods:

```kotlin
engine.artistProfile(candidate)
engine.albumProfile(candidate)
engine.trackProfile(candidate, album = "optional album hint")
```

See [identity-resolution.md](identity-resolution.md) for the full disambiguation flow and `engine.search()`.

---

## Providing an MBID directly

If you already know the MusicBrainz ID, pass it to skip identity resolution entirely:

```kotlin
val profile = engine.artistProfile(
    name = "Radiohead",
    mbid = "a74b1b7f-71a5-4011-9441-d0b5e4122711",
)
// profile.identityMatch will be null (resolution not attempted)
```

---

## Bulk enrichment

Enrich a list of requests as a `Flow` — results emit one at a time as each completes:

```kotlin
engine.enrichBatch(
    listOf(
        EnrichmentRequest.forAlbum("OK Computer", "Radiohead"),
        EnrichmentRequest.forAlbum("Kid A", "Radiohead"),
        EnrichmentRequest.forAlbum("The Bends", "Radiohead"),
    ),
    setOf(EnrichmentType.ALBUM_ART, EnrichmentType.GENRE),
).collect { (request, results) ->
    val title = (request as EnrichmentRequest.ForAlbum).title
    updateUI(title, results.albumArt(), results.genres())
}
```

Cache hits return immediately. Cancel the Flow via `take(N)` to stop early. See [cache-management.md](cache-management.md) for offline fallback with `CacheMode.STALE_IF_ERROR`.

---

## Next steps

- [identity-resolution.md](identity-resolution.md) — how identity resolution works under the hood
- [results-and-errors.md](results-and-errors.md) — Tier 2 named accessors and error handling
- [configuration.md](configuration.md) — tuning confidence, TTLs, providers, and recommendations
- [android.md](android.md) — Room cache, Hilt, and WorkManager integration
