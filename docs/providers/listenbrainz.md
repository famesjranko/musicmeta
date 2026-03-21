# ListenBrainz Provider

> Open-source listen tracking platform (MetaBrainz project). Provides popularity data based on real user listening habits. No API key required.

## API Overview

| | |
|---|---|
| **Base URL** | `https://api.listenbrainz.org/1` |
| **Auth** | None for read endpoints |
| **Rate Limit** | Not strictly documented; we use 100ms |
| **Format** | JSON (top-level array for popularity endpoints) |
| **Reference Docs** | https://listenbrainz.readthedocs.io/en/latest/users/api/index.html |
| **API Key Required** | No |

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
    "track_name": "Creep",
    "total_listen_count": 234567,
    "total_user_count": 45678
  }
]
```

## What We Extract

| Field | Source | Notes |
|-------|--------|-------|
| Track title | `track_name` | |
| Recording MBID | `recording_mbid` | Cross-reference with MusicBrainz |
| Listen count | `total_listen_count` | Cumulative across all users |
| Rank | Array index + 1 | Implicit ordering by listen count |

## What We DON'T Extract (Available Data)

### From Current Response (ignored)

| Field | Where | Useful For |
|-------|-------|------------|
| `total_user_count` | Each recording | Listener count (distinct users vs total plays) |
| `artist_mbids[]` | Each recording | Multiple artist MBIDs (for collaborations) |

### Endpoints Not Yet Called

| Endpoint | Data | Useful For |
|----------|------|------------|
| `GET /1/stats/artist/{mbid}/listeners` | Artist-level aggregate: total_listen_count, total_user_count | ARTIST_POPULARITY (aggregate, not just top tracks) |
| `GET /1/popularity/recording/{mbid}` | Single recording stats | TRACK_POPULARITY (for specific track lookup) |
| `GET /1/stats/sitewide/artists` | Global top artists | Rankings |
| `GET /1/explore/fresh-releases` | Recent notable releases | Discovery |
| `GET /1/popularity/top-recordings` | Global top recordings | Rankings |

### Recommendation Endpoints (future potential)

| Endpoint | Data | Useful For |
|----------|------|------------|
| `GET /1/cf/recommendation/user/{user}/recording` | Collaborative filtering recommendations | Similar tracks |
| `GET /1/explore/similar-artists/{mbid}` | Similar artists by listening patterns | SIMILAR_ARTISTS (alternative to Last.fm) |

## Gotchas & Edge Cases

- **Requires artist MBID**: No text search. Depends entirely on MusicBrainz identity resolution.
- **JSON array response**: Unlike most APIs, the popularity endpoint returns a bare JSON array, not `{ "data": [...] }`. Our code uses `httpClient.fetchJsonArray()` for this.
- **Smaller user base than Last.fm**: ListenBrainz has fewer users, so listen counts are lower. But it's growing and is fully open-source.
- **Data freshness**: Stats are updated periodically, not real-time. May not reflect very recent releases.
- **No rate limit headers**: ListenBrainz doesn't document strict rate limits for read endpoints, but be respectful. 100ms between requests is safe.
- **Empty array = no data**: Returns `[]` for unknown artists, not 404. We treat empty as `NotFound`.
- **Open source**: ListenBrainz is part of the MetaBrainz ecosystem (same org as MusicBrainz). Data is CC0 licensed.
- **Recording MBIDs**: The recording_mbid values can be used to look up full track details in MusicBrainz.

## Internal Architecture

```
ListenBrainzProvider
├── ListenBrainzApi       — top recordings endpoint + parsing
└── ListenBrainzModels    — DTO: ListenBrainzPopularTrack (recordingMbid, title, artistName, listenCount)
```

Constructor params:
- `httpClient: HttpClient`
- `rateLimiter: RateLimiter` — 100ms is fine
