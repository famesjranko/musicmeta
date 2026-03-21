# Discogs Provider

> The definitive database for physical releases — vinyl, CD, cassette. Strong on labels, pressings, and credits. Requires a personal access token.

## API Overview

| | |
|---|---|
| **Base URL** | `https://api.discogs.com` |
| **Auth** | Personal access token (query param `token`) |
| **Rate Limit** | 60 requests/minute (authenticated); 25/min unauthenticated |
| **Format** | JSON |
| **Reference Docs** | https://www.discogs.com/developers |
| **API Key Required** | Yes (personal access token) |

## Getting a Token

1. Create a Discogs account at https://www.discogs.com/users/create
2. Go to https://www.discogs.com/settings/developers
3. Click "Generate new token"
4. Pass as `discogs.token` system property or `DISCOGS_TOKEN` env var

Note: This is a **personal access token**, not OAuth. It gives read-only access to the public database.

## Endpoints We Use

### Database Search
```
GET /database/search?type=release&title={title}&artist={artist}&per_page={limit}&token={token}
```

Response:
```json
{
  "results": [
    {
      "id": 1234567,
      "title": "Radiohead - OK Computer",
      "label": ["Parlophone", "Capitol Records"],
      "year": "1997",
      "country": "UK",
      "cover_image": "https://i.discogs.com/...",
      "thumb": "https://i.discogs.com/...",
      "type": "release",
      "format": ["CD", "Album"],
      "genre": ["Electronic", "Rock"],
      "style": ["Alternative Rock", "Art Rock"],
      "resource_url": "https://api.discogs.com/releases/1234567",
      "barcode": ["724385522925"]
    }
  ],
  "pagination": { "pages": 5, "items": 47 }
}
```

## What We Extract

| Field | Source | Notes |
|-------|--------|-------|
| Title | `results[].title` | Format: "Artist - Album". Splitting on " - " to verify artist. |
| Label | `results[].label[0]` | First label only |
| Year | `results[].year` | String |
| Country | `results[].country` | |
| Cover image | `results[].cover_image` | Full-size image |
| Release type | `results[].type` | Usually "release" |

## What We DON'T Extract (Available Data)

### From Current Search Response (ignored)

| Field | Where | Useful For |
|-------|-------|------------|
| `id` | Each result | **Critical** — needed for detailed release lookup |
| `resource_url` | Each result | Direct URL to full release API |
| `thumb` | Each result | Smaller thumbnail image |
| `format[]` | Each result | "CD", "Vinyl", "Cassette", "Digital" — format info |
| `genre[]` | Each result | GENRE — Discogs has its own genre taxonomy |
| `style[]` | Each result | Sub-genres (more specific than genre) |
| `barcode[]` | Each result | UPC/EAN for cross-referencing |
| Multiple labels | `label[]` | We only take `[0]`, but releases often have multiple |

### Endpoints Not Yet Called

| Endpoint | Data | Useful For |
|----------|------|------------|
| `GET /releases/{id}` | Full release: tracklist with credits, notes, companies, formats, images[], videos[], genres, styles, community rating | Everything |
| `GET /masters/{id}` | Master release (canonical version): main_release, versions_url, tracklist, images | RELEASE_EDITIONS — all pressings |
| `GET /masters/{id}/versions` | All versions/pressings with format, label, country, year | RELEASE_EDITIONS |
| `GET /artists/{id}` | **Artist profile**: realname, profile (bio), namevariations, urls[], images[], members[], groups[] | ARTIST_BIO, BAND_MEMBERS, ARTIST_LINKS |
| `GET /artists/{id}/releases` | Full discography with role (Main, Remix, Producer, etc.) | ARTIST_DISCOGRAPHY |
| `GET /database/search?type=artist` | Artist search by name | Artist lookup |

### Artist Members Endpoint (high value)

```
GET /artists/{id}
```

Response includes:
```json
{
  "members": [
    { "id": 270222, "name": "Thom Yorke", "active": true, "resource_url": "..." },
    { "id": 354187, "name": "Jonny Greenwood", "active": true, "resource_url": "..." }
  ],
  "groups": [
    { "id": 3840, "name": "Atoms for Peace", "active": false, "resource_url": "..." }
  ]
}
```

This is one of the best sources for **band member lists**.

## Gotchas & Edge Cases

- **Title format**: Discogs titles are `"Artist - Album"`, not separate fields. We split on ` - ` and use `ArtistMatcher.isMatch()` to verify the artist half.
- **Physical-release focus**: Discogs excels at physical media. Digital-only releases may be missing or have poor metadata. This is why confidence is low (0.6).
- **Multiple pressings**: Searching "OK Computer" may return the UK CD, US vinyl, Japanese special edition, etc. Each is a separate result with different labels, countries, barcodes.
- **Image CDN**: `cover_image` URLs point to `i.discogs.com` CDN. These require the same `User-Agent` header or they may return 403.
- **Not extracting `id`**: This is the biggest current gap. Without the release `id`, we can't do detailed lookups for tracklists, credits, or full metadata.
- **Rate limit is per-minute, not per-second**: 60/min = 1/sec average, but bursts are allowed. Our 100ms limiter may need adjustment if other providers also use Discogs.
- **Genre vs Style**: Discogs has a curated genre taxonomy (broad: "Rock", "Electronic") and styles (specific: "Shoegaze", "IDM"). Both are in search results but we extract neither.
- **Authenticated rate limit**: 60/min with token, 25/min without. Always use the token.
- **Pagination**: Search results are paginated. We only fetch page 1. For comprehensive results, would need to follow `pagination.urls.next`.

## Internal Architecture

```
DiscogsProvider
├── DiscogsApi       — search endpoint + parsing
└── DiscogsModels    — DTO: DiscogsRelease (title, label, year, country, coverImage, releaseType)
```

Constructor params:
- `personalToken: String` (or `tokenProvider: () -> String`)
- `httpClient: HttpClient`
- `rateLimiter: RateLimiter` — 100ms works within 60/min budget
