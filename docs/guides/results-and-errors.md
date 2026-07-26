# Results and Error Handling

## Three API tiers

| Tier | Return type | Best for |
|------|-------------|----------|
| Tier 1: Profile methods | `ArtistProfile`, `AlbumProfile`, `TrackProfile` | Most use cases — named properties, no casting |
| Tier 2: Named accessors | `EnrichmentResults` | When you need control over which types to request, or per-type error checking |
| Tier 3: Raw map | `Map<EnrichmentType, EnrichmentResult>` | Diagnostics, retry logic, custom aggregation |

See [quick-start.md](quick-start.md) for Tier 1 profile examples. This guide covers Tier 2 and Tier 3.

---

## Tier 2: EnrichmentResults named accessors

`EnrichmentResults` wraps the raw result map with type-safe accessors. Use it when you want named access but need more control than profiles provide.

```kotlin
val results = engine.enrich(
    EnrichmentRequest.forAlbum("OK Computer", "Radiohead"),
    setOf(EnrichmentType.ALBUM_ART, EnrichmentType.GENRE, EnrichmentType.LABEL),
)
```

### Typed accessors

```kotlin
results.albumArt()          // EnrichmentData.Artwork?
results.artistPhoto()       // EnrichmentData.Artwork?
results.biography()         // EnrichmentData.Biography?
results.lyrics()            // EnrichmentData.Lyrics? (prefers synced, falls back to plain)
results.credits()           // EnrichmentData.Credits?
results.similarArtists()    // EnrichmentData.SimilarArtists?
results.similarAlbums()     // EnrichmentData.SimilarAlbums?
results.similarTracks()     // EnrichmentData.SimilarTracks?
results.discography()       // EnrichmentData.Discography?
results.topTracks()         // EnrichmentData.TopTracks?
results.radio()             // EnrichmentData.RadioPlaylist?
results.radioDiscovery()    // EnrichmentData.RadioPlaylist? (LB Radio)
results.trackPreview()      // EnrichmentData.TrackPreview?
results.artistPopularity()  // EnrichmentData.Popularity?
results.trackPopularity()   // EnrichmentData.Popularity?
```

### Metadata field accessors (with fallback)

These unwrap `EnrichmentData.Metadata` fields and automatically fall back to `ALBUM_METADATA` when the specific type has no result:

```kotlin
results.genres()            // List<String> — tries GENRE, falls back to ALBUM_METADATA
results.genreTags()         // List<GenreTag> — same fallback
results.label()             // String? — tries LABEL, falls back to ALBUM_METADATA
results.releaseDate()       // String? — tries RELEASE_DATE, falls back to ALBUM_METADATA
results.releaseType()       // String? — tries RELEASE_TYPE, falls back to ALBUM_METADATA
results.country()           // String? — tries COUNTRY, falls back to ALBUM_METADATA
```

### Generic typed accessor

For types without a named accessor, use the generic `get<T>()`:

```kotlin
val background = results.get<EnrichmentData.Artwork>(EnrichmentType.ARTIST_BACKGROUND)
val timeline = results.get<EnrichmentData.ArtistTimeline>(EnrichmentType.ARTIST_TIMELINE)
val members = results.get<EnrichmentData.BandMembers>(EnrichmentType.BAND_MEMBERS)
```

### Diagnostics

```kotlin
// Was this type part of the request?
results.wasRequested(EnrichmentType.LYRICS_SYNCED)  // true/false

// Get the raw EnrichmentResult for error inspection
when (val r = results.result(EnrichmentType.ALBUM_ART)) {
    is EnrichmentResult.Success -> println("Got art from ${r.provider}")
    is EnrichmentResult.NotFound -> println("No art found by ${r.provider}")
    is EnrichmentResult.RateLimited -> println("Rate limited, retry after ${r.retryAfterMs}ms")
    is EnrichmentResult.Error -> println("Error (${r.errorKind}): ${r.message}")
    null -> println("Type was not requested or not in results")
}
```

### Identity resolution on results

```kotlin
results.identity?.identifiers       // EnrichmentIdentifiers (MBIDs, Wikidata, etc.)
results.identity?.match             // IdentityMatch? (RESOLVED, BEST_EFFORT, SUGGESTIONS)
results.identity?.matchScore        // Int? (0-100)
results.identity?.suggestions       // List<SearchCandidate>
```

---

## Tier 3: Raw map

The raw map gives you full `EnrichmentResult` objects with provider name, confidence score, identity match info, and resolved identifiers.

```kotlin
val results = engine.enrich(
    EnrichmentRequest.forArtist("Radiohead"),
    EnrichmentRequest.DEFAULT_ARTIST_TYPES,
)

for ((type, result) in results.raw) {
    when (result) {
        is EnrichmentResult.Success -> {
            println("$type: ${result.provider} (conf=${result.confidence})")
            println("  identity: ${result.identityMatch} score=${result.identityMatchScore}")
            println("  resolved IDs: ${result.resolvedIdentifiers}")
        }
        is EnrichmentResult.NotFound -> println("$type: not found by ${result.provider}")
        is EnrichmentResult.RateLimited -> println("$type: rate limited (${result.provider})")
        is EnrichmentResult.Error -> println("$type: error from ${result.provider}: ${result.message}")
    }
}
```

When to use the raw map:
- Building retry logic based on `RateLimited.retryAfterMs`
- Logging per-provider diagnostics (which provider won, at what confidence)
- Custom aggregation across multiple types
- Provider-specific handling based on `result.provider`

---

## EnrichmentResult sealed class

Every enrichment type produces one of four result variants:

```kotlin
sealed class EnrichmentResult {
    data class Success(
        val type: EnrichmentType,
        val data: EnrichmentData,
        val provider: String,
        val confidence: Float,
        val resolvedIdentifiers: EnrichmentIdentifiers?,
        val identityMatchScore: Int?,
        val identityMatch: IdentityMatch?,
    )

    data class NotFound(
        val type: EnrichmentType,
        val provider: String,
        val suggestions: List<SearchCandidate>?,
        val identityMatch: IdentityMatch?,
    )

    data class RateLimited(
        val type: EnrichmentType,
        val provider: String,
        val retryAfterMs: Long?,
    )

    data class Error(
        val type: EnrichmentType,
        val provider: String,
        val message: String,
        val cause: Throwable?,
        val errorKind: ErrorKind,
    )
}
```

---

## ErrorKind enum

| Value | Cause |
|-------|-------|
| `NETWORK` | Connectivity or timeout failure |
| `AUTH` | 401/403 — check API key |
| `PARSE` | Malformed JSON or unexpected schema |
| `RATE_LIMIT` | 429 response |
| `TIMEOUT` | Engine-level enrichment timeout expired |
| `UNKNOWN` | Uncategorized error |

---

## Per-type error checking

```kotlin
val results = engine.enrich(
    EnrichmentRequest.forAlbum("OK Computer", "Radiohead"),
    setOf(EnrichmentType.ALBUM_ART, EnrichmentType.LYRICS_SYNCED, EnrichmentType.CREDITS),
)

for (type in results.requestedTypes) {
    when (val r = results.result(type)) {
        is EnrichmentResult.Success -> println("$type: OK (${r.provider}, conf=${r.confidence})")
        is EnrichmentResult.NotFound -> println("$type: not found")
        is EnrichmentResult.RateLimited -> println("$type: rate limited, retry in ${r.retryAfterMs}ms")
        is EnrichmentResult.Error -> {
            when (r.errorKind) {
                ErrorKind.NETWORK -> println("$type: network error — ${r.message}")
                ErrorKind.AUTH -> println("$type: auth error — check API key")
                ErrorKind.TIMEOUT -> println("$type: timed out")
                else -> println("$type: error — ${r.message}")
            }
        }
        null -> println("$type: no result (unexpected)")
    }
}
```

---

## Distinguishing "not found" from "all providers failed"

The provider chain tries providers in priority order. If all providers return `NotFound`, the final result is `NotFound` — the data genuinely does not exist. But if a provider returns `RateLimited` or `Error`, the chain preserves that result so you can tell the difference:

```kotlin
when (val r = results.result(EnrichmentType.ALBUM_ART)) {
    is EnrichmentResult.NotFound -> {
        // Providers looked and found nothing — the data does not exist
        println("No album art available")
    }
    is EnrichmentResult.RateLimited -> {
        // A provider was rate limited — data might exist, try again later
        println("Try again in ${r.retryAfterMs ?: "a few"}ms")
    }
    is EnrichmentResult.Error -> {
        // A provider failed — data might exist but could not be fetched
        println("Provider error: ${r.message}")
    }
    is EnrichmentResult.Success -> { /* got data */ }
    null -> { /* type was not requested */ }
}
```

---

## Timeout behavior

The engine applies a global timeout (default 30 seconds, configurable via `enrichTimeoutMs`). Types that have not resolved by the deadline receive an `Error` result with `ErrorKind.TIMEOUT`:

```kotlin
val engine = EnrichmentEngine.Builder()
    .withDefaultProviders()
    .config(EnrichmentConfig(enrichTimeoutMs = 10_000)) // 10 seconds
    .build()

val results = engine.enrich(
    EnrichmentRequest.forArtist("Radiohead"),
    EnrichmentRequest.DEFAULT_ARTIST_TYPES,
)

// Types that finished in time have their normal result.
// Types that timed out have Error with TIMEOUT kind.
results.raw.filter { (_, r) ->
    r is EnrichmentResult.Error && r.errorKind == ErrorKind.TIMEOUT
}.forEach { (type, _) ->
    println("$type timed out")
}
```

Types that complete before the timeout are not affected, even if other types are still in flight.

---

## Failure isolation guarantees

The engine resolves each enrichment type independently, and `enrich()` never throws. A provider
failure — network error, rate limit, timeout — is a typed result on that one type; every other type
returns as normal. Profile accessors are independently nullable for the same reason:

```kotlin
val profile = engine.artistProfile("Radiohead")

// Genre was rate limited, but bio and photo succeeded
profile.results.result(EnrichmentType.GENRE)         // -> RateLimited
profile.results.result(EnrichmentType.ARTIST_BIO)    // -> Success

profile.bio?.text    // -> "Radiohead are an English rock band..."
profile.genres       // -> emptyList() (failed gracefully)
```

### A throwing cache degrades to a miss

The cache is an optimisation, so if your `EnrichmentCache` throws — a Room disk error, say —
`enrich()` logs it and carries on. A failed read degrades to a cache miss and the providers are
queried; a failed write degrades to the result simply not being cached. It is never surfaced to the
caller as an exception.

### A throwing merger or synthesizer is reported, not degraded

A `ResultMerger` or `CompositeSynthesizer` registered via `addMerger` / `addSynthesizer` runs
guarded: if yours throws, that type comes back as `Error` and every other type in the call —
including results already resolved and cache hits already collected — is returned as normal.

It is reported rather than degraded to a miss because a merger *produces* the type's result, so
swallowing the failure would be indistinguishable from a genuine `NotFound`.

### Cancellation still propagates

If the calling coroutine is cancelled, `CancellationException` is rethrown as structured concurrency
requires — swallowing it would make `enrich()` uncancellable. That is not a failure result to
handle; it means the caller went away.

The `enrichTimeoutMs` deadline is *not* this case: expiry is caught internally and returned as
`Error` results with `ErrorKind.TIMEOUT`, as above.
