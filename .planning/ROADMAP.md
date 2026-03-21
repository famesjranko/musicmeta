# Roadmap: musicmeta v0.4.0 Provider Abstraction Overhaul

## Overview

v0.4.0 moves from a working prototype to a well-abstracted library. Five sequential phases: fix known bugs first (no deps), then build the provider abstraction layer that isolates internal wiring from the public API, then clean up the public type surface, then add 6 new enrichment types with artwork size support, and finally deepen coverage from existing providers using the now-solid foundation.

## Milestones

- 🚧 **v0.4.0 Provider Abstraction Overhaul** - Phases 1-5 (in progress)

## Phases

- [ ] **Phase 1: Bug Fixes** - Fix 5 known provider bugs before touching shared files
- [ ] **Phase 2: Provider Abstraction** - Typed identifiers, identity provider formalization, mapper pattern, API key config
- [ ] **Phase 3: Public API Cleanup** - TTL in EnrichmentType, extensible identifiers, error categorization, HttpResult
- [ ] **Phase 4: New Types** - 6 new enrichment types, artwork sizes, expanded Wikidata properties
- [ ] **Phase 5: Deepening** - Back cover art, album metadata from more sources, track popularity fix, Wikipedia media, batch endpoints, confidence standardization

## Phase Details

### Phase 1: Bug Fixes
**Goal**: All known provider bugs are corrected and the test suite passes cleanly before structural changes touch the same files
**Depends on**: Nothing (first phase)
**Requirements**: BUG-01, BUG-02, BUG-03, BUG-04, BUG-05
**Success Criteria** (what must be TRUE):
  1. MusicBrainz empty search results return NotFound (not RateLimited); unit tests assert this
  2. Last.fm API calls use HTTPS; no plain-HTTP requests leave the library
  3. Last.fm does not advertise TRACK_POPULARITY in its capabilities; requesting it returns NotFound from that provider
  4. LRCLIB duration values are passed as float with no precision loss; a track with a non-integer duration matches correctly
  5. Wikidata claim resolution returns the preferred-rank claim when one exists, not always the first in the array
**Plans**: 2 plans
Plans:
- [x] 01-01-PLAN.md — Fix MusicBrainz empty-result misclassification, Last.fm HTTPS, Last.fm TRACK_POPULARITY removal
- [x] 01-02-PLAN.md — Fix LRCLIB duration truncation, Wikidata preferred-rank claim selection

### Phase 2: Provider Abstraction
**Goal**: Provider internals are isolated behind a typed abstraction layer so that adding or changing a provider never requires touching public API types
**Depends on**: Phase 1
**Requirements**: ABST-01, ABST-02, ABST-03, ABST-04, ABST-05, ABST-06
**Success Criteria** (what must be TRUE):
  1. ProviderChain skips a provider when the request lacks the specific identifier that provider requires (e.g., WikidataProvider skipped without wikidataId), verified by unit test
  2. MusicBrainzProvider.isIdentityProvider is true and implements resolveIdentity(); ProviderRegistry selects it via that flag, not by capability heuristic
  3. EnrichmentData.IdentifierResolution no longer exists in the sealed class; code referencing it does not compile
  4. Each of the 11 providers has a corresponding *Mapper.kt file; no provider directly constructs EnrichmentData subclasses inline
  5. EnrichmentEngine.Builder accepts apiKeys(ApiKeyConfig) and withDefaultProviders() constructs all providers from it
  6. needsIdentityResolution() is derived from provider capability declarations, not from a hardcoded type list
**Plans**: 3 plans
Plans:
- [x] 02-01-PLAN.md — Typed IdentifierRequirement enum, identity provider formalization, data-driven needsIdentityResolution
- [x] 02-02-PLAN.md — Remove IdentifierResolution from EnrichmentData, rework identity resolution pathway
- [x] 02-03-PLAN.md — Extract 11 mapper files, add ApiKeyConfig and Builder.withDefaultProviders()

### Phase 3: Public API Cleanup
**Goal**: Public types are clean, extensible, and free of provider-specific leaks so consumers interact with a stable surface
**Depends on**: Phase 2
**Requirements**: API-01, API-02, API-03, API-04, API-05
**Success Criteria** (what must be TRUE):
  1. EnrichmentType enum carries defaultTtlMs directly; DefaultEnrichmentEngine has no ttlFor() function; ttlOverrides in EnrichmentConfig overrides per-type TTL
  2. EnrichmentIdentifiers.withExtra("deezerId", "123") stores the value and get("deezerId") returns it; adding new identifier keys requires no data class change
  3. SimilarArtist and PopularTrack carry identifiers: EnrichmentIdentifiers (not musicBrainzId: String?)
  4. EnrichmentResult.Error includes an errorKind: ErrorKind field; callers can distinguish NETWORK vs AUTH vs PARSE failures without parsing the message string
  5. HttpClient.fetchJsonResult() returns HttpResult<JSONObject> with distinct subtypes for Ok, ClientError (404), ServerError (500), RateLimited (429), and NetworkError
**Plans**: 2 plans
Plans:
- [x] 03-01-PLAN.md — TTL in EnrichmentType enum, extensible identifiers, SimilarArtist/PopularTrack migration
- [x] 03-02-PLAN.md — ErrorKind enum on EnrichmentResult.Error, HttpResult sealed class with fetchJsonResult()

### Phase 4: New Types
**Goal**: Consumers can enrich band membership, artist discography, album tracks, similar tracks, artist banners, external links, and artwork at multiple sizes using a single enrich() call
**Depends on**: Phase 3
**Requirements**: TYPE-01, TYPE-02, TYPE-03, TYPE-04, TYPE-05, TYPE-06, TYPE-07, TYPE-08
**Success Criteria** (what must be TRUE):
  1. enrich(ForArtist("Radiohead"), setOf(BAND_MEMBERS)) returns BandMembers with at least one member from MusicBrainz in an E2E test
  2. enrich(ForArtist("Radiohead"), setOf(ARTIST_DISCOGRAPHY)) returns Discography with album entries from at least one provider
  3. enrich(ForAlbum("OK Computer", "Radiohead"), setOf(ALBUM_TRACKS)) returns Tracklist with tracks from at least one provider
  4. enrich(ForTrack("Karma Police", "Radiohead"), setOf(SIMILAR_TRACKS)) returns SimilarTracks results
  5. enrich(ForArtist("Radiohead"), setOf(ARTIST_BANNER, ARTIST_LINKS)) returns Artwork (banner) and ArtistLinks with external URLs from at least one provider each
  6. Artwork results from Cover Art Archive, Deezer, iTunes, and Fanart.tv include a populated sizes list with at least two size entries
  7. WikidataProvider returns birth/death date, country of origin, and occupation in Metadata when those Wikidata properties are present
**Plans**: 4 plans
Plans:
- [x] 04-01-PLAN.md — New EnrichmentType values, EnrichmentData subclasses, ArtworkSize, serialization tests
- [x] 04-02-PLAN.md — MusicBrainz: BAND_MEMBERS, ARTIST_DISCOGRAPHY, ALBUM_TRACKS, ARTIST_LINKS capabilities
- [x] 04-03-PLAN.md — Deezer discography/tracks, Last.fm similar tracks, Fanart.tv artist banner
- [x] 04-04-PLAN.md — Artwork sizes for 4 providers, Wikidata expanded properties, Discogs band members

### Phase 5: Deepening
**Goal**: Existing enrichment types are more complete: back cover art is available, album metadata comes from more sources, track popularity works correctly, artist photos have supplemental Wikipedia coverage, ListenBrainz batch endpoints are used, and all providers report confidence scores consistently
**Depends on**: Phase 4
**Requirements**: DEEP-01, DEEP-02, DEEP-03, DEEP-04, DEEP-05, DEEP-06
**Success Criteria** (what must be TRUE):
  1. enrich(ForAlbum("OK Computer", "Radiohead"), setOf(ALBUM_ART_BACK)) returns back cover Artwork via Cover Art Archive when a back image exists
  2. enrich(ForAlbum(...), setOf(ALBUM_METADATA)) populates trackCount and label fields sourced from Deezer, iTunes, or Discogs data that was previously ignored
  3. enrich(ForTrack("Karma Police", "Radiohead"), setOf(TRACK_POPULARITY)) returns a meaningful play count from Last.fm track.getInfo or ListenBrainz batch recording endpoint (not artist-level data)
  4. WikipediaProvider can return supplemental ARTIST_PHOTO images from the Wikipedia page media list endpoint for artists with few Wikidata images
  5. ListenBrainz batch endpoints (popularity/recording, popularity/artist, top-release-groups-for-artist) are called with multiple IDs in one request rather than one request per item
  6. All provider Success results use ConfidenceCalculator constants (idBasedLookup, authoritative, searchScore, fuzzyMatch); no provider hardcodes a raw float confidence value
**Plans**: 4 plans
Plans:
- [x] 05-01-PLAN.md — ALBUM_ART_BACK and ALBUM_BOOKLET types via Cover Art Archive JSON endpoint
- [x] 05-02-PLAN.md — Last.fm track.getInfo for TRACK_POPULARITY, ListenBrainz batch endpoints
- [ ] 05-03-PLAN.md — Album metadata from Deezer/iTunes/Discogs, Wikipedia page media for ARTIST_PHOTO
- [ ] 05-04-PLAN.md — ConfidenceCalculator utility and provider-wide confidence standardization

## Progress

**Execution Order:** 1 → 2 → 3 → 4 → 5

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Bug Fixes | 2/2 | Complete | 2026-03-21 |
| 2. Provider Abstraction | 0/3 | Not started | - |
| 3. Public API Cleanup | 0/2 | Not started | - |
| 4. New Types | 2/4 | In Progress|  |
| 5. Deepening | 2/4 | In Progress|  |
