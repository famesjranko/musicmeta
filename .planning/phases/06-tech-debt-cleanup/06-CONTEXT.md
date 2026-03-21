# Phase 6: Tech Debt Cleanup - Context

**Gathered:** 2026-03-22
**Status:** Ready for planning

<domain>
## Phase Boundary

Migrate all 11 provider API classes from legacy nullable fetchJson()/fetchJsonArray() to HttpResult-returning fetchJsonResult(). Wire ErrorKind categorization on all provider Error results. Wire ListenBrainz ARTIST_DISCOGRAPHY capability from existing plumbing. Store Discogs release ID and master ID from search results.

</domain>

<decisions>
## Implementation Decisions

### Claude's Discretion
All implementation choices are at Claude's discretion — pure infrastructure phase.

Key guidance from PRD:
- HttpResult migration follows mechanical pattern: replace `httpClient.fetchJson(url) ?: return null` with `when (val result = httpClient.fetchJsonResult(url))` across 27 call sites
- ErrorKind mapping: NetworkError → NETWORK, RateLimited → RATE_LIMIT, ClientError(401/403) → AUTH, JSONException → PARSE
- ListenBrainz wiring: API (getTopReleaseGroupsForArtist) and mapper (toDiscography) already exist, just add ProviderCapability and route in enrich()
- Discogs IDs: add 2 fields to DiscogsRelease model, parse 2 extra JSON fields from search results

</decisions>

<code_context>
## Existing Code Insights

### Reusable Assets
- HttpResult sealed class already defined with Ok/ClientError/ServerError/RateLimited/NetworkError subtypes
- fetchJsonResult() already implemented on DefaultHttpClient
- ErrorKind enum already defined with UNKNOWN/NETWORK/RATE_LIMIT/AUTH/PARSE values
- ListenBrainz getTopReleaseGroupsForArtist() and toDiscography() mapper already implemented
- FakeHttpClient in testutil/

### Established Patterns
- All 11 providers follow Api/Models/Provider/Mapper structure
- Provider mapper pattern isolates DTO-to-EnrichmentData mapping
- ConfidenceCalculator used for all confidence scoring

### Integration Points
- All *Api.kt files (11 total) for HttpResult migration
- All *Provider.kt files (11 total) for ErrorKind adoption
- ListenBrainzProvider.kt for capability wiring
- DiscogsModels.kt and DiscogsApi.kt for ID storage

</code_context>

<specifics>
## Specific Ideas

No specific requirements — infrastructure phase following PRD Phase 0 specification.

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope.

</deferred>
