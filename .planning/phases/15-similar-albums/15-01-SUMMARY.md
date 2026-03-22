---
phase: 15-similar-albums
plan: 01
subsystem: api
tags: [deezer, enrichment, recommendations, similar-albums, kotlin]

# Dependency graph
requires:
  - phase: 14-artist-radio
    provides: ArtistRadioProvider standalone pattern and DeezerMapper/DeezerApi structure
  - phase: 13-similar-artists-merger
    provides: DeezerApi.getRelatedArtists and ArtistMatcher/ConfidenceCalculator patterns
provides:
  - EnrichmentType.SIMILAR_ALBUMS (30-day TTL)
  - EnrichmentData.SimilarAlbums sealed subclass
  - SimilarAlbum top-level data class with artistMatchScore and era-aware scoring
  - DeezerMapper.toSimilarAlbum() mapping function
  - SimilarAlbumsProvider: standalone provider fetching related artists then their top albums
affects: [15-02-similar-albums-wiring, phase-16-genre-discovery]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - Standalone provider pattern (all I/O inside provider, not synthesizer)
    - Era proximity multiplier: ±5yr = 1.2x, ±10yr = 1.0x, beyond = 0.8x
    - deezerId-first resolution with searchArtist+isMatch fallback

key-files:
  created:
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/deezer/SimilarAlbumsProvider.kt
  modified:
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/EnrichmentType.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/EnrichmentData.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/deezer/DeezerMapper.kt

key-decisions:
  - "SimilarAlbum.year is Int? (not String?) matching ReleaseEdition.year pattern already in the file"
  - "SimilarAlbumsProvider is standalone (NOT composite): all Deezer API calls happen inside the provider, not in a synthesizer"
  - "Era multiplier uses discrete tiers (±5yr/±10yr) rather than continuous decay for simplicity and predictability"
  - "Deduplication uses title.lowercase()|artist.lowercase() key, keeps highest-scored duplicate"

patterns-established:
  - "Standalone multi-call provider: fetch related entities then sub-resources, score inline, return aggregated result"
  - "Era proximity scoring: seedYear comparison with tiered multipliers applied to base position score"

requirements-completed: [ALB-01, ALB-02, ALB-03, ALB-04]

# Metrics
duration: 5min
completed: 2026-03-22
---

# Phase 15 Plan 01: Similar Albums Data Model and Provider Summary

**SIMILAR_ALBUMS type and SimilarAlbumsProvider via Deezer related artists plus per-artist top album sampling with era proximity scoring**

## Performance

- **Duration:** ~5 min
- **Started:** 2026-03-22T14:35:32Z
- **Completed:** 2026-03-22T14:41:00Z
- **Tasks:** 2
- **Files modified:** 4

## Accomplishments
- Added SIMILAR_ALBUMS to EnrichmentType with 30-day TTL
- Added SimilarAlbum top-level data class and EnrichmentData.SimilarAlbums sealed subclass, both @Serializable
- Added DeezerMapper.toSimilarAlbum() mapping DeezerArtistAlbum + artist name + score to SimilarAlbum
- Created SimilarAlbumsProvider: fetches up to 5 related artists, samples up to 3 albums each, applies artist position score * era proximity multiplier, deduplicates by title+artist, sorts by score desc, caps at 20

## Task Commits

Each task was committed atomically:

1. **Task 1: Add SIMILAR_ALBUMS to EnrichmentType and EnrichmentData** - `dea7a0c` (feat)
2. **Task 2: Add DeezerMapper.toSimilarAlbum and create SimilarAlbumsProvider** - `c4fce2b` (feat)

## Files Created/Modified
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/EnrichmentType.kt` - Added SIMILAR_ALBUMS entry with 30-day TTL in "Discovery / recommendations" section
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/EnrichmentData.kt` - Added SimilarAlbums sealed subclass and SimilarAlbum top-level data class
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/deezer/DeezerMapper.kt` - Added toSimilarAlbum() mapping function
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/deezer/SimilarAlbumsProvider.kt` - New standalone provider implementing SIMILAR_ALBUMS

## Decisions Made
- SimilarAlbum.year is Int? (not String?) to match the ReleaseEdition.year pattern already in EnrichmentData.kt
- SimilarAlbumsProvider is standalone (not composite): all Deezer API calls happen inside the provider, not a synthesizer — consistent with the decision logged in STATE.md
- Era multiplier uses discrete tiers (±5yr = 1.2x, ±10yr = 1.0x, beyond = 0.8x) rather than continuous decay — simpler, predictable behavior
- Plan's `!!` in the dedup step was replaced with `?: dupes.first()` to respect the no-`!!` code style rule

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Code Style] Replaced !! with safe fallback in deduplication**
- **Found during:** Task 2 (SimilarAlbumsProvider implementation)
- **Issue:** Plan snippet used `.maxByOrNull { it.artistMatchScore }!!` which violates the no-`!!` rule in CLAUDE.md
- **Fix:** Changed to `.maxByOrNull { it.artistMatchScore } ?: dupes.first()` — `dupes` is always non-empty at this point (it's a value from `groupBy`), so `dupes.first()` is a safe fallback
- **Files modified:** SimilarAlbumsProvider.kt
- **Verification:** Build passes
- **Committed in:** c4fce2b (Task 2 commit)

---

**Total deviations:** 1 auto-fixed (code style / null-safety)
**Impact on plan:** Minimal — no behavior change since `dupes` is always non-empty. Required by project code style.

## Issues Encountered
None

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Plan 15-02 can now wire SimilarAlbumsProvider into the Builder and add unit tests
- All acceptance criteria met: SIMILAR_ALBUMS type, SimilarAlbum/SimilarAlbums data model, DeezerMapper.toSimilarAlbum, SimilarAlbumsProvider with era scoring all compile cleanly

---
*Phase: 15-similar-albums*
*Completed: 2026-03-22*
