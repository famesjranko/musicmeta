# LRCLIB Provider

> Free, open lyrics API. Primary source for synced (timed) and plain lyrics. No API key required. One of our most fully-utilized providers.

## API Overview

| | |
|---|---|
| **Base URL** | `https://lrclib.net` |
| **Auth** | None |
| **Rate Limit** | Not strictly documented; we use 200ms |
| **Format** | JSON |
| **Reference Docs** | https://lrclib.net/docs |
| **Source Code** | https://github.com/tranxuanthang/lrclib |
| **API Key Required** | No |

## Endpoints We Use

### Exact Match Lookup
```
GET /api/get?artist_name={artist}&track_name={track}&album_name={album}&duration={seconds}
```

Returns a single result or 404:

```json
{
  "id": 12345,
  "trackName": "Creep",
  "artistName": "Radiohead",
  "albumName": "Pablo Honey",
  "duration": 238,
  "instrumental": false,
  "syncedLyrics": "[00:00.00] When you were here before\n[00:04.50] Couldn't look you in the eye...",
  "plainLyrics": "When you were here before\nCouldn't look you in the eye..."
}
```

`album_name` and `duration` are optional but improve match accuracy.

### Search
```
GET /api/search?artist_name={artist}&track_name={track}
```

Returns a JSON array of candidates:

```json
[
  {
    "id": 12345,
    "trackName": "Creep",
    "artistName": "Radiohead",
    "albumName": "Pablo Honey",
    "duration": 238,
    "instrumental": false,
    "syncedLyrics": "...",
    "plainLyrics": "..."
  }
]
```

## Matching Strategy

1. **Try exact match first** with all available fields (artist, track, album, duration)
2. If exact match fails (404), **fall back to search** (artist + track only)
3. Take first search result

Confidence:
- Exact match: **0.95** (very high — exact artist+track+album+duration)
- Search match: **0.70** (good but may be wrong version/remix)

## What We Extract

| Field | Source | Notes |
|-------|--------|-------|
| Synced lyrics | `syncedLyrics` | LRC format with timestamps `[mm:ss.cc]` |
| Plain lyrics | `plainLyrics` | Text only, no timestamps |
| Is instrumental | `instrumental` | Boolean — if true, no lyrics expected |

## What We DON'T Extract (available but unused)

| Field | Where | Useful For |
|-------|-------|------------|
| `id` | Result | Could cache by LRCLIB ID for faster re-lookups |
| `duration` | Result | Track duration validation |
| `albumName` | Result | Cross-reference verification |

The API is minimal by design — we're already using most of it.

### Endpoint Not Yet Called

| Endpoint | Data | Useful For |
|----------|------|------------|
| `GET /api/get/{id}` | Fetch by LRCLIB ID | Fast re-lookup if ID is cached |

## Synced Lyrics Format (LRC)

Synced lyrics use the standard LRC format:
```
[00:00.00] When you were here before
[00:04.50] Couldn't look you in the eye
[00:08.65] You're just like an angel
```

Each line has `[mm:ss.cc]` timestamp + text. Consumers need to parse this for karaoke-style display.

## Gotchas & Edge Cases

- **Track-only**: Only handles `ForTrack` requests. Album and artist requests return `NotFound`.
- **Duration is in seconds**: The API expects integer seconds, not milliseconds. Our provider converts: `durationMs / 1000`.
- **Instrumental detection**: `instrumental: true` means the track has no vocals. We return this as `EnrichmentData.Lyrics(isInstrumental = true)` — it's a successful result, not NotFound.
- **Synced vs plain availability**: Some tracks have synced lyrics, some have only plain, some have both. We return whatever is available. If requesting `LYRICS_SYNCED` but only plain exists, we still return the data (caller decides).
- **404 on exact match is normal**: Many tracks don't have an exact match with album+duration. The search fallback usually finds them.
- **Empty lyrics strings**: Sometimes `syncedLyrics` or `plainLyrics` is an empty string rather than null. We filter with `.takeIf { it.isNotBlank() }`.
- **Community-submitted**: Lyrics are user-contributed. Quality varies, especially for non-English tracks.
- **LRC timestamps may vary**: Different submissions for the same song may have slightly different timestamps.
- **No bulk endpoint**: Each track requires its own request. For large playlists, this means many sequential calls.

## Internal Architecture

```
LrcLibProvider
├── LrcLibApi       — exact match + search endpoints + parsing
└── LrcLibModels    — DTO: LrcLibResult (id, trackName, artistName, albumName, duration, instrumental, syncedLyrics, plainLyrics)
```

Constructor params:
- `httpClient: HttpClient`
- `rateLimiter: RateLimiter` — 200ms recommended
