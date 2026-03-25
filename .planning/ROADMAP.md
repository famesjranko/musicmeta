# Roadmap: musicmeta

## Milestones

- ✅ **v0.4.0 Provider Abstraction Overhaul** — Phases 1-5 (shipped 2026-03-21)
- ✅ **v0.5.0 New Capabilities & Tech Debt Cleanup** — Phases 6-11 (shipped 2026-03-22)
- ✅ **v0.6.0 Recommendations Engine** — Phases 12-18 (shipped 2026-03-23)
- ✅ **v0.8.0 Production Readiness** — Phases 19-22 (shipped 2026-03-24)
- 🚧 **v0.9.0 LB Radio & Track Preview** — Phases 23-25 (in progress)

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

<details>
<summary>✅ v0.6.0 Recommendations Engine (Phases 12-18) — SHIPPED 2026-03-23</summary>

- [x] Phase 12: Engine Refactoring (2/2 plans) — completed 2026-03-22
- [x] Phase 13: Similar Artists + Merger (2/2 plans) — completed 2026-03-22
- [x] Phase 14: Artist Radio (2/2 plans) — completed 2026-03-22
- [x] Phase 15: Similar Albums (2/2 plans) — completed 2026-03-22
- [x] Phase 16: Genre Discovery (2/2 plans) — completed 2026-03-22
- [x] Phase 17: Catalog Filtering Interface (2/2 plans) — completed 2026-03-22
- [x] Phase 18: Integration and Docs (2/2 plans) — completed 2026-03-23

Full details: `.planning/milestones/v0.6.0-ROADMAP.md`

</details>

<details>
<summary>✅ v0.8.0 Production Readiness (Phases 19-22) — SHIPPED 2026-03-24</summary>

- [x] Phase 19: OkHttp Adapter (2/2 plans) — completed 2026-03-24
- [x] Phase 20: Stale Cache (2/2 plans) — completed 2026-03-24
- [x] Phase 21: Bulk Enrichment (1/1 plan) — completed 2026-03-24
- [x] Phase 22: Maven Central Publishing (2/2 plans) — completed 2026-03-24

Full details: `.planning/milestones/v0.8.0-ROADMAP.md`

</details>

### 🚧 v0.9.0 LB Radio & Track Preview (In Progress)

**Milestone Goal:** Extend discovery with a second radio source (ListenBrainz LB Radio) and a new track preview enrichment type (Deezer 30s previews). Two new enrichment types: TRACK_PREVIEW and ARTIST_RADIO_DISCOVERY.

- [x] **Phase 23: Track Preview** — TRACK_PREVIEW enrichment type via Deezer search preview field, with consumer accessors (completed 2026-03-25)
- [x] **Phase 24: Artist Radio Discovery** — ARTIST_RADIO_DISCOVERY via ListenBrainz LB Radio with auth gating, discovery mode config, and catalog integration (completed 2026-03-26)
- [ ] **Phase 25: Documentation** — Provider docs, configuration guide, and project file updates for v0.9.0

## Phase Details

### Phase 23: Track Preview
**Goal**: Consumers can enrich any track with TRACK_PREVIEW to get a Deezer 30-second preview URL
**Depends on**: Phase 22 (existing Deezer search infrastructure; extends DeezerTrackSearchResult with already-available fields)
**Requirements**: PREV-01, PREV-02, PREV-03, PREV-04, PREV-05, INTEG-04, INTEG-05
**Success Criteria** (what must be TRUE):
  1. Calling `engine.enrich(forTrack(...), setOf(TRACK_PREVIEW))` returns a `TrackPreview` with a non-null url for a known Deezer-indexed track
  2. `TrackProfile.preview` returns the preview when TRACK_PREVIEW was requested, null otherwise
  3. `EnrichmentResults.trackPreview()` returns the same preview accessible via the named accessor
  4. TRACK_PREVIEW does not appear in DEFAULT_TRACK_TYPES (requesting a track profile without explicit TRACK_PREVIEW does not trigger a preview lookup)
  5. TRACK_PREVIEW does not appear in RECOMMENDATION_TYPES (catalog filtering does not apply to previews)
**Plans**: 2 plans
Plans:
- [x] 23-01-PLAN.md — Core type + provider: EnrichmentType, EnrichmentData.TrackPreview, DeezerModels/Api/Mapper/Provider changes
- [x] 23-02-PLAN.md — Consumer accessors + tests: EnrichmentResults.trackPreview(), TrackProfile.preview, DeezerMapper/Provider tests

### Phase 24: Artist Radio Discovery
**Goal**: Consumers can enrich an artist with ARTIST_RADIO_DISCOVERY to get a ListenBrainz community-driven radio playlist, with configurable discovery depth and catalog filtering support
**Depends on**: Phase 23 (TRACK_PREVIEW establishes the v0.9.0 type registration pattern; Phase 24 follows the same model for the new type)
**Requirements**: RADIO-01, RADIO-02, RADIO-03, RADIO-04, RADIO-05, RADIO-06, RADIO-07, INTEG-01, INTEG-02, INTEG-03
**Success Criteria** (what must be TRUE):
  1. Calling `engine.enrich(forArtist(...), setOf(ARTIST_RADIO_DISCOVERY))` with a valid listenBrainzToken returns a `RadioPlaylist` with track title, artist, album, durationMs, and MBIDs populated
  2. Without a listenBrainzToken in ApiKeyConfig, ARTIST_RADIO_DISCOVERY returns NotFound (capability silently absent) and existing ListenBrainz endpoints continue working
  3. Setting `EnrichmentConfig(radioDiscoveryMode = RadioDiscoveryMode.HARD)` changes the mode sent to the LB Radio API
  4. `ArtistProfile.radioDiscovery` and `EnrichmentResults.radioDiscovery()` both return the playlist when ARTIST_RADIO_DISCOVERY was requested
  5. ARTIST_RADIO_DISCOVERY appears in DEFAULT_ARTIST_TYPES and in RECOMMENDATION_TYPES (catalog filtering applies)
**Plans**: 2 plans
Plans:
- [x] 24-01-PLAN.md — Foundation: ARTIST_RADIO_DISCOVERY enum, RadioDiscoveryMode config, HttpClient header support, ListenBrainzApi.getRadio() with JSPF parsing
- [x] 24-02-PLAN.md — Provider + integration + tests: ListenBrainzMapper/Provider wiring, Builder token threading, consumer accessors, catalog registration, unit tests

### Phase 25: Documentation
**Goal**: All consumer-facing docs accurately reflect the two new enrichment types, auth requirements, and configuration options added in v0.9.0
**Depends on**: Phase 24 (docs describe implemented behavior; must run after both types are shipped)
**Requirements**: DOCS-01, DOCS-02, DOCS-03
**Success Criteria** (what must be TRUE):
  1. `docs/providers/deezer.md` lists the `preview` field under extracted data (not under "What We DON'T Extract")
  2. `docs/providers/listenbrainz.md` documents LB Radio endpoint and states that a user token is required
  3. `docs/guides/configuration.md` explains `radioDiscoveryMode` and `listenBrainzToken` setup with example snippets
  4. CHANGELOG.md, STORIES.md, ROADMAP.md, and README.md all reflect the v0.9.0 additions (type count updated to 34)
**Plans**: 1 plan
Plans:
- [ ] 25-01-PLAN.md — Provider docs + project files: deezer.md, listenbrainz.md, configuration.md, CHANGELOG, STORIES, ROADMAP, README

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
| 12. Engine Refactoring | v0.6.0 | 2/2 | Complete | 2026-03-22 |
| 13. Similar Artists + Merger | v0.6.0 | 2/2 | Complete | 2026-03-22 |
| 14. Artist Radio | v0.6.0 | 2/2 | Complete | 2026-03-22 |
| 15. Similar Albums | v0.6.0 | 2/2 | Complete | 2026-03-22 |
| 16. Genre Discovery | v0.6.0 | 2/2 | Complete | 2026-03-22 |
| 17. Catalog Filtering Interface | v0.6.0 | 2/2 | Complete | 2026-03-22 |
| 18. Integration and Docs | v0.6.0 | 2/2 | Complete | 2026-03-23 |
| 19. OkHttp Adapter | v0.8.0 | 2/2 | Complete | 2026-03-24 |
| 20. Stale Cache | v0.8.0 | 2/2 | Complete | 2026-03-24 |
| 21. Bulk Enrichment | v0.8.0 | 1/1 | Complete | 2026-03-24 |
| 22. Maven Central Publishing | v0.8.0 | 2/2 | Complete | 2026-03-24 |
| 23. Track Preview | v0.9.0 | 2/2 | Complete   | 2026-03-25 |
| 24. Artist Radio Discovery | v0.9.0 | 2/2 | Complete | 2026-03-26 |
| 25. Documentation | v0.9.0 | 0/1 | Not started | - |
