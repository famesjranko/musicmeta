# Phase 16: Genre Discovery - Context

**Gathered:** 2026-03-23
**Status:** Ready for planning

<domain>
## Phase Boundary

Add new `GENRE_DISCOVERY` composite EnrichmentType that finds related genres for any entity with genre data. Uses a static genre affinity taxonomy (~60-80 relationships) and the CompositeSynthesizer interface from Phase 12. Depends on GENRE as its sub-type.

</domain>

<decisions>
## Implementation Decisions

### Architecture — Composite Type via CompositeSynthesizer
- GENRE_DISCOVERY is a composite type — depends on GENRE as sub-type
- Implements `CompositeSynthesizer` interface (Phase 12) — `type = GENRE_DISCOVERY`, `dependencies = setOf(GENRE)`
- `GenreAffinityMatcher` as stateless object implementing CompositeSynthesizer (same pattern as TimelineSynthesizer)
- Register via `Builder.addSynthesizer()` (Phase 12 infrastructure)

### Static Genre Taxonomy
- ~60-80 genre relationships covering parent, child, and sibling types
- Relationship types: "parent" (rock → alternative rock), "child" (alternative rock → rock), "sibling" (post-rock ↔ math rock)
- Relationship weights: sibling=0.9, child=0.8, parent=0.7 — siblings are most actionable for discovery
- Input: GenreTag list from GENRE resolution (with confidence scores)
- Output scoring: `affinity = inputConfidence × relationshipWeight`
- Sorted by affinity descending, deduplicated by name

### Data Model
- New `EnrichmentType.GENRE_DISCOVERY` with 30-day TTL
- New `EnrichmentData.GenreDiscovery(relatedGenres: List<GenreAffinity>)` sealed subclass
- New `GenreAffinity(name, affinity, relationship, sourceGenres)` — @Serializable
- `sourceGenres` tracks which input genres triggered each result

### Claude's Discretion
- The specific genres and relationships in the static taxonomy (aim for major genres: rock, pop, hip-hop, electronic, jazz, metal, etc. plus their subgenres)
- Name normalization approach (leverage GenreMerger.normalize() for consistency)
- How to handle input genres not in the taxonomy (skip gracefully)
- Test coverage approach for taxonomy completeness

</decisions>

<code_context>
## Existing Code Insights

### Reusable Assets
- `CompositeSynthesizer` interface (Phase 12) — `type`, `dependencies`, `synthesize(resolved, identityResult, request)`
- `TimelineSynthesizer` — reference implementation of CompositeSynthesizer
- `GenreMerger.normalize()` — internal function for genre name normalization (lowercase, trim, alias mapping)
- `GenreTag` data class — has `name`, `confidence`, `sources` fields
- `EnrichmentData.Metadata.genreTags` — where GENRE results live

### Established Patterns
- Composite types extract sub-type results via `(resolved[subType] as? Success)?.data as? ExpectedType`
- Synthesizer returns `EnrichmentResult.Success` with synthesizer name as provider
- `COMPOSITE_DEPENDENCIES` discovered from registered synthesizers' `dependencies` property

### Integration Points
- `EnrichmentType.kt` — add GENRE_DISCOVERY
- `EnrichmentData.kt` — add GenreDiscovery + GenreAffinity
- New file: `engine/GenreAffinityMatcher.kt` (implements CompositeSynthesizer)
- `EnrichmentEngine.kt` (Builder) — register via addSynthesizer()

</code_context>

<specifics>
## Specific Ideas

- Reference plan suggests ~60-80 relationships across parent/child/sibling
- Major genre families to cover: rock, pop, hip-hop/rap, electronic, jazz, metal, folk, country, classical, r&b/soul, punk, blues
- Use GenreMerger.normalize() to match input tags against taxonomy keys for consistency

</specifics>

<deferred>
## Deferred Ideas

- Dynamic genre taxonomy from Last.fm tag.getsimilartags — undocumented endpoint, defer to v0.6.x
- Genre hierarchy (parent/child tree navigation) — deferred per PROJECT.md

</deferred>
