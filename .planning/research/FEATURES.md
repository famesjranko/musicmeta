# Feature Research

**Domain:** Music metadata enrichment library — v0.6.0 Recommendations Engine
**Researched:** 2026-03-23
**Confidence:** HIGH (existing API docs verified), MEDIUM (behavioural conventions from industry patterns)

---

## Context: What Already Exists

v0.5.0 shipped:
- `SIMILAR_ARTISTS` — Last.fm (`artist.getsimilar`, match 0–1) + ListenBrainz (`/explore/lb-radio/artist/{mbid}/similar`, score 0–∞ normalized internally)
- `SIMILAR_TRACKS` — Last.fm (`track.getsimilar`, match 0–1)
- `CREDITS` — MusicBrainz recording relations + Discogs extraartists, with `roleCategory` (performance/production/songwriting)
- `GENRE` — mergeable type: GenreMerger normalizes + deduplicates + additively scores tags from all providers
- `ARTIST_TIMELINE` — composite type: resolves `ARTIST_DISCOGRAPHY` + `BAND_MEMBERS` sub-types then synthesizes

The engine supports three resolution patterns:
1. **Short-circuit chain** — first `Success` wins (standard)
2. **Mergeable** — all providers' results collected, then merged (GENRE pattern)
3. **Composite** — sub-types resolved first, then synthesized (ARTIST_TIMELINE pattern)

---

## Feature Landscape

### Table Stakes (Users Expect These)

Features a recommendations-capable enrichment library must have. Missing these = the feature category is incomplete.

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Deezer SIMILAR_ARTISTS as 3rd provider | Three independent similarity sources give broader coverage; Deezer `/artist/{id}/related` returns a ranked list, no auth | LOW | Fits existing short-circuit chain. Deezer IDs not stored in `resolvedIdentifiers` today — requires prior Deezer album search result or a `GET /search/artist` lookup first |
| Deezer SIMILAR_TRACKS via artist radio | Deezer `/artist/{id}/radio` returns 25 tracks ordered by Deezer popularity for that artist — functions as a "related tracks" list | MEDIUM | No match scores. Must synthesize position-based scores (position 1 → 1.0, decays to 0.1 at position 25) to produce `SimilarTrack.matchScore`. Same Deezer ID resolution problem as above |
| SIMILAR_ALBUMS as new composite type | "If you like this album, try these" is the most natural album-level discovery entry point | HIGH | No direct API for this. Must be synthesized (see Differentiators section). New `EnrichmentType.SIMILAR_ALBUMS` needed |
| Radio/Mix seed-based track list | Seed-to-playlist is a table-stakes feature of any music discovery product | MEDIUM | Deezer `/artist/{id}/radio` is the only available endpoint; wraps directly into a new `EnrichmentType.RADIO_MIX`. Differs from SIMILAR_TRACKS: seed is the artist, output is a track list, not similarity-ranked results |
| ListenBrainz CF recommendations (non-personalized path) | Returning recording MBIDs + scores for a named LB user is the only CF option available without server-side ML | MEDIUM | `GET /1/cf/recommendation/user/{username}/recording` is public (no auth token required to READ). Caller supplies a ListenBrainz username string. Scores are raw CF weights (0–∞, higher = stronger). Response includes `recording_mbid` only — titles must be resolved separately via MusicBrainz batch or trusted as-is |

### Differentiators (Competitive Advantage)

Features that add library value beyond baseline enrichment.

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Multi-provider SIMILAR_ARTISTS merger (mergeable pattern) | Three sources (Last.fm, ListenBrainz, Deezer) cover different audience bases — merged result is more complete than any single source | MEDIUM | Promote SIMILAR_ARTISTS from short-circuit to mergeable type, analogous to GENRE. Merging strategy: union by name/MBID with score averaging across providers that agree, preserving solo entries at their native score. Requires a `SimilarArtistsMerger` analogous to `GenreMerger` |
| SIMILAR_ALBUMS synthesized composite | Industry standard: "similar albums" = top albums from similar artists filtered to same genre bucket and nearby era (±10 years). No API provides this directly | HIGH | Composite type depending on: `SIMILAR_ARTISTS` (for the artist pool) + `ARTIST_DISCOGRAPHY` (for each similar artist's albums) + `GENRE` (for genre filtering). Era from `release_date` on `DiscographyAlbum`. Confidence = min of input sub-type confidences. Needs rate-limit awareness — expanding 5 similar artists into their discographies means 5+ extra API calls |
| Credit-based discovery via CREDITS data | "More tracks produced by X" is a power-user feature valued in collector communities. Directly usable from existing CREDITS data with no new API calls | MEDIUM | No new providers needed. New `EnrichmentType.CREDIT_DISCOVERY`. Resolution logic: query `CREDITS` for the seed, extract unique persons by `roleCategory` (focus: production + songwriting), then for each person look up their MusicBrainz recording relations via MusicBrainz `/ws/2/artist/{mbid}?inc=recording-rels`. High fan-out — gate on explicit opt-in or small result cap (≤3 persons, ≤10 recordings each) |
| Genre discovery via GenreMerger confidence scores | Genre affinity ("if you like post-rock, try math rock") requires genre neighbor mapping. Confidence scores from existing GENRE results can drive this without new API calls | MEDIUM | New `EnrichmentType.GENRE_DISCOVERY`. Input: `GENRE` result for seed entity. Output: list of related genre names with affinity scores. Three approaches in increasing complexity: (1) static affinity table hardcoded in library (LOW effort, LOW accuracy), (2) Last.fm `tag.gettopartists` + overlap scoring (MEDIUM, MEDIUM), (3) defer to v0.7 once genre taxonomy is built. Recommendation: start with approach (1), flag for upgrade |
| ListenBrainz CF with user-personalized scope | Real CF output differs per user. The `/cf/recommendation/user/{username}/recording` endpoint returns personalized recording MBIDs | MEDIUM | Caller must supply ListenBrainz username as part of request — not an `EnrichmentRequest` field today. Needs a new `ForUser` request variant or a config-level username. Response is recording MBIDs with raw scores; consumer must decide how to display/resolve titles. Personalized = different result per username, so cache key must include username |

### Anti-Features (Commonly Requested, Often Problematic)

| Feature | Why Requested | Why Problematic | Alternative |
|---------|---------------|-----------------|-------------|
| Spotify/Apple Music similar endpoints | These have massive CF datasets | Both require OAuth and have restrictive ToS for library use. Out of scope per PROJECT.md constraints | Maximize depth from existing 11 providers first |
| SIMILAR_ALBUMS via audio fingerprint | Acoustically similar albums feel natural to users | No open audio analysis API without OAuth (AcousticBrainz is defunct). Would require a new heavyweight provider | Genre + era + similar-artist heuristic covers 80% of the value |
| Real-time personalization (live scrobble stream) | Truly fresh recommendations require live data | ListenBrainz CF recommendations are batch-updated (not real-time). Trying to stream would require WebSocket or polling — not a library concern | Cache CF results with short TTL (e.g., 24h) and surface `last_updated` to consumer |
| Full Deezer OAuth for personalized radio | Deezer's user-specific radio is better than public radio | OAuth adds auth complexity to a no-auth-required library | Public artist radio (`/artist/{id}/radio`) covers the use case adequately |
| Playlist management (create/update) | Closing the loop from recommendations to action | Write operations require OAuth on every platform. Library is read-only enrichment by design | Return track lists; let consumer manage playlist creation |
| Genre taxonomy hierarchy | "Post-rock is a subgenre of rock" navigation | Requires maintaining a static tree that diverges across sources; community tags are flat. Deferred per PROJECT.md | Affinity table (genre neighbors with confidence) is simpler and more actionable |
| Credit discovery for all roles equally | "More from this mastering engineer" | Mastering engineers rarely have MBID-linked recording relations in MusicBrainz. Sparse data = high NotFound rate | Scope to `production` + `songwriting` roleCategories where data is denser |

---

## Feature Dependencies

```
SIMILAR_ALBUMS (composite)
    └──requires──> SIMILAR_ARTISTS
                       └──requires──> identity resolution (MBID)
    └──requires──> ARTIST_DISCOGRAPHY (per similar artist)
    └──uses──> GENRE (for filtering, optional enhancement)

RADIO_MIX
    └──requires──> Deezer artist ID
                       └──from──> prior album search result OR new artist search

GENRE_DISCOVERY
    └──requires──> GENRE (existing mergeable type)

CREDIT_DISCOVERY
    └──requires──> CREDITS (existing type)
                       └──requires──> MBID from identity resolution

ListenBrainz CF (LISTENING_BASED)
    └──requires──> ListenBrainz username (new input, not in EnrichmentRequest today)
    └──produces──> recording MBIDs (not resolved to full track data)

Deezer SIMILAR_ARTISTS / SIMILAR_TRACKS / RADIO_MIX
    └──requires──> Deezer artist ID
                       └──not stored in resolvedIdentifiers today
                       └──must be resolved via GET /search/artist?q={name} first
```

### Dependency Notes

- **SIMILAR_ALBUMS requires SIMILAR_ARTISTS:** The only viable derivation path without a direct API. Must resolve similar artists first, then expand to their discographies. This is fan-out: 5 similar artists × ~3 discography items = ~15 lookups minimum.
- **Deezer artist ID is a new identifier gap:** The existing `EnrichmentIdentifiers` has no `deezerArtistId` field. Three Deezer features need it (SIMILAR_ARTISTS, SIMILAR_TRACKS, RADIO_MIX). Either add to `EnrichmentIdentifiers` or store in `extra` map (consistent with Discogs ID pattern from v0.5.0).
- **ListenBrainz CF requires username:** The `EnrichmentRequest` sealed class has no user-scoped variant. Either add `ForUser(username, ...)` or pass username via `EnrichmentConfig`. Using `config` is simpler but limits to one user per engine instance. `ForUser` is cleaner but a breaking change.
- **SIMILAR_ARTISTS merger is a prerequisite for SIMILAR_ALBUMS quality:** If SIMILAR_ARTISTS stays short-circuit, SIMILAR_ALBUMS only sees one provider's candidates. Upgrading SIMILAR_ARTISTS to mergeable first gives SIMILAR_ALBUMS a richer input pool.

---

## MVP Definition

### Launch With (v0.6.0)

These are the highest-value, lowest-risk features that map cleanly to existing patterns.

- [ ] **Deezer SIMILAR_ARTISTS** — third provider in existing chain. Resolves Deezer artist ID via search, returns ranked list. Fits existing `EnrichmentData.SimilarArtists` shape. Builds foundation for SIMILAR_ALBUMS.
- [ ] **Deezer SIMILAR_TRACKS** — second provider in existing chain. Position-based synthetic match score (position 1 = 1.0, linear decay). Fits existing `EnrichmentData.SimilarTracks` shape.
- [ ] **RADIO_MIX** — new `EnrichmentType`. Same Deezer `/artist/{id}/radio` endpoint as SIMILAR_TRACKS but returns a `TrackList` (ordered, no scores). Distinct from SIMILAR_TRACKS in semantics: a mix is ordered/curated, not ranked by similarity.
- [ ] **SIMILAR_ALBUMS** — new composite `EnrichmentType`. Synthesizes from SIMILAR_ARTISTS + ARTIST_DISCOGRAPHY per artist, optionally filtered by genre overlap. Most valuable new discovery surface.
- [ ] **ListenBrainz CF (LISTENING_BASED)** — new `EnrichmentType`. Returns recording MBIDs with scores for a named LB user. New request dimension (username). Cache key must include username.

### Add After Validation (v0.6.x)

- [ ] **SIMILAR_ARTISTS as mergeable type** — promotes from short-circuit to all-provider merge, with a `SimilarArtistsMerger`. Trigger: consumer feedback that coverage from single provider is thin.
- [ ] **GENRE_DISCOVERY** — genre affinity table. Static table in v1, Last.fm `tag.gettopartists` overlap in v2. Trigger: consumers ask "what genres are near X?"
- [ ] **CREDIT_DISCOVERY** — scoped to production + songwriting. Gated by opt-in flag or small result cap. Trigger: consumers using CREDITS data ask for "more from this person."

### Future Consideration (v0.7+)

- [ ] **Genre taxonomy hierarchy** — deferred per PROJECT.md; wait until genre data matures.
- [ ] **ForUser request variant** — clean breaking-change addition to request model if ListenBrainz CF sees real usage.
- [ ] **Full SIMILAR_ARTISTS merger** — build `SimilarArtistsMerger` once the three providers (Last.fm, ListenBrainz, Deezer) are all live and coverage can be measured.

---

## Feature Prioritization Matrix

| Feature | User Value | Implementation Cost | Priority |
|---------|------------|---------------------|----------|
| Deezer SIMILAR_ARTISTS | HIGH | LOW — fits existing chain | P1 |
| Deezer SIMILAR_TRACKS | HIGH | LOW — fits existing chain | P1 |
| RADIO_MIX | HIGH | LOW — same endpoint, new type | P1 |
| SIMILAR_ALBUMS | HIGH | HIGH — fan-out composite, era logic | P1 |
| ListenBrainz CF (LISTENING_BASED) | MEDIUM | MEDIUM — new request dimension | P1 |
| SIMILAR_ARTISTS as mergeable | MEDIUM | MEDIUM — new merger class | P2 |
| GENRE_DISCOVERY (static table) | MEDIUM | LOW | P2 |
| CREDIT_DISCOVERY | LOW | HIGH — MusicBrainz fan-out | P3 |
| GENRE_DISCOVERY (API-backed) | LOW | HIGH | P3 |

---

## Detailed Behaviour Notes per Feature

### 1. SIMILAR_ARTISTS — Multi-Provider Merge Strategy

**Current state:** Last.fm (priority 100) + ListenBrainz (priority 50, MBID required). Short-circuit chain.

**Adding Deezer:** Deezer `/artist/{id}/related` returns up to 20 related artists ordered by Deezer's internal ranking — no numeric match score. Must synthesize a score from position (same as Deezer SIMILAR_TRACKS approach: position 1 = 1.0, position 20 = 0.1, linear decay).

**Merge strategy options:**
- *Short-circuit (current):* Keep as-is, add Deezer at priority 50 alongside ListenBrainz. Simple, but wastes two providers' data.
- *Union with deduplication (recommended for v0.6.0):* If keeping short-circuit, use the first provider that returns results (Last.fm preferred). Defer full merge to P2.
- *Weighted average merge (v0.6.x):* Collect all three, group by name/MBID, average scores from providers that agree, keep solo entries at native score. Analogous to GenreMerger's additive confidence. Needs `SimilarArtistsMerger`.

**Recommendation for v0.6.0:** Add Deezer at priority 50 (alongside ListenBrainz). Both serve as fallbacks to Last.fm. Upgrade to mergeable pattern in v0.6.x once all three providers are live and coverage can be compared.

### 2. SIMILAR_TRACKS — Deezer Radio Score Normalization

**Problem:** Last.fm `track.getsimilar` returns match scores 0–1. Deezer `/artist/{id}/radio` returns 25 tracks with no scores — it is a popularity-ordered playlist for the artist, not a semantic similarity ranking.

**Normalization approach:**
- Assign synthetic match score by list position: `score = 1.0 - ((position - 1) / (totalCount - 1)) * 0.9`
- Position 1 → 1.0, position 25 → 0.1 (floor)
- This makes Deezer radio tracks comparable to Last.fm scores in the `SimilarTrack` model
- Confidence for Deezer SIMILAR_TRACKS result should be lower than Last.fm's (0.6 vs 0.85) since position ≠ semantic similarity

**Deezer as fallback:** Deezer `/artist/{id}/radio` is seeded on the artist, not the track, so results may not be similar to the specific seed track. It is useful as a fallback when Last.fm returns `NotFound` (obscure tracks). Priority: 50 (fallback to Last.fm's 100).

### 3. SIMILAR_ALBUMS — Synthesis Strategy

**No direct API.** Industry-standard heuristic for library-based tools (beaTunes, beets):
1. Get similar artists (existing SIMILAR_ARTISTS resolution)
2. Fetch top/most-popular albums from each similar artist (ARTIST_DISCOGRAPHY or ListenBrainz top release groups)
3. Filter: keep only albums where genre overlap with seed ≥ 1 tag (optional but improves relevance)
4. Filter: keep albums within ±10 years of seed album release date (era proximity)
5. Score: `matchScore = similarArtistScore * genreOverlapBonus * eraProximityFactor`
6. Deduplicate, sort descending, cap at 20

**Data shape:** Returns `EnrichmentData.Discography` (reuses `DiscographyAlbum`) or a new `SimilarAlbums` type. Reusing `Discography` avoids a new sealed class but is semantically confusing. Prefer new sealed class: `EnrichmentData.SimilarAlbums(albums: List<SimilarAlbum>)` where `SimilarAlbum` adds `matchScore: Float` to `DiscographyAlbum`.

**Cost:** For 5 similar artists, this triggers 5 ARTIST_DISCOGRAPHY calls. Cache these aggressively (30-day TTL already defined). Gate the synthesis at engine level, not provider level.

### 4. RADIO_MIX — Table Stakes

**Seed types** music apps support: artist, track, album, genre, playlist. For this library, constrained to artist seed (Deezer `/artist/{id}/radio` only). Single-source for now.

**Table stakes for a radio/mix feature:**
- Ordered track list (not ranked by similarity score)
- Deduplication: no repeated track MBIDs in the list
- Variety: tracks from different albums (Deezer radio handles this naturally)
- Length: 25 tracks is Deezer's fixed output — acceptable
- Exclude seed: the seed artist's tracks dominate the radio by design; this is expected behaviour

**Data shape:** `EnrichmentData.Tracklist` already exists (`tracks: List<TrackInfo>`). Radio returns the same shape but with a "this is a generated mix" semantic. Reuse `Tracklist` to avoid proliferating data classes, or add `EnrichmentData.RadioMix(tracks: List<TrackInfo>, seedArtist: String)` for semantic clarity. Prefer semantic clarity — use `RadioMix`.

### 5. CREDIT_DISCOVERY — Granularity

**What consumers expect:** "More recordings featuring this producer" is a power feature used in liner-notes communities and audiophile tools (Discogs, AllMusic). Expected UX:
- Input: seed track + role category (production preferred)
- Output: other recordings where that person has the same role
- Granularity: producer > composer > engineer > label (in descending data density)

**Data availability:** MusicBrainz has dense data for major-label records and well-catalogued indie. Discogs `extraartists` covers production credits for many releases. Both are already in CREDITS (v0.5.0).

**Implementation:** Query CREDITS for seed, extract person MBIDs by roleCategory, call MusicBrainz `/ws/2/artist/{mbid}?inc=recording-rels&limit=10`. High fan-out risk — gate on `roleCategory = production OR songwriting`, cap at 3 persons and 10 results per person.

**Complexity drivers:** MusicBrainz rate limit is 1 req/sec. Each credit person = 1 additional request. Cap strictly or use batch. Mark as P3 for v0.6.0.

### 6. GENRE_DISCOVERY — Affinity Table vs API

**How music apps do it:**
- Spotify: implicit (no user-visible genre graph)
- Apple Music: curated genre browsing, not affinity-based
- Last.fm: `tag.getsimilartags` endpoint (undocumented but functional) returns similar tags
- AllMusic: curated genre tree with "similar genres" links

**Options for this library:**
1. **Static affinity table** (LOW complexity): hardcoded `Map<String, List<String>>` mapping genres to neighbors. Example: `"post-rock" → ["math rock", "ambient", "progressive rock"]`. Covers top 50 genres. Maintainable, deterministic, fast.
2. **Last.fm tag.getsimilartags** (MEDIUM complexity): calls Last.fm `?method=tag.getsimilartags&tag={tag}` — undocumented but returns similar tags with similarity scores. Dynamic but depends on undocumented endpoint stability.
3. **Defer entirely** (NO complexity): mark GENRE_DISCOVERY as v0.7 scope.

**Recommendation:** Option 1 for v0.6.0 if built at all. The static table is fast, testable, and covers the real use case. Option 2 can replace/supplement it later.

### 7. LISTENING_BASED — Consumer Contract

**What the consumer provides:**
- A ListenBrainz username (string) — the library cannot discover this; consumer owns it
- Optional: `count` (default 25, max per LB API), `offset` for pagination

**What the library returns:**
- List of `recording_mbid` + raw CF score pairs
- Scores are raw CF weights (0–∞, not normalized to 0–1). Consumer should treat them as relative rankings, not absolute confidences.
- `last_updated` timestamp from ListenBrainz — useful for consumer to know how stale the data is

**How this differs from non-personalized recommendations:**
- Non-personalized (SIMILAR_ARTISTS, SIMILAR_TRACKS): seeded on metadata, same result for all callers
- Personalized CF: seeded on a specific user's listen history, different result per username

**Cache key implication:** Cache key must include username: `"user:{username}:LISTENING_BASED"`. TTL should be short (24h) since LB CF data updates periodically.

**Request model implication:** `EnrichmentRequest.ForArtist/ForAlbum/ForTrack` do not have a username field. Options:
1. Add `EnrichmentRequest.ForUser(username: String)` — cleanest, breaking change
2. Pass username via `EnrichmentConfig.listenBrainzUsername` — non-breaking but limits to one user per engine instance
3. Pass via `EnrichmentRequest.identifiers.extra["listenbrainz_username"]` — works but abuses the identifiers map

**Recommendation:** `ForUser` request variant for v0.6.0 since it is cleanest and project is pre-1.0 (clean breaks are safe).

**Response shape:** New `EnrichmentData.PersonalizedRecommendations(recordings: List<RecommendedRecording>)` where `RecommendedRecording(recordingMbid: String, score: Float, lastUpdated: Long?)`.

---

## New EnrichmentTypes Required

| New Type | Resolution Pattern | Data Shape |
|----------|-------------------|------------|
| `SIMILAR_ALBUMS` | Composite (depends on SIMILAR_ARTISTS + ARTIST_DISCOGRAPHY per similar artist) | New `EnrichmentData.SimilarAlbums` |
| `RADIO_MIX` | Standard chain (Deezer only, for now) | New `EnrichmentData.RadioMix` |
| `LISTENING_BASED` | Standard chain (ListenBrainz CF, user-scoped) | New `EnrichmentData.PersonalizedRecommendations` |
| `GENRE_DISCOVERY` | Static table or Last.fm tag API | New `EnrichmentData.GenreDiscovery` |
| `CREDIT_DISCOVERY` | MusicBrainz artist recording-rels fan-out | New `EnrichmentData.CreditDiscovery` |

Existing types extended (new providers added):
- `SIMILAR_ARTISTS` — add Deezer provider (priority 50)
- `SIMILAR_TRACKS` — add Deezer provider (priority 50, fallback)

---

## Sources

- ListenBrainz CF recommendation endpoint: https://listenbrainz.readthedocs.io/en/latest/users/api/recommendation.html (HIGH confidence — official docs, verified March 2026)
- Deezer API endpoint reference (internal doc): `/home/andy/music-enrichment/docs/providers/deezer.md` (HIGH confidence — project-maintained)
- ListenBrainz API overview (internal doc): `/home/andy/music-enrichment/docs/providers/listenbrainz.md` (HIGH confidence — project-maintained)
- Last.fm API reference (internal doc): `/home/andy/music-enrichment/docs/providers/lastfm.md` (HIGH confidence — project-maintained)
- Spotify recommendation system architecture: https://www.music-tomorrow.com/blog/how-spotify-recommendation-system-works-complete-guide (MEDIUM confidence — secondary source, industry patterns)
- beaTunes matchlist approach (position-score normalization pattern): https://www.beatunes.com/en/beatunes-matchlist.html (MEDIUM confidence — industry precedent)
- Playlist seed conventions (Pandora multi-seed): https://community.pandora.com/t5/Android/Can-you-still-seed-stations-with-multiple-songs-or-multiple/td-p/51857 (LOW confidence — user forum, for UX convention context only)

---

*Feature research for: musicmeta v0.6.0 Recommendations Engine*
*Researched: 2026-03-23*
