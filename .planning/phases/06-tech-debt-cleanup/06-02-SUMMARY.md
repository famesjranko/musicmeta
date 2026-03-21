---
phase: 06-tech-debt-cleanup
plan: 02
subsystem: http
tags: [kotlin, httpclient, httpresult, errorkind, coverartarchive, lrclib, musicbrainz]

# Dependency graph
requires:
  - phase: 06-tech-debt-cleanup/06-01
    provides: fetchJsonArrayResult, postJsonResult, postJsonArrayResult on HttpClient; FakeHttpClient.givenIoException()
provides:
  - CoverArtArchiveApi.getArtworkMetadata migrated from fetchJson to fetchJsonResult
  - LrcLibApi.getLyrics migrated from fetchJson to fetchJsonResult
  - LrcLibApi.searchLyrics migrated from fetchJsonArray to fetchJsonArrayResult
  - MusicBrainzApi all 7 fetchJson call sites migrated to fetchJsonResult
  - CoverArtArchiveProvider, LrcLibProvider, MusicBrainzProvider emit ErrorKind.NETWORK/PARSE on errors via mapError()
  - Error-path tests for all 3 providers using givenIoException()
affects: [06-03, all plans depending on MusicBrainz identity resolution]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "HttpResult migration: fetchJsonResult inside rateLimiter.execute block; when(val r = ...) { is Ok -> r.body; else -> return@execute null }"
    - "mapError() helper: IOException->NETWORK, JSONException->PARSE, else->UNKNOWN; same pattern as Plan 01"

key-files:
  created: []
  modified:
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/coverartarchive/CoverArtArchiveApi.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/coverartarchive/CoverArtArchiveProvider.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/lrclib/LrcLibApi.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/lrclib/LrcLibProvider.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/musicbrainz/MusicBrainzApi.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/musicbrainz/MusicBrainzProvider.kt
    - musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/provider/coverartarchive/CoverArtArchiveProviderTest.kt
    - musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/provider/lrclib/LrcLibProviderTest.kt
    - musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/provider/musicbrainz/MusicBrainzProviderTest.kt

key-decisions:
  - "CoverArtArchiveApi fetchRedirectUrl calls left as-is — redirect URL pattern is fundamentally different from JSON fetches and has no HttpResult equivalent"
  - "MusicBrainzApi uses when(val r = httpClient.fetchJsonResult(...)) inside rateLimiter.execute { } block with return@execute null for non-Ok results, preserving the existing nullable return contract"

patterns-established:
  - "HttpResult inside rateLimiter.execute: use return@execute null/emptyList() for non-Ok branches so outer ?: return still works"
  - "mapError() private helper consistent across all providers: IOException->NETWORK, JSONException->PARSE, else->UNKNOWN"

requirements-completed: [DEBT-01, DEBT-02]

# Metrics
duration: 15min
completed: 2026-03-21
---

# Phase 6 Plan 02: CoverArtArchive, LrcLib, MusicBrainz HttpResult Migration Summary

**CoverArtArchive (1 site), LrcLib (2 sites), and MusicBrainz (7 sites) Api classes migrated from nullable fetchJson/fetchJsonArray to fetchJsonResult/fetchJsonArrayResult; all 3 Providers now map errors to ErrorKind via mapError() helper**

## Performance

- **Duration:** ~15 min
- **Started:** 2026-03-21T16:06:00Z
- **Completed:** 2026-03-21T16:13:18Z
- **Tasks:** 2
- **Files modified:** 9

## Accomplishments
- Migrated CoverArtArchiveApi.getArtworkMetadata (1 fetchJson call) to fetchJsonResult; fetchRedirectUrl calls left as-is per plan
- Migrated LrcLibApi: getLyrics (fetchJson -> fetchJsonResult) and searchLyrics (fetchJsonArray -> fetchJsonArrayResult)
- Migrated MusicBrainzApi: all 7 fetchJson call sites (searchReleases, searchArtists, searchRecordings, lookupRelease, lookupArtist, lookupArtistWithRels, browseReleaseGroups) to fetchJsonResult
- Added mapError() to CoverArtArchiveProvider, LrcLibProvider, MusicBrainzProvider with IOException->NETWORK, JSONException->PARSE, else->UNKNOWN
- Added error-path tests to all 3 Provider test files using givenIoException()
- Combined with Plan 01: 7 of 11 providers fully migrated (Wikidata, Wikipedia, FanartTv, iTunes, CoverArtArchive, LrcLib, MusicBrainz)

## Task Commits

Each task was committed atomically:

1. **Task 1: Migrate CoverArtArchive and LrcLib to HttpResult/ErrorKind** - `6ba7cd1` (feat)
2. **Task 2: Migrate MusicBrainz to HttpResult/ErrorKind (7 call sites)** - `a84aba7` (feat)

## Files Created/Modified
- `provider/coverartarchive/CoverArtArchiveApi.kt` - fetchJson -> fetchJsonResult in getArtworkMetadata
- `provider/coverartarchive/CoverArtArchiveProvider.kt` - added mapError() helper with ErrorKind mapping
- `provider/lrclib/LrcLibApi.kt` - fetchJson -> fetchJsonResult (getLyrics), fetchJsonArray -> fetchJsonArrayResult (searchLyrics)
- `provider/lrclib/LrcLibProvider.kt` - added mapError() helper with ErrorKind mapping
- `provider/musicbrainz/MusicBrainzApi.kt` - all 7 fetchJson calls migrated to fetchJsonResult
- `provider/musicbrainz/MusicBrainzProvider.kt` - added mapError() helper with ErrorKind mapping
- `provider/coverartarchive/CoverArtArchiveProviderTest.kt` - added NETWORK ErrorKind error-path test
- `provider/lrclib/LrcLibProviderTest.kt` - added NETWORK ErrorKind error-path test
- `provider/musicbrainz/MusicBrainzProviderTest.kt` - added NETWORK ErrorKind error-path test

## Decisions Made
- CoverArtArchiveApi `fetchRedirectUrl` calls left unchanged. The redirect pattern checks if artwork exists by following a 307 redirect — this is semantically different from JSON fetches and there is no HttpResult-returning redirect variant.
- MusicBrainz rateLimiter.execute block pattern: using `return@execute null` in the else branch preserves the existing `?: return emptyList()/null` outer behavior. The rateLimiter returns the last expression, so the nullable `JSONObject?` contract is preserved throughout.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- 7 of 11 providers fully migrated to HttpResult + ErrorKind
- Remaining 4 providers (Last.fm, Deezer, Discogs, ListenBrainz) ready for Plan 06-03
- MusicBrainz identity resolution provider validated at scale (7 call sites migrated successfully)
- mapError() pattern consistent across all 7 migrated providers

---
*Phase: 06-tech-debt-cleanup*
*Completed: 2026-03-21*
