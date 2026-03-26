# ListenBrainz Provider

> Open-source listen tracking platform (MetaBrainz project). Provides popularity, top tracks, discography, similar artists, and radio discovery based on real user listening habits. Most endpoints require no API key; LB Radio requires a free user token.

## API Overview

| | |
|---|---|
| **Base URL** | `https://api.listenbrainz.org/1` |
| **Auth** | None for read endpoints; `Authorization: Token {token}` for LB Radio |
| **Rate Limit** | Not strictly documented; we use 100ms |
| **Format** | JSON (top-level array for popularity endpoints) |
| **Reference Docs** | https://listenbrainz.readthedocs.io/en/latest/users/api/index.html |
| **API Key Required** | No for most endpoints; yes for `ARTIST_RADIO_DISCOVERY` (free ListenBrainz account) |

## Endpoints We Use

### Top Recordings for Artist
```
GET /1/popularity/top-recordings-for-artist/{artistMbid}
```

Returns a **JSON array** (not object — note: `fetchJsonArray()` not `fetchJson()`):

```json
[
  {
    "artist_mbids": ["a74b1b7f-..."],
    "artist_name": "Radiohead",
    "recording_mbid": "b3015bab-...",
    "recording_name": "Creep",
    "total_listen_count": 234567,
    "total_user_count": 45678,
    "release_name": "Pablo Honey",
    "release_mbid": "abc123-...",
    "release_color": {"red": 120, "green": 80, "blue": 60},
    "caa_id": 12345678,
    "caa_release_mbid": "abc123-...",
    "length": 238000
  }
]
```

### LB Radio
```
GET /1/explore/lb-radio?prompt=artist:({mbid_or_name})&mode={easy|medium|hard}
Authorization: Token {listenBrainzToken}
```

`prompt` accepts either an artist MBID (`artist:(a74b1b7f-71a5-4011-9441-d0b5e4122711)`) or an artist name (`artist:(Radiohead)`) when no MBID is available. The `mode` parameter controls discovery depth: `easy` stays close to the seed artist, `hard` ventures further.

Response (JSPF playlist format):
```json
{
  "payload": {
    "jspf": {
      "playlist": {
        "track": [
          {
            "title": "Everything In Its Right Place",
            "creator": "Radiohead",
            "album": "Kid A",
            "duration": 248000,
            "identifier": ["https://musicbrainz.org/recording/{recording_mbid}"],
            "extension": {
              "https://musicbrainz.org/doc/jspf#playlist": {
                "artist_identifiers": ["https://musicbrainz.org/artist/{artist_mbid}"],
                "release_identifier": "https://musicbrainz.org/release/{release_mbid}"
              }
            }
          }
        ]
      }
    }
  }
}
```

JSPF `duration` is already in milliseconds. MBIDs are embedded in full MusicBrainz URLs — extracted via `substringAfterLast("/")`.

## What We Extract

**From top recordings (`/1/popularity/top-recordings-for-artist`):**

| Field | Source | Notes |
|-------|--------|-------|
| Track title | `recording_name` | |
| Recording MBID | `recording_mbid` | Cross-reference with MusicBrainz |
| Listen count | `total_listen_count` | Cumulative across all users |
| Rank | Array index + 1 | Implicit ordering by listen count |

**From LB Radio (`/1/explore/lb-radio`):**

| Field | Source | Notes |
|-------|--------|-------|
| Track title | `track.title` | |
| Artist name | `track.creator` | |
| Album title | `track.album` | Nullable |
| Duration | `track.duration` | Already in milliseconds |
| Recording MBID | `track.identifier[0]` | Extracted from MusicBrainz URL |
| Artist MBID | `extension…artist_identifiers[0]` | Extracted from MusicBrainz URL |
| Release MBID | `extension…release_identifier` | Extracted from MusicBrainz URL |

## What We DON'T Extract (Available Data)

### From Current Response (ignored)

| Field | Where | Useful For |
|-------|-------|------------|
| `total_user_count` | Each recording | Listener count (distinct users vs total plays) |
| `release_name` | Each recording | Album/release name (top-level string, not nested) |
| `release` | Each recording | Object with `mbid`, `name`, `color` — links to release |
| `caa_id` | Each recording | Cover Art Archive image ID |
| `caa_release_mbid` | Each recording | Release MBID for Cover Art Archive lookup |
| `length` | Each recording | Track duration in milliseconds |
| `artist_mbids[]` | Each recording | Multiple artist MBIDs (for collaborations) |

### Endpoints Not Yet Called

#### Popularity Endpoints

| Endpoint | Data | Useful For |
|----------|------|------------|
| `GET /1/popularity/top-release-groups-for-artist/{mbid}` | Top albums by listen count | HIGH VALUE — album popularity ranking |
| `POST /1/popularity/recording` | Batch lookup: send `{"recording_mbids": [...]}`, get listen count + user count per recording | TRACK_POPULARITY (batch) |
| `POST /1/popularity/artist` | Batch lookup: send `{"artist_mbids": [...]}`, get listen count + user count per artist | ARTIST_POPULARITY (batch) |
| `POST /1/popularity/release` | Batch lookup for releases | RELEASE_POPULARITY (batch) |
| `POST /1/popularity/release-group` | Batch lookup for release groups | RELEASE_GROUP_POPULARITY (batch) |

#### Statistics Endpoints

| Endpoint | Data | Useful For |
|----------|------|------------|
| `GET /1/stats/artist/{mbid}/listeners` | Top listeners for specific artist | ARTIST_POPULARITY |
| `GET /1/stats/release-group/{mbid}/listeners` | Top listeners for specific release group | RELEASE_GROUP_POPULARITY |
| `GET /1/stats/sitewide/artists` | Global top artists (params: `count`, `offset`, `range`) | Rankings |
| `GET /1/stats/sitewide/recordings` | Global top recordings | Rankings |
| `GET /1/stats/sitewide/releases` | Global top releases | Rankings |
| `GET /1/stats/sitewide/release-groups` | Global top release groups | Rankings |

Range parameter values: `this_week`, `this_month`, `this_year`, `week`, `month`, `quarter`, `year`, `half_yearly`, `all_time`.

#### Recommendation Endpoints (future potential)

| Endpoint | Data | Useful For |
|----------|------|------------|
| `GET /1/cf/recommendation/user/{user}/recording` | Collaborative filtering recommendations | Similar tracks |
| `GET /1/explore/similar-artists/{mbid}` | Similar artists by listening patterns | SIMILAR_ARTISTS (alternative to Last.fm) |
| `GET /1/explore/fresh-releases` | Recent notable releases | Discovery |

## Gotchas & Edge Cases

- **Requires artist MBID**: Top recordings endpoint requires MBID — no text search. Depends entirely on MusicBrainz identity resolution.
- **LB Radio requires auth**: `ARTIST_RADIO_DISCOVERY` is silently absent when `ApiKeyConfig.listenBrainzToken` is not set. Other ListenBrainz endpoints (top recordings, similar artists, discography) continue working without any token. The token is free — create a ListenBrainz account at https://listenbrainz.org.
- **JSON array response**: Unlike most APIs, the popularity endpoint returns a bare JSON array, not `{ "data": [...] }`. Our code uses `httpClient.fetchJsonArray()` for this.
- **Smaller user base than Last.fm**: ListenBrainz has fewer users, so listen counts are lower. But it's growing and is fully open-source.
- **Data freshness**: Stats are updated periodically, not real-time. May not reflect very recent releases.
- **No rate limit headers**: ListenBrainz doesn't document strict rate limits for read endpoints, but be respectful. 100ms between requests is safe.
- **Empty array = no data**: Returns `[]` for unknown artists, not 404. We treat empty as `NotFound`.
- **Open source**: ListenBrainz is part of the MetaBrainz ecosystem (same org as MusicBrainz). Data is CC0 licensed.
- **Recording MBIDs**: The recording_mbid values can be used to look up full track details in MusicBrainz.
- **Batch POST endpoints**: The popularity POST endpoints accept arrays of MBIDs and return results in the same order. Up to MAX_ITEMS_PER_GET items per request. Null values in the response indicate the entity was not found.

## Internal Architecture

```
ListenBrainzProvider
├── ListenBrainzApi       — top recordings + LB Radio (auth-gated) + parsing
└── ListenBrainzModels    — DTOs: ListenBrainzPopularTrack, ListenBrainzRadioTrack
```

Constructor params:
- `httpClient: HttpClient`
- `rateLimiter: RateLimiter` — 100ms is fine
- `authToken: String?` — optional; enables `ARTIST_RADIO_DISCOVERY` when present
