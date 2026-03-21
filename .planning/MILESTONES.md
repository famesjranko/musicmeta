# Milestones

## v0.5.0 New Capabilities & Tech Debt Cleanup (Shipped: 2026-03-21)

**Phases completed:** 6 phases, 16 plans, 26 tasks

**Key accomplishments:**

- HttpResult-returning method variants added to HttpClient (fetchJsonArrayResult, postJsonResult, postJsonArrayResult) and 4 providers fully migrated from nullable fetchJson to typed fetchJsonResult with ErrorKind error classification
- CoverArtArchive (1 site), LrcLib (2 sites), and MusicBrainz (7 sites) Api classes migrated from nullable fetchJson/fetchJsonArray to fetchJsonResult/fetchJsonArrayResult; all 3 Providers now map errors to ErrorKind via mapError() helper
- 4 remaining providers migrated from nullable fetchJson/fetchJsonArray/postJsonArray to typed HttpResult variants with ErrorKind error classification, completing the DEBT-01 and DEBT-02 milestone across all 11 providers
- ListenBrainz ARTIST_DISCOGRAPHY wired at priority 50 and Discogs release/master IDs stored in resolvedIdentifiers via discogsReleaseId/discogsMasterId extra map keys
- CREDITS enrichment type with MusicBrainz recording lookup parsing 11 role types (vocal, instrument, performer, producer, engineer, mixer, mastering, recording engineer, composer, lyricist, arranger) into Credits/Credit @Serializable data classes with performance/production/songwriting roleCategory
- Discogs CREDITS fallback at priority 50 using discogsReleaseId from Phase 6 to fetch release extraartists, with track-level filtering and role-to-category keyword mapping covering performance/production/songwriting
- One-liner:
- Discogs master versions endpoint wired as RELEASE_EDITIONS fallback (priority 50) reading discogsMasterId from identifiers.extra, with DiscogsMasterVersion model, getMasterVersions API, and toReleaseEditions mapper
- ARTIST_TIMELINE EnrichmentType with ArtistTimeline/TimelineEvent data classes and a pure TimelineSynthesizer that produces chronologically sorted, deduplicated timeline events from life-span metadata, discography albums, and band member changes
- Composite ARTIST_TIMELINE resolution wired into DefaultEnrichmentEngine — requesting ARTIST_TIMELINE transparently auto-resolves ARTIST_DISCOGRAPHY and BAND_MEMBERS sub-types, feeds them plus identity metadata (beginDate/endDate/artistType) to TimelineSynthesizer, and returns the synthesized timeline without exposing sub-types to callers
- GenreTag @Serializable data class with confidence + sources, Metadata.genreTags field, and GenreMerger.merge() with alias normalization, deduplication, and additive confidence scoring
- RED:
- Task 1 — ProviderChain.resolveAll():
- Last.fm ALBUM_METADATA at priority 40 via album.getinfo with genre tags, and Discogs community rating (average 4.2f, have/want counts) extracted from existing release details call
- 1. [Rule 3 - Blocking] Parallel agent test compilation blocked build
- 1. [Rule 1 - Bug] Fixed ITunesProvider.exactMatch() unresolved reference

---

## v0.4.0 Provider Abstraction Overhaul (Shipped: 2026-03-21)

**Phases completed:** 5 phases, 15 plans, 31 tasks

**Key accomplishments:**

- Fix MusicBrainz empty-result misclassification to NotFound, Last.fm HTTP-to-HTTPS, and TRACK_POPULARITY removal with 6 new TDD tests
- Fixed LRCLIB duration truncation (Int to Double) and Wikidata preferred-rank P18 claim selection, verified by 5 new TDD tests
- IdentifierRequirement enum with 6 typed values replacing boolean requiresIdentifier, plus isIdentityProvider flag and data-driven needsIdentityResolution
- Removed IdentifierResolution sealed subclass from public API; MusicBrainzProvider returns Metadata directly with resolved IDs on Success.resolvedIdentifiers, engine uses provider.resolveIdentity() for the identity resolution pathway
- Extracted 11 mapper objects isolating DTO-to-EnrichmentData mapping from provider logic, plus ApiKeyConfig with Builder.withDefaultProviders() for one-line engine setup
- TTL moved into EnrichmentType enum entries with config overrides, extensible extra identifier map on EnrichmentIdentifiers, SimilarArtist/PopularTrack migrated to use EnrichmentIdentifiers
- ErrorKind enum on EnrichmentResult.Error and HttpResult sealed class with typed HTTP responses via fetchJsonResult()
- 6 new EnrichmentType enum values, 5 sealed subclasses, 6 supporting data classes, ArtworkSize with sizes field on Artwork, all with serialization round-trip tests
- MusicBrainz provider expanded from 5 to 9 capabilities with band members from artist-rels, discography from browse endpoint, tracklist from media array, and artist links from url-rels
- Deezer discography/tracks, Last.fm similar tracks, and Fanart.tv banner capabilities with search-then-fetch pattern and 7 new unit tests
- All 4 artwork providers emit sizes, Wikidata returns expanded properties (birth/death/country/occupation), Discogs returns band members via artist endpoint
- ALBUM_ART_BACK and ALBUM_BOOKLET enrichment types backed by Cover Art Archive JSON metadata endpoint with image type filtering
- Last.fm TRACK_POPULARITY restored via track.getInfo with track-level playcount/listeners; ListenBrainz batch POST endpoints for recording and artist popularity with top-recordings fallback
- ALBUM_METADATA type served by Deezer (priority 50), iTunes (30), Discogs (40) mining previously ignored search fields; Wikipedia ARTIST_PHOTO via page media-list as supplemental source (priority 30)
- ConfidenceCalculator utility with 4 semantic scoring methods replacing hardcoded floats across all 11 providers

---
