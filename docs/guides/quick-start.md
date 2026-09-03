# Quick Start

Get up and running in 5 minutes. The profile methods, `enrich()` and `search()` are `suspend fun`, so most examples below must run inside a coroutine; `enrichProgressive()`, `enrichBatch()`, `enrichBatchProgressive()` and `close()` are ordinary functions.

## Engine setup

```kotlin
val engine = EnrichmentEngine.Builder()
    // Required by MusicBrainz and Wikimedia: a URL or email they can reach you at.
    .contact("https://example.com/myapp")
    .apiKeys(ApiKeyConfig(
        lastFmKey = "...",              // optional — enables Last.fm
        fanartTvProjectKey = "...",     // optional — enables Fanart.tv
        discogsPersonalToken = "...",   // optional — enables Discogs
        listenBrainzToken = "...",      // optional — enables LB Radio discovery
    ))
    .withDefaultProviders()  // last: reads the contact and keys set above
    .build()
```

Omit `.contact()` and the engine logs one warning at `build()`: the default User-Agent carries no
contact information, so MusicBrainz throttles you against its shared anonymous pool and Wikimedia
may answer 403. Details in [providers.md](../providers.md#user-agent-and-contact-information).

8 of 11 providers work without API keys. `withDefaultProviders()` registers all of them and conditionally adds key-requiring providers only when their key is present.

Call `engine.close()` when you are done with the engine. It releases the scope that fan-outs you stopped collecting keep running on — see [streaming.md](streaming.md).

### With OkHttp (recommended for Android)

```kotlin
// Add: implementation("io.github.famesjranko:musicmeta-okhttp:0.12.0")
val engine = EnrichmentEngine.Builder()
    .httpClient(OkHttpEnrichmentClient(myOkHttpClient, "MyApp/1.0 ( https://example.com/myapp )"))
    .withDefaultProviders()
    .build()
```

This replaces the default `HttpURLConnection` transport with your existing `OkHttpClient` — interceptors, certificate pinning, and connection pooling all apply.

Write the contact into that User-Agent string yourself: `.contact()` cannot reach a client you pass to `.httpClient()`, so the requirement above is yours to satisfy here.

---

## Tier 1: Profile methods

Profile methods return structured data classes with named properties. No casting, no map lookups, no sealed-class matching. All are `suspend fun` — call them from a coroutine or `runBlocking`.

### Artist profile

```kotlin
// Inside a coroutine or runBlocking { }
val profile = engine.artistProfile("Radiohead")

// Identity
profile.identifiers.musicBrainzId   // "a74b1b7f-71a5-4011-9441-d0b5e4122711"
profile.canonicalStatus              // CanonicalStatus.RESOLVED
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
profile.topTracks?.tracks            // List<TopTrack> merged from Last.fm, ListenBrainz and Deezer
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
profile.canonicalStatus
profile.identityMatchScore
profile.suggestions                  // List<SearchCandidate> when status is AMBIGUOUS

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
profile.canonicalStatus
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
profile.trackMetadata?.durationMs    // track length in milliseconds

// Stats & recommendations
profile.popularity?.listenCount
profile.similarTracks?.tracks        // List<SimilarTrack> with matchScore and sources
profile.preview?.url                 // 30-second MP3 preview URL (Deezer)
profile.genreDiscovery               // List<GenreAffinity>
```

---

## Custom type sets

By default, profile methods request all types relevant to the entity (16 for artists, 15 for albums, 9 for tracks). Override to request fewer types for faster responses:

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

When identity resolution is ambiguous, profile methods report `AMBIGUOUS` and offer the near misses. Results still arrive — every provider that searches by name answers regardless — but they may describe the wrong entity, so check the status before trusting them and re-enrich from the chosen candidate:

```kotlin
val profile = engine.artistProfile("Bush")

if (profile.canonicalStatus == CanonicalStatus.AMBIGUOUS) {
    println("Did you mean?")
    profile.suggestions.forEach { candidate ->
        println("  ${candidate.title} — ${candidate.disambiguation} (${candidate.score}%)")
    }

    val chosen = profile.suggestions.first()

    // Re-enrich using the candidate — its MBID pins the entity, so nothing
    // is left for a name search to get wrong
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

If you already know the MusicBrainz ID, pass it and MusicBrainz looks the entity up under that id
instead of searching for the name:

```kotlin
val profile = engine.artistProfile(
    name = "Radiohead",
    mbid = "a74b1b7f-71a5-4011-9441-d0b5e4122711",
)
// profile.canonicalStatus == CanonicalStatus.RESOLVED
```

Identity resolution still runs here, because the default artist type set asks for a photo and a bio
— types keyed on a Wikidata id and a Wikipedia title, which only resolution can supply. Ask for
types the MusicBrainz id alone satisfies and nothing is looked up at all:

```kotlin
val genresOnly = engine.artistProfile(
    name = "Radiohead",
    mbid = "a74b1b7f-71a5-4011-9441-d0b5e4122711",
    types = setOf(EnrichmentType.GENRE),
)
// genresOnly.canonicalStatus == CanonicalStatus.NOT_ATTEMPTED_IDENTIFIER_TRUSTED
```

`NOT_ATTEMPTED_IDENTIFIER_TRUSTED` means nobody checked that the id names the entity you described
— it is your assertion carried through, not MusicBrainz agreeing with it.

---

## Fast path: pre-resolved identifiers

Enrichment results carry the identifiers they were resolved with, and top tracks carry their own
(a Deezer id, readable via `identifiers.get(IdentifierNamespace.DEEZER)`, for one). Passing them
back lets the provider that recognises one skip its own search and go straight to a direct lookup:

```kotlin
// Deezer looks the track up by id instead of searching for it
val preview = engine.trackProfile(
    title = topTrack.title,
    artist = topTrack.artist,
    identifiers = topTrack.identifiers,  // has a Deezer id
    types = setOf(EnrichmentType.TRACK_PREVIEW),
)
```

What this saves is that provider's own search, not the MusicBrainz round trip: identity resolution
runs unless the request carries a MusicBrainz id every requested type is content with, and a Deezer
id never suppresses it.

`resolveTrackPreviews` does the same for a list, resolving concurrently:

```kotlin
val previews = engine.resolveTrackPreviews(
    topTracks.map { TrackPreviewRequest(it.title, it.artist, identifiers = it.identifiers) }
)
previews.forEach { println("${it.title}: ${it.preview?.url}") }
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

Cache hits return immediately. Cancelling collection — `take(N)`, or leaving the screen — stops the
remaining requests from starting, but does not abort the one already in flight: that fan-out keeps
running until it settles or hits the enrich timeout, still writes its results to the cache, and
still spends rate-limit budget while nobody is watching. See [streaming.md](streaming.md) for that
contract in full, and [cache-management.md](cache-management.md) for offline fallback with
`CacheMode.STALE_IF_ERROR`.

Both `enrich()` and `enrichBatch()` wait for a complete answer. `enrichProgressive()` and
`enrichBatchProgressive()` emit as each type settles instead — see [streaming.md](streaming.md).

---

## Next steps

- [identity-resolution.md](identity-resolution.md) — how identity resolution works under the hood
- [results-and-errors.md](results-and-errors.md) — Tier 2 named accessors and error handling
- [streaming.md](streaming.md) — progressive results, one type at a time, as they settle
- [configuration.md](configuration.md) — tuning confidence, TTLs, providers, and recommendations
- [android.md](android.md) — Room cache, Hilt, and WorkManager integration
