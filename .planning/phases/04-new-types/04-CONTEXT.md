# Phase 4: New Types - Context

**Gathered:** 2026-03-21
**Status:** Ready for planning

<domain>
## Phase Boundary

Add 6 new enrichment types (BAND_MEMBERS, ARTIST_DISCOGRAPHY, ALBUM_TRACKS, SIMILAR_TRACKS, ARTIST_BANNER, ARTIST_LINKS), artwork sizes enhancement to existing Artwork type, and expanded Wikidata properties (birth/death dates, country, occupation).

</domain>

<decisions>
## Implementation Decisions

### Claude's Discretion
All implementation choices are at Claude's discretion — infrastructure phase adding new capabilities using the abstraction layer built in Phases 2-3.

</decisions>

<code_context>
## Existing Code Insights

### Reusable Assets
- Mapper pattern from Phase 2 — all new types should follow *Mapper.kt pattern
- `IdentifierRequirement` enum — new capabilities declare precise requirements
- `EnrichmentIdentifiers.extra` map — can store provider-specific IDs (deezerId, discogsArtistId)
- `HttpResult` sealed class — new API endpoints can use fetchJsonResult()
- Existing provider Api/Models/Provider/Mapper structure

### Established Patterns
- Provider adds capability → mapper handles DTO-to-EnrichmentData → provider orchestrates
- `EnrichmentType(defaultTtlMs, category)` — new types must declare both
- Tests use backtick names, Given-When-Then, FakeHttpClient, runTest

### Integration Points
- `EnrichmentType.kt` — add 6 new enum values
- `EnrichmentData.kt` — add 5 new sealed subclasses + ArtworkSize + enhance Artwork
- Each provider's Api/Mapper/Provider files for new capabilities
- E2E tests for each new type

</code_context>

<specifics>
## Specific Ideas

PRD specifies exact provider sources per type — see docs/PRD.md Phase 3 section for implementation details per provider.

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope.

</deferred>
