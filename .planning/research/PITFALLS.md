# Pitfalls Research

**Domain:** Adding recommendation modules to existing music metadata enrichment library
**Researched:** 2026-03-23
**Confidence:** HIGH (codebase analysis) / MEDIUM (API behavior) / LOW (external docs only)

---

## Critical Pitfalls

### Pitfall 1: Multi-Provider Similarity Score Incompatibility

**What goes wrong:**
Last.fm `match` is a float 0.0–1.0 derived from collaborative filtering plus tag overlap. Deezer
`/artist/{id}/related` returns ranked artists with no numeric score at all — just an ordered list.
ListenBrainz `/1/explore/similar-artists/{mbid}` returns artists sorted by a listen-pattern
similarity that is not normalized to the same scale. If you feed all three into the same merge
logic and average or sum scores, you corrupt the output: artists that appear in Deezer's
ranked list get assigned score 0.0 (missing), which drags them below the confidence threshold
and drops them. Artists with a Last.fm score of 0.4 (moderate similarity) may rank above a Deezer
top-1 match that you assigned an arbitrary positional score.

**Why it happens:**
The existing `SimilarArtist` data class has `matchScore: Float` as a required field. Providers
that don't return a score must invent one. The temptation is to assign `0.5f` as a default, which
mixes semantic meaning (Last.fm: "half similar") with structural meaning (Deezer: "present in
results"), breaking any downstream ranking.

**How to avoid:**
Before implementing the merge, define what `matchScore` means for each source:
- Last.fm: pass through directly (0.0–1.0, collaborative + tag)
- ListenBrainz similar-artists: assign by rank position using `(1.0 - index/count)`
- Deezer related: assign by rank position using `(1.0 - index/count)`

Document the derivation in the mapper. Do not mix computed positional scores with semantic
Last.fm scores in a straight average; instead treat Last.fm score as primary confidence and
position-derived scores as tie-breakers. The merge de-duplicates by artist name/MBID and keeps
the highest score; it does NOT average.

**Warning signs:**
- A well-known artist's close collaborator appears near the bottom of merged results
- Last.fm-only results consistently outrank Deezer results regardless of Deezer rank
- Tests show different orderings on each run (indicates unstable sort on equal scores)

**Phase to address:** Phase 1 (Deezer SIMILAR_ARTISTS + SIMILAR_TRACKS) — establish score
derivation rules before any merge code is written.

---

### Pitfall 2: SIMILAR_ARTISTS Is Currently a Short-Circuit Chain, Not a Merge

**What goes wrong:**
`GENRE` is the only `MERGEABLE_TYPE` in `DefaultEnrichmentEngine.MERGEABLE_TYPES`. `SIMILAR_ARTISTS`
uses `ProviderChain.resolve()` (short-circuit), meaning only the highest-priority provider runs.
Adding Deezer as a third provider at priority 50 (or any priority below 100) means it never runs
when Last.fm succeeds — which is almost always when an API key is present. You get exactly the
same SIMILAR_ARTISTS result as before, with no benefit from the Deezer data.

**Why it happens:**
The engine's merge/short-circuit decision is made by the set `MERGEABLE_TYPES`. It is easy to add
a new provider capability without promoting the type to mergeable, because the existing tests pass
(Last.fm still returns data). The Deezer contribution is silently discarded.

**How to avoid:**
Add `SIMILAR_ARTISTS` to `MERGEABLE_TYPES` and implement a `SimilarArtistsMerger` analogous to
`GenreMerger`. The merger de-duplicates by MBID (prefer) or normalized artist name, keeps the
highest score per artist, and merges the source list. Set a result cap (20 artists) to avoid
bloat. The `Success` result provider field should be `"similar_artists_merger"`.

**Warning signs:**
- Unit test shows Deezer provider returns data but the engine result contains only Last.fm artists
- Adding `DeezerProvider` to the test builder does not change `SIMILAR_ARTISTS` output
- The Deezer provider's `enrich()` is never called in integration tests

**Phase to address:** Phase 1 — must be addressed before any Deezer provider code is considered
done.

---

### Pitfall 3: Deezer Requires an Artist Numeric ID, Which the Engine Never Stores

**What goes wrong:**
`GET /artist/{id}/related` and `GET /artist/{id}/radio` require a Deezer numeric artist ID. The
current `DeezerProvider` obtains this ID during `enrichDiscography()` and stores it in
`resolvedIdentifiers.extra["deezerId"]`. However, `EnrichmentRequest.ForArtist` starts with no
Deezer ID. For SIMILAR_ARTISTS and SIMILAR_TRACKS, a new `enrichSimilarArtists()` or
`enrichSimilarTracks()` method must first call `GET /search/artist?q={name}` to get the ID before
calling the related/radio endpoints. This is two HTTP calls per request instead of one, and the
intermediate artist search is fuzzy — the first result may not be the correct artist.

The common mistake is to call `api.searchArtist(name)` and use `result.first()` without
verification. For artists with generic names ("The National", "Phoenix", "America"), the first
search result is often wrong.

**Why it happens:**
Deezer IDs are opaque numeric values with no cross-reference in the existing identifier system.
Unlike MusicBrainz IDs, they cannot be resolved from a known MBID. The `extra` map in
`EnrichmentIdentifiers` can store them, but only after a prior Deezer call (e.g., discography)
has run and propagated the ID.

**How to avoid:**
In the new Deezer methods for SIMILAR_ARTISTS and SIMILAR_TRACKS:
1. Check `request.identifiers.extra["deezerId"]` first — use it directly if present
2. If absent, call `searchArtist(name)` and verify the result against `ArtistMatcher.isMatch()`
3. Store the resolved ID in `resolvedIdentifiers.extra["deezerId"]` so downstream calls reuse it
4. Return `NotFound` if no verified match exists — do not guess

**Warning signs:**
- Wrong artist's "radio" tracks appearing for obscure artists with common names
- Two Deezer HTTP calls visible in logs for every SIMILAR_ARTISTS request when discography was
  not pre-fetched
- `ArtistMatcher.isMatch()` not called before using search result

**Phase to address:** Phase 1 — the ID lookup and verification pattern must be in the Deezer API
layer before any endpoint using artist IDs is wired up.

---

### Pitfall 4: ListenBrainz CF Endpoint Is User-Scoped with No Username in EnrichmentRequest

**What goes wrong:**
`GET /1/cf/recommendation/user/{username}/recording` requires a ListenBrainz username, not a
MusicBrainz ID. The `EnrichmentRequest` hierarchy (`ForArtist`, `ForAlbum`, `ForTrack`) has no
concept of a logged-in user. The endpoint returns personalized recommendations for that user's
listening history — not recommendations for an artist or track. The data model is fundamentally
different from every other enrichment type in this library.

Additionally, the endpoint returns 204 No Content when recommendations have not yet been
generated for the user (this is distinct from 404 and requires separate handling). The
`HttpClient` must handle 204 as an empty response, not an error.

**Why it happens:**
The CF endpoint appears in the ListenBrainz docs alongside other endpoints that work with MBIDs.
It's natural to assume it follows the same pattern. The distinction — user-scoped vs.
content-scoped — is only clear when you read the endpoint path: `/user/{username}` vs.
`/artist/{mbid}`.

**How to avoid:**
Do not model ListenBrainz CF as a standard `EnrichmentProvider`. It is a user-preference service,
not a content enrichment service. The correct approach is one of:

Option A (recommended for v0.6.0): Treat it as a factory or out-of-band feature. Add a
`ListenBrainzRecommendationService` separate from the provider chain, accepting a username
parameter. This is not an `EnrichmentType` — it's a separate public API on the engine.

Option B: Extend `EnrichmentRequest` with an optional `userContext` field containing a
ListenBrainz username, and add CF as a specialized `EnrichmentType.LISTENING_BASED`. This
works but bloats `EnrichmentRequest` with a concern that applies to only one of 11 providers.

Option A is cleaner and avoids poisoning the request model. The feature is explicitly
out-of-band, which is honest about what it is.

**Warning signs:**
- Trying to declare `IdentifierRequirement` for a ListenBrainz username (no enum value exists)
- Writing a provider that returns `NotFound` when `request.identifiers.musicBrainzId` is null,
  but the real missing piece is a username
- 204 responses parsed as JSON throwing an exception

**Phase to address:** Phase 4 (Listening-Based recommendations) — design the user-context API
shape before writing any code.

---

### Pitfall 5: SIMILAR_ALBUMS Composite Creates a Two-Level Dependency Chain

**What goes wrong:**
SIMILAR_ALBUMS is described as "synthesized from similar artists + genre + era." This means its
`COMPOSITE_DEPENDENCIES` would include `SIMILAR_ARTISTS`, `GENRE`, and optionally `RELEASE_DATE`
or `RELEASE_TYPE`. The existing composite resolution in `DefaultEnrichmentEngine.resolveTypes()`
resolves sub-types and then synthesizes the composite. This works for `ARTIST_TIMELINE` because
its sub-types (`ARTIST_DISCOGRAPHY`, `BAND_MEMBERS`) are leaf providers.

The problem: if `SIMILAR_ARTISTS` is itself a merged type (after Pitfall 2 fix), the composite
resolution must wait for the full merge to complete before synthesizing. The engine does handle
this (mergeables run before composites), but `SIMILAR_ALBUMS` requires `SIMILAR_ARTISTS` for an
*album* request — and the current `SIMILAR_ARTISTS` only works for `ForArtist`. Calling
SIMILAR_ARTISTS from a `ForAlbum` request propagates `NotFound`, so the composite gets nothing.

Further: the synthesizer needs to do something useful with similar artists + genre + era to produce
album recommendations. "Artists similar to this artist who released albums in a similar genre/era"
requires looking up those artists' discographies — that's potentially N additional provider calls,
not a pure synthesis.

**Why it happens:**
Composite types look simple on paper ("combine X + Y") but the synthesis step requires data
across request scopes. `SIMILAR_ALBUMS` for a `ForAlbum` request needs artist-level data from the
album's artist — a scope hop the current engine does not support. The `ARTIST_TIMELINE` composite
works because all its inputs are also artist-scoped.

**How to avoid:**
For SIMILAR_ALBUMS, the synthesis strategy must be defined precisely before implementation:
- Option A: Limit to genre + era matching against the known discography of similar artists
  (no extra API calls, but shallow results). This is the v0.6.0 scope.
- Option B: Accept that SIMILAR_ALBUMS will need a sub-request for each similar artist's
  discography — add a batch resolution path to the engine.

Option A is correct for v0.6.0 and should be documented as a known limitation. The synthesizer
takes the genre tags and release year from the source album, then filters/scores entries in
the similar artists' discographies by genre overlap and year proximity. No extra API calls.
Result quality will be moderate for obscure albums where genre/era data is sparse.

**Warning signs:**
- Synthesizer function has more than 40 lines
- Synthesizer makes HTTP calls (it should be pure, using only already-resolved data)
- The composite has `COMPOSITE_DEPENDENCIES` pointing to another composite type

**Phase to address:** Phase 2 (SIMILAR_ALBUMS) — design the synthesizer contract (pure function,
no I/O) before any implementation.

---

### Pitfall 6: Credit-Based Discovery Has No Reverse Lookup

**What goes wrong:**
CREDITS data contains: `{ name: "Rick Rubin", role: "producer", roleCategory: "production" }`.
Credit-based discovery means "find other tracks produced by Rick Rubin." This requires a reverse
index: given a credit name, find all recordings they contributed to. Neither MusicBrainz nor
Discogs in the current implementation provides this — the existing CREDITS provider looks up
credits for a given recording, not all recordings for a given person.

The MusicBrainz API has this: `GET /ws/2/recording?artist={person-mbid}` with the artist's
production role included in the `inc` parameter. But the Credit data model stores credit names,
not the credited person's MBID. If you search by name, you get name collisions ("John Smith")
and no guarantee the result is the same person.

**Why it happens:**
Credits were implemented as a lookup (recording → credits), and the reverse direction (person →
recordings) was not in scope for v0.5.0. It is easy to assume that because you have the data,
discovery follows trivially. It does not.

**How to avoid:**
Before implementing credit-based discovery, decide the lookup key:
- MusicBrainz credits should store the credited artist's MBID in `Credit.identifiers.musicBrainzId`
  (check if `MusicBrainzParser.parseRecordingCredits()` already returns this)
- Discogs credits store credited artist IDs in `extraartists[].id` — these should be stored in
  `Credit.identifiers.extra["discogsArtistId"]`

With person MBIDs stored, discovery is: resolve person MBID from credit → call
`GET /ws/2/recording?artist={person-mbid}&inc=artist-credits` → return results as similar tracks.
Without MBIDs, discovery degrades to a name-based search that is unreliable.

Verify the current `MusicBrainzParser.parseRecordingCredits()` output before designing the feature.

**Warning signs:**
- Credit-based discovery test uses a credit with a common name ("John Smith") and expects specific
  results — this will be flaky
- Discovery feature builds a fuzzy artist name search rather than an MBID lookup
- `Credit.identifiers` is always `EnrichmentIdentifiers()` (empty) in test data

**Phase to address:** Phase 3 (Credit-Based Discovery) — audit Credit model for MBID storage
before writing any discovery logic.

---

## Technical Debt Patterns

| Shortcut | Immediate Benefit | Long-term Cost | When Acceptable |
|----------|-------------------|----------------|-----------------|
| Assign `0.5f` as default matchScore for Deezer ranked results | No new data class fields | Corrupts ranking when mixed with Last.fm semantic scores | Never |
| Skip SimilarArtistsMerger, run SIMILAR_ARTISTS as short-circuit | Deezer provider wired quickly | Deezer data silently never used; test coverage misleads | Never |
| Store deezerId only in `resolvedIdentifiers` Success result, not propagated back to request | Simpler code | Every SIMILAR_ARTISTS call always does two Deezer requests | Only if discography always runs first in same enrich() call |
| Implement CF as a standard EnrichmentProvider with a hardcoded test username | Feature appears to work in tests | Test fixture contamination; username leaks into public API shape | Never |
| Make SIMILAR_ALBUMS do N sub-requests in the synthesizer | More album candidates | Synthesizer is no longer a pure function; timeout risk scales with N | Never in synthesizer — move to engine layer if needed |
| Normalize all similarity scores to 0–1 by dividing by max | Simple code | Loses meaning when max varies by provider run (empty results) | Low-stakes exploratory features only |

---

## Integration Gotchas

| Integration | Common Mistake | Correct Approach |
|-------------|----------------|------------------|
| Deezer `/artist/{id}/related` | Calling without verifying the artist ID came from the correct artist | Always verify via `ArtistMatcher.isMatch()` before accepting a searched artist ID |
| Deezer `/artist/{id}/radio` | Treating empty `data` array as a parse error | Empty array = artist not found in Deezer or Deezer has no radio for them; return `NotFound` |
| Deezer fan-out concurrency | 100ms `RateLimiter` is per-provider but `resolveTypes()` calls providers concurrently | Deezer has 3 new capabilities; concurrent fan-out could issue 3 Deezer requests simultaneously, breaching the ~50/5s limit. The `RateLimiter` must be shared across all Deezer methods via the same `DeezerApi` instance |
| Last.fm `getsimilar` match score | Score is 0–1 but values above 0.8 are extremely rare (most are 0.1–0.4) | Do not apply `minConfidence` filter to individual similar-artist match scores — these are relative similarity signals, not provider quality scores |
| ListenBrainz CF 204 | `fetchJson()` throws on empty body | Check HTTP status before parsing; 204 should return `NotFound`, not `Error` |
| ListenBrainz CF `last_updated` | Ignoring stale recommendations | Cache TTL for CF results should be short (1–7 days); stale recommendations with old `last_updated` should be flagged or re-fetched |
| Genre discovery from GenreMerger | Using raw `genreTags` from a single provider | Must use merged tags from `resolveAll()` output to get multi-provider confidence; single-provider tags have inflated variance |
| MusicBrainz Credits MBID | `Credit.identifiers` assumed empty by existing code | Verify actual parser output before assuming credits have no MBIDs; if absent, plan to add MBID extraction before building reverse lookup |

---

## Performance Traps

| Trap | Symptoms | Prevention | When It Breaks |
|------|----------|------------|----------------|
| Uncached SIMILAR_ALBUMS triggering 4+ provider calls per request | Response time spikes; MusicBrainz rate limiter backpressure | Cache SIMILAR_ALBUMS aggressively (30-day TTL); it has no real-time data | Any caller requesting SIMILAR_ALBUMS without prior cache warm-up |
| Credit-based discovery making per-credit reverse lookups | O(N credits) API calls for a track with many collaborators | Batch or limit: resolve top-1 or top-2 credits by roleCategory ("producer", "composer") | Tracks with 10+ distinct credits (common for hip-hop productions) |
| SIMILAR_ARTISTS merge collecting all providers including slow ones | P95 latency dominates | Circuit breakers handle provider failures, but slow providers block the merge; add per-provider timeout | Any request where one provider takes >2s (Deezer geographic restrictions) |
| Deezer concurrent fan-out breaching rate limit | Intermittent 429s, circuit breaker opens, all Deezer results lost | Ensure `DeezerApi` instance is shared across all provider capabilities so the `RateLimiter` serializes calls | When 3+ Deezer-backed types are requested in the same `enrich()` call |
| SIMILAR_ALBUMS composite triggering sub-types that trigger further composites | Stack overflow or unbounded resolution | Never put a composite type in `COMPOSITE_DEPENDENCIES` of another composite | Immediately on first request if SIMILAR_ALBUMS depends on ARTIST_TIMELINE |

---

## "Looks Done But Isn't" Checklist

- [ ] **Deezer SIMILAR_ARTISTS:** Provider returns data in isolation — verify it actually runs in the engine (SIMILAR_ARTISTS added to MERGEABLE_TYPES, SimilarArtistsMerger wired)
- [ ] **Deezer SIMILAR_TRACKS:** Provider returns data for a known artist — verify behavior for obscure artists not in Deezer's catalog (empty `data[]` handled as `NotFound`)
- [ ] **SIMILAR_ALBUMS synthesizer:** Returns results for mainstream artists — verify behavior when genre data is missing (zero genreTags) or similar artists returned empty
- [ ] **ListenBrainz CF:** Test with a username that has recommendations — verify 204 handled correctly for users with no generated recommendations
- [ ] **Credit-based discovery:** Works for a well-documented recording — verify behavior for recordings with zero MBID-linked credits (Discogs-only or anonymous credits)
- [ ] **Genre discovery:** Returns neighbors — verify that the normalized genre key is used for comparison, not the raw display name (prevents "Alt Rock" vs "alt rock" mismatches)
- [ ] **Rate limiting under fan-out:** Each feature works in isolation — verify that requesting SIMILAR_ARTISTS + SIMILAR_TRACKS + SIMILAR_ALBUMS in one call does not cause Deezer 429s
- [ ] **Score normalization:** Merged results look reasonable — verify the merge produces a stable sort (same input always produces same output order)

---

## Recovery Strategies

| Pitfall | Recovery Cost | Recovery Steps |
|---------|---------------|----------------|
| SIMILAR_ARTISTS implemented as short-circuit (Pitfall 2) | LOW | Add `SIMILAR_ARTISTS` to `MERGEABLE_TYPES`; implement `SimilarArtistsMerger`; existing provider tests still pass |
| Wrong Deezer artist ID used (Pitfall 3) | LOW | Add `ArtistMatcher.isMatch()` guard in `searchArtist()` helper; add test for common-name artist |
| ListenBrainz CF modeled as EnrichmentProvider (Pitfall 4) | HIGH | Requires removing from `ProviderRegistry`, adding new public API method, updating any callers |
| SIMILAR_ALBUMS synthesizer makes I/O calls (Pitfall 5) | MEDIUM | Extract I/O to engine layer, keep synthesizer pure; requires new engine method for batch sub-requests |
| Credit discovery built without MBIDs (Pitfall 6) | MEDIUM | Audit `MusicBrainzParser.parseRecordingCredits()` output; add MBID extraction to parser; may require changing `Credit` data class shape |
| Score mixing corruption (Pitfall 1) | LOW | Define score derivation rules in mapper constants; rewrite merge to use max-score-wins instead of average |

---

## Pitfall-to-Phase Mapping

| Pitfall | Prevention Phase | Verification |
|---------|------------------|--------------|
| Multi-provider score incompatibility (Pitfall 1) | Phase 1 — Deezer SIMILAR_ARTISTS | FakeProvider test: Last.fm + Deezer both return artists; merged result has stable ordering with correct top artist |
| SIMILAR_ARTISTS not in MERGEABLE_TYPES (Pitfall 2) | Phase 1 — before DeezerProvider wiring | Test: DeezerProvider in registry returns artists; engine result contains artists from both providers |
| Deezer artist ID not verified (Pitfall 3) | Phase 1 — DeezerApi.searchArtist() | Test: artist with common name returns `NotFound` when match fails `ArtistMatcher.isMatch()` |
| ListenBrainz CF user-scope mismatch (Pitfall 4) | Phase 4 — design phase | No `EnrichmentProvider` subclass for CF; a separate service/function is the only implementation path |
| SIMILAR_ALBUMS composite scope hop (Pitfall 5) | Phase 2 — synthesizer design | Synthesizer is a pure function with no I/O; unit test runs without any HTTP calls |
| Credit discovery reverse lookup gap (Pitfall 6) | Phase 3 — audit before coding | `MusicBrainzParser.parseRecordingCredits()` test asserts MBID is present in Credit.identifiers for known recording |
| Genre merge used incorrectly (Pitfall 7) | Phase 5 — Genre Discovery | Genre discovery test uses `resolveAll()` output, not a single provider's raw tags |
| Deezer concurrent rate limit (Pitfall 8) | Phase 1 | Integration test: 3 Deezer-backed types in one call; verify single `DeezerApi` instance shared across capabilities |

---

## Sources

- Codebase analysis: `DefaultEnrichmentEngine.kt`, `ProviderChain.kt`, `ProviderRegistry.kt`, `GenreMerger.kt`, `ConfidenceCalculator.kt`, `DeezerProvider.kt`, `ListenBrainzProvider.kt`, `LastFmProvider.kt`, `MusicBrainzProvider.kt`, `TimelineSynthesizer.kt`, `EnrichmentData.kt`, `EnrichmentType.kt`
- Provider documentation: `docs/providers/deezer.md`, `docs/providers/listenbrainz.md`, `docs/providers/lastfm.md`
- Project context: `.planning/PROJECT.md`
- ListenBrainz CF endpoint: https://listenbrainz.readthedocs.io/en/latest/users/api/recommendation.html (204 = no recommendations generated; endpoint is experimental and subject to change)
- Music credit completeness: https://soundcharts.com/en/blog/music-metadata (50M+ songs missing producer/songwriter credits; cross-database inconsistency is systemic)
- Last.fm similarity algorithm: https://www.quora.com/How-does-Last-fm-compute-lists-of-similar-artists (collaborative filtering + tag-based; not a pure content signal)
- Deezer rate limit: https://github.com/BackInBash/DeezerSync/issues/6 (community-observed ~50 req/5s; no official documentation)

---
*Pitfalls research for: v0.6.0 Recommendations Engine — adding 7 recommendation modules to existing music metadata enrichment library*
*Researched: 2026-03-23*
