---
gsd_state_version: 1.0
milestone: v0.4.0
milestone_name: Provider Abstraction Overhaul
status: unknown
stopped_at: Completed 05-01-PLAN.md
last_updated: "2026-03-21T09:52:41.168Z"
progress:
  total_phases: 5
  completed_phases: 4
  total_plans: 15
  completed_plans: 12
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-03-21)

**Core value:** Consumers get comprehensive music metadata from a single enrich() call without knowing provider details.
**Current focus:** Phase 05 — Deepening

## Current Position

Phase: 05 (Deepening) — EXECUTING
Plan: 2 of 4

## Performance Metrics

**Velocity:**

- Total plans completed: 0
- Average duration: --
- Total execution time: 0 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| - | - | - | - |

**Recent Trend:**

- Last 5 plans: --
- Trend: --

*Updated after each plan completion*
| Phase 01-bug-fixes P01 | 4min | 2 tasks | 5 files |
| Phase 01-bug-fixes P02 | 4min | 2 tasks | 5 files |
| Phase 02-provider-abstraction P01 | 10min | 3 tasks | 18 files |
| Phase 02-provider-abstraction P02 | 5min | 2 tasks | 10 files |
| Phase 02-provider-abstraction P03 | 7min | 2 tasks | 24 files |
| Phase 03-public-api-cleanup P01 | 5min | 2 tasks | 11 files |
| Phase 03-public-api-cleanup P02 | 7min | 2 tasks | 7 files |
| Phase 04-new-types P01 | 2min | 2 tasks | 4 files |
| Phase 04-new-types P03 | 3min | 2 tasks | 12 files |
| Phase 04-new-types P02 | 7min | 2 tasks | 7 files |
| Phase 04-new-types P04 | 8min | 2 tasks | 20 files |
| Phase 05-deepening P01 | 4min | 2 tasks | 6 files |

## Accumulated Context

### Decisions

- Provider mapper pattern: isolate DTO-to-EnrichmentData mapping so public API shape changes only touch mappers (pending Phase 2)
- Clean breaks over deprecation: no external consumers at pre-1.0; IdentifierResolution removal is a hard delete (pending Phase 2)
- MusicBrainz as identity backbone: MBIDs + Wikidata/Wikipedia links enable precise downstream lookups (established)
- [Phase 01-bug-fixes]: Null API response and empty results both map to NotFound (API layer conflates both to emptyList)
- [Phase 01-bug-fixes]: LRCLIB duration uses Double parameter type for precise float formatting via Kotlin string interpolation
- [Phase 01-bug-fixes]: Wikidata preferred-rank fallback uses first claim (index 0) for backward compatibility
- [Phase 02-provider-abstraction]: IdentifierRequirement enum replaces boolean requiresIdentifier with 6 typed values for precise provider skipping
- [Phase 02-provider-abstraction]: Identity provider selected by isIdentityProvider flag, not GENRE/LABEL capability heuristic
- [Phase 02-provider-abstraction]: needsIdentityResolution scans provider capabilities data-driven, retains no-MBID baseline check
- [Phase 02-provider-abstraction]: IdentifierResolution removed as clean break; engine calls provider.resolveIdentity() for identity resolution, reads IDs from result.resolvedIdentifiers
- [Phase 02-provider-abstraction]: Mapper objects use pure functions in provider package, isolating all DTO-to-EnrichmentData mapping
- [Phase 02-provider-abstraction]: ApiKeyConfig with nullable Strings; withDefaultProviders creates 8 keyless providers always, key-requiring providers only with keys
- [Phase 03-public-api-cleanup]: TTL values carried as defaultTtlMs on EnrichmentType enum entries with config.ttlOverrides for per-type override
- [Phase 03-public-api-cleanup]: EnrichmentIdentifiers extended with extra map, get(), withExtra() for extensible provider IDs; @Serializable added for data class embedding
- [Phase 03-public-api-cleanup]: ErrorKind.UNKNOWN default preserves all existing Error construction sites; fetchJsonResult has no retry logic
- [Phase 04-new-types]: Supporting data classes (BandMember, DiscographyAlbum, etc.) placed as top-level @Serializable types consistent with existing SimilarArtist/PopularTrack pattern
- [Phase 04-new-types]: Deezer uses search-then-fetch pattern: searchArtist for ID, then getArtistAlbums/getAlbumTracks by ID; Deezer IDs stored in identifiers.extra
- [Phase 04-new-types]: Last.fm SIMILAR_TRACKS type check handled before ForArtist cast, allowing ForTrack requests for this type only
- [Phase 04-new-types]: Separate lookupArtistWithRels method avoids artist-rels overhead on existing lookups; new types routed through enrichArtistNewType for clean dispatch
- [Phase 04-new-types]: CAA JSON metadata fetched alongside redirect URL for artwork sizes with graceful degradation
- [Phase 04-new-types]: Wikidata multi-property fetch (P18|P569|P570|P495|P106) replaces single-property calls; QID maps for country/occupation
- [Phase 04-new-types]: Discogs band members uses search-then-fetch pattern: searchArtist for ID, getArtist for member list
- [Phase 05-deepening]: Back/booklet use findImageByType filtering CAA JSON images array by types field, no release-group fallback

### Pending Todos

None yet.

### Blockers/Concerns

None yet.

## Session Continuity

Last session: 2026-03-21T09:52:41.166Z
Stopped at: Completed 05-01-PLAN.md
Resume file: None
