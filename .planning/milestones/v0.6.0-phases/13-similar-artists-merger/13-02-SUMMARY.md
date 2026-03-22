---
phase: 13-similar-artists-merger
plan: "02"
subsystem: engine
tags: [merger, similar-artists, deduplication, scoring]
dependency_graph:
  requires: [13-01]
  provides: [SIM-02, SIM-03]
  affects: [EnrichmentEngine.Builder, DefaultEnrichmentEngine, SIMILAR_ARTISTS type]
tech_stack:
  added: []
  patterns: [ResultMerger strategy, additive scoring, normalized dedup]
key_files:
  created:
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/engine/SimilarArtistMerger.kt
    - musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/engine/SimilarArtistMergerTest.kt
  modified:
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/EnrichmentEngine.kt
    - musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/engine/DefaultEnrichmentEngineTest.kt
decisions:
  - Builder integration test uses DefaultEnrichmentEngine directly with explicit mergers list rather than Builder, matching existing test patterns for engine helper
metrics:
  duration: 5m
  completed: "2026-03-22T14:07:04Z"
  tasks_completed: 2
  files_modified: 4
---

# Phase 13 Plan 02: SimilarArtistMerger + Builder Wiring Summary

**One-liner:** SimilarArtistMerger deduplicates similar artists by normalized name with additive matchScore capping and multi-provider source tracking, registered in EnrichmentEngine.Builder alongside GenreMerger.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | Create SimilarArtistMerger implementing ResultMerger | ff31dbf | SimilarArtistMerger.kt, SimilarArtistMergerTest.kt |
| 2 | Wire SimilarArtistMerger into Builder and verify end-to-end merge | 816c403 | EnrichmentEngine.kt, DefaultEnrichmentEngineTest.kt |

## What Was Built

### SimilarArtistMerger (engine/SimilarArtistMerger.kt)

Stateless `object SimilarArtistMerger : ResultMerger` implementing the ResultMerger strategy pattern. The merger:

- Returns `NotFound("all_providers")` when the results list is empty
- Deduplicates artists by normalized name (lowercase trim), preserving first-seen casing
- Sums `matchScore` across providers with `coerceAtMost(1.0f)` cap
- Merges `sources` lists via `distinct()` to avoid duplicates
- Merges `identifiers`: prefers non-null `musicBrainzId`, combines `extra` maps from all providers
- Sorts final list by `matchScore` descending
- Reports `provider = "similar_artist_merger"` and `confidence = maxOf(provider confidences)`

### Builder Wiring (EnrichmentEngine.kt)

Added `SimilarArtistMerger` to the Builder's default `mergers` list alongside `GenreMerger`. No user configuration needed — SIMILAR_ARTISTS is now automatically a mergeable type dispatched through `resolveAll` + `merge` in `DefaultEnrichmentEngine.resolveTypes()`.

### Integration Test (DefaultEnrichmentEngineTest.kt)

New test `enrich merges SIMILAR_ARTISTS from multiple providers` proves end-to-end:
- "Muse" from lastfm (0.9) + deezer (0.5) deduplicates to 1 entry with score 1.0 and both sources
- "Bjork" (lastfm-only) and "Portishead" (deezer-only) each appear with single-provider sources
- Result sorted by matchScore descending (Muse=1.0, Portishead=0.8, Bjork=0.7)

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Integration test engine construction needed explicit mergers**

- **Found during:** Task 2 verification (test failure)
- **Issue:** The `engine()` helper in DefaultEnrichmentEngineTest constructs `DefaultEnrichmentEngine` directly with default parameters (only `GenreMerger`). SIMILAR_ARTISTS therefore fell through to the short-circuit chain returning the first provider's result ("lastfm") instead of the merger.
- **Fix:** Integration test constructs `DefaultEnrichmentEngine` with `mergers = listOf(GenreMerger, SimilarArtistMerger)` explicitly, matching what the Builder would produce.
- **Files modified:** DefaultEnrichmentEngineTest.kt
- **Commit:** 816c403

## Known Stubs

None. All SimilarArtist data flows from providers through the merger to the final result.

## Self-Check

Checking created files exist and commits are present...

- FOUND: SimilarArtistMerger.kt
- FOUND: SimilarArtistMergerTest.kt
- FOUND: 13-02-SUMMARY.md
- FOUND commit ff31dbf: feat(13-02): implement SimilarArtistMerger with dedup/scoring/source-merge
- FOUND commit 816c403: feat(13-02): wire SimilarArtistMerger into Builder and add integration test

## Self-Check: PASSED
