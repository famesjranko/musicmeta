# Identity Resolution

Identity resolution is the step that makes downstream providers accurate. Before fanning out to content providers, the engine calls MusicBrainz to resolve a stable ID for the entity. This section explains how that works and what to do when it does not.

## How it works

1. The engine calls `MusicBrainzProvider.resolveIdentity()` with the request (title + artist text).
2. MusicBrainz returns search results ranked by its own score (0–100), which the mapper divides by 100 before it reaches `matchScore`.
3. If the top result is confident enough, the engine populates `EnrichmentIdentifiers` on the request with the MBID, Wikidata ID, and Wikipedia title.
4. All subsequent providers receive the enriched request and can do ID-based lookups instead of fuzzy text search.

Without identity resolution, providers fall back to name-matching against their own catalogs — less precise and more prone to returning data for the wrong entity.

---

## CanonicalStatus and LookupProvenance

Two independent facts, never merged into one value:

- `results.identity.status: CanonicalStatus` — how MusicBrainz's canonical resolution went for
  this call, set exactly once. Never `null` — every reason resolution did not run has its own
  explicit state.
- `EnrichmentResult.Success.provenance: LookupProvenance` — how *that provider* selected the
  entity behind its own result, not whether MusicBrainz agreed. `EXACT_NAME` requires that
  provider's own hit to come back under the requested title, compared and not merely scored;
  resolving by identifier compares no name to anything, so an identifier that resolved does not by
  itself earn a downstream name-searching provider `EXACT_NAME` — except when the request named no
  entity at all, where MusicBrainz's own resolved name stands in as `EXACT_NAME` by construction.
  Full route table: [how-it-works.md](../how-it-works.md).

| `CanonicalStatus` | Meaning | What to do |
|---|---|---|
| `RESOLVED` | MusicBrainz confirmed the entity | Show results normally |
| `AMBIGUOUS` | Near-miss candidates but no confident match | Show a "did you mean?" prompt |
| `UNRESOLVED` | Searched, found neither a match nor candidates | Show results with a caveat — they may be for the wrong entity |
| `CONTRADICTED` | An identifier on the request disagreed with the name or year beside it | Tell the user their identifier is wrong; the results beside it are the entity they *named*, and may be complete — see the exception below |
| `FAILED` | The identity provider errored — usually transient | Show a caveat; offer a retry, which may resolve |
| `RESOLVING` | Identity resolution is still running for this call | Only seen on a pre-terminal `enrichProgressive` emission — never `enrich()`'s return or a terminal emission |
| `NOT_ATTEMPTED_DISABLED` | Identity resolution is turned off | Treat as confident |
| `NOT_ATTEMPTED_IDENTIFIER_TRUSTED` | The request carried an identifier, so nothing was resolved | Treat as your own assertion carried through — trusted, not verified |
| `NOT_ATTEMPTED_CACHE_HIT` | Every requested type was served from cache, on an engine with resolution enabled | Treat as confident |
| `NOT_ATTEMPTED_NO_PROVIDER` | Needed resolution, but no identity provider is registered | Treat as confident |

`CONTRADICTED` fires two ways: an identifier naming a confidently different artist, or a supplied
album `year` landing two or more years before the release group's own first release (a *later* year
is never judged — it could be any reissue or regional pressing). `RELEASE_EDITIONS` applies the same
year check to a supplied release-group id.

Not every type can fall back to the name that recovered it. `CREDITS` and `RELEASE_EDITIONS` are
identifier-only — there is no title to search on — so a contradicting identifier leaves them
`NotFound` rather than "complete, but by name". Every other requested type still answers under the
name.

`identity.identifiers` is not cleared by a `CONTRADICTED` verdict: when nothing else on the call
supplies fresh identifiers, the request's own come back unchanged — including the one just reported
wrong. Never re-supply identifiers read off a `CONTRADICTED` response on a later call.

An album request with a blank artist and a real title is a related but separate case: MusicBrainz
widens the search instead of refusing it, so the candidate pool comes back as `AMBIGUOUS` rather
than resolving. The equivalent track request answers `NotFound` without spending a search at all —
see [providers.md](../providers.md) for the underlying rule.

---

## Identity info on EnrichmentResults

Every `EnrichmentResults` object carries top-level identity resolution info:

```kotlin
val results = engine.enrich(
    EnrichmentRequest.forArtist("Radiohead"),
    setOf(EnrichmentType.GENRE),
)

val identity = results.identity
identity.identifiers.musicBrainzId    // "a74b1b7f-..."
identity.identifiers.wikidataId       // "Q44191"
identity.identifiers.wikipediaTitle   // "Radiohead"
identity.status                       // CanonicalStatus.RESOLVED
identity.matchScore                   // 1.0f
identity.suggestions                  // List<SearchCandidate> (non-empty when AMBIGUOUS)
identity.title                        // canonical title of the entity, when one was looked up
identity.artist                       // canonical artist credit, joined as MusicBrainz joins it
```

`title` and `artist` are the names read off the entity an **identifier** named, which is the only
way a caller using `EnrichmentRequest.forTrackByMbid` and its siblings learns what their identifier
resolved to. They stay `null` when resolution matched by name search: a search hit is what a *name*
matched, not what an identifier named. An artist's own name arrives as `title` with `artist` null,
the same shape a `SearchCandidate` for an artist has.

`matchScore` measures how well the lookup went, not whether the entity is the one the caller meant.
A request carrying an identifier resolves by looking it up, and that lookup scores 1.0 whether or
not the identifier names what the caller described — a wrong-but-live MBID resolves at full score.
Read `status` for that question instead: `CONTRADICTED` is the only status that reports a supplied
identifier naming something else, and `NOT_ATTEMPTED_IDENTIFIER_TRUSTED` means nobody checked at
all. The same caveat applies to `EnrichmentResult.Success.confidence`.

---

## The "did you mean?" flow

When the query is ambiguous ("Bush" matches both the British and Canadian bands), the engine returns `AMBIGUOUS` with near-miss candidates instead of guessing:

```kotlin
val results = engine.enrich(
    EnrichmentRequest.forArtist("Bush"),
    setOf(EnrichmentType.GENRE, EnrichmentType.ARTIST_BIO),
)

when (results.identity.status) {
    CanonicalStatus.RESOLVED,
    CanonicalStatus.NOT_ATTEMPTED_DISABLED,
    CanonicalStatus.NOT_ATTEMPTED_IDENTIFIER_TRUSTED,
    CanonicalStatus.NOT_ATTEMPTED_CACHE_HIT,
    CanonicalStatus.NOT_ATTEMPTED_NO_PROVIDER -> {
        println("Genres: ${results.genres()}")
    }
    // The identifier you passed names a different entity. The results are still worth showing —
    // they describe the name you passed, which the engine fell back to — but the identifier is
    // wrong and nothing else in the response will tell you so. identity.identifiers may still
    // carry the identifier this verdict just disowned, so never read it back for a later call.
    CanonicalStatus.CONTRADICTED -> {
        println("That MBID is not this entity. Genres, by name: ${results.genres()}")
    }
    // enrich()'s return never carries RESOLVING — only a pre-terminal enrichProgressive
    // emission can. Listed to keep the when exhaustive.
    CanonicalStatus.RESOLVING -> Unit
    CanonicalStatus.AMBIGUOUS -> {
        val suggestions = results.identity.suggestions
        suggestions.forEach { candidate ->
            println("${candidate.title} — ${candidate.disambiguation} (%.2f)".format(candidate.matchScore))
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
    CanonicalStatus.UNRESOLVED -> {
        // Results came from fuzzy search — may be wrong
        println("Results may not be accurate: ${results.genres()}")
    }
    CanonicalStatus.FAILED -> {
        // Identity provider errored (usually transient) — same fuzzy results, but retrying may fix
        println("Couldn't verify the match — try again: ${results.genres()}")
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
| `provider` | `String` | Source provider (typically "musicbrainz") |
| `identifiers` | `EnrichmentIdentifiers` | MBIDs and linked IDs — use `identifiers.musicBrainzId` when re-enriching |
| `matchScore` | `Float` | 0.0–1.0, how well the candidate matched within its own provider's pool |
| `artist` | `String?` | Artist name (null for artist queries) |
| `year` | `String?` | Release year |
| `country` | `String?` | Release country code (e.g., "GB") |
| `releaseType` | `String?` | "Album", "Single", "EP", etc. |
| `thumbnailUrl` | `String?` | Cover art or artist image thumbnail. Always null on a MusicBrainz candidate — a release search response carries no cover-art flag — so it is populated only for the Deezer and iTunes candidates `search()` adds |
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
    println("${c.title} by ${c.artist} (${c.year}) — %.2f".format(c.matchScore))
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
// results.identity.status == CanonicalStatus.NOT_ATTEMPTED_IDENTIFIER_TRUSTED — the identifier was
// trusted, not checked
```

You can also disable resolution globally:

```kotlin
val engine = EnrichmentEngine.Builder()
    .config(EnrichmentConfig(enableIdentityResolution = false))
    .withDefaultProviders()
    .build()
```

With resolution disabled, all providers fall back to fuzzy text search. Use this only when you always supply MBIDs upfront, or when MusicBrainz availability is a concern.

---

## Cache key convergence after disambiguation

When the user picks a disambiguation candidate, the re-enrichment request carries the resolved MBID. The engine keys that exact-bearing call from the complete request tuple, including its names, selectors, and identifiers. A canonical-name alias is also written only when this identity-resolution step supplied the canonical names; later name-only lookups can then reuse that explicitly resolved result. An exact-bearing lookup never reads through a bare-name alias, because a caller-supplied name is not proof that it names the same entity as the identifier. See [cache-management.md](cache-management.md) for the cache-key contract.
