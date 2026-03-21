# Requirements: musicmeta

**Defined:** 2026-03-22
**Core Value:** Consumers get comprehensive, accurate music metadata from a single enrich() call without knowing which APIs exist, how they authenticate, or how to correlate identifiers across services.

## v0.5.0 Requirements

Requirements for v0.5.0 milestone. Each maps to roadmap phases.

### Tech Debt

- [x] **DEBT-01**: HttpResult migration across all 11 provider API classes (27 call sites)
- [x] **DEBT-02**: ErrorKind adoption with proper categorization across all 11 providers
- [x] **DEBT-03**: ListenBrainz ARTIST_DISCOGRAPHY capability wired from existing plumbing
- [x] **DEBT-04**: Discogs release ID and master ID stored from search results

### Credits

- [x] **CRED-01**: User can enrich track-level credits (performers, producers, composers, engineers) via CREDITS type
- [x] **CRED-02**: MusicBrainz provides recording credits from artist-rels and work-rels at priority 100
- [ ] **CRED-03**: Discogs provides release credits from extraartists at priority 50
- [x] **CRED-04**: Credits include roleCategory grouping (performance, production, songwriting)

### Editions

- [ ] **EDIT-01**: User can list all editions/pressings of an album via RELEASE_EDITIONS type
- [ ] **EDIT-02**: MusicBrainz provides editions from release-group releases at priority 100
- [ ] **EDIT-03**: Discogs provides editions from master versions at priority 50

### Timeline

- [ ] **TIME-01**: User can request a structured artist timeline via ARTIST_TIMELINE composite type
- [ ] **TIME-02**: Timeline synthesizes life-span, discography, and band member changes chronologically
- [ ] **TIME-03**: Engine supports composite enrichment types that depend on sub-type resolution

### Genre

- [ ] **GENR-01**: Genre results carry per-tag confidence scores via GenreTag data class
- [ ] **GENR-02**: GenreMerger normalizes, deduplicates, and scores tags from multiple providers
- [ ] **GENR-03**: ProviderChain supports mergeable types (collect all results instead of short-circuiting)
- [ ] **GENR-04**: Backward compatible — genres list still populated alongside genreTags

### Provider Coverage

- [ ] **PROV-01**: Last.fm album.getinfo provides ALBUM_METADATA at priority 40
- [ ] **PROV-02**: iTunes lookup endpoints provide ALBUM_TRACKS and ARTIST_DISCOGRAPHY at priority 30
- [ ] **PROV-03**: Fanart.tv album-specific endpoint for faster ALBUM_ART lookup
- [ ] **PROV-04**: ListenBrainz similar artists provides SIMILAR_ARTISTS at priority 50
- [ ] **PROV-05**: Discogs release details deepened with community ratings and collector signals

## Future Requirements

### Deferred

- **FUTURE-01**: Deezer SIMILAR_TRACKS implementation (descoped from v0.4.0)
- **FUTURE-02**: Album-level credits aggregation (ForAlbum request aggregating per-track credits)

## Out of Scope

| Feature | Reason |
|---------|--------|
| New providers (Spotify, Apple Music) | Focus on extracting more from existing 11 |
| Android module changes | Core-only focus |
| Wikipedia structured HTML parsing | High complexity, low ROI vs Wikidata |
| Real-time/streaming APIs | Not relevant to enrichment use case |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| DEBT-01 | Phase 6 | Complete |
| DEBT-02 | Phase 6 | Complete |
| DEBT-03 | Phase 6 | Complete |
| DEBT-04 | Phase 6 | Complete |
| CRED-01 | Phase 7 | Complete |
| CRED-02 | Phase 7 | Complete |
| CRED-03 | Phase 7 | Pending |
| CRED-04 | Phase 7 | Complete |
| EDIT-01 | Phase 8 | Pending |
| EDIT-02 | Phase 8 | Pending |
| EDIT-03 | Phase 8 | Pending |
| TIME-01 | Phase 9 | Pending |
| TIME-02 | Phase 9 | Pending |
| TIME-03 | Phase 9 | Pending |
| GENR-01 | Phase 10 | Pending |
| GENR-02 | Phase 10 | Pending |
| GENR-03 | Phase 10 | Pending |
| GENR-04 | Phase 10 | Pending |
| PROV-01 | Phase 11 | Pending |
| PROV-02 | Phase 11 | Pending |
| PROV-03 | Phase 11 | Pending |
| PROV-04 | Phase 11 | Pending |
| PROV-05 | Phase 11 | Pending |

**Coverage:**
- v0.5.0 requirements: 23 total
- Mapped to phases: 23
- Unmapped: 0 ✓

---
*Requirements defined: 2026-03-22*
*Last updated: 2026-03-22 after roadmap creation*
