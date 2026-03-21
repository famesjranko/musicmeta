# Requirements: musicmeta

**Defined:** 2026-03-21
**Core Value:** Consumers get comprehensive music metadata from a single enrich() call without knowing provider details.

## v0.4.0 Requirements

Requirements for the Provider Abstraction Overhaul milestone. Each maps to roadmap phases.

### Bug Fixes

- [x] **BUG-01**: MusicBrainz empty results return NotFound instead of RateLimited
- [x] **BUG-02**: Last.fm API uses HTTPS instead of HTTP
- [x] **BUG-03**: Last.fm TRACK_POPULARITY removed from capabilities until properly wired
- [x] **BUG-04**: LRCLIB duration passed as float (no precision loss)
- [x] **BUG-05**: Wikidata filters claims by rank (preferred > normal) instead of taking [0]

### Provider Abstraction

- [x] **ABST-01**: ProviderCapability uses typed IdentifierRequirement enum instead of boolean
- [x] **ABST-02**: Identity resolution formalized as provider role (isIdentityProvider, resolveIdentity())
- [x] **ABST-03**: IdentifierResolution removed from public EnrichmentData sealed class
- [x] **ABST-04**: Provider mapper pattern extracted (11 *Mapper.kt files)
- [x] **ABST-05**: Centralized ApiKeyConfig with EnrichmentEngine.Builder integration
- [x] **ABST-06**: needsIdentityResolution() derived from provider capabilities (not hardcoded)

### Public API

- [x] **API-01**: TTL moved into EnrichmentType enum with config override
- [x] **API-02**: EnrichmentIdentifiers gains extensible extra map with get()/withExtra()
- [x] **API-03**: SimilarArtist and PopularTrack use EnrichmentIdentifiers instead of musicBrainzId
- [x] **API-04**: ErrorKind enum added to EnrichmentResult.Error
- [x] **API-05**: HttpResult sealed class added with fetchJsonResult() on HttpClient

### New Types

- [ ] **TYPE-01**: BAND_MEMBERS type with MusicBrainz and Discogs providers
- [ ] **TYPE-02**: ARTIST_DISCOGRAPHY type with MusicBrainz and Deezer providers
- [ ] **TYPE-03**: ALBUM_TRACKS type with MusicBrainz and Deezer providers
- [ ] **TYPE-04**: SIMILAR_TRACKS type with Last.fm and Deezer providers
- [ ] **TYPE-05**: ARTIST_BANNER type with Fanart.tv provider
- [ ] **TYPE-06**: ARTIST_LINKS type with MusicBrainz provider
- [ ] **TYPE-07**: Artwork sizes enhancement (sizes list on Artwork, all 4 artwork providers updated)
- [ ] **TYPE-08**: Wikidata expanded properties (P569, P570, P495, P106, P373)

### Deepening

- [ ] **DEEP-01**: ALBUM_ART_BACK and ALBUM_BOOKLET types via Cover Art Archive JSON endpoint
- [ ] **DEEP-02**: Album metadata from Deezer, iTunes, Discogs (currently ignored fields)
- [ ] **DEEP-03**: Track-level popularity fix via Last.fm track.getInfo and ListenBrainz batch
- [ ] **DEEP-04**: Wikipedia page media for supplemental ARTIST_PHOTO coverage
- [ ] **DEEP-05**: ListenBrainz batch endpoints (popularity/artist, top-release-groups, sitewide/artists)
- [ ] **DEEP-06**: Confidence scoring standardization via ConfidenceCalculator utility

## Future Requirements

Deferred beyond v0.4.0.

### New Capabilities

- **CAP-01**: CREDITS type (producers, performers, composers) via MusicBrainz/Discogs
- **CAP-02**: RELEASE_EDITIONS type (original, deluxe, remaster, vinyl) via MusicBrainz
- **CAP-03**: Artist timeline (formed, albums, member changes, hiatus, reunion)
- **CAP-04**: Genre deep dive (merged/deduplicated tags with confidence, top artists per genre)

## Out of Scope

| Feature | Reason |
|---------|--------|
| New providers (Spotify, Apple Music) | Focus on extracting more from existing 11 providers |
| Android module changes | Core-only milestone; Android module unchanged |
| Deprecation annotations | No external consumers; clean breaks preferred |
| Wikipedia structured HTML parsing | High complexity, low ROI vs Wikidata properties |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| BUG-01 | Phase 1 | Complete |
| BUG-02 | Phase 1 | Complete |
| BUG-03 | Phase 1 | Complete |
| BUG-04 | Phase 1 | Complete |
| BUG-05 | Phase 1 | Complete |
| ABST-01 | Phase 2 | Complete |
| ABST-02 | Phase 2 | Complete |
| ABST-03 | Phase 2 | Complete |
| ABST-04 | Phase 2 | Complete |
| ABST-05 | Phase 2 | Complete |
| ABST-06 | Phase 2 | Complete |
| API-01 | Phase 3 | Complete |
| API-02 | Phase 3 | Complete |
| API-03 | Phase 3 | Complete |
| API-04 | Phase 3 | Complete |
| API-05 | Phase 3 | Complete |
| TYPE-01 | Phase 4 | Pending |
| TYPE-02 | Phase 4 | Pending |
| TYPE-03 | Phase 4 | Pending |
| TYPE-04 | Phase 4 | Pending |
| TYPE-05 | Phase 4 | Pending |
| TYPE-06 | Phase 4 | Pending |
| TYPE-07 | Phase 4 | Pending |
| TYPE-08 | Phase 4 | Pending |
| DEEP-01 | Phase 5 | Pending |
| DEEP-02 | Phase 5 | Pending |
| DEEP-03 | Phase 5 | Pending |
| DEEP-04 | Phase 5 | Pending |
| DEEP-05 | Phase 5 | Pending |
| DEEP-06 | Phase 5 | Pending |

**Coverage:**
- v0.4.0 requirements: 30 total (note: REQUIREMENTS.md header previously said 25; actual count is 30)
- Mapped to phases: 30
- Unmapped: 0

---
*Requirements defined: 2026-03-21*
*Last updated: 2026-03-21 after roadmap creation*
