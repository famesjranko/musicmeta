---
phase: 08-release-editions
verified: 2026-03-22T00:00:00Z
status: passed
score: 11/11 must-haves verified
re_verification: false
---

# Phase 08: Release Editions Verification Report

**Phase Goal:** Consumers can list all known editions and pressings of an album via a single RELEASE_EDITIONS enrichment type backed by MusicBrainz and Discogs
**Verified:** 2026-03-22
**Status:** passed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | RELEASE_EDITIONS enum value exists with 1-year TTL | VERIFIED | `EnrichmentType.kt` line 44: `RELEASE_EDITIONS(365L * 24 * 60 * 60 * 1000)` |
| 2 | ReleaseEditions and ReleaseEdition are @Serializable data classes in EnrichmentData.kt | VERIFIED | `EnrichmentData.kt` lines 83-84 (inner class) and lines 160-169 (top-level); both annotated `@Serializable` with all 8 required fields |
| 3 | MusicBrainz lookupReleaseGroup fetches releases from release-group endpoint | VERIFIED | `MusicBrainzApi.kt` lines 97-107: fetches `$BASE_URL/release-group/$releaseGroupMbid?fmt=json&inc=releases+labels+media` |
| 4 | Parser extracts title, format, country, year, label, catalogNumber, barcode from release-group releases | VERIFIED | `MusicBrainzParser.kt` lines 153-181: `parseReleaseGroupDetail` extracts all 7 fields; format from `media[0].format`, label from `label-info[0].label.name`, catalogNumber from `label-info[0].catalog-number` |
| 5 | MusicBrainz RELEASE_EDITIONS capability wired at priority 100 with MUSICBRAINZ_RELEASE_GROUP_ID requirement | VERIFIED | `MusicBrainzProvider.kt` lines 51-54: `ProviderCapability(EnrichmentType.RELEASE_EDITIONS, priority = 100, identifierRequirement = IdentifierRequirement.MUSICBRAINZ_RELEASE_GROUP_ID)` |
| 6 | enrichAlbumEditions returns Success with non-empty editions list when release-group has releases | VERIFIED | `MusicBrainzProvider.kt` lines 150-164: reads MBID, calls `api.lookupReleaseGroup`, parses, maps, returns `EnrichmentResult.Success` with `ConfidenceCalculator.idBasedLookup()` (1.0f) |
| 7 | Discogs RELEASE_EDITIONS capability exists at priority 50 | VERIFIED | `DiscogsProvider.kt` line 44: `ProviderCapability(EnrichmentType.RELEASE_EDITIONS, priority = 50)` |
| 8 | getMasterVersions fetches versions from Discogs master endpoint | VERIFIED | `DiscogsApi.kt` lines 65-74: fetches `$MASTERS_URL/$masterId/versions?per_page=100&token=...`; MASTERS_URL defined at line 193 |
| 9 | Discogs editions include title, format, country, year, label, catalogNumber from master versions | VERIFIED | `DiscogsMapper.kt` lines 100-116: `toReleaseEditions` maps all 6 fields from `DiscogsMasterVersion`; barcode=null (intentional — Discogs versions API has no barcode field) |
| 10 | enrichAlbumEditions reads discogsMasterId from identifiers.extra and fetches master versions | VERIFIED | `DiscogsProvider.kt` lines 125-141: reads `discogsMasterId` via `identifiers.get("discogsMasterId")`, parses to Long, calls `api.getMasterVersions` |
| 11 | enrichAlbumEditions returns NotFound when discogsMasterId is absent | VERIFIED | `DiscogsProvider.kt` line 130: `?: return EnrichmentResult.NotFound(type, id)` |

**Score:** 11/11 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/EnrichmentType.kt` | RELEASE_EDITIONS enum value | VERIFIED | Line 44: value present with 365-day TTL |
| `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/EnrichmentData.kt` | ReleaseEditions and ReleaseEdition data classes | VERIFIED | Both `@Serializable`; ReleaseEdition has all 8 fields (title, format, country, year, label, catalogNumber, barcode, identifiers) |
| `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/musicbrainz/MusicBrainzApi.kt` | lookupReleaseGroup API method | VERIFIED | Lines 97-107; returns raw `JSONObject?` following same pattern as `lookupRecording` |
| `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/musicbrainz/MusicBrainzParser.kt` | parseReleaseGroupDetail parser | VERIFIED | Lines 153-181; extracts all edition fields including format from `media[0]` and label from `label-info[0]` |
| `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/musicbrainz/MusicBrainzMapper.kt` | toReleaseEditions mapper | VERIFIED | Lines 104-118; maps `MusicBrainzReleaseGroupDetail` to `EnrichmentData.ReleaseEditions`; year extracted as `date?.take(4)?.toIntOrNull()` |
| `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/musicbrainz/MusicBrainzProvider.kt` | RELEASE_EDITIONS capability and enrichAlbumEditions | VERIFIED | Capability at lines 51-54; `enrichAlbumEditions` at lines 150-164; dispatch guard at line 120 |
| `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/musicbrainz/MusicBrainzModels.kt` | MusicBrainzReleaseGroupDetail and MusicBrainzEdition DTOs | VERIFIED | Lines 81-96: both data classes with all required fields |
| `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/discogs/DiscogsModels.kt` | DiscogsMasterVersion data class | VERIFIED | Lines 48-56: 7 fields (id, title, format, label, country, year, catno) |
| `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/discogs/DiscogsApi.kt` | getMasterVersions API method and MASTERS_URL | VERIFIED | Lines 65-74 (method) and line 193 (constant); `parseMasterVersions` at lines 143-157 |
| `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/discogs/DiscogsMapper.kt` | toReleaseEditions mapper | VERIFIED | Lines 100-116; stores `discogsReleaseId` in `identifiers.extra` when `version.id > 0` |
| `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/discogs/DiscogsProvider.kt` | RELEASE_EDITIONS capability and enrichAlbumEditions | VERIFIED | Capability at line 44; dispatch guard at lines 69-77; `enrichAlbumEditions` at lines 125-141 |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| MusicBrainzProvider.kt | MusicBrainzApi.lookupReleaseGroup | `api.lookupReleaseGroup` in `enrichAlbumEditions` | WIRED | Line 154: `val json = api.lookupReleaseGroup(releaseGroupMbid)` |
| MusicBrainzProvider.kt | MusicBrainzParser.parseReleaseGroupDetail | `parseReleaseGroupDetail(json)` | WIRED | Line 156: `val detail = MusicBrainzParser.parseReleaseGroupDetail(json)` |
| MusicBrainzProvider.kt | MusicBrainzMapper.toReleaseEditions | `toReleaseEditions(detail)` | WIRED | Line 160: `data = MusicBrainzMapper.toReleaseEditions(detail)` |
| DiscogsProvider.kt | DiscogsApi.getMasterVersions | `api.getMasterVersions` in `enrichAlbumEditions` | WIRED | Line 133: `val versions = api.getMasterVersions(masterId)` |
| DiscogsProvider.kt | DiscogsMapper.toReleaseEditions | `DiscogsMapper.toReleaseEditions(versions)` | WIRED | Line 137: `data = DiscogsMapper.toReleaseEditions(versions)` |
| DiscogsProvider.enrichAlbumEditions | identifiers.extra discogsMasterId | `identifiers.get("discogsMasterId")` | WIRED | Line 129: reads from `identifiers.extra` stored by Phase 6 DEBT-04 |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|-------------|--------|----------|
| EDIT-01 | 08-01-PLAN.md | User can list all editions/pressings of an album via RELEASE_EDITIONS type | SATISFIED | `EnrichmentType.RELEASE_EDITIONS` exists; `EnrichmentData.ReleaseEditions` and `ReleaseEdition` data classes provide the consumer-facing contract |
| EDIT-02 | 08-01-PLAN.md | MusicBrainz provides editions from release-group releases at priority 100 | SATISFIED | `MusicBrainzProvider` capability at priority 100 with `MUSICBRAINZ_RELEASE_GROUP_ID` requirement; full pipeline from API to mapper to provider verified |
| EDIT-03 | 08-02-PLAN.md | Discogs provides editions from master versions at priority 50 | SATISFIED | `DiscogsProvider` capability at priority 50; reads `discogsMasterId` from `identifiers.extra`; full pipeline from API to mapper to provider verified |

All 3 requirement IDs (EDIT-01, EDIT-02, EDIT-03) appear in plan frontmatter and are accounted for. No orphaned requirements — REQUIREMENTS.md traceability table maps all three to Phase 8 and marks them complete.

### Anti-Patterns Found

None. No TODO/FIXME/placeholder/stub comments found in any modified files. All data fields flow from real API responses.

### Human Verification Required

None. All key behaviors are verifiable programmatically:
- Type and data class definitions are static and complete
- API endpoints are hardcoded strings in the implementation
- Capability priorities and identifier requirements are explicit constants
- Key links are traceable via grep

### Test Coverage

| Test Class | Tests Added | All Pass |
|------------|-------------|----------|
| `MusicBrainzParserTest` | 5 (parseReleaseGroupDetail x3, toReleaseEditions, round-trip serialization) | Yes |
| `MusicBrainzProviderTest` | 5 (capability, success, NotFound x2, Error/NETWORK) | Yes |
| `DiscogsMapperTest` | 4 (toReleaseEditions x4) | Yes |
| `DiscogsProviderTest` | 6 (capability, success, NotFound x2, Error/NETWORK, non-ForAlbum) | Yes |

All 20 new tests pass. Full test suite (`./gradlew :musicmeta-core:test`) passes.

### Commits Verified

| Commit | Description |
|--------|-------------|
| `3003bf4` | feat(08-01): add RELEASE_EDITIONS type, data model, MusicBrainz API/Parser/Mapper |
| `06ffa87` | feat(08-01): wire MusicBrainz RELEASE_EDITIONS capability into Provider |
| `39448c2` | feat(08-02): Discogs master versions API, model, and toReleaseEditions mapper |
| `6bc38f8` | feat(08-02): wire Discogs RELEASE_EDITIONS capability at priority 50 |

All 4 commits exist in git log.

### EnrichmentShowcaseTest Update

The exhaustive `when` expression in `EnrichmentShowcaseTest.snippet()` was updated with `is EnrichmentData.ReleaseEditions -> "${data.editions.size} editions"` (line 354), preventing compile-time breakage from the new sealed subclass.

---

## Gaps Summary

No gaps. All must-haves from both plan frontmatters are fully implemented, wired, and tested. The phase goal is achieved: consumers can call `enrich(ForAlbum(...), EnrichmentType.RELEASE_EDITIONS)` and receive a `ReleaseEditions` list backed by MusicBrainz (priority 100, uses release-group MBID) or Discogs (priority 50, uses discogsMasterId from identifiers.extra).

---

_Verified: 2026-03-22_
_Verifier: Claude (gsd-verifier)_
