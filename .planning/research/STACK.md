# Stack Research

**Domain:** Music recommendation engine (Kotlin/JVM library, v0.6.0 additions)
**Researched:** 2026-03-23
**Confidence:** HIGH

---

## Summary

No new library dependencies are needed for v0.6.0. All 7 recommendation modules can be built
using the existing stack. The work is API integration, synthesis logic, and engine wiring —
not new infrastructure.

---

## Existing Stack (No Changes Required)

The following are already present and sufficient for all v0.6.0 work:

| Technology | Version | Role |
|------------|---------|------|
| Kotlin/JVM | 2.1.0 | Language |
| kotlinx.coroutines | 1.9.0 | Concurrency (`async`, `coroutineScope`) for fan-out |
| org.json | 20231013 | JSON parsing in all provider `*Api.kt` files |
| kotlinx.serialization-json | 1.7.3 | `EnrichmentData` serialization |
| Java 17 | — | Runtime target |

No additions to `gradle/libs.versions.toml` are required.

---

## Module 1: Deezer SIMILAR_ARTISTS

**Endpoint:** `GET https://api.deezer.com/artist/{id}/related`

**Response shape:**
```json
{
  "data": [
    {
      "id": 1234,
      "name": "Thom Yorke",
      "picture_small": "...",
      "picture_medium": "...",
      "picture_big": "...",
      "picture_xl": "...",
      "nb_album": 5,
      "nb_fan": 123456,
      "radio": true
    }
  ]
}
```

**Rate limit:** Same ~50 req/5s budget as existing Deezer calls. Use the existing 100ms
`RateLimiter`. No separate limiter needed — the shared `rateLimiter` instance passed into
`DeezerApi` already serialises all Deezer calls.

**Auth:** None. Public endpoint, same as all current Deezer usage.

**ID source:** The Deezer artist ID is already stored in `resolvedIdentifiers.extra["deezerId"]`
whenever `enrichDiscography()` resolves an artist (see `DeezerProvider.enrichDiscography()`).
For SIMILAR_ARTISTS requests where ARTIST_DISCOGRAPHY was not previously called, a name-based
`searchArtist()` lookup is required first — same pattern already used for discography.

**Integration pattern:** Add `getRelatedArtists(artistId: Long)` to `DeezerApi`. Add
`SIMILAR_ARTISTS` capability (priority 50 — fallback to Last.fm primary) to `DeezerProvider`.
Map `id`/`name`/`picture_medium` to `SimilarArtist(name, matchScore=0.8f, identifiers)` with
the Deezer ID stored in `identifiers.extra["deezerId"]`. The fixed matchScore of 0.8 is
appropriate — Deezer's related endpoint does not return a similarity score.

**Confidence:** HIGH — verified against official Deezer API wiki and deezer-python library docs.

---

## Module 2: Deezer SIMILAR_TRACKS (Radio endpoint)

**Endpoint:** `GET https://api.deezer.com/artist/{id}/radio`

**Response shape:**
```json
{
  "data": [
    {
      "id": 5678,
      "title": "Everything in Its Right Place",
      "readable": true,
      "duration": 265,
      "rank": 890000,
      "explicit_lyrics": false,
      "preview": "https://cdns-preview-x.dzcdn.net/...",
      "artist": {
        "id": 399,
        "name": "Radiohead"
      },
      "album": {
        "id": 103248,
        "title": "Kid A",
        "cover_medium": "..."
      }
    }
  ]
}
```

**Rate limit:** Same 100ms limiter. Radio returns up to 25 tracks per call. No pagination.

**Auth:** None.

**ID source:** Same artist ID lookup pattern as SIMILAR_ARTISTS above. Check
`identifiers.extra["deezerId"]` first; fall back to `searchArtist(name)` if missing.

**Integration pattern:** Add `getArtistRadio(artistId: Long)` to `DeezerApi`. Map each track
to `SimilarTrack(title, artist=artist.name, matchScore=0.7f, identifiers)`. The fixed 0.7
matchScore reflects that this is a genre-compatible seeded track list, not a direct similarity
score. Add `SIMILAR_TRACKS` capability (priority 50 — fallback to Last.fm primary) to
`DeezerProvider`.

**Confidence:** HIGH — response shape verified against Deezer API wiki and deezer-python docs.

---

## Module 3: ListenBrainz CF Recommendations

**Endpoint:** `GET https://api.listenbrainz.org/1/cf/recommendation/user/{user_name}/recording`

**Authentication:** PUBLIC — no user token required to read CF recommendations for a given
username. (Write/feedback endpoints require tokens; read does not.)

**Response shape:**
```json
{
  "payload": {
    "last_updated": 1685000000,
    "mbids": [
      { "recording_mbid": "526bd613-fddd-4bd6-9137-ab709ac74cab", "score": 9.345 },
      { "recording_mbid": "...", "score": 6.998 }
    ],
    "user_name": "exampleuser",
    "count": 25,
    "total_mbid_count": 1000,
    "offset": 0
  }
}
```

**Query parameters:** `count` (default 25), `offset` (default 0) for pagination.

**Score:** Raw CF score (unbounded float, higher = more recommended). Normalise to 0–1 by
dividing by the maximum score in the result set, or use linear scaling against a known
practical maximum (~15 observed). Use `ConfidenceCalculator.fuzzyMatch()` as the provider
confidence since CF quality depends on how much listening history the user has.

**User-scoped nature:** This endpoint requires a ListenBrainz `user_name` — it returns
recommendations for a specific user, not for an anonymous artist query. This is fundamentally
different from all other enrichment types, which are entity-centric (artist/album/track).

**Integration design:** Pass the user name as an `EnrichmentConfig` property (e.g.,
`listenBrainzUserName: String?`) rather than via `EnrichmentRequest`. The provider checks
`isAvailable` based on whether the username is configured. `ForArtist` requests can match the
artist MBID against returned recording MBIDs via a MusicBrainz lookup — but that adds a second
API call. Simpler: return the raw recording list as `SimilarTracks` (recording_mbid in
identifiers) and let the consumer resolve details. Pagination via `offset`/`count` should be
supported in the `ListenBrainzApi` method but not exposed in the provider (fetch first page
only by default, configurable via constructor param).

**Confidence:** HIGH — verified against official ListenBrainz readthedocs.

---

## Module 4: SIMILAR_ALBUMS (Synthesis)

**No new API calls needed.** SIMILAR_ALBUMS is a composite type synthesized from existing
data already in the enrichment pipeline.

**Algorithm:** Resolve SIMILAR_ARTISTS, GENRE, and ARTIST_DISCOGRAPHY, then cross-reference:

1. Take top N similar artists (from Last.fm/ListenBrainz/Deezer providers)
2. For each similar artist, find albums in their discography with release year within
   ±5 years of the seed album (era matching)
3. Filter by genre overlap: compute intersection of GenreTag names between seed and candidate
   (using the normalised names from `GenreMerger.normalize()` — already public on the object)
4. Score each candidate album: `matchScore = artistMatch * eraScore * genreOverlap`
5. Sort by score, deduplicate by artist+album title normalised, take top 20

**Scoring specifics:**
- `artistMatch` = the `SimilarArtist.matchScore` from the provider (0–1)
- `eraScore` = `1.0 - (|yearDiff| / 5).coerceIn(0f, 1f)` (0 if >5 years apart, 1.0 if same year)
- `genreOverlap` = `sharedTags / maxTags` where shared = count of normalised tag names in
  both sets; if GENRE data is unavailable, default to 1.0 (no penalty)

**Output type:** New `EnrichmentData.SimilarAlbums` with `albums: List<SimilarAlbum>` where
`SimilarAlbum` carries `title`, `artist`, `year`, `score: Float`, and `identifiers`.

**Synthesizer:** Implement as `SimilarAlbumSynthesizer` following the `TimelineSynthesizer`
pattern — a stateless `object` with a pure `synthesize(similarArtists, discographies, genre)`
function. Wire it into `DefaultEnrichmentEngine.synthesizeComposite()`.

**COMPOSITE_DEPENDENCIES entry:**
```kotlin
EnrichmentType.SIMILAR_ALBUMS to setOf(
    EnrichmentType.SIMILAR_ARTISTS,
    EnrichmentType.ARTIST_DISCOGRAPHY,
    EnrichmentType.GENRE,
)
```

**No external library needed** — the scoring algorithm is simple arithmetic. Cosine similarity
or more complex ML-based approaches are out of scope; the confidence of the result is bounded
by the confidence of the upstream similar-artists provider anyway.

**Confidence:** HIGH — algorithm derived from existing GenreMerger and TimelineSynthesizer
patterns already proven in the codebase.

---

## Module 5: Credit-Based Discovery

**No new API calls needed.** This synthesizes from existing CREDITS data.

**Query pattern:** Consumer calls `enrich(ForArtist(name="Brian Eno"), setOf(CREDITS))` and
gets back a `Credits` list. Credit-based discovery inverts this: given a seed track/album with
CREDITS already resolved, find other releases featuring the same producer/composer.

**Implementation:**

There are two valid approaches. Approach A is simpler and fits the existing engine model:

**Approach A (recommended): Engine-level synthesis**
- New composite type `CREDIT_DISCOVERY` depends on `CREDITS`
- Engine calls `synthesizeComposite()` with the credits result
- `CreditDiscoverySynthesizer` filters credits by `roleCategory == "production"` (or
  `roleCategory == "composition"`) and returns those credits as `SimilarArtists` with
  the credit person's name and MBID (from `Credit.identifiers.musicBrainzId`)
- Consumer can then call `enrich(ForArtist(name=creditPerson.name, mbid=creditMbid),
  setOf(ARTIST_DISCOGRAPHY))` to browse their other work

**Approach B (deferred): Cross-entity query**
- "Find all albums by producer X" requires querying MusicBrainz recording relationships
  by person MBID — a new `MusicBrainzApi.getRecordingsByContributor(personMbid)` call
- HIGH complexity, out of scope for v0.6.0

**Data model addition:** No new `EnrichmentData` subclass strictly required for Approach A —
the result is a filtered view of existing `SimilarArtists` data. However, a dedicated
`CreditedPerson` data class makes intent clearer:
```kotlin
data class CreditedPerson(
    val name: String,
    val role: String,
    val roleCategory: String?,
    val identifiers: EnrichmentIdentifiers,
)
```

**Confidence:** HIGH for Approach A. The existing CREDITS data already carries MBIDs and
roleCategory from MusicBrainzProvider and DiscogsProvider.

---

## Module 6: Genre Affinity Matching

**No new API calls needed.** Uses existing `GenreTag` data with confidence scores from
`GenreMerger`.

**Algorithm:** Given a seed artist with resolved GENRE data (a `List<GenreTag>` with
confidence scores), find genre neighbours via cosine similarity of tag-confidence vectors.

**Implementation in this library:** The library doesn't maintain a corpus of all artists'
genre vectors (that would require a database). The practical implementation is:

1. New composite type `GENRE_AFFINITY` (or named `GENRE_NEIGHBORS`)
2. Depends on `SIMILAR_ARTISTS` and `GENRE`
3. For each similar artist (already resolved), the consumer can enrich them for GENRE
   separately — but that is N additional API calls
4. **Practical v0.6.0 scope:** Implement genre affinity as a utility function
   `GenreAffinityCalculator.similarity(tags1: List<GenreTag>, tags2: List<GenreTag>): Float`
   that returns cosine similarity of the two tag-confidence vectors. This function is usable
   by a consumer who has both genre results and similar-artist genre results, and by
   `SimilarAlbumSynthesizer` for the genre overlap scoring.

**Cosine similarity (pure Kotlin, no library):**
```kotlin
fun similarity(a: List<GenreTag>, b: List<GenreTag>): Float {
    val aMap = a.associate { GenreMerger.normalize(it.name) to it.confidence }
    val bMap = b.associate { GenreMerger.normalize(it.name) to it.confidence }
    val shared = aMap.keys.intersect(bMap.keys)
    val dot = shared.sumOf { (aMap[it]!! * bMap[it]!!).toDouble() }.toFloat()
    val magA = sqrt(aMap.values.sumOf { (it * it).toDouble() }).toFloat()
    val magB = sqrt(bMap.values.sumOf { (it * it).toDouble() }).toFloat()
    if (magA == 0f || magB == 0f) return 0f
    return dot / (magA * magB)
}
```

Uses only `kotlin.math.sqrt` — no dependency. This is a ~10 line pure function.

**Confidence:** HIGH. Cosine similarity on weighted tag vectors is the standard approach for
content-based genre recommendation. No external ML library needed at this scale.

---

## Module 7: Deezer ID Propagation for Related/Radio Endpoints

**Critical constraint for Modules 1 and 2:** Both `GET /artist/{id}/related` and
`GET /artist/{id}/radio` require a numeric Deezer artist ID. The existing code already stores
this in `resolvedIdentifiers.extra["deezerId"]` when `enrichDiscography()` resolves an artist
successfully. However, this identifier is not guaranteed to be present when SIMILAR_ARTISTS
or SIMILAR_TRACKS are requested independently (without prior ARTIST_DISCOGRAPHY resolution).

**Resolution:** The `DeezerProvider.enrichSimilarArtists()` and `enrichSimilarTracks()`
methods must follow this pattern:
1. Check `request.identifiers.extra["deezerId"]` — use directly if present
2. If absent, call `api.searchArtist(name)` to resolve — same pattern as `enrichDiscography()`
3. Store the resolved Deezer ID in `resolvedIdentifiers` of the Success result

This is the same two-step pattern already used by `enrichDiscography()`. No architectural
change required.

---

## Alternatives Considered

| Recommended | Alternative | Why Not |
|-------------|-------------|---------|
| Pure Kotlin cosine similarity | Apache Commons Math `CosineSimilarity` | Adds a JVM dependency for 10 lines of math. No benefit at this scale. |
| Synthesis-based SIMILAR_ALBUMS | Last.fm `album.getsimilar` | Last.fm has no `album.getsimilar` endpoint. No direct album similarity API exists in the existing provider set. |
| `GenreAffinityCalculator` utility | ML embedding service (e.g., word2vec on genre names) | Overkill for a library; requires network call or model loading. |
| User-scoped CF config param | New `ForUser` request type | A `ForUser` request type changes the engine contract for all 6 other recommendation modules. Config param isolates the user-scoping cleanly. |
| Approach A credit discovery | MusicBrainz `getRecordingsByContributor` | Cross-entity query requires new MusicBrainz endpoint, high complexity, deferred to post-v0.6.0. |

---

## What NOT to Add

| Avoid | Why | Use Instead |
|-------|-----|-------------|
| New HTTP client library (OkHttp, Ktor) | Zero benefit — `DefaultHttpClient` handles all 11 providers already | Existing `DefaultHttpClient` |
| Apache Commons Math | 1.6MB jar for cosine similarity | Pure Kotlin 10-line implementation |
| Spotify or Apple Music providers | Out of scope per PROJECT.md — focus on existing 11 | None; existing providers cover all 7 modules |
| `kotlinx.coroutines` version bump | 1.9.0 is current; no new coroutine features needed | Stay at 1.9.0 |
| Room schema changes | Android module out of scope for v0.6.0 | New `EnrichmentType` entries auto-serialise via existing cache |
| ListenBrainz user token storage | CF recommendations are read-only / public | Pass username as plain string config param |

---

## New EnrichmentTypes Required

These additions to the `EnrichmentType` enum are the only schema changes needed:

| Type | Category | TTL | Provider(s) | Composite? |
|------|----------|-----|-------------|------------|
| `SIMILAR_ALBUMS` | Composite | 30 days | synthesizer | Yes |
| `RADIO_MIX` | Relationships | 7 days | Deezer (`/radio`) | No |
| `CREDIT_DISCOVERY` | Composite | 30 days | synthesizer | Yes |
| `GENRE_AFFINITY` | Relationships | 30 days | synthesizer | Yes (optional) |
| `LISTENING_RECS` | Relationships | 1 day | ListenBrainz CF | No |

`RADIO_MIX` and `LISTENING_RECS` use short TTLs because the underlying data changes
frequently (radio is random; CF recommendations refresh periodically).

---

## Integration Points Summary

| Module | Touches | New Files |
|--------|---------|-----------|
| Deezer SIMILAR_ARTISTS | `DeezerApi`, `DeezerModels`, `DeezerProvider` | None — extends existing files |
| Deezer SIMILAR_TRACKS / Radio | `DeezerApi`, `DeezerModels`, `DeezerProvider` | None — extends existing files |
| ListenBrainz CF | `ListenBrainzApi`, `ListenBrainzModels`, `ListenBrainzProvider`, `EnrichmentConfig` | None |
| SIMILAR_ALBUMS | `DefaultEnrichmentEngine`, `EnrichmentData`, `EnrichmentType` | `SimilarAlbumSynthesizer.kt` |
| Credit Discovery | `DefaultEnrichmentEngine`, `EnrichmentData`, `EnrichmentType` | `CreditDiscoverySynthesizer.kt` |
| Genre Affinity | `GenreMerger` (expose `normalize`) | `GenreAffinityCalculator.kt` |
| RADIO_MIX | `DeezerApi`, `DeezerProvider` | None — uses same radio endpoint as SIMILAR_TRACKS |

---

## Version Compatibility

All existing versions remain compatible. No transitive dependency conflicts introduced.

| Existing Dependency | Version | Status |
|---------------------|---------|--------|
| kotlinx.coroutines-core | 1.9.0 | No change needed |
| kotlinx.serialization-json | 1.7.3 | New `EnrichmentData` subclasses work automatically |
| org.json | 20231013 | Sufficient for all new API parsing |
| Kotlin | 2.1.0 | No change needed |

---

## Sources

- ListenBrainz CF recommendation docs — `https://listenbrainz.readthedocs.io/en/latest/users/api/recommendation.html` — HIGH confidence (official docs, fetched 2026-03-23)
- Deezer artist related/radio response fields — `https://github.com/antoineraulin/deezer-api/wiki/artist` — MEDIUM confidence (community wiki, consistent with deezer-python library docs)
- Deezer Python library artist resource — `https://deezer-python.readthedocs.io/en/stable/api_reference/resources/artist.html` — MEDIUM confidence (third-party wrapper, reflects current API shape)
- Existing codebase: `DeezerApi.kt`, `DeezerProvider.kt`, `ListenBrainzApi.kt`, `DefaultEnrichmentEngine.kt` — HIGH confidence (source of truth for integration patterns)
- `docs/providers/deezer.md`, `docs/providers/listenbrainz.md`, `docs/providers/lastfm.md` — HIGH confidence (project-maintained API reference)

---
*Stack research for: music recommendation engine (v0.6.0)*
*Researched: 2026-03-23*
