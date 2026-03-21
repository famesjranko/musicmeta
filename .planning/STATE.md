---
gsd_state_version: 1.0
milestone: v0.5.0
milestone_name: New Capabilities & Tech Debt Cleanup
status: unknown
stopped_at: Completed 09-artist-timeline/09-02-PLAN.md
last_updated: "2026-03-21T17:34:41.716Z"
progress:
  total_phases: 6
  completed_phases: 4
  total_plans: 10
  completed_plans: 10
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-03-22)

**Core value:** Consumers get comprehensive, accurate music metadata from a single enrich() call without knowing which APIs exist, how they authenticate, or how to correlate identifiers across services.
**Current focus:** Phase 09 — Artist Timeline

## Current Position

Phase: 10
Plan: Not started

## Performance Metrics

**Velocity:**

- Total plans completed: 0 (v0.5.0)
- Average duration: --
- Total execution time: 0 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| - | - | - | - |

*Updated after each plan completion*
| Phase 06-tech-debt-cleanup P01 | 8 | 2 tasks | 16 files |
| Phase 06-tech-debt-cleanup P03 | 15 | 2 tasks | 12 files |
| Phase 06-tech-debt-cleanup P02 | 15 | 2 tasks | 9 files |
| Phase 06-tech-debt-cleanup P04 | 15 | 2 tasks | 6 files |
| Phase 07-credits-personnel P01 | 25 | 2 tasks | 10 files |
| Phase 07-credits-personnel P02 | 20 | 2 tasks | 5 files |
| Phase 08-release-editions P01 | 4 | 2 tasks | 10 files |
| Phase 08-release-editions P02 | 8 | 2 tasks | 6 files |
| Phase 09-artist-timeline P01 | 2 | 2 tasks | 5 files |
| Phase 09-artist-timeline P02 | 4 | 2 tasks | 2 files |

## Accumulated Context

### Decisions

- Provider mapper pattern: isolate DTO-to-EnrichmentData mapping so public API shape changes only touch mappers (established v0.4.0)
- Clean breaks over deprecation: no external consumers at pre-1.0 (established v0.4.0)
- MusicBrainz as identity backbone: MBIDs + Wikidata/Wikipedia links enable precise downstream lookups (established)
- ConfidenceCalculator with semantic tiers for standardized confidence scoring (established v0.4.0)
- HttpResult and ErrorKind introduced in v0.4.0 but not yet adopted — Phase 6 completes adoption across all 11 providers
- [Phase 06-tech-debt-cleanup]: Api classes keep nullable return types during HttpResult migration; IOException/JSONException propagate to Provider where mapError() converts them to ErrorKind
- [Phase 06-tech-debt-cleanup]: FakeHttpClient.givenIoException() added to test Provider-level ErrorKind handling; distinct from givenError() which tests null-return path
- [Phase 06-tech-debt-cleanup]: Api classes keep nullable return types: HttpResult error branches convert to null via when-else pattern, preserving existing Provider interface shape
- [Phase 06-tech-debt-cleanup]: ListenBrainzApi uses fetchJsonArrayResult/postJsonArrayResult (not fetchJsonResult) because all 4 endpoints return JSONArray responses
- [Phase 06-tech-debt-cleanup]: CoverArtArchiveApi fetchRedirectUrl calls left unchanged — redirect pattern has no HttpResult equivalent and is semantically distinct from JSON fetches
- [Phase 06-tech-debt-cleanup]: MusicBrainzApi rateLimiter.execute block uses return@execute null in else branch to preserve nullable return contract while using fetchJsonResult internally
- [Phase 06-tech-debt-cleanup]: Discogs IDs stored via extra map keys discogsReleaseId/discogsMasterId consistent with withExtra pattern; masterId omitted when master_id=0 as Discogs uses 0 for absent master
- [Phase 07-credits-personnel]: lookupRecording returns raw JSONObject to avoid bloating MusicBrainzRecording with optional relation fields only present during lookup
- [Phase 07-credits-personnel]: enrichTrackCredits dispatched via type-check guard at top of enrichTrack, consistent with enrichAlbumTracks pattern
- [Phase 07-credits-personnel]: Work-rel composer/lyricist/arranger extracted from nested work.relations when top-level rel is performance/work; mapArtistRelType/mapWorkRelType helpers centralize role-to-category mapping
- [Phase 07-credits-personnel]: DiscogsCredit id uses Long? with takeIf { it > 0 } — same 0-means-absent convention as masterId in Phase 6
- [Phase 07-credits-personnel]: parseCreditsArray extracted as private helper shared by release-level and track-level extraartists parsing
- [Phase 07-credits-personnel]: CREDITS dispatch guard in enrich() placed before album-type cast, consistent with BAND_MEMBERS guard pattern
- [Phase 08-release-editions]: lookupReleaseGroup returns raw JSONObject (same pattern as lookupRecording) — release sub-structures are complex and only needed for editions
- [Phase 08-release-editions]: RELEASE_EDITIONS dispatch guard placed after ALBUM_TRACKS guard in enrichAlbum, consistent with existing pattern
- [Phase 08-release-editions]: parseReleaseGroupDetail extracts format from media[0].format and label/catalogNumber from label-info[0] — first medium and first label only
- [Phase 08-release-editions]: RELEASE_EDITIONS dispatch guard placed after CREDITS guard in enrich(), consistent with established type-check guard pattern
- [Phase 08-release-editions]: enrichAlbumEditions reads discogsMasterId from identifiers.extra (Phase 6 DEBT-04 dependency), not by fuzzy search — enables precise lookup
- [Phase 08-release-editions]: barcode=null in toReleaseEditions — Discogs master versions API flat response does not include barcode field
- [Phase 09-artist-timeline]: TimelineSynthesizer.synthesize() accepts EnrichmentResult? so callers pass raw results without unwrapping — simplifies Plan 02 integration
- [Phase 09-artist-timeline]: null artistType in Metadata defaults to Group behavior (formed/disbanded) — most MusicBrainz artists without explicit type are groups
- [Phase 09-artist-timeline]: TimelineEvent placed as top-level @Serializable class, consistent with BandMember, DiscographyAlbum, etc.
- [Phase 09-artist-timeline]: resolveIdentity() returns Pair<EnrichmentRequest, EnrichmentResult?> so raw identity result threads to composite synthesizer regardless of which IDENTITY_TYPES the caller requested
- [Phase 09-artist-timeline]: COMPOSITE_DEPENDENCIES map in companion object maps ARTIST_TIMELINE to sub-types; filterKeys excludes sub-type results from caller-visible return map

### Pending Todos

None yet.

### Blockers/Concerns

- Phase 7 (Credits) and Phase 8 (Release Editions) depend on Phase 6 DEBT-04 (Discogs ID storage) — do not plan them before Phase 6 completes

## Session Continuity

Last session: 2026-03-21T17:31:43.938Z
Stopped at: Completed 09-artist-timeline/09-02-PLAN.md
Resume file: None
