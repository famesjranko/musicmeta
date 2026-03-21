---
phase: 01-bug-fixes
verified: 2026-03-21T08:00:00Z
status: passed
score: 5/5 must-haves verified
must_haves:
  truths:
    - "MusicBrainz empty search results return NotFound (not RateLimited); unit tests assert this"
    - "Last.fm API calls use HTTPS; no plain-HTTP requests leave the library"
    - "Last.fm does not advertise TRACK_POPULARITY in its capabilities; requesting it returns NotFound from that provider"
    - "LRCLIB duration values are passed as float with no precision loss; a track with a non-integer duration matches correctly"
    - "Wikidata claim resolution returns the preferred-rank claim when one exists, not always the first in the array"
  artifacts:
    - path: "musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/musicbrainz/MusicBrainzProvider.kt"
      provides: "Corrected empty-result handling (NotFound, not RateLimited)"
    - path: "musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/lastfm/LastFmApi.kt"
      provides: "HTTPS base URL"
    - path: "musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/lastfm/LastFmProvider.kt"
      provides: "No TRACK_POPULARITY capability, 4 capabilities total"
    - path: "musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/lrclib/LrcLibApi.kt"
      provides: "Float duration parameter (Double? not Int?)"
    - path: "musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/lrclib/LrcLibProvider.kt"
      provides: "Float division for duration (it / 1000.0)"
    - path: "musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/wikidata/WikidataApi.kt"
      provides: "Rank-aware claim selection (preferred > first)"
  key_links:
    - from: "MusicBrainzProvider.enrichAlbum"
      to: "EnrichmentResult.NotFound"
      via: "empty search results"
    - from: "LastFmApi.buildUrl"
      to: "BASE_URL"
      via: "string interpolation"
    - from: "LrcLibProvider.enrichTrack"
      to: "LrcLibApi.getLyrics"
      via: "durationSec parameter"
    - from: "WikidataApi.extractImageFilename"
      to: "P18 claim array"
      via: "rank filtering"
---

# Phase 1: Bug Fixes Verification Report

**Phase Goal:** All known provider bugs are corrected and the test suite passes cleanly before structural changes touch the same files
**Verified:** 2026-03-21T08:00:00Z
**Status:** passed
**Re-verification:** No -- initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | MusicBrainz empty search results return NotFound (not RateLimited); unit tests assert this | VERIFIED | MusicBrainzProvider.kt has zero occurrences of `EnrichmentResult.RateLimited`; lines 129, 170, 200 all return `EnrichmentResult.NotFound(type, id)`; 3 new tests (`empty album search results return NotFound`, `empty artist search results return NotFound`, `empty recording search results return NotFound`) all pass |
| 2 | Last.fm API calls use HTTPS; no plain-HTTP requests leave the library | VERIFIED | LastFmApi.kt line 80: `const val BASE_URL = "https://ws.audioscrobbler.com/2.0/"`; zero occurrences of `http://ws.audioscrobbler`; test `API calls use HTTPS` asserts all requested URLs start with `https://` |
| 3 | Last.fm does not advertise TRACK_POPULARITY in its capabilities; requesting it returns NotFound | VERIFIED | LastFmProvider.kt capabilities list has exactly 4 entries (SIMILAR_ARTISTS, GENRE, ARTIST_BIO, ARTIST_POPULARITY) -- no TRACK_POPULARITY; when-block has no TRACK_POPULARITY branch (falls through to `else -> NotFound`); tests `capabilities do not include TRACK_POPULARITY` and `enrich returns NotFound for TRACK_POPULARITY` both pass |
| 4 | LRCLIB duration values are passed as float with no precision loss | VERIFIED | LrcLibApi.kt line 25: `durationSec: Double? = null` (not Int?); LrcLibProvider.kt line 61: `it / 1000.0` (not `(it / 1000).toInt()`); tests `duration is passed as float preserving fractional seconds` (238500ms -> 238.5) and `duration with exact milliseconds is passed as float` (180000ms -> 180.0) both pass |
| 5 | Wikidata claim resolution returns the preferred-rank claim when one exists | VERIFIED | WikidataApi.kt lines 33-36: scans for `"preferred"` rank, falls back to `p18.getJSONObject(0)`; tests `preferred rank claim is selected over normal rank`, `normal rank claim is used when no preferred rank exists`, and `single claim without rank field still works` all pass |

**Score:** 5/5 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `musicmeta-core/src/main/kotlin/.../musicbrainz/MusicBrainzProvider.kt` | Corrected empty-result handling | VERIFIED | 0 occurrences of RateLimited; 8 occurrences of NotFound across all branches |
| `musicmeta-core/src/test/kotlin/.../musicbrainz/MusicBrainzProviderTest.kt` | 3 new empty-result tests | VERIFIED | Tests at lines 103, 116, 129; existing test renamed to `enrich returns NotFound on null response` at line 91 |
| `musicmeta-core/src/main/kotlin/.../lastfm/LastFmApi.kt` | HTTPS base URL | VERIFIED | Line 80: `https://ws.audioscrobbler.com/2.0/` |
| `musicmeta-core/src/main/kotlin/.../lastfm/LastFmProvider.kt` | No TRACK_POPULARITY capability | VERIFIED | 4 capabilities only; no TRACK_POPULARITY in capabilities or when-block |
| `musicmeta-core/src/test/kotlin/.../lastfm/LastFmProviderTest.kt` | 3 new HTTPS/TRACK_POPULARITY tests | VERIFIED | Tests at lines 191, 205, 215 |
| `musicmeta-core/src/main/kotlin/.../lrclib/LrcLibApi.kt` | Float duration parameter | VERIFIED | Line 25: `durationSec: Double? = null` |
| `musicmeta-core/src/main/kotlin/.../lrclib/LrcLibProvider.kt` | Float division | VERIFIED | Line 61: `it / 1000.0` |
| `musicmeta-core/src/test/kotlin/.../lrclib/LrcLibProviderTest.kt` | 2 new duration tests + 1 updated assertion | VERIFIED | Tests at lines 150, 169; existing test assertion updated to `duration=238.0` at line 146 |
| `musicmeta-core/src/main/kotlin/.../wikidata/WikidataApi.kt` | Rank-aware claim selection | VERIFIED | Lines 33-36: preferred rank scan with first-claim fallback |
| `musicmeta-core/src/test/kotlin/.../wikidata/WikidataProviderTest.kt` | 3 new rank tests | VERIFIED | Tests at lines 116, 144, 172 |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| MusicBrainzProvider.enrichAlbum (line 129) | EnrichmentResult.NotFound | empty releases check | WIRED | `if (releases.isEmpty()) return EnrichmentResult.NotFound(type, id)` |
| MusicBrainzProvider.enrichArtist (line 170) | EnrichmentResult.NotFound | empty artists check | WIRED | Same pattern |
| MusicBrainzProvider.enrichTrack (line 200) | EnrichmentResult.NotFound | empty recordings check | WIRED | Same pattern |
| LastFmApi.buildUrl (line 41) | BASE_URL (line 80) | string interpolation | WIRED | `"$BASE_URL?method=..."` uses `https://ws.audioscrobbler.com/2.0/` |
| LrcLibProvider.enrichTrack (line 61) | LrcLibApi.getLyrics (line 25) | durationSec parameter | WIRED | Provider: `it / 1000.0` produces Double; API: `durationSec: Double?` accepts it |
| WikidataApi.extractImageFilename (lines 33-36) | P18 claim array | rank filtering | WIRED | Scans for "preferred" rank, falls back to index 0 |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| BUG-01 | 01-01-PLAN | MusicBrainz empty results return NotFound instead of RateLimited | SATISFIED | Truth #1 verified; zero RateLimited in MusicBrainzProvider.kt; 3 new tests pass |
| BUG-02 | 01-01-PLAN | Last.fm API uses HTTPS instead of HTTP | SATISFIED | Truth #2 verified; BASE_URL is https://; test asserts HTTPS on all URLs |
| BUG-03 | 01-01-PLAN | Last.fm TRACK_POPULARITY removed from capabilities | SATISFIED | Truth #3 verified; 4 capabilities (no TRACK_POPULARITY); falls through to NotFound |
| BUG-04 | 01-02-PLAN | LRCLIB duration passed as float (no precision loss) | SATISFIED | Truth #4 verified; Double parameter, float division, 2 new tests |
| BUG-05 | 01-02-PLAN | Wikidata filters claims by rank (preferred > normal) | SATISFIED | Truth #5 verified; preferred rank scan with fallback, 3 new tests |

No orphaned requirements found. REQUIREMENTS.md maps BUG-01 through BUG-05 to Phase 1, and all five are claimed by plans 01-01 and 01-02.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|

No anti-patterns found. All 10 modified files scanned for TODO/FIXME/HACK/PLACEHOLDER markers, empty implementations, and stub patterns. Zero matches.

### Human Verification Required

No human verification needed. All five bugs are correctness fixes that are fully verifiable through automated checks (grep for banned patterns, test assertions). No visual, UX, or external-service behavior is involved.

### Gaps Summary

No gaps found. All 5 observable truths verified, all 10 artifacts pass three-level checks (exists, substantive, wired), all 6 key links confirmed wired, all 5 requirements satisfied, and all 8 commit hashes validated. The test suite passes cleanly across all four provider test classes.

---

_Verified: 2026-03-21T08:00:00Z_
_Verifier: Claude (gsd-verifier)_
