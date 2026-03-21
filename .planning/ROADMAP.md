# Roadmap: musicmeta

## Milestones

- ✅ **v0.4.0 Provider Abstraction Overhaul** — Phases 1-5 (shipped 2026-03-21)
- 🚧 **v0.5.0 New Capabilities & Tech Debt Cleanup** — Phases 6-11 (in progress)

## Phases

<details>
<summary>✅ v0.4.0 Provider Abstraction Overhaul (Phases 1-5) — SHIPPED 2026-03-21</summary>

- [x] Phase 1: Bug Fixes (2/2 plans) — completed 2026-03-21
- [x] Phase 2: Provider Abstraction (3/3 plans) — completed 2026-03-21
- [x] Phase 3: Public API Cleanup (2/2 plans) — completed 2026-03-21
- [x] Phase 4: New Types (4/4 plans) — completed 2026-03-21
- [x] Phase 5: Deepening (4/4 plans) — completed 2026-03-21

Full details: `.planning/milestones/v0.4.0-ROADMAP.md`

</details>

### 🚧 v0.5.0 New Capabilities & Tech Debt Cleanup (In Progress)

**Milestone Goal:** Complete the "app-ready" story with credits, release editions, artist timeline, genre enhancement, deeper provider coverage, and v0.4.0 tech debt cleanup.

- [x] **Phase 6: Tech Debt Cleanup** - Migrate all 11 providers to HttpResult/ErrorKind and wire ListenBrainz + Discogs IDs (completed 2026-03-21)
- [ ] **Phase 7: Credits & Personnel** - New CREDITS enrichment type from MusicBrainz and Discogs
- [ ] **Phase 8: Release Editions** - New RELEASE_EDITIONS enrichment type from MusicBrainz and Discogs
- [ ] **Phase 9: Artist Timeline** - New ARTIST_TIMELINE composite type synthesizing existing enrichment data
- [ ] **Phase 10: Genre Enhancement** - Multi-provider genre merging with per-tag confidence scores
- [ ] **Phase 11: Provider Coverage Expansion** - Deeper coverage from Last.fm, iTunes, Fanart.tv, ListenBrainz, and Discogs

## Phase Details

### Phase 6: Tech Debt Cleanup
**Goal**: All 11 providers use HttpResult/ErrorKind uniformly and the ListenBrainz/Discogs identifier gaps from v0.4.0 are closed
**Depends on**: Nothing (no external deps, all internal refactors)
**Requirements**: DEBT-01, DEBT-02, DEBT-03, DEBT-04
**Success Criteria** (what must be TRUE):
  1. Every provider API call returns HttpResult so callers can distinguish network errors from not-found from rate-limit without inspecting exception types
  2. Every provider maps API errors to the appropriate ErrorKind so error diagnostics are consistent across all 11 providers
  3. ListenBrainz appears in a provider chain for ARTIST_DISCOGRAPHY and returns results in E2E tests
  4. Discogs release ID and master ID are stored on EnrichmentIdentifiers after a successful Discogs search so downstream phases can use them without re-searching
**Plans:** 4/4 plans complete

Plans:
- [x] 06-01-PLAN.md — HttpClient extensions + Wikidata/Wikipedia/FanartTv/iTunes migration
- [x] 06-02-PLAN.md — CoverArtArchive/LrcLib/MusicBrainz migration
- [x] 06-03-PLAN.md — Deezer/Last.fm/Discogs/ListenBrainz migration
- [x] 06-04-PLAN.md — ListenBrainz ARTIST_DISCOGRAPHY wiring + Discogs ID storage

### Phase 7: Credits & Personnel
**Goal**: Consumers can retrieve track-level credits (performers, producers, composers, engineers) via a single CREDITS enrichment type backed by MusicBrainz and Discogs
**Depends on**: Phase 6 (Discogs IDs from DEBT-04 enable precise Discogs credit lookups)
**Requirements**: CRED-01, CRED-02, CRED-03, CRED-04
**Success Criteria** (what must be TRUE):
  1. User can request EnrichmentType.CREDITS for a track and receive a non-empty credits list
  2. Credits from MusicBrainz (recording/work artist-rels) are returned at priority 100 and include performer, producer, composer, and engineer roles
  3. Credits from Discogs (extraartists) are returned at priority 50 as fallback when MusicBrainz has no data
  4. Each credit entry carries a roleCategory field (performance, production, songwriting) so consumers can group credits without parsing role strings
**Plans:** 1/2 plans executed

Plans:
- [x] 07-01-PLAN.md — CREDITS data model + MusicBrainz recording credits (API, parser, mapper, provider)
- [ ] 07-02-PLAN.md — Discogs release details credits (API, models, mapper, provider)

### Phase 8: Release Editions
**Goal**: Consumers can list all known editions and pressings of an album via a single RELEASE_EDITIONS enrichment type backed by MusicBrainz and Discogs
**Depends on**: Phase 6 (Discogs master ID from DEBT-04 enables direct master-versions lookup)
**Requirements**: EDIT-01, EDIT-02, EDIT-03
**Success Criteria** (what must be TRUE):
  1. User can request EnrichmentType.RELEASE_EDITIONS for an album and receive a list of editions with at least title, year, country, and format
  2. MusicBrainz returns editions from the release-group releases endpoint at priority 100
  3. Discogs returns editions from the master versions endpoint at priority 50
**Plans**: TBD

### Phase 9: Artist Timeline
**Goal**: Consumers can retrieve a structured chronological artist timeline that synthesizes life-span, discography, and band-member changes without making separate enrichment calls
**Depends on**: Phase 6 (clean provider infrastructure); benefits from existing ARTIST_DISCOGRAPHY and BAND_MEMBERS types from v0.4.0
**Requirements**: TIME-01, TIME-02, TIME-03
**Success Criteria** (what must be TRUE):
  1. User can request EnrichmentType.ARTIST_TIMELINE and receive a list of timeline events in chronological order
  2. Timeline events cover at least three categories: life-span events (born/died/formed/disbanded), discography releases, and band-member changes
  3. The engine resolves ARTIST_DISCOGRAPHY and BAND_MEMBERS sub-types automatically when ARTIST_TIMELINE is requested, without the caller specifying them
**Plans**: TBD

### Phase 10: Genre Enhancement
**Goal**: Genre results carry per-tag confidence scores and the provider chain merges tags from all providers rather than short-circuiting on the first success
**Depends on**: Phase 6 (HttpResult infrastructure makes multi-provider collection safer)
**Requirements**: GENR-01, GENR-02, GENR-03, GENR-04
**Success Criteria** (what must be TRUE):
  1. Each genre result includes a genreTags list where every entry has a tag name and a confidence score between 0 and 1
  2. When multiple providers return genre data, GenreMerger combines them: duplicate tags are deduplicated and scores are boosted, not overwritten
  3. Requesting GENRE collects results from all capable providers rather than stopping at the first success
  4. The existing genres list (plain strings) is still populated so callers using the old field do not break
**Plans**: TBD

### Phase 11: Provider Coverage Expansion
**Goal**: Last.fm, iTunes, Fanart.tv, ListenBrainz, and Discogs serve additional enrichment types, filling gaps where only one provider previously covered a type
**Depends on**: Phase 6 (consistent HttpResult/ErrorKind baseline; Discogs IDs for PROV-05)
**Requirements**: PROV-01, PROV-02, PROV-03, PROV-04, PROV-05
**Success Criteria** (what must be TRUE):
  1. Last.fm album.getinfo appears in the ALBUM_METADATA chain at priority 40 and returns title, artist, and tag data
  2. iTunes lookup endpoints appear in the ALBUM_TRACKS and ARTIST_DISCOGRAPHY chains at priority 30 and return results for mainstream releases
  3. Fanart.tv album-specific endpoint is used for ALBUM_ART lookups when an album MBID is available, replacing or supplementing the artist-based endpoint
  4. ListenBrainz similar-artists endpoint appears in a SIMILAR_ARTISTS chain at priority 50 and returns results
  5. Discogs release detail fields (community rating, have/want counts) are included in enrichment results when a Discogs release ID is available
**Plans**: TBD

## Progress

**Execution Order:** 6 → 7 → 8 → 9 → 10 → 11

| Phase | Milestone | Plans Complete | Status | Completed |
|-------|-----------|----------------|--------|-----------|
| 1. Bug Fixes | v0.4.0 | 2/2 | Complete | 2026-03-21 |
| 2. Provider Abstraction | v0.4.0 | 3/3 | Complete | 2026-03-21 |
| 3. Public API Cleanup | v0.4.0 | 2/2 | Complete | 2026-03-21 |
| 4. New Types | v0.4.0 | 4/4 | Complete | 2026-03-21 |
| 5. Deepening | v0.4.0 | 4/4 | Complete | 2026-03-21 |
| 6. Tech Debt Cleanup | v0.5.0 | 4/4 | Complete   | 2026-03-21 |
| 7. Credits & Personnel | v0.5.0 | 1/2 | In Progress|  |
| 8. Release Editions | v0.5.0 | 0/TBD | Not started | - |
| 9. Artist Timeline | v0.5.0 | 0/TBD | Not started | - |
| 10. Genre Enhancement | v0.5.0 | 0/TBD | Not started | - |
| 11. Provider Coverage Expansion | v0.5.0 | 0/TBD | Not started | - |
