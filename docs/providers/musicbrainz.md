# MusicBrainz Provider

> The identity backbone of the enrichment engine. Resolves MBIDs, Wikidata IDs, and Wikipedia titles that downstream providers use for precise lookups.

## API Overview

| | |
|---|---|
| **Base URL** | `https://musicbrainz.org/ws/2` |
| **Auth** | None — but a descriptive `User-Agent` is **required** |
| **Rate Limit** | 1 request/second (enforced server-side; we use 1100ms) |
| **Format** | JSON (`?fmt=json`) |
| **Query Syntax** | Lucene (special chars must be escaped) |
| **Reference Docs** | https://musicbrainz.org/doc/MusicBrainz_API |
| **Search Docs** | https://musicbrainz.org/doc/MusicBrainz_API/Search |
| **API Key Required** | No |

## User-Agent Requirement

MusicBrainz will block requests without a descriptive User-Agent. Format:

```
AppName/Version (contact-url-or-email)
```

Example: `MusicMetaShowcase/1.0 (https://github.com/famesjranko/musicmeta)`

This is set via `DefaultHttpClient(userAgent)` and applies to all providers, but MusicBrainz is the one that enforces it.

## Endpoints We Use

### Search: Release
```
GET /ws/2/release?query={lucene}&fmt=json&limit={n}
```
Lucene query: `release:"OK Computer" AND artistname:"Radiohead"`

Returns: `releases[]` — each with id, title, artist-credit, date, country, barcode, tags, label-info, release-group (id, primary-type), cover-art-archive.front, disambiguation, score.

### Search: Artist
```
GET /ws/2/artist?query={lucene}&fmt=json&limit={n}
```
Lucene query: `artist:"Radiohead"`

Returns: `artists[]` — each with id, name, type, country, life-span, tags, disambiguation, score. **No relations in search** — need lookup for those.

### Search: Recording
```
GET /ws/2/recording?query={lucene}&fmt=json&limit={n}
```
Lucene query: `recording:"Bohemian Rhapsody" AND artistname:"Queen"`

Returns: `recordings[]` — each with id, title, isrcs, tags, score.

### Lookup: Release
```
GET /ws/2/release/{mbid}?fmt=json&inc=artist-credits+labels+release-groups+tags
```
Full release detail. Score is always 100 for direct lookups.

### Lookup: Artist
```
GET /ws/2/artist/{mbid}?fmt=json&inc=tags+url-rels
```
Full artist detail with URL relations (wikidata, wikipedia, etc).

## What We Extract

| Field | Source | Notes |
|-------|--------|-------|
| MBID | `id` | Primary identifier for all downstream providers |
| Release Group ID | `release-group.id` | Used by Cover Art Archive as fallback |
| Title | `title` | |
| Artist Credit | `artist-credit[].artist.name + joinphrase` | Handles "feat." and "&" cases |
| Date | `date` | Format varies: "2003", "2003-06", "2003-06-09" |
| Country | `country` | ISO 3166-1 alpha-2 |
| Barcode | `barcode` | UPC/EAN |
| Tags/Genres | `tags[].name` (sorted by `count` desc) | Falls back to `release-group.tags` |
| Label | `label-info[0].label.name` | First label only |
| Release Type | `release-group.primary-type` | "Album", "Single", "EP", etc. |
| Wikidata ID | `relations[type=wikidata].url.resource` → extract Q-ID | Lookup only (not in search) |
| Wikipedia Title | `relations[type=wikipedia].url.resource` → extract title | Lookup only |
| Has Front Cover | `cover-art-archive.front` | Boolean — avoids 404s on CAA |
| Artist Type | `type` | "Person", "Group", "Orchestra", "Choir", etc. |
| Life Span | `life-span.begin`, `life-span.end` | Band formation/dissolution or birth/death |
| Disambiguation | `disambiguation` | Distinguishes same-name entities |

## What We DON'T Extract (Available Data)

### From Current Responses (just ignored)

| Field | Where | Useful For |
|-------|-------|------------|
| `media[]` (tracklist) | Release lookup | ALBUM_TRACKS — track titles, positions, durations, ISRCs |
| `media[].format` | Release lookup | Vinyl, CD, Digital, etc. |
| `relations[]` (non-wiki) | Artist lookup | All URL relations: official site, bandcamp, spotify, youtube, twitter, etc. |
| `status` | Release | "Official", "Promotion", "Bootleg" |
| `packaging` | Release | "Jewel Case", "Digipak", etc. |
| `text-representation.language` | Release | Album language |
| `artist.gender` | Artist lookup | Male/Female/Non-binary/Other |
| `artist.area` | Artist lookup | More specific than country |
| `recording.length` | Recording search | Track duration in ms |
| `recording.first-release-date` | Recording | When track first appeared |

### From Endpoints Not Yet Called

| Endpoint | Data | Useful For |
|----------|------|------------|
| `GET /release-group?artist={mbid}&type=album` | All release groups by artist | ARTIST_DISCOGRAPHY |
| `GET /artist/{mbid}?inc=artist-rels` | Band member relationships | BAND_MEMBERS |
| `GET /release/{mbid}?inc=recording-level-rels+artist-rels` | Per-track credits | CREDITS |
| `GET /recording/{mbid}?inc=artist-rels+work-rels` | Composer, lyricist, performer | CREDITS |
| `GET /work/{id}?inc=artist-rels` | Songwriting credits | CREDITS |

### Relationship Types Available

When `inc=artist-rels` is added, the `relations[]` array contains:
- `member of band` — who is/was in the group (with time periods)
- `collaboration` — joint projects
- `is person` — real name behind stage name
- `supporting musician` — live/session musicians
- `vocal`, `instrument`, `performer` — recording credits

When `inc=url-rels` is added (already used):
- `wikidata`, `wikipedia` — **currently extracted**
- `official homepage`, `bandcamp`, `soundcloud`, `youtube`, `social network`, `streaming`, `discogs`, `allmusic`, `setlist.fm`, `songkick` — **not extracted**

## Gotchas & Edge Cases

- **Rate limiting is strict**: 1 req/sec. Exceeding it returns 503. Our `RateLimiter(1100)` adds buffer.
- **Lucene special chars**: Characters `+ - & | ! ( ) { } [ ] ^ " ~ * ? : \ /` must be escaped. `MusicBrainzApi.escapeLucene()` handles this. Watch for artist names like "AC/DC", "Guns N' Roses", "!!!".
- **Search vs Lookup**: Search results include metadata (tags, labels) but **not** URL relations. Lookups include relations but cost an extra request. The provider only does lookups when the requested type needs relations (ARTIST_PHOTO, ARTIST_BIO, etc.).
- **Score interpretation**: Search scores are 0–100. We map directly to confidence (score/100). `minMatchScore` (default 80) filters poor matches.
- **Artist credit join phrases**: "The Beatles" is simple, but "Eminem feat. Rihanna" has a joinphrase " feat. ". The parser concatenates `artist.name + joinphrase` for each credit.
- **Tags on release-groups, not releases**: Genre tags are primarily on release-groups in MusicBrainz. The parser falls back: `release.tags` → `release-group.tags`.
- **Empty results ≠ not found**: If the search returns an empty array, it might be a rate limit (MusicBrainz returns 200 with empty results when throttled). We treat empty results as `RateLimited`.
- **Date format varies**: Can be "2003", "2003-06", or "2003-06-09" — consumers should handle all three.

## Internal Architecture

```
MusicBrainzProvider
├── MusicBrainzApi          — HTTP calls + rate limiting
├── MusicBrainzParser       — JSON → DTOs (extractors for tags, labels, relations, etc.)
└── MusicBrainzModels       — DTOs: MusicBrainzRelease, MusicBrainzArtist, MusicBrainzRecording
```

Constructor params:
- `httpClient: HttpClient` — shared HTTP client
- `rateLimiter: RateLimiter` — should be 1100ms for MusicBrainz
- `minMatchScore: Int = 80` — minimum search score to accept
- `thumbnailSize: Int = 250` — CAA thumbnail size for search candidates
