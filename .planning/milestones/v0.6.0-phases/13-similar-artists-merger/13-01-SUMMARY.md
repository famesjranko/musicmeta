---
phase: 13-similar-artists-merger
plan: "01"
subsystem: enrichment-providers
tags: [similar-artists, deezer, lastfm, listenbrainz, sources-tracking]
dependency_graph:
  requires: []
  provides: [SimilarArtist.sources, DeezerSimilarArtists]
  affects: [EnrichmentData.kt, LastFmMapper, ListenBrainzMapper, DeezerProvider]
tech_stack:
  added: []
  patterns: [positional-score-mapping, provider-source-tagging, artist-search-with-matcher-guard]
key_files:
  created: []
  modified:
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/EnrichmentData.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/lastfm/LastFmMapper.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/listenbrainz/ListenBrainzMapper.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/deezer/DeezerModels.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/deezer/DeezerApi.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/deezer/DeezerMapper.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/deezer/DeezerProvider.kt
    - musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/provider/deezer/DeezerProviderTest.kt
decisions:
  - "SimilarArtist.sources defaults to emptyList() for backward compatibility with existing call sites"
  - "Deezer positional match score formula: 1.0f - (index / count) * 0.9f gives 1.0 for first, ~0.1 for last"
  - "DeezerProvider.enrichSimilarArtists checks identifiers.extra[deezerId] before searching, enabling caching"
metrics:
  duration_seconds: 223
  completed_date: "2026-03-22"
  tasks_completed: 2
  files_modified: 8
---

# Phase 13 Plan 01: Add Sources Field to SimilarArtist and Deezer SIMILAR_ARTISTS Provider Summary

Added `sources` field to `SimilarArtist` data class and implemented Deezer as a third SIMILAR_ARTISTS provider via `/artist/{id}/related`, with all three providers (Last.fm, ListenBrainz, Deezer) populating per-artist source provenance for use by the Plan 02 merger.

## Tasks Completed

| Task | Description | Commit |
|------|-------------|--------|
| 1 | Add sources field to SimilarArtist; backfill LastFmMapper and ListenBrainzMapper | 73137e4 |
| 2 | Add Deezer SIMILAR_ARTISTS provider with DeezerRelatedArtist model, API method, mapper, and capability | 947fb69 |

## What Was Built

### Task 1: SimilarArtist.sources Field

`SimilarArtist` data class in `EnrichmentData.kt` gained `sources: List<String> = emptyList()`, matching the existing `GenreTag.sources` pattern. The default empty list preserves backward compatibility — existing call sites without `sources` continue to compile.

`LastFmMapper.toSimilarArtists` now sets `sources = listOf("lastfm")` on each `SimilarArtist`.
`ListenBrainzMapper.toSimilarArtists` now sets `sources = listOf("listenbrainz")` on each `SimilarArtist`.

### Task 2: Deezer SIMILAR_ARTISTS Provider

**DeezerModels.kt** — Added `DeezerRelatedArtist(id: Long, name: String)` for the `/artist/{id}/related` response.

**DeezerApi.kt** — Added `getRelatedArtists(artistId: Long, limit: Int = 20)` which calls `/artist/{artistId}/related?limit=N`, parses the `data` array, and returns a list of `DeezerRelatedArtist`.

**DeezerMapper.kt** — Added `toSimilarArtists(artists: List<DeezerRelatedArtist>)` which produces `EnrichmentData.SimilarArtists` with:
- Positional match scores: `1.0f - (index / count) * 0.9f` (first = 1.0, last ≈ 0.1)
- `sources = listOf("deezer")` on each artist
- `deezerId` stored in `identifiers.extra` for downstream caching

**DeezerProvider.kt** — Three additions:
1. `ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, priority = 30)` in `capabilities`
2. `EnrichmentType.SIMILAR_ARTISTS -> enrichSimilarArtists(request)` in the `when` dispatch
3. `enrichSimilarArtists()` private method:
   - Rejects non-`ForArtist` requests with `NotFound`
   - Checks `identifiers.extra["deezerId"]` first (cached ID path)
   - Falls back to `api.searchArtist(name)` with `ArtistMatcher.isMatch()` guard
   - Returns `NotFound` if artist search fails or name doesn't match
   - Calls `api.getRelatedArtists(artist.id)` and returns `NotFound` if empty
   - Returns `Success` with confidence 0.8 (`fuzzyMatch(true)`) and `deezerId` in `resolvedIdentifiers`

## Decisions Made

- **sources defaults to emptyList()**: Backward compatibility for all existing `SimilarArtist` constructors. Matches `GenreTag.sources` precedent in the codebase.
- **Positional score formula**: `1.0f - (index / count) * 0.9f` ensures the first artist scores 1.0 and the last scores 0.1 regardless of list size. The `coerceAtLeast(1)` prevents division-by-zero on empty lists.
- **Deezer artist ID caching**: `enrichSimilarArtists` checks `identifiers.extra["deezerId"]` before issuing a search request. This matches the pattern used in `enrichDiscography` and enables performance optimization when the ID was previously resolved.
- **ArtistMatcher.isMatch() guard**: Prevents Deezer from silently returning data for the wrong artist when the search result name differs from the request.

## Deviations from Plan

None - plan executed exactly as written.

## Known Stubs

None — all three providers produce fully wired `SimilarArtist` lists with `sources` populated.

## Self-Check: PASSED
