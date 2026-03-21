# Phase 9: Artist Timeline - Context

**Gathered:** 2026-03-22
**Status:** Ready for planning
**Source:** PRD v0.5.0 Phase 3 + Smart Discuss (autonomous)

<domain>
## Phase Boundary

New ARTIST_TIMELINE composite enrichment type that synthesizes life-span, discography, and band member changes into a chronological timeline. Introduces the composite enrichment concept to the engine — types that depend on other enrichment types being resolved first.

</domain>

<decisions>
## Implementation Decisions

### Data Model
- ArtistTimeline and TimelineEvent data classes as top-level @Serializable types in EnrichmentData.kt
- TimelineEvent fields: date (String — "1985", "1985-04", "1985-04-22"), type (String), description (String), relatedEntity (nullable String), identifiers (EnrichmentIdentifiers)
- Event types: "formed", "first_album", "member_joined", "member_left", "album_release", "hiatus_start", "hiatus_end", "disbanded", "born", "died"
- ARTIST_TIMELINE TTL: 30 days (30L * 24 * 60 * 60 * 1000)
- ForArtist requests only

### Composite Enrichment (PRD Approach A — Engine-level)
- New engine concept: composite types that depend on sub-type resolution before synthesis
- ARTIST_TIMELINE depends on: ARTIST_DISCOGRAPHY, BAND_MEMBERS (already existing types)
- Engine resolves dependencies first, then calls synthesis function
- TimelineSynthesizer.kt — new file in engine/ package
- Synthesizer takes: EnrichmentRequest + resolved sub-results → produces ArtistTimeline

### Timeline Synthesis Logic
1. Extract dates from identity resolution metadata (MusicBrainz life-span begin/end from existing artist lookup)
2. Extract album release years from ARTIST_DISCOGRAPHY results (DiscographyAlbum.year)
3. Extract member join/leave dates from BAND_MEMBERS results (BandMember.activePeriod if available)
4. Sort all events chronologically by date string
5. Deduplicate overlapping events (same date + same type → keep first)
6. Mark first album as "first_album" type, rest as "album_release"

### Engine Integration
- DefaultEnrichmentEngine needs to detect composite types and resolve sub-types first
- Could use a simple check: if type is ARTIST_TIMELINE → resolve ARTIST_DISCOGRAPHY + BAND_MEMBERS first, then synthesize
- No need for a generic CompositeType registry for v0.5.0 — just handle ARTIST_TIMELINE specifically
- Keep it simple: resolve sub-types via existing enrichment pipeline, collect results, pass to synthesizer

### Claude's Discretion
- How to wire composite resolution into DefaultEnrichmentEngine (inline vs separate method)
- BandMember date parsing specifics (MusicBrainz may or may not have member dates)
- Graceful degradation when sub-types return NotFound (timeline with fewer event categories)
- Life-span data access — may need to expose begin/end dates from MusicBrainz identity resolution

</decisions>

<code_context>
## Existing Code Insights

### Reusable Assets
- DefaultEnrichmentEngine.resolveTypes() — existing fan-out concurrency for type resolution
- DiscographyAlbum data class — has title, year fields for album_release events
- BandMember data class — has name, may have date info from MusicBrainz artist-rels
- MusicBrainz identity resolution — already fetches artist life-span (begin/end dates)
- EnrichmentRequest.ForArtist — has title (artist name)

### Established Patterns
- Engine resolves types concurrently via coroutineScope { async {} }
- Provider chains try providers in order, Success short-circuits
- EnrichmentResult.Success carries data + resolvedIdentifiers

### Integration Points
- EnrichmentType.kt — add ARTIST_TIMELINE enum value
- EnrichmentData.kt — add ArtistTimeline, TimelineEvent data classes
- DefaultEnrichmentEngine — modify to handle composite types
- New: engine/TimelineSynthesizer.kt

</code_context>

<specifics>
## Specific Ideas

PRD recommends Approach A (engine-level composite) over Approach B (provider-level):
- Reuses existing enrichment data from sub-types
- No duplicate API calls
- Timeline is a synthesis function, not a provider

Life-span data may need to be extracted from MusicBrainz identity resolution results. The identity resolution already calls artist lookup which returns life-span.begin/end — this data may need to be made accessible to the synthesizer.

</specifics>

<deferred>
## Deferred Ideas

- Generic CompositeType registry (for now, just handle ARTIST_TIMELINE specifically)
- Wikidata dates (P569/P570) as supplemental date source
- Hiatus detection from MusicBrainz annotations

</deferred>
