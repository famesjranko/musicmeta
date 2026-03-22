# Requirements: musicmeta v0.6.0 Recommendations Engine

**Defined:** 2026-03-23
**Core Value:** Consumers get comprehensive, accurate music metadata from a single enrich() call without knowing which APIs exist, how they authenticate, or how to correlate identifiers across services.

## v0.6.0 Requirements

Requirements for the Recommendations Engine milestone. Each maps to roadmap phases.

### Engine Refactoring

- [ ] **ENG-01**: Engine extracts merger dispatch into ResultMerger interface
- [ ] **ENG-02**: Engine extracts composite dispatch into CompositeSynthesizer interface
- [ ] **ENG-03**: DefaultEnrichmentEngine is under 300 lines after refactoring

### Similar Artists

- [ ] **SIM-01**: Deezer provides similar artists via /artist/{id}/related endpoint
- [ ] **SIM-02**: SIMILAR_ARTISTS is promoted to mergeable type (like GENRE)
- [ ] **SIM-03**: SimilarArtistMerger deduplicates by name/MBID and handles score differences across providers
- [ ] **SIM-04**: SimilarArtist data class includes sources field tracking which providers contributed

### Artist Radio

- [ ] **RAD-01**: User can request ARTIST_RADIO enrichment for any artist
- [ ] **RAD-02**: Deezer provides radio tracks via /artist/{id}/radio endpoint
- [ ] **RAD-03**: RadioPlaylist returns ordered tracks with title, artist, album, duration, and identifiers

### Similar Albums

- [ ] **ALB-01**: User can request SIMILAR_ALBUMS enrichment for any album
- [ ] **ALB-02**: SimilarAlbumsProvider fetches related artists then their top albums from Deezer
- [ ] **ALB-03**: Albums are scored by artist similarity and optionally filtered by era proximity
- [ ] **ALB-04**: SimilarAlbum includes title, artist, year, artistMatchScore, thumbnail, and identifiers

### Genre Discovery

- [ ] **GEN-01**: User can request GENRE_DISCOVERY enrichment for any entity with genre data
- [ ] **GEN-02**: Static genre taxonomy covers ~60-80 genre relationships
- [ ] **GEN-03**: GenreAffinity results include name, affinity score, relationship type, and source genres

### Catalog Filtering

- [ ] **CAT-01**: CatalogProvider interface allows consumers to check item availability
- [ ] **CAT-02**: CatalogFilterMode supports unfiltered, available-only, and available-first modes
- [ ] **CAT-03**: Engine applies catalog filtering to recommendation-type results before returning
- [ ] **CAT-04**: Recommendations work unfiltered by default when no CatalogProvider is configured

### Integration

- [ ] **INT-01**: EnrichmentShowcaseTest updated with v0.6.0 recommendation feature spotlight
- [ ] **INT-02**: README, ROADMAP, CHANGELOG, and STORIES updated for v0.6.0

## Future Requirements

Deferred to later milestones. Tracked but not in current roadmap.

### Credit-Based Discovery (v0.7.0)

- **CRED-01**: User can discover other tracks/albums by a credited person (producer, composer)
- **CRED-02**: Discovery is scoped to production + songwriting roles where data is dense
- **CRED-03**: Result cap prevents fan-out (≤3 persons, ≤10 recordings each)

### Listening-Based Recommendations (v0.8.0)

- **LIS-01**: User can get personalized recommendations via ListenBrainz collaborative filtering
- **LIS-02**: Recommendations are user-scoped (require ListenBrainz username)
- **LIS-03**: Cache key includes username for per-user caching

### Catalog Implementations (v0.8.0)

- **CATIMP-01**: LocalLibraryCatalog scans local files and matches by title/artist
- **CATIMP-02**: SpotifyCatalog checks Spotify catalog via OAuth
- **CATIMP-03**: Fingerprint-based matching (AcoustID/Chromaprint) for local library

## Out of Scope

Explicitly excluded. Documented to prevent scope creep.

| Feature | Reason |
|---------|--------|
| Spotify/Apple Music similar endpoints | Require OAuth and restrictive ToS — focus on existing 11 providers |
| Audio fingerprint-based similarity | No open audio analysis API without OAuth (AcousticBrainz defunct) |
| Real-time personalization (live scrobble stream) | ListenBrainz CF is batch-updated, not real-time |
| Full Deezer OAuth for personalized radio | Adds auth complexity to no-auth library |
| Playlist management (create/update) | Write operations require OAuth — library is read-only |
| Genre taxonomy hierarchy | Flat affinity table covers the use case; deferred per PROJECT.md |
| SIMILAR_TRACKS via Deezer radio | Radio is artist-seeded not track-seeded — different semantics |
| ForUser request variant | Clean but breaking — defer until CF sees real usage in v0.8.0 |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| ENG-01 | — | Pending |
| ENG-02 | — | Pending |
| ENG-03 | — | Pending |
| SIM-01 | — | Pending |
| SIM-02 | — | Pending |
| SIM-03 | — | Pending |
| SIM-04 | — | Pending |
| RAD-01 | — | Pending |
| RAD-02 | — | Pending |
| RAD-03 | — | Pending |
| ALB-01 | — | Pending |
| ALB-02 | — | Pending |
| ALB-03 | — | Pending |
| ALB-04 | — | Pending |
| GEN-01 | — | Pending |
| GEN-02 | — | Pending |
| GEN-03 | — | Pending |
| CAT-01 | — | Pending |
| CAT-02 | — | Pending |
| CAT-03 | — | Pending |
| CAT-04 | — | Pending |
| INT-01 | — | Pending |
| INT-02 | — | Pending |

**Coverage:**
- v0.6.0 requirements: 23 total
- Mapped to phases: 0
- Unmapped: 23 ⚠️

---
*Requirements defined: 2026-03-23*
*Last updated: 2026-03-23 after initial definition*
