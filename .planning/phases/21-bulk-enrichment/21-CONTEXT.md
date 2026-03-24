# Phase 21: Bulk Enrichment - Context

**Gathered:** 2026-03-24
**Status:** Ready for planning

<domain>
## Phase Boundary

Add `enrichBatch()` to `EnrichmentEngine` interface with default implementation returning `Flow<Pair<EnrichmentRequest, EnrichmentResults>>`. Sequential iteration — each request enriched one at a time. Cache hits return instantly. Flow cancellation via cooperative cancellation stops processing remaining requests. ~20 lines of implementation.

</domain>

<decisions>
## Implementation Decisions

### Claude's Discretion

All implementation choices are at Claude's discretion — pure infrastructure phase.

Key constraints from research and docs/v0.8.0.md:
- Add `enrichBatch()` as a default method on `EnrichmentEngine` interface (custom implementations inherit it)
- Default implementation uses `flow { for (request in requests) { emit(request to enrich(request, types, forceRefresh)) } }`
- Flow is in `kotlinx-coroutines-core` (already a dependency) — no new dep needed
- Sequential iteration is deliberate — rate limiter naturally throttles, cache hits return fast
- `DefaultEnrichmentEngine` gets explicit override (same logic, allows future optimization without interface change)
- `forceRefresh: Boolean = false` parameter on enrichBatch
- Tests use Turbine (already in test bundle) for Flow testing
- Test cases: normal batch, empty list, cancellation (take(1)), forceRefresh propagation

</decisions>

<code_context>
## Existing Code Insights

### Reusable Assets
- `EnrichmentEngine` interface at `musicmeta-core/.../EnrichmentEngine.kt`
- `DefaultEnrichmentEngine` at `musicmeta-core/.../engine/DefaultEnrichmentEngine.kt`
- `EnrichmentResults` wrapper (v0.7.0) — returned by `enrich()`
- Turbine 1.2.0 already in test bundle for Flow testing

### Established Patterns
- Default method implementations on interfaces for backward compatibility
- `EnrichmentRequest` sealed class: `ForAlbum`, `ForArtist`, `ForTrack`
- `EnrichmentResults` wraps `Map<EnrichmentType, EnrichmentResult>` with named accessors
- Test names use backtick style with Given-When-Then comments

### Integration Points
- `EnrichmentEngine` interface — add enrichBatch() with default
- `DefaultEnrichmentEngine` — explicit override
- Flow import from `kotlinx.coroutines.flow`

</code_context>

<specifics>
## Specific Ideas

No specific requirements — infrastructure phase. Follow docs/v0.8.0.md Phase 3 section.

</specifics>

<deferred>
## Deferred Ideas

- Real pipelining (concurrent identity resolution + downstream fan-out overlap) — optimize when proven bottleneck
- Progress callbacks beyond Flow emission counting

</deferred>
