---
gsd_state_version: 1.0
milestone: v0.5.0
milestone_name: New Capabilities & Tech Debt Cleanup
status: unknown
stopped_at: Completed 06-tech-debt-cleanup/06-04-PLAN.md
last_updated: "2026-03-21T16:23:12.697Z"
progress:
  total_phases: 6
  completed_phases: 1
  total_plans: 4
  completed_plans: 4
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-03-22)

**Core value:** Consumers get comprehensive, accurate music metadata from a single enrich() call without knowing which APIs exist, how they authenticate, or how to correlate identifiers across services.
**Current focus:** Phase 06 — Tech Debt Cleanup

## Current Position

Phase: 7
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

### Pending Todos

None yet.

### Blockers/Concerns

- Phase 7 (Credits) and Phase 8 (Release Editions) depend on Phase 6 DEBT-04 (Discogs ID storage) — do not plan them before Phase 6 completes

## Session Continuity

Last session: 2026-03-21T16:19:37.538Z
Stopped at: Completed 06-tech-debt-cleanup/06-04-PLAN.md
Resume file: None
