# Deezer Provider

> Free public API with no auth required. Used for album art, artist metadata, radio playlists, similar artists, album tracks, and track previews.

## API Overview

| | |
|---|---|
| **Base URL** | `https://api.deezer.com` |
| **Auth** | None for search/public endpoints |
| **Rate Limit** | Not officially published; commonly cited as ~50 requests / 5 seconds. We use 100ms. |
| **Format** | JSON |
| **Reference Docs** | https://developers.deezer.com/api |
| **Explorer** | https://developers.deezer.com/api/explorer |
| **API Key Required** | No (public endpoints) |

## Capabilities

| Type | Notes |
|------|-------|
| `ALBUM_ART` | From album search results |
| `ARTIST_PHOTO` | From `artist.picture_*` in album search results |
| `ARTIST_DISCOGRAPHY` | Via `/artist/{id}/albums` |
| `ALBUM_TRACKS` | Via `/album/{id}/tracks` |
| `ALBUM_METADATA` | Via album search |
| `ARTIST_RADIO` | Via `/artist/{id}/radio` |
| `SIMILAR_ARTISTS` | Via `/artist/{id}/related` |
| `SIMILAR_ALBUMS` | Via `SimilarAlbumsProvider` (separate provider, direct API calls) |
| `SIMILAR_TRACKS` | Via `/track/{id}/radio` |
| `ARTIST_TOP_TRACKS` | Via `/artist/{id}/top` |
| `TRACK_PREVIEW` | Via `/search/track` — 30-second MP3 CDN URL |

## Endpoints We Use

### Album Search
```
GET /search/album?q={query}&limit={n}
```

Query format: `"artist name album title"` (free text).

Response:
```json
{
  "data": [
    {
      "id": 103248,
      "title": "OK Computer",
      "artist": {
        "id": 399,
        "name": "Radiohead",
        "picture_small": "...",
        "picture_medium": "...",
        "picture_big": "...",
        "picture_xl": "..."
      },
      "cover_small": "https://api.deezer.com/album/103248/image?size=small",
      "cover_medium": "...",
      "cover_big": "...",
      "cover_xl": "...",
      "nb_tracks": 12,
      "release_date": "1997-06-16",
      "record_type": "album",
      "explicit_lyrics": false,
      "fans": 45678
    }
  ]
}
```

### Track Search
```
GET /search/track?q={query}&limit={n}
```

Query format: `"artist name track title"` (free text).

Response (abbreviated):
```json
{
  "data": [
    {
      "id": 3135556,
      "title": "Creep",
      "artist": { "name": "Radiohead" },
      "album": { "title": "Pablo Honey" },
      "duration": 238,
      "preview": "https://cdns-preview-d.dzcdn.net/stream/c-dce2...30s.mp3"
    }
  ]
}
```

The `preview` field is a 30-second 128kbps MP3 CDN URL. Present for most tracks; `null` or empty for a small minority without preview rights.

## Cover Image Sizes

| Field | Pixels | URL Pattern |
|-------|--------|-------------|
| `cover_small` | 56x56 | `/image?size=small` |
| `cover_medium` | 250x250 | `/image?size=medium` |
| `cover_big` | 500x500 | `/image?size=big` |
| `cover_xl` | 1000x1000 | `/image?size=xl` |

We prefer `cover_xl` → `cover_big` → `cover_medium` → `cover_small`, with `cover_medium` as thumbnail.

## What We Extract

**From album search (`/search/album`):**

| Field | Source | Notes |
|-------|--------|-------|
| Album title | `data[].title` | |
| Artist name | `data[].artist.name` | Verified via `ArtistMatcher.isMatch()` |
| Cover URLs | `cover_small` through `cover_xl` | Best available used as main, medium as thumbnail |

**From track search (`/search/track`):**

| Field | Source | Notes |
|-------|--------|-------|
| Track title | `data[].title` | |
| Artist name | `data[].artist.name` | Verified via `ArtistMatcher.isMatch()` |
| Preview URL | `data[].preview` | 30-second MP3 CDN URL; null when not available |
| Duration | `data[].duration` | In seconds; stored as `durationMs` (×1000) |
| Album title | `data[].album.title` | Contextual — not validated against request |

## What We DON'T Extract (Available Data)

### From Current Search Response (ignored)

| Field | Where | Useful For |
|-------|-------|------------|
| `id` | Album object | Needed for detailed album lookup |
| `artist.id` | Artist object | Needed for artist endpoints |
| `artist.picture_*` | Artist object | ARTIST_PHOTO — 4 sizes available! |
| `nb_tracks` | Album object | Track count validation |
| `release_date` | Album object | RELEASE_DATE |
| `record_type` | Album object | RELEASE_TYPE ("album", "single", "ep", "compilation") |
| `explicit_lyrics` | Album object | Content filtering |
| `fans` | Album object | Album popularity metric |

### Endpoints Not Yet Called

| Endpoint | Data | Useful For |
|----------|------|------------|
| `GET /album/{id}` | Full album details: tracklist URL, genres, label, duration, rating, contributors, UPC | Album metadata |
| `GET /album/{id}/tracks` | Tracklist with title, duration, preview URL, disk_number, track_position, ISRC | ALBUM_TRACKS |
| `GET /artist/{id}` | Bio snippet, image, nb_album, nb_fan, radio | Artist profile |
| `GET /artist/{id}/albums` | Full discography with release dates and types | ARTIST_DISCOGRAPHY |
| `GET /artist/{id}/related` | Related/similar artists | SIMILAR_ARTISTS |
| `GET /artist/{id}/top` | Top 5 tracks by popularity | Track rankings |
| `GET /artist/{id}/radio` | Auto-generated radio tracks | Similar tracks |
| `GET /search/artist?q={name}` | Artist search | Artist lookup |
| `GET /genre` | All genres | Genre taxonomy |
| `GET /genre/{id}/artists` | Top artists per genre | Genre exploration |

## Gotchas & Edge Cases

- **Search is fuzzy**: `q=Radiohead OK Computer` may return unrelated results if the exact album isn't in Deezer. We use `ArtistMatcher.isMatch()` to verify.
- **No auth but rate limited**: 50 requests per 5 seconds. Exceeding returns 429. Our 100ms limiter is within bounds for sequential use but could be tight under fan-out.
- **Image URLs are proxied**: Deezer cover URLs go through `api.deezer.com/album/{id}/image?size=...` which redirects to CDN. These are stable for caching.
- **Artist images in search results**: Every album search result includes `artist.picture_*` at 4 sizes — we throw these away entirely.
- **`record_type` values**: "album", "single", "ep", "compilation". Useful for filtering.
- **Regional availability**: Some albums may not be available in all regions. The API returns them regardless.
- **Preview URLs**: 30-second 128kbps MP3 CDN URLs via `data[].preview` in track search. Exposed as `TRACK_PREVIEW` enrichment type. TTL is 24 hours — CDN URLs are stable but may rotate. Not available for all tracks; `DeezerMapper.toTrackPreview()` returns `null` when `previewUrl` is blank.
- **Deezer ToS for previews**: Free for non-commercial use. Check Deezer's terms for commercial applications.
- **Deezer IDs are numeric**: Unlike MusicBrainz UUIDs, Deezer uses numeric IDs. No cross-reference unless you search.
- **Docs behind login wall**: The full Deezer API documentation at developers.deezer.com requires login to view. Public third-party mirrors exist but may be outdated.
- **OAuth 2.0 available**: For user-specific data (playlists, recommendations, favorites). Not needed for public search/catalog endpoints.
- **API status (as of March 2026)**: The Deezer API remains publicly accessible with no sunset announcements. The Deezer Native SDK was deprecated but the REST API continues operating.

## Internal Architecture

```
DeezerProvider
├── DeezerApi       — album search + track search + parsing
└── DeezerModels    — DTOs: DeezerAlbumResult, DeezerTrackSearchResult (id, title, artistName, previewUrl, durationSec, albumTitle)
```

Constructor params:
- `httpClient: HttpClient`
- `rateLimiter: RateLimiter` — 100ms recommended
