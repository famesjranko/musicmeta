---
phase: 04-new-types
plan: 04
subsystem: providers
tags: [artwork-sizes, wikidata, discogs, band-members, cover-art-archive, deezer, itunes, fanarttv]

requires:
  - phase: 04-01
    provides: EnrichmentData types (ArtworkSize, BandMembers, Artwork.sizes)
  - phase: 04-03
    provides: Fanart.tv ARTIST_BANNER capability and Deezer discography/tracklist methods

provides:
  - Artwork sizes populated by all 4 artwork providers (CAA, Deezer, iTunes, Fanart.tv)
  - WikidataProvider COUNTRY capability with expanded properties (birth/death/country/occupation)
  - DiscogsProvider BAND_MEMBERS capability via artist search-then-fetch pattern

affects: [05-deepen-coverage]

tech-stack:
  added: []
  patterns:
    - "CAA JSON metadata endpoint for thumbnail sizes alongside redirect URL"
    - "Wikidata multi-property fetch (P18|P569|P570|P495|P106) in single API call"
    - "Discogs artist search-then-fetch for band members"
    - "FanartTvImage rich model replacing plain URL strings"

key-files:
  created: []
  modified:
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/coverartarchive/CoverArtArchiveApi.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/coverartarchive/CoverArtArchiveMapper.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/coverartarchive/CoverArtArchiveProvider.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/deezer/DeezerMapper.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/itunes/ITunesMapper.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/fanarttv/FanartTvModels.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/fanarttv/FanartTvApi.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/fanarttv/FanartTvMapper.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/fanarttv/FanartTvProvider.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/wikidata/WikidataApi.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/wikidata/WikidataMapper.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/wikidata/WikidataProvider.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/discogs/DiscogsApi.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/discogs/DiscogsModels.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/discogs/DiscogsMapper.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/discogs/DiscogsProvider.kt

key-decisions:
  - "CAA JSON metadata fetched alongside redirect URL for sizes; graceful degradation if metadata unavailable"
  - "Wikidata properties fetched in single API call (P18|P569|P570|P495|P106) replacing separate getArtistImageUrl"
  - "Wikidata QID-to-name maps for countries and occupations with QID fallback for unmapped values"
  - "FanartTvImage data class replaces plain URL strings for richer image metadata"
  - "Discogs band members uses search-then-fetch pattern: searchArtist for ID, getArtist for members"

patterns-established:
  - "Multi-property Wikidata fetch: single API call for multiple property IDs"
  - "Artwork sizes: each provider populates ArtworkSize list with provider-specific resolution info"

requirements-completed: [TYPE-01, TYPE-07, TYPE-08]

duration: 8min
completed: 2026-03-21
---

# Phase 04 Plan 04: Artwork Sizes and Provider Enhancements Summary

**All 4 artwork providers emit sizes, Wikidata returns expanded properties (birth/death/country/occupation), Discogs returns band members via artist endpoint**

## Performance

- **Duration:** 8 min
- **Started:** 2026-03-21T09:21:14Z
- **Completed:** 2026-03-21T09:29:49Z
- **Tasks:** 2
- **Files modified:** 20

## Accomplishments
- All 4 artwork providers (CAA, Deezer, iTunes, Fanart.tv) now populate ArtworkSize lists on Artwork results
- WikidataProvider expanded to fetch birth/death dates, country, and occupation via single multi-property API call
- DiscogsProvider gained BAND_MEMBERS capability using artist search-then-fetch pattern
- FanartTvImage rich model replaces plain URL strings for better image metadata

## Task Commits

Each task was committed atomically:

1. **Task 1: Artwork sizes for Cover Art Archive, Deezer, iTunes, and Fanart.tv** - `b7530dd` (feat)
2. **Task 2: Wikidata expanded properties and Discogs band members** - `86a8914` (feat)

## Files Created/Modified
- `CoverArtArchiveApi.kt` - Added getArtworkMetadata() JSON endpoint with CoverArtArchiveImage/ImageList models
- `CoverArtArchiveMapper.kt` - Updated toArtwork() to accept optional CoverArtArchiveImage for sizes
- `CoverArtArchiveProvider.kt` - Added fetchFrontImage() to get thumbnail sizes from metadata
- `DeezerMapper.kt` - toArtwork() now populates 4 sizes (small/medium/big/xl with dimensions)
- `ITunesMapper.kt` - toArtwork() generates 4 sizes (250/500/1000/3000px) from URL template
- `FanartTvModels.kt` - FanartTvImage data class with url/id/likes replaces List<String>
- `FanartTvApi.kt` - extractImages() replaces extractUrls(), parses id and likes per image
- `FanartTvMapper.kt` - toArtwork() accepts FanartTvImage and all-images list for sizes
- `FanartTvProvider.kt` - enrichFromImages() works with FanartTvImage lists
- `WikidataApi.kt` - getEntityProperties() fetches P18/P569/P570/P495/P106 in one call with QID maps
- `WikidataMapper.kt` - Added toMetadata() mapping WikidataEntityProperties to Metadata
- `WikidataProvider.kt` - Added COUNTRY capability (priority 50) with routing in enrich()
- `DiscogsApi.kt` - Added searchArtist() and getArtist() endpoints
- `DiscogsModels.kt` - Added DiscogsArtist and DiscogsMember data classes
- `DiscogsMapper.kt` - Added toBandMembers() mapping to EnrichmentData.BandMembers
- `DiscogsProvider.kt` - Added BAND_MEMBERS capability (priority 50) with enrichBandMembers()
- `CoverArtArchiveProviderTest.kt` - Added test for artwork with sizes from metadata endpoint
- `FanartTvProviderTest.kt` - Added tests for multi-image sizes and single-image no-sizes
- `WikidataProviderTest.kt` - Added tests for Metadata with dates/country and NotFound for empty claims
- `DiscogsProviderTest.kt` - Added tests for BandMembers success, no members, and search failure

## Decisions Made
- CAA JSON metadata fetched alongside redirect URL for sizes; returns artwork without sizes if metadata fetch fails (graceful degradation)
- Wikidata properties fetched in single API call replacing separate getArtistImageUrl (delegated to getEntityProperties)
- QID-to-name maps for 14 countries and 5 occupations with QID string fallback for unmapped values
- FanartTvImage replaces plain URL strings, carrying id and likes metadata
- Discogs band members use search-then-fetch: searchArtist to find ID, getArtist for member list

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- Phase 04 (New Types) is now complete with all 4 plans executed
- Ready for Phase 05 (Deepen Coverage) which builds on the new types and capabilities

## Self-Check: PASSED

All key files verified present. Both task commits (b7530dd, 86a8914) verified in git log.

---
*Phase: 04-new-types*
*Completed: 2026-03-21*
