# Identity Resolution

Identity resolution is the step that makes downstream providers accurate. Before fanning out to content providers, the engine calls MusicBrainz to resolve a stable ID for the entity. This section explains how that works and what to do when it does not.

## How it works

1. The engine calls `MusicBrainzProvider.resolveIdentity()` with the request (title + artist text).
2. MusicBrainz returns search results ranked by score (0–100).
3. If the top result is confident enough, the engine populates `EnrichmentIdentifiers` on the request with the MBID, Wikidata ID, and Wikipedia title.
4. All subsequent providers receive the enriched request and can do ID-based lookups instead of fuzzy text search.

Without identity resolution, providers fall back to name-matching against their own catalogs — less precise and more prone to returning data for the wrong entity.

---

## IdentityMatch enum

Every enrichment result carries an `identityMatch` field describing how the resolution went:

| Value | Meaning | What to do |
|-------|---------|------------|
| `RESOLVED` | MusicBrainz found a confident match | Show results normally |
| `BEST_EFFORT` | Identity resolution failed, but providers returned results via fuzzy search | Show results with a caveat — they may be for the wrong entity |
| `SUGGESTIONS` | Identity resolution found near-miss candidates but no confident match | Show a "did you mean?" prompt |

A `null` identity match on `results.identity` means resolution was not attempted — either an MBID was provided upfront, results came from cache, or resolution is disabled. Treat as confident.

---

## Identity info on EnrichmentResults

Every `EnrichmentResults` object carries top-level identity resolution info:

```kotlin
val results = engine.enrich(
    EnrichmentRequest.forArtist("Radiohead"),
    setOf(EnrichmentType.GENRE),
)

val identity = results.identity
identity?.identifiers?.musicBrainzId    // "a74b1b7f-..."
identity?.identifiers?.wikidataId       // "Q44191"
identity?.identifiers?.wikipediaTitle   // "Radiohead"
identity?.match                         // IdentityMatch.RESOLVED
identity?.matchScore                    // 100
identity?.suggestions                   // List<SearchCandidate> (non-empty when SUGGESTIONS)
```

---

## The "did you mean?" flow

When the query is ambiguous ("Bush" matches both the British and Canadian bands), the engine returns `SUGGESTIONS` with near-miss candidates instead of guessing:

```kotlin
val results = engine.enrich(
    EnrichmentRequest.forArtist("Bush"),
    setOf(EnrichmentType.GENRE, EnrichmentType.ARTIST_BIO),
)

when (results.identity?.match) {
    IdentityMatch.RESOLVED -> {
        println("Genres: ${results.genres()}")
    }
    IdentityMatch.SUGGESTIONS -> {
        val suggestions = results.identity?.suggestions ?: emptyList()
        suggestions.forEach { candidate ->
            println("${candidate.title} — ${candidate.disambiguation} (${candidate.score}%)")
            // "Bush — British rock band (95%)"
            // "Bush — Canadian band (82%)"
        }
        // User picks one, re-enrich with the candidate's MBID
        val chosen = suggestions.first()
        val resolved = engine.enrich(
            EnrichmentRequest.forArtist(chosen.title, mbid = chosen.identifiers.musicBrainzId),
            setOf(EnrichmentType.GENRE, EnrichmentType.ARTIST_BIO),
        )
    }
    IdentityMatch.BEST_EFFORT -> {
        // Results came from fuzzy search — may be wrong
        println("Results may not be accurate: ${results.genres()}")
    }
    null -> {
        // Resolution not attempted (MBID provided, cached, or disabled)
        println("Genres: ${results.genres()}")
    }
}
```

With profile methods, the same flow is available via `profile.suggestions` and the `SearchCandidate` overloads — see [quick-start.md](quick-start.md).

---

## SearchCandidate fields

`SearchCandidate` is the type returned for both suggestions and `engine.search()` results:

| Field | Type | Description |
|-------|------|-------------|
| `title` | `String` | Artist name or album/track title |
| `artist` | `String?` | Artist name (null for artist queries) |
| `year` | `String?` | Release year |
| `country` | `String?` | Release country code (e.g., "GB") |
| `releaseType` | `String?` | "Album", "Single", "EP", etc. |
| `score` | `Int` | MusicBrainz relevance score (0–100) |
| `thumbnailUrl` | `String?` | Cover art or artist image thumbnail |
| `identifiers` | `EnrichmentIdentifiers` | MBIDs and linked IDs — use `identifiers.musicBrainzId` when re-enriching |
| `provider` | `String` | Source provider (typically "musicbrainz") |
| `disambiguation` | `String?` | MusicBrainz disambiguation comment (e.g., "British rock band" vs "Canadian band") |

---

## Using search() for manual disambiguation

The `search()` method returns candidates without running enrichment. Use it for search-ahead UIs where the user picks an entity before the app fetches metadata:

```kotlin
val candidates = engine.search(
    EnrichmentRequest.forAlbum("Dark Side", "Pink Floyd"),
    limit = 5,
)

candidates.forEach { c ->
    println("${c.title} by ${c.artist} (${c.year}) — ${c.score}%")
    println("  MBID: ${c.identifiers.musicBrainzId}")
    println("  Disambiguation: ${c.disambiguation}")
    println("  Thumbnail: ${c.thumbnailUrl}")
}

// User picks one, then enrich with its MBID
val chosen = candidates.first()
val results = engine.enrich(
    EnrichmentRequest.forAlbum(chosen.title, chosen.artist ?: "", chosen.identifiers.musicBrainzId),
    EnrichmentRequest.DEFAULT_ALBUM_TYPES,
)
```

`engine.search()` is also a `suspend fun` and must be called from a coroutine.

---

## Skipping identity resolution

Provide an MBID upfront to skip the MusicBrainz search entirely. Downstream providers receive the identifier directly:

```kotlin
val results = engine.enrich(
    EnrichmentRequest.forArtist("Radiohead", mbid = "a74b1b7f-71a5-4011-9441-d0b5e4122711"),
    setOf(EnrichmentType.GENRE),
)
// results.identity will be null — resolution was not needed
```

You can also disable resolution globally:

```kotlin
val engine = EnrichmentEngine.Builder()
    .withDefaultProviders()
    .config(EnrichmentConfig(enableIdentityResolution = false))
    .build()
```

With resolution disabled, all providers fall back to fuzzy text search. Use this only when you always supply MBIDs upfront, or when MusicBrainz availability is a concern.

---

## Cache key convergence after disambiguation

When the user picks a disambiguation candidate, the re-enrichment request carries the resolved MBID. The engine generates the cache key from the canonical (lowercase, normalized) form of the request plus MBID. Subsequent lookups for the same entity — even from different initial spellings — converge to the same cache key after resolution.

This means a query for "bush" and a query for "Bush" that both resolve to the same MBID will share cached results. See [cache-management.md](cache-management.md) for more on cache key structure.
