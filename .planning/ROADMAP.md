# Roadmap: musicmeta

## Milestones

- ✅ **v0.4.0 Provider Abstraction Overhaul** — Phases 1-5 (shipped 2026-03-21)
- ✅ **v0.5.0 New Capabilities & Tech Debt Cleanup** — Phases 6-11 (shipped 2026-03-22)
- 🚧 **v0.6.0 Recommendations Engine** — Phases 12-18 (in progress)

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

<details>
<summary>✅ v0.5.0 New Capabilities & Tech Debt Cleanup (Phases 6-11) — SHIPPED 2026-03-22</summary>

- [x] Phase 6: Tech Debt Cleanup (4/4 plans) — completed 2026-03-21
- [x] Phase 7: Credits & Personnel (2/2 plans) — completed 2026-03-21
- [x] Phase 8: Release Editions (2/2 plans) — completed 2026-03-21
- [x] Phase 9: Artist Timeline (2/2 plans) — completed 2026-03-21
- [x] Phase 10: Genre Enhancement (3/3 plans) — completed 2026-03-21
- [x] Phase 11: Provider Coverage Expansion (3/3 plans) — completed 2026-03-21

Full details: `.planning/milestones/v0.5.0-ROADMAP.md`

</details>

### 🚧 v0.6.0 Recommendations Engine (In Progress)

**Milestone Goal:** Turn musicmeta from a metadata lookup library into a recommendation engine by adding discovery features built on top of enrichment data.

- [x] **Phase 12: Engine Refactoring** — Extract ResultMerger and CompositeSynthesizer interfaces; bring DefaultEnrichmentEngine under 300 lines (completed 2026-03-22)
- [x] **Phase 13: Similar Artists + Merger** — Add Deezer as third SIMILAR_ARTISTS provider; promote to mergeable type with SimilarArtistMerger (completed 2026-03-22)
- [x] **Phase 14: Artist Radio** — New ARTIST_RADIO type backed by Deezer /artist/{id}/radio endpoint (completed 2026-03-22)
- [x] **Phase 15: Similar Albums** — New SIMILAR_ALBUMS composite type synthesized from similar artists, genre, and era data (completed 2026-03-22)
- [ ] **Phase 16: Genre Discovery** — New GENRE_DISCOVERY composite type using static genre affinity taxonomy
- [ ] **Phase 17: Catalog Filtering Interface** — CatalogProvider interface and engine-level filtering applied to recommendation results
- [ ] **Phase 18: Integration and Docs** — EnrichmentShowcaseTest updated; README, ROADMAP, CHANGELOG, STORIES finalized for v0.6.0

## Phase Details

### Phase 12: Engine Refactoring
**Goal**: DefaultEnrichmentEngine responsibilities are partitioned into focused interfaces so merger and synthesizer logic can be extended without modifying the engine class
**Depends on**: Phase 11 (v0.5.0 complete)
**Requirements**: ENG-01, ENG-02, ENG-03
**Success Criteria** (what must be TRUE):
  1. ResultMerger interface exists and DefaultEnrichmentEngine delegates all mergeable-type dispatch to it
  2. CompositeSynthesizer interface exists and DefaultEnrichmentEngine delegates all composite-type dispatch to it
  3. DefaultEnrichmentEngine source file is under 300 lines
  4. All existing tests pass without modification after the refactor
**Plans**: 2 plans
Plans:
- [x] 12-01-PLAN.md — Define ResultMerger and CompositeSynthesizer interfaces; adapt GenreMerger and TimelineSynthesizer as first implementations
- [x] 12-02-PLAN.md — Refactor DefaultEnrichmentEngine to delegate to interface registries; update Builder wiring

### Phase 13: Similar Artists + Merger
**Goal**: Users get richer similar-artist results that combine Last.fm, ListenBrainz, and Deezer data — deduplicated and scored — instead of only the first provider that responds
**Depends on**: Phase 12
**Requirements**: SIM-01, SIM-02, SIM-03, SIM-04
**Success Criteria** (what must be TRUE):
  1. Calling enrich() for SIMILAR_ARTISTS on an artist with Deezer coverage returns artists contributed by Deezer even when Last.fm also returns results
  2. The merged result contains no duplicate artists (matched by name or MBID)
  3. Each SimilarArtist in the result has a sources field listing which providers contributed it
  4. Deezer artist ID is resolved via searchArtist + name match guard, not assumed to be cached
**Plans**: 2 plans
Plans:
- [x] 13-01-PLAN.md — Add sources field to SimilarArtist, backfill existing mappers, implement Deezer SIMILAR_ARTISTS provider
- [x] 13-02-PLAN.md — Create SimilarArtistMerger and wire into Builder for automatic multi-provider merge

### Phase 14: Artist Radio
**Goal**: Users can request a radio-style playlist seeded by any artist, returned as an ordered list of tracks with full metadata
**Depends on**: Phase 12
**Requirements**: RAD-01, RAD-02, RAD-03
**Success Criteria** (what must be TRUE):
  1. Calling enrich() for ARTIST_RADIO on a ForArtist request returns a RadioPlaylist result
  2. Each RadioTrack in the playlist includes title, artist, album, duration, and Deezer identifiers
  3. ARTIST_RADIO results are cached with a 7-day TTL
**Plans**: 2 plans
Plans:
- [x] 14-01-PLAN.md — Add ARTIST_RADIO type, RadioPlaylist/RadioTrack data model, DeezerRadioTrack DTO, and DeezerMapper.toRadioPlaylist()
- [x] 14-02-PLAN.md — Implement DeezerApi.getArtistRadio(), wire ARTIST_RADIO into DeezerProvider, add unit tests

### Phase 15: Similar Albums
**Goal**: Users can discover albums similar to one they know, ranked by artist similarity and era proximity, without the engine making additional API calls during synthesis
**Depends on**: Phase 13
**Requirements**: ALB-01, ALB-02, ALB-03, ALB-04
**Success Criteria** (what must be TRUE):
  1. Calling enrich() for SIMILAR_ALBUMS on a ForAlbum request returns a ranked list of similar albums
  2. Each SimilarAlbum includes title, artist, year, artistMatchScore, thumbnail, and identifiers
  3. Albums are ordered with higher artistMatchScore results appearing first; era proximity influences scoring when year data is present
  4. The SimilarAlbumsProvider is a standalone provider — no I/O occurs inside the synthesizer function itself
**Plans**: 2 plans
Plans:
- [x] 15-01-PLAN.md — Add SIMILAR_ALBUMS type, SimilarAlbum/SimilarAlbums data model, DeezerMapper.toSimilarAlbum(), and SimilarAlbumsProvider
- [x] 15-02-PLAN.md — Wire SimilarAlbumsProvider into Builder.withDefaultProviders() and write SimilarAlbumsProviderTest

### Phase 16: Genre Discovery
**Goal**: Users can discover related genres for any entity that has genre data, receiving affinity-scored neighbors with relationship type context
**Depends on**: Phase 12
**Requirements**: GEN-01, GEN-02, GEN-03
**Success Criteria** (what must be TRUE):
  1. Calling enrich() for GENRE_DISCOVERY on an entity with existing genre data returns a list of GenreAffinity results
  2. The static genre taxonomy covers at least 60 genre relationships spanning parent, child, and sibling relationship types
  3. Each GenreAffinity result includes name, affinity score, relationship type, and the source genre(s) that triggered it
**Plans**: 2 plans
Plans:
- [ ] 16-01-PLAN.md — Add GENRE_DISCOVERY type, GenreDiscovery/GenreAffinity data model, and GenreAffinityMatcher with static taxonomy
- [ ] 16-02-PLAN.md — Wire GenreAffinityMatcher into Builder default synthesizer list and write GenreAffinityMatcherTest

### Phase 17: Catalog Filtering Interface
**Goal**: Library consumers can plug in their own catalog (local library, streaming service, etc.) so recommendation results are pre-filtered or re-ranked by availability before being returned
**Depends on**: Phase 15, Phase 16
**Requirements**: CAT-01, CAT-02, CAT-03, CAT-04
**Success Criteria** (what must be TRUE):
  1. CatalogProvider interface is publicly accessible and documents the contract a consumer must implement
  2. EnrichmentConfig accepts an optional CatalogProvider and a CatalogFilterMode (UNFILTERED, AVAILABLE_ONLY, AVAILABLE_FIRST)
  3. Requesting a recommendation type with a configured CatalogProvider and AVAILABLE_ONLY mode returns only items that pass the provider's availability check
  4. Requesting a recommendation type with no CatalogProvider configured returns all results, identical to previous behavior
**Plans**: TBD

### Phase 18: Integration and Docs
**Goal**: v0.6.0 features are demonstrated end-to-end in the showcase test and all documentation accurately reflects the completed milestone
**Depends on**: Phase 13, Phase 14, Phase 15, Phase 16, Phase 17
**Requirements**: INT-01, INT-02
**Success Criteria** (what must be TRUE):
  1. EnrichmentShowcaseTest contains at least one demonstration of each new v0.6.0 enrichment type (ARTIST_RADIO, SIMILAR_ALBUMS, GENRE_DISCOVERY) and the updated SIMILAR_ARTISTS behavior
  2. README reflects the new enrichment types and recommendation capabilities
  3. CHANGELOG contains a v0.6.0 entry listing all shipped features
**Plans**: TBD

## Progress

| Phase | Milestone | Plans Complete | Status | Completed |
|-------|-----------|----------------|--------|-----------|
| 1. Bug Fixes | v0.4.0 | 2/2 | Complete | 2026-03-21 |
| 2. Provider Abstraction | v0.4.0 | 3/3 | Complete | 2026-03-21 |
| 3. Public API Cleanup | v0.4.0 | 2/2 | Complete | 2026-03-21 |
| 4. New Types | v0.4.0 | 4/4 | Complete | 2026-03-21 |
| 5. Deepening | v0.4.0 | 4/4 | Complete | 2026-03-21 |
| 6. Tech Debt Cleanup | v0.5.0 | 4/4 | Complete | 2026-03-21 |
| 7. Credits & Personnel | v0.5.0 | 2/2 | Complete | 2026-03-21 |
| 8. Release Editions | v0.5.0 | 2/2 | Complete | 2026-03-21 |
| 9. Artist Timeline | v0.5.0 | 2/2 | Complete | 2026-03-21 |
| 10. Genre Enhancement | v0.5.0 | 3/3 | Complete | 2026-03-21 |
| 11. Provider Coverage | v0.5.0 | 3/3 | Complete | 2026-03-21 |
| 12. Engine Refactoring | v0.6.0 | 2/2 | Complete    | 2026-03-22 |
| 13. Similar Artists + Merger | v0.6.0 | 2/2 | Complete    | 2026-03-22 |
| 14. Artist Radio | v0.6.0 | 2/2 | Complete    | 2026-03-22 |
| 15. Similar Albums | v0.6.0 | 2/2 | Complete    | 2026-03-22 |
| 16. Genre Discovery | v0.6.0 | 0/2 | Not started | - |
| 17. Catalog Filtering Interface | v0.6.0 | 0/TBD | Not started | - |
| 18. Integration and Docs | v0.6.0 | 0/TBD | Not started | - |
