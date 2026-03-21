# Phase 5: Deepening - Context

**Gathered:** 2026-03-21
**Status:** Ready for planning

<domain>
## Phase Boundary

Deepen existing enrichment types: add back cover art, mine more album metadata from existing providers, fix track-level popularity, add Wikipedia page media for artist photos, wire ListenBrainz batch endpoints, and standardize confidence scoring across all providers.

</domain>

<decisions>
## Implementation Decisions

### Claude's Discretion
All implementation choices are at Claude's discretion — infrastructure phase deepening existing capabilities.

</decisions>

<code_context>
## Existing Code Insights

### Reusable Assets
- Mapper pattern — all new mapping goes through *Mapper.kt files
- `HttpResult` sealed class — new endpoints should use fetchJsonResult()
- `EnrichmentIdentifiers.extra` — can store provider-specific IDs for batch lookups
- `ArtworkSize` type — already used by CAA, Deezer, iTunes, Fanart.tv
- Phase 4 added new API endpoints to MusicBrainz, Deezer, Last.fm, Discogs

### Established Patterns
- Provider adds capability → mapper handles DTO-to-EnrichmentData → provider orchestrates
- TDD: red test first, then green implementation
- Tests use FakeHttpClient with canned JSON responses

### Integration Points
- `EnrichmentType.kt` — add ALBUM_ART_BACK, ALBUM_BOOKLET (if needed), ALBUM_METADATA
- `CoverArtArchiveApi/Mapper` — use JSON metadata endpoint for back/booklet art
- `DeezerMapper`, `ITunesMapper`, `DiscogsMapper` — extract more album metadata
- `LastFmApi/Provider` — add track.getInfo for track-level popularity
- `ListenBrainzApi/Provider` — batch POST endpoints
- `WikipediaApi/Provider` — page media-list endpoint
- All 11 providers — standardize confidence via ConfidenceCalculator

</code_context>

<specifics>
## Specific Ideas

PRD specifies exact endpoints and data fields — see docs/PRD.md Phase 4 section.

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope.

</deferred>
