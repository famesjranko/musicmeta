# Phase 11: Provider Coverage Expansion - Context

**Gathered:** 2026-03-22
**Status:** Ready for planning
**Source:** PRD v0.5.0 Phase 5 + Smart Discuss (autonomous)

<domain>
## Phase Boundary

Expand provider coverage by adding high-value, low-effort endpoints that deepen existing enrichment types with additional providers. No new enrichment types — purely more sources for existing types. Target: fill gaps where only one provider covered a type.

</domain>

<decisions>
## Implementation Decisions

### PROV-01: Last.fm Album Info (ALBUM_METADATA at priority 40)
- New API: getAlbumInfo(album, artist) — album.getinfo method
- Returns wiki summary, playcount, listeners, tags, tracks
- New model: LastFmAlbumInfo
- New mapper: LastFmMapper.toAlbumMetadata(info) — maps playcount, tags to genres, wiki to summary
- Priority 40 (between Deezer 50 and iTunes 30)

### PROV-02: iTunes Lookup Endpoints (ALBUM_TRACKS + ARTIST_DISCOGRAPHY at priority 30)
- New API: lookupAlbumTracks(collectionId) — GET /lookup?id={collectionId}&entity=song
- New API: lookupArtistAlbums(artistId) — GET /lookup?id={artistId}&entity=album
- Store collectionId and artistId from search results in identifiers.extra
- New model: ITunesTrackResult
- New mappers: ITunesMapper.toTracklist(), ITunesMapper.toDiscography()
- Both at priority 30 — fallback behind MusicBrainz (100) and Deezer (50)

### PROV-03: Fanart.tv Album-Specific Art Endpoint
- New API: getAlbumImages(releaseGroupMbid) — GET /v3/music/albums/{mbid}
- Returns albumcover[] and cdart[] without needing full artist response
- Modify FanartTvProvider ALBUM_ART enrichment to try album endpoint first, fall back to artist endpoint
- Faster and more targeted than current artist-based scan

### PROV-04: ListenBrainz Similar Artists (SIMILAR_ARTISTS at priority 50)
- New API: getSimilarArtists(artistMbid, count) — GET /1/explore/lb-radio/artist/{mbid}/similar
- New model: ListenBrainzSimilarArtist(artistMbid, artistName, score)
- New mapper: ListenBrainzMapper.toSimilarArtists()
- Priority 50 — fallback behind Last.fm (100). Works without API key.

### PROV-05: Discogs Deeper Release Data (from Phase 7 endpoint)
- Phase 7 already added getReleaseDetails() for credits — same response has:
  - community.rating.average, community.rating.count
  - community.have, community.want
- Extend DiscogsMapper.toAlbumMetadata() to extract community data
- No additional API calls — piggybacks on credits endpoint

### Claude's Discretion
- Internal implementation details
- Test fixture JSON structure
- Whether to split across multiple plans or combine smaller items

</decisions>

<code_context>
## Existing Code Insights

### Reusable Assets
- All provider Api/Models/Mapper/Provider structures established
- HttpResult pattern from Phase 6
- identifiers.extra storage pattern from Phase 6 (Discogs IDs)
- FakeHttpClient for testing
- ConfidenceCalculator for scores

### Established Patterns
- Search-then-fetch (Deezer, iTunes): store ID from search, use in lookup
- Priority chains: primary at 100, secondary at 50, tertiary at 30
- Mapper objects with pure functions

### Integration Points
- LastFmApi/Models/Mapper/Provider — new album.getinfo endpoint
- ITunesApi/Models/Mapper/Provider — new lookup endpoints, store IDs from search
- FanartTvApi/Models/Mapper/Provider — new album endpoint
- ListenBrainzApi/Models/Mapper/Provider — new similar artists endpoint
- DiscogsMapper — extend existing toAlbumMetadata with community data

</code_context>

<specifics>
## Specific Ideas

PROV-05 is nearly free — the API call is already made in Phase 7. Just extract more fields from the response. Should be combined with another small item to reduce plan overhead.

</specifics>

<deferred>
## Deferred Ideas

- Last.fm artist.gettoptracks for enhanced ARTIST_POPULARITY
- Discogs images[] and videos[] from release details
- Discogs release notes as album description

</deferred>
