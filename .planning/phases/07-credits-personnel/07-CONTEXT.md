# Phase 7: Credits & Personnel - Context

**Gathered:** 2026-03-22
**Status:** Ready for planning
**Source:** PRD v0.5.0 Phase 1 + Smart Discuss (autonomous)

<domain>
## Phase Boundary

New CREDITS enrichment type enabling consumers to retrieve track-level credits (performers, producers, composers, engineers) from MusicBrainz recording/work artist-rels and Discogs release extraartists. ForTrack requests only for this milestone.

</domain>

<decisions>
## Implementation Decisions

### Data Model
- Credits and Credit data classes as top-level @Serializable types in EnrichmentData.kt (matches BandMember, DiscographyAlbum pattern)
- Credit fields: name, role, roleCategory (nullable), identifiers (EnrichmentIdentifiers)
- roleCategory values: "performance", "production", "songwriting" (nullable for unmapped roles)
- CREDITS TTL: 30 days (30L * 24 * 60 * 60 * 1000)
- ForTrack only — ForAlbum aggregation deferred to future

### MusicBrainz Credits
- New API: lookupRecording(mbid) with inc=artist-rels+work-rels
- Parse artist-rels for: vocal, instrument, performer, producer, engineer, mixer, mastering, recording
- Parse work-rels for: composer, lyricist, arranger (songwriting credits)
- Follow parseBandMembers() pattern in MusicBrainzParser
- Recording MBID from identity resolution (existing pattern)
- Priority 100, identifierRequirement = MUSICBRAINZ_ID
- New parser: parseRecordingCredits(json) → List<MusicBrainzCredit>
- New mapper: MusicBrainzMapper.toCredits()

### Discogs Credits
- New API: getReleaseDetails(releaseId) returning extraartists
- Requires discogsReleaseId from Phase 6 DEBT-04 (stored in identifiers.extra)
- Role string → roleCategory via keyword-based lookup map
- Priority 50, fallback behind MusicBrainz
- Filter tracklist[].extraartists by matching track title for ForTrack requests

### Claude's Discretion
- Internal implementation details for parser/mapper structure
- Test fixture JSON structure
- Error handling specifics within the established mapError() pattern

</decisions>

<code_context>
## Existing Code Insights

### Reusable Assets
- MusicBrainzParser.parseBandMembers() — identical pattern for parsing artist-rels
- MusicBrainzMapper — established mapper object pattern
- DiscogsApi.searchRelease() — existing search, now stores releaseId from Phase 6
- ConfidenceCalculator — for confidence scoring
- FakeHttpClient with givenIoException() — for error tests

### Established Patterns
- Provider: Api/Models/Parser/Mapper/Provider structure
- New types: add to EnrichmentType enum, sealed subclass in EnrichmentData
- Serialization: @Serializable on all data classes, round-trip tests
- TDD: behavior-first tests using FakeProvider/FakeHttpClient

### Integration Points
- EnrichmentType.kt — add CREDITS enum value
- EnrichmentData.kt — add Credits, Credit data classes
- MusicBrainzApi/Parser/Mapper/Provider — new recording endpoint + credits capability
- DiscogsApi/Models/Mapper/Provider — new release details endpoint + credits capability
- IdentifierRequirement — may need DISCOGS_RELEASE_ID if not using extra map

</code_context>

<specifics>
## Specific Ideas

PRD Phase 1 specifies exact MusicBrainz relationship types to parse:
- vocal → attributes[0] (e.g., "lead vocals") → performance
- instrument → attributes[0] (e.g., "guitar") → performance
- performer → "performer" → performance
- producer → "producer" → production
- engineer → "engineer" → production
- mixer → "mixer" → production
- mastering → "mastering" → production
- recording → "recording engineer" → production
- composer → "composer" (work-rels) → songwriting
- lyricist → "lyricist" (work-rels) → songwriting
- arranger → "arranger" → songwriting

</specifics>

<deferred>
## Deferred Ideas

- ForAlbum credits aggregation via MusicBrainz recording-level-rels endpoint
- Discogs company credits (labels, distributors from companies[] array)

</deferred>
