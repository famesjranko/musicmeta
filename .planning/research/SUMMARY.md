# Project Research Summary

**Project:** musicmeta v0.6.0 — Recommendations Engine
**Domain:** Music metadata enrichment library (Kotlin/JVM), adding 7 recommendation modules to an existing provider-chain engine
**Researched:** 2026-03-23
**Confidence:** HIGH

## Executive Summary

The v0.6.0 Recommendations Engine extends an already-working enrichment library rather than building from scratch. All 7 recommendation modules can be implemented using the existing stack — no new dependencies are needed. The work divides cleanly into three categories: (a) adding new capabilities to existing Deezer and ListenBrainz providers (SIMILAR_ARTISTS, SIMILAR_TRACKS), (b) adding one new composite type synthesized from existing data (SIMILAR_ALBUMS), and (c) adding two new out-of-band engine methods that bypass the provider chain entirely (credit-based discovery, listening recommendations). A sixth module, genre affinity, is a pure utility function requiring no API calls or new types.

The recommended approach treats each module as fitting one of four established patterns from the codebase: standard provider addition, composite type synthesis, engine-level utility, and new engine method. The critical insight from architecture research is that ListenBrainz collaborative filtering and credit-based discovery are fundamentally user-scoped or person-scoped queries — not entity-enrichment calls — and must not be forced into the `EnrichmentRequest`/`EnrichmentProvider` model. Both should be added as dedicated methods on `EnrichmentEngine` (`discoverByCredit()`, `listeningRecommendations()`), bypassing the provider chain.

The highest-risk pitfalls involve multi-provider similarity score incompatibility (Deezer returns ranked lists with no numeric scores, which corrupt Last.fm semantic scores if naively merged) and the Deezer artist ID resolution gap (three Deezer features require a numeric Deezer artist ID not currently stored in `EnrichmentIdentifiers`). Both pitfalls must be addressed in Phase 1 before any later module builds on Deezer data. SIMILAR_ALBUMS is the highest-value new feature and the highest-complexity implementation, requiring a pure synthesizer that operates on already-resolved sub-types with no I/O calls inside the synthesis step.

## Key Findings

### Recommended Stack

No new library dependencies are required for any of the 7 modules. The existing stack — Kotlin 2.1.0, kotlinx.coroutines 1.9.0, org.json for HTTP parsing, kotlinx.serialization-json for `EnrichmentData` — is sufficient. Pure-Kotlin cosine similarity replaces Apache Commons Math for genre affinity (10 lines, no external JAR). No new entries in `gradle/libs.versions.toml`.

**Core technologies:**
- Kotlin/JVM 2.1.0 — language; no upgrade needed
- kotlinx.coroutines 1.9.0 — existing fan-out pattern (`coroutineScope { async {} }`) handles all concurrent resolution; no new concurrency primitives needed
- org.json — all new API response parsing (Deezer, ListenBrainz CF) follows the same pattern as existing providers
- kotlinx.serialization-json — new `EnrichmentData` subtypes (`SimilarAlbums`, `SimilarAlbum`) auto-serialize via the existing sealed class hierarchy

The only schema additions needed are new `EnrichmentType` enum entries: `SIMILAR_ALBUMS` (30-day TTL, composite) and `LISTENING_RECS` (1-day TTL, non-composite). `SIMILAR_TRACKS` and `SIMILAR_ARTISTS` already exist and receive new providers; `RADIO_MIX` maps directly to `SIMILAR_TRACKS` (no new type warranted per architecture research).

### Expected Features

**Must have (table stakes) for v0.6.0:**
- Deezer SIMILAR_ARTISTS — third provider in existing short-circuit chain, foundational for SIMILAR_ALBUMS
- Deezer SIMILAR_TRACKS — second provider in existing chain; position-based synthetic match score (position 1 = 1.0, linear decay to 0.1 at position 25)
- SIMILAR_ALBUMS — new composite `EnrichmentType` synthesized from SIMILAR_ARTISTS + GENRE + ARTIST_DISCOGRAPHY; highest-value new discovery surface
- ListenBrainz CF (LISTENING_RECS) — new out-of-band engine method returning recording MBIDs with CF scores for a named LB user

**Should have (competitive differentiators):**
- SIMILAR_ARTISTS promoted to mergeable type — upgrades from short-circuit to all-provider union with `SimilarArtistsMerger`; improves SIMILAR_ALBUMS input quality
- Genre affinity (`GenreGraph` utility) — cosine similarity on `List<GenreTag>`, exposed as a pure utility object analogous to `GenreMerger`; no new `EnrichmentType` needed
- Credit-based discovery — new `discoverByCredit(personMbid, role, limit)` engine method; scoped to production/songwriting roles where MBID data is dense

**Defer (v0.6.x / v0.7+):**
- Genre taxonomy hierarchy — deferred per PROJECT.md; flat affinity table covers the use case
- `ForUser` request variant — clean model for ListenBrainz CF but a breaking change; defer until CF sees real usage
- Full SIMILAR_ARTISTS merger with score averaging — build once all three providers are live and coverage can be measured
- MusicBrainz `getRecordingsByContributor` cross-entity query — high complexity; Approach A (synthesis from existing CREDITS) is v0.6.0 scope

### Architecture Approach

Four distinct patterns from the codebase handle all 7 modules without inventing new abstractions. Standard provider addition (Pitfall-aware: SIMILAR_ARTISTS must be promoted to `MERGEABLE_TYPES` or Deezer data is silently discarded) handles Deezer SIMILAR_ARTISTS and SIMILAR_TRACKS. Composite type synthesis — the `ARTIST_TIMELINE` pattern — handles SIMILAR_ALBUMS via a new pure `SimilarAlbumsSynthesizer` object. Engine-level utility (the `GenreMerger` pattern) handles genre affinity as a `GenreGraph` object. New engine methods on the `EnrichmentEngine` interface handle the two out-of-band features whose query shape does not fit `EnrichmentRequest`.

**Major components introduced in v0.6.0:**
1. `DeezerApi` (extended) — adds `getRelatedArtists(artistId, limit)` and `getArtistRadio(artistId, limit)`; shared `RateLimiter` instance must cover all three Deezer capabilities to prevent concurrent fan-out exceeding ~50 req/5s
2. `SimilarAlbumsSynthesizer` — new pure stateless object; mirrors `TimelineSynthesizer`; accepts already-resolved `SimilarArtists`, `Genre`, and `ArtistDiscography` results; no I/O
3. `GenreGraph` — new public utility object; `neighbors(tags, limit)` using cosine similarity; no provider registration
4. `DefaultEnrichmentEngine` (extended) — adds `discoverByCredit()` and `listeningRecommendations()` methods; both bypass provider chain and call `MusicBrainzApi`/`ListenBrainzApi` directly via Builder-injected instances

### Critical Pitfalls

1. **Multi-provider score incompatibility** — Deezer returns ranked lists with no numeric scores; Last.fm returns semantic floats 0–1. Mixing them in a naive average corrupts ranking. Prevention: derive Deezer scores from position (`1.0 - index/count`), treat Last.fm as primary confidence, use max-score-wins (not averaging) in the merger. Must be defined before any merge code is written (Phase 1).

2. **SIMILAR_ARTISTS not in MERGEABLE_TYPES** — Adding Deezer at priority 50 to a short-circuit chain means its data is silently discarded whenever Last.fm succeeds. Prevention: add `SIMILAR_ARTISTS` to `MERGEABLE_TYPES` and implement `SimilarArtistsMerger` before wiring the Deezer provider. Verify with a test that asserts both providers' artists appear in the engine result.

3. **Deezer artist ID resolution gap** — Three Deezer features require a numeric Deezer artist ID not stored in `EnrichmentIdentifiers` unless a prior discography call ran. Prevention: check `identifiers.extra["deezerId"]` first; fall back to `searchArtist(name)` with `ArtistMatcher.isMatch()` verification; return `NotFound` rather than guessing. The shared `DeezerApi` instance must be used across all capabilities to ensure the `RateLimiter` serializes all concurrent Deezer calls.

4. **ListenBrainz CF user-scope mismatch** — The CF endpoint is user-scoped, not entity-scoped; modeling it as an `EnrichmentProvider` requires username in `EnrichmentRequest` which poisons the request model. Prevention: implement as a dedicated `listeningRecommendations(username, limit)` method on `EnrichmentEngine`; handle 204 No Content as `NotFound` (not an error) in `ListenBrainzApi`.

5. **SIMILAR_ALBUMS synthesizer with I/O** — The temptation is to fetch each similar artist's discography inside the synthesizer. This breaks the pure-function synthesis model, introduces uncounted HTTP calls, and creates timeout risk. Prevention: scope v0.6.0 SIMILAR_ALBUMS to artist-level signals from already-resolved sub-types only; if per-similar-artist discography is needed, declare it as a sub-type dependency at the engine level, not inside the synthesizer.

6. **Credit discovery without MBIDs** — Credit-based discovery requires a reverse lookup (person → recordings), which requires the credited person's MBID. Prevention: audit `MusicBrainzParser.parseRecordingCredits()` before writing any discovery logic; verify that `Credit.identifiers.musicBrainzId` is populated for production credits. If absent, add MBID extraction to the parser first.

## Implications for Roadmap

Based on research, the build order is driven by two constraints: (a) Deezer provider extensions must be stable before SIMILAR_ALBUMS can be tested with real data, and (b) ListenBrainz CF and credit discovery are independent of all other modules. Genre affinity is a utility that can be delivered at any point.

### Phase 1: Deezer Provider Extensions
**Rationale:** Foundational for SIMILAR_ALBUMS (Phase 2) and validates the "add to existing provider" pattern before more complex work begins. Must address the three Deezer pitfalls (score incompatibility, MERGEABLE_TYPES promotion, ID resolution gap) before any code ships.
**Delivers:** Deezer as third SIMILAR_ARTISTS provider and second SIMILAR_TRACKS provider; `SimilarArtistsMerger` promoting SIMILAR_ARTISTS to a mergeable type; verified artist ID resolution with `ArtistMatcher.isMatch()` guard.
**Addresses:** Deezer SIMILAR_ARTISTS (table stakes), Deezer SIMILAR_TRACKS (table stakes)
**Avoids:** Pitfall 1 (score incompatibility), Pitfall 2 (short-circuit silently discards Deezer), Pitfall 3 (unverified artist ID), Pitfall 8 (concurrent Deezer fan-out via shared `RateLimiter`)
**Research flag:** Standard patterns; skip research-phase.

### Phase 2: SIMILAR_ALBUMS Composite
**Rationale:** Highest-value new discovery surface; depends on Phase 1 providing real SIMILAR_ARTISTS data to validate the synthesizer. Must define the synthesizer contract as a pure function with no I/O before implementation begins.
**Delivers:** New `EnrichmentType.SIMILAR_ALBUMS`; `SimilarAlbumsSynthesizer` pure object; new `EnrichmentData.SimilarAlbums` and `SimilarAlbum` data classes; 30-day TTL caching.
**Implements:** Composite type synthesis pattern (mirrors `ARTIST_TIMELINE` / `TimelineSynthesizer`)
**Avoids:** Pitfall 5 (synthesizer with I/O — verify synthesizer is pure before merging)
**Research flag:** Well-documented pattern (ARTIST_TIMELINE precedent); skip research-phase.

### Phase 3: Genre Affinity Utility
**Rationale:** Independent of all other modules; pure computation on existing GENRE data; lowest implementation risk; can be delivered in parallel with or after Phase 2.
**Delivers:** `GenreGraph` public utility object with `neighbors(tags, limit)` using cosine similarity; usable by consumers after any `GENRE` enrichment; also used by `SimilarAlbumsSynthesizer` for genre overlap scoring.
**Implements:** Engine-level utility pattern (mirrors `GenreMerger`)
**Research flag:** Standard patterns; skip research-phase.

### Phase 4: Credit-Based Discovery
**Rationale:** Independent of Deezer phases; depends on CREDITS type (shipped in v0.5.0); must audit `MusicBrainzParser.parseRecordingCredits()` for MBID presence before writing any discovery logic.
**Delivers:** New `discoverByCredit(personMbid, role, limit)` method on `EnrichmentEngine`; MusicBrainz person-relationship query for production/songwriting roles; `CreditDiscoveryResult` data class; result cap (3 persons, 10 recordings each) to prevent fan-out.
**Avoids:** Pitfall 6 (credit discovery without MBIDs — audit parser first); anti-pattern of `ForPerson` request type
**Research flag:** Needs pre-implementation audit: verify `MusicBrainzParser.parseRecordingCredits()` outputs populated `Credit.identifiers.musicBrainzId`. If absent, parser changes are required before Phase 4 proper begins.

### Phase 5: ListenBrainz Collaborative Filtering
**Rationale:** Independent of all other modules; must be modeled as an out-of-band engine method from the start (not an `EnrichmentProvider`) to avoid poisoning the request model.
**Delivers:** New `listeningRecommendations(username, limit)` method on `EnrichmentEngine`; new `ListenBrainzApi.getCFRecommendations()` method; `ListeningRecommendation` data class; 204 No Content handled as `NotFound`; 1-day cache TTL.
**Avoids:** Pitfall 4 (CF user-scope mismatch — no `ForUser` request type, no `EnrichmentProvider` subclass)
**Research flag:** Standard patterns; skip research-phase. API endpoint is marked experimental in ListenBrainz docs — monitor for breaking changes.

### Phase Ordering Rationale

- Phase 1 must precede Phase 2 because the SIMILAR_ALBUMS synthesizer needs real SIMILAR_ARTISTS data from multiple providers to be meaningfully testable; unit testing with fakes alone is insufficient to validate the scoring algorithm.
- Phases 3, 4, and 5 are independent of each other and of Phase 2; they can be sequenced in any order or run in parallel based on team capacity.
- Phase 4 has a pre-work gate (parser audit) that should be done at the start of Phase 4 planning, not deferred to implementation.
- The `SimilarArtistsMerger` in Phase 1 is a prerequisite for full SIMILAR_ALBUMS quality (Phase 2), reinforcing the Phase 1 → Phase 2 dependency.

### Research Flags

Phases likely needing pre-implementation audit or research:
- **Phase 4 (Credit-Based Discovery):** Audit `MusicBrainzParser.parseRecordingCredits()` output before writing any discovery logic. If `Credit.identifiers.musicBrainzId` is absent for production credits, parser changes become a dependency that may scope-expand this phase.

Phases with standard patterns (skip research-phase):
- **Phase 1:** Deezer provider extension follows exact same pattern as existing `enrichDiscography()`; pitfalls are identified and prevention is clear.
- **Phase 2:** SIMILAR_ALBUMS composite follows exact same pattern as `ARTIST_TIMELINE`; synthesizer contract and dependency declaration are fully specified.
- **Phase 3:** `GenreGraph` is a pure utility; cosine similarity implementation is 10 lines verified against the existing `GenreMerger` pattern.
- **Phase 5:** ListenBrainz CF API is documented (official readthedocs); no auth required; 204 edge case is called out explicitly.

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | HIGH | No new dependencies; all existing library versions verified; integration patterns confirmed against codebase source |
| Features | HIGH | API endpoints verified against official ListenBrainz docs and project-maintained Deezer docs; scoring conventions derived from established industry patterns (beaTunes, Last.fm) |
| Architecture | HIGH | Direct codebase analysis of `DefaultEnrichmentEngine`, `TimelineSynthesizer`, `GenreMerger`, `DeezerProvider`; four patterns are proven and in production |
| Pitfalls | HIGH (codebase) / MEDIUM (API behavior) | Pitfalls 1–3, 5 derived from direct code analysis; Pitfalls 4, 6 from API docs + code; Deezer rate limit from community observation only (no official docs) |

**Overall confidence:** HIGH

### Gaps to Address

- **Deezer rate limit (50 req/5s):** Observed from community sources only, not official Deezer developer documentation. The shared `RateLimiter` at 100ms delay should stay conservative; treat 429 responses as rate-limit signals and back off. Validate in Phase 1 integration testing.
- **ListenBrainz CF endpoint stability:** The endpoint is marked experimental in official docs. Implement with a defensive wrapper and surface `last_updated` to consumers so they can detect stale recommendations. Monitor for breaking changes post-v0.6.0 ship.
- **`Credit.identifiers.musicBrainzId` population:** Not confirmed from code analysis (would require reading `MusicBrainzParser.parseRecordingCredits()` output). This is the Phase 4 gate: if MBIDs are absent, credit discovery requires a parser change before any discovery logic can be written.
- **SIMILAR_ALBUMS quality with sparse data:** The synthesizer produces moderate results when GENRE data is missing (zero `GenreTag` entries). The genre overlap defaults to 1.0 (no penalty) in this case, which may inflate scores for artists who share no verified genre overlap. Document as a known limitation; monitor consumer feedback after v0.6.0 ships.

## Sources

### Primary (HIGH confidence)
- Project codebase: `DefaultEnrichmentEngine.kt`, `DeezerProvider.kt`, `DeezerApi.kt`, `ListenBrainzProvider.kt`, `ListenBrainzApi.kt`, `ProviderChain.kt`, `TimelineSynthesizer.kt`, `GenreMerger.kt`, `EnrichmentData.kt`, `EnrichmentType.kt` — direct analysis
- Project provider docs: `docs/providers/deezer.md`, `docs/providers/listenbrainz.md`, `docs/providers/lastfm.md` — project-maintained API reference
- ListenBrainz CF recommendation endpoint: https://listenbrainz.readthedocs.io/en/latest/users/api/recommendation.html — official docs; 204 behavior and public-read confirmed

### Secondary (MEDIUM confidence)
- Deezer artist API (community wiki): https://github.com/antoineraulin/deezer-api/wiki/artist — `/related` and `/radio` endpoint shapes confirmed; not official Deezer developer portal (requires login)
- deezer-python library docs: https://deezer-python.readthedocs.io/en/stable/api_reference/resources/artist.html — third-party wrapper; consistent with community wiki
- Spotify recommendation architecture: https://www.music-tomorrow.com/blog/how-spotify-recommendation-system-works-complete-guide — industry patterns for score normalization and multi-provider merge
- beaTunes matchlist (position-score normalization): https://www.beatunes.com/en/beatunes-matchlist.html — industry precedent for rank-to-score derivation

### Tertiary (LOW confidence)
- Deezer rate limit (50 req/5s): https://github.com/BackInBash/DeezerSync/issues/6 — community-observed only; no official documentation; use conservatively
- Music credit completeness: https://soundcharts.com/en/blog/music-metadata — contextual only; confirms sparse MBID coverage for production credits in less-documented catalogs

---
*Research completed: 2026-03-23*
*Ready for roadmap: yes*
