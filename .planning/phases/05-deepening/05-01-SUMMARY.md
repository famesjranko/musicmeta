---
phase: 05-deepening
plan: 01
subsystem: provider
tags: [cover-art-archive, artwork, enrichment-types, caa-metadata]

# Dependency graph
requires:
  - phase: 04-new-types
    provides: "CAA JSON metadata endpoint, artwork sizes, CoverArtArchiveMapper"
provides:
  - "ALBUM_ART_BACK enrichment type with CAA provider support"
  - "ALBUM_BOOKLET enrichment type with CAA provider support"
  - "CoverArtArchiveImage.types field for image type filtering"
affects: [05-deepening]

# Tech tracking
tech-stack:
  added: []
  patterns: ["findImageByType pattern for filtering CAA images by types array"]

key-files:
  created:
    - "musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/provider/coverartarchive/CoverArtArchiveApiTest.kt"
  modified:
    - "musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/EnrichmentType.kt"
    - "musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/coverartarchive/CoverArtArchiveApi.kt"
    - "musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/coverartarchive/CoverArtArchiveProvider.kt"
    - "musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/provider/coverartarchive/CoverArtArchiveProviderTest.kt"
    - "musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/EnrichmentDataSerializationTest.kt"

key-decisions:
  - "Back/booklet use findImageByType filtering CAA JSON images array by types field, no release-group fallback (only release has typed images)"

patterns-established:
  - "findImageByType: reusable pattern for extracting specific image types from CAA metadata JSON"

requirements-completed: [DEEP-01]

# Metrics
duration: 4min
completed: 2026-03-21
---

# Phase 05 Plan 01: Back Cover and Booklet Art Summary

**ALBUM_ART_BACK and ALBUM_BOOKLET enrichment types backed by Cover Art Archive JSON metadata endpoint with image type filtering**

## Performance

- **Duration:** 4 min
- **Started:** 2026-03-21T09:47:20Z
- **Completed:** 2026-03-21T09:51:45Z
- **Tasks:** 2
- **Files modified:** 6

## Accomplishments
- Added ALBUM_ART_BACK and ALBUM_BOOKLET enum values with 90-day TTL to EnrichmentType
- Extended CoverArtArchiveImage model with types field and updated parseImageList to parse types JSON array
- Added findImageByType() method to CoverArtArchiveProvider for type-filtered image lookup
- Full TDD coverage: 4 API-level tests + 7 provider-level tests for back cover and booklet scenarios

## Task Commits

Each task was committed atomically:

1. **Task 1: Add ALBUM_ART_BACK/ALBUM_BOOKLET types, update CAA image model** - `e462fde` (feat)
2. **Task 2: CAA provider supports ALBUM_ART_BACK and ALBUM_BOOKLET capabilities** - `e3ba36b` (feat)

_Both tasks used TDD: RED (failing tests) -> GREEN (implementation) committed together._

## Files Created/Modified
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/EnrichmentType.kt` - Added ALBUM_ART_BACK and ALBUM_BOOKLET enum values
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/coverartarchive/CoverArtArchiveApi.kt` - Added types field to CoverArtArchiveImage, parsing types JSON array
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/coverartarchive/CoverArtArchiveMapper.kt` - No changes needed (already handles any image type)
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/coverartarchive/CoverArtArchiveProvider.kt` - Added capabilities, findImageByType(), when-dispatch in enrich()
- `musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/provider/coverartarchive/CoverArtArchiveApiTest.kt` - New test class for types parsing
- `musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/provider/coverartarchive/CoverArtArchiveProviderTest.kt` - Added 7 tests for back cover and booklet
- `musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/EnrichmentDataSerializationTest.kt` - Added TTL validation tests for new types

## Decisions Made
- Back/booklet lookups use only the release ID (not release-group fallback) because the CAA JSON metadata endpoint with typed images is only available for releases, not release groups
- findImageByType uses `firstOrNull { imageType in it.types }` for simple containment check on the types list
- Thumbnail URL for back/booklet extracted from image.thumbnails["small"] for consistency with CAA thumbnail naming

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Back cover and booklet art types are fully functional via the existing CAA JSON metadata endpoint
- The findImageByType pattern can be extended for future CAA image types if needed
- Ready for remaining Phase 05 plans (track popularity, batch endpoints, confidence standardization)

## Self-Check: PASSED

All files verified present, both commits exist, ALBUM_ART_BACK and ALBUM_BOOKLET confirmed in EnrichmentType.

---
*Phase: 05-deepening*
*Completed: 2026-03-21*
