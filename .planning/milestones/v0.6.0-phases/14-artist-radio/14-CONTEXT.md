# Phase 14: Artist Radio - Context

**Gathered:** 2026-03-23
**Status:** Ready for planning

<domain>
## Phase Boundary

Add new `ARTIST_RADIO` EnrichmentType backed by Deezer `/artist/{id}/radio` endpoint. Returns an ordered playlist of tracks (no match scores — this is a curated mix, not similarity-ranked results). ForArtist requests only. 7-day TTL.

</domain>

<decisions>
## Implementation Decisions

### Type & Data Model
- New `EnrichmentType.ARTIST_RADIO` with 7-day TTL (radio is dynamic content)
- New `EnrichmentData.RadioPlaylist(tracks: List<RadioTrack>)` sealed subclass
- New `RadioTrack(title, artist, album?, durationMs?, identifiers)` — no matchScore (radio tracks are ordered, not ranked by similarity)
- Both classes need `@Serializable` annotation for cache round-trip

### Provider Implementation
- Deezer `/artist/{id}/radio` returns ~25 tracks ordered by Deezer's internal radio algorithm
- Same Deezer artist ID resolution pattern as Phase 13: check `identifiers.extra["deezerId"]` first, fall back to `searchArtist + ArtistMatcher.isMatch()`
- Capability at priority 100 (only provider for this type)
- ForArtist requests only — return NotFound for ForAlbum/ForTrack
- Confidence via `ConfidenceCalculator.fuzzyMatch(true)` = 0.8

### Claude's Discretion
- Whether to reuse Phase 13's Deezer artist ID resolution code or extract a shared helper
- RadioTrack field mapping from Deezer response JSON
- Test fixture structure

</decisions>

<code_context>
## Existing Code Insights

### Reusable Assets
- `DeezerProvider.enrichSimilarArtists()` (Phase 13) — same searchArtist + ArtistMatcher pattern for resolving Deezer artist ID
- `DeezerApi.getRelatedArtists()` — reference for adding another Deezer API method
- `DeezerMapper.toSimilarArtists()` — reference for adding another mapper method

### Established Patterns
- Provider additions: add model → API method → mapper → capability + enrich dispatch
- EnrichmentType enum values include TTL as first constructor param
- `@Serializable` on all EnrichmentData subclasses and supporting data classes

### Integration Points
- `EnrichmentType.kt` — add `ARTIST_RADIO(7 * 24 * 60 * 60 * 1000L)`
- `EnrichmentData.kt` — add RadioPlaylist + RadioTrack sealed subclasses
- `DeezerApi.kt` — add `getArtistRadio(artistId: Long, limit: Int = 25)`
- `DeezerModels.kt` — add `DeezerRadioTrack` DTO
- `DeezerMapper.kt` — add `toRadioPlaylist()`
- `DeezerProvider.kt` — add ARTIST_RADIO capability + `enrichArtistRadio()` dispatch

</code_context>

<specifics>
## Specific Ideas

- Reference plan specifies ARTIST_RADIO is semantically distinct from SIMILAR_TRACKS: radio is artist-seeded (not track-seeded), no match scores, output is a playlist not a similarity ranking
- Deezer radio response shape: `{"data": [{"id": 123, "title": "Creep", "artist": {"name": "Radiohead"}, "album": {"title": "Pablo Honey"}, "duration": 238}]}`

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope.

</deferred>
