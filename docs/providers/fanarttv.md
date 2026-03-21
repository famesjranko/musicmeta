# Fanart.tv Provider

> Community-curated high-quality artist imagery. Best source for backgrounds, logos, banners, and CD art. Requires MBID + API key.

## API Overview

| | |
|---|---|
| **Base URL** | `https://webservice.fanart.tv/v3/music` |
| **Auth** | Project API key (query param `api_key`) |
| **Rate Limit** | Not strictly documented; we use 100ms |
| **Format** | JSON |
| **Reference Docs** | https://fanart.tv/get-an-api-key/ |
| **Image Types Docs** | https://fanart.tv/music-fanart/ |
| **API Key Required** | Yes (free project key) |

## Getting an API Key

1. Create an account at https://fanart.tv
2. Go to https://fanart.tv/get-an-api-key/
3. Register a project — you'll get a **project API key**
4. Free tier gives access to all images. VIP members get early access to new uploads.
5. Pass as `fanarttv.apikey` system property or `FANARTTV_API_KEY` env var

## Endpoints We Use

### Artist Images
```
GET /v3/music/{artistMbid}?api_key={key}
```

Single endpoint returns ALL image types for an artist:

```json
{
  "name": "Radiohead",
  "mbid_id": "a74b1b7f-...",
  "artistthumb": [
    { "id": "12345", "url": "https://assets.fanart.tv/...", "likes": "3", "lang": "en" }
  ],
  "artistbackground": [...],
  "hdmusiclogo": [...],
  "musiclogo": [...],
  "musicbanner": [...],
  "albums": {
    "{albumMbid}": {
      "albumcover": [{ "id": "...", "url": "...", "likes": "2", "disc": "1" }],
      "cdart": [{ "id": "...", "url": "...", "likes": "1", "disc": "1", "size": "1000" }]
    }
  }
}
```

## Image Types

| Type Key | Description | Typical Resolution | Our Mapping |
|----------|-------------|-------------------|-------------|
| `artistthumb` | Artist photo/headshot | 1000x1000 | ARTIST_PHOTO |
| `artistbackground` | Wide background/fanart | 1920x1080 | ARTIST_BACKGROUND |
| `hdmusiclogo` | HD transparent logo | 800x310 | ARTIST_LOGO |
| `musiclogo` | Standard transparent logo | 400x155 | (not mapped, fallback for ARTIST_LOGO) |
| `musicbanner` | Wide banner image | 1000x185 | Parsed but **not mapped to any type** |
| `albumcover` | Album cover art (per album) | 1000x1000 | ALBUM_ART (low priority 30) |
| `cdart` | CD disc artwork (per album) | 1000x1000 | CD_ART |

## What We Extract

| Field | Source | Notes |
|-------|--------|-------|
| Artist photo URL | `artistthumb[0].url` | First thumbnail only |
| Background URL | `artistbackground[0].url` | First background only |
| Logo URL | `hdmusiclogo[0].url` | HD logo only |
| Album cover URLs | `albums.{mbid}.albumcover[0].url` | First cover per album |
| CD art URLs | `albums.{mbid}.cdart[0].url` | First disc art per album |
| Banner URLs | `musicbanner[].url` | **Parsed into model but not mapped to any EnrichmentType** |

## What We DON'T Extract (Available Data)

### From Current Response (ignored fields)

| Field | Where | Useful For |
|-------|-------|------------|
| `id` | Each image object | Stable identifier for caching/dedup |
| `likes` | Each image object | Rank images by community votes (higher = better quality) |
| `lang` | Each image object | Filter by language/locale |
| `disc` | albumcover, cdart | Multi-disc album support |
| `size` | cdart | Actual image dimensions |
| `musiclogo` | Artist-level | Fallback for logo if no HD version exists |
| Multiple images per type | All arrays | We take `[0]` only — could return all variants |
| `musicbanner` | Artist-level | **Already parsed** — just needs a new EnrichmentType |

### Album-Specific Images

The `albums` object is keyed by **album MBID**, but our provider receives the **artist MBID** for `ForArtist` requests. For `ForAlbum` requests, we'd need to look up the specific album MBID within the nested structure.

## Gotchas & Edge Cases

- **Requires MBID**: No text search. Depends on MusicBrainz identity resolution running first.
- **Artist MBID, not album MBID**: The endpoint takes an artist MBID. Album images are nested inside the response. This means one call gets images for ALL albums by that artist.
- **Community-curated**: Coverage depends on artist popularity. Mainstream artists have rich imagery; obscure artists may have nothing.
- **Multiple images per type**: An artist might have 5 backgrounds. We currently take `[0]`. The `likes` field could be used to pick the most popular.
- **Banner data is wasted**: `musicbanner` URLs are parsed into `FanartTvArtistImages.banners` but never mapped to an `EnrichmentType`. Adding `ARTIST_BANNER` would be trivial.
- **Logo variants**: `hdmusiclogo` (800x310) and `musiclogo` (400x155) are separate. We only use HD. Could fall back to standard.
- **Free API limits**: The free tier has full access but may have lower priority during high traffic.
- **404 for unknown artists**: If the artist MBID isn't in Fanart.tv's database, you get a 404.
- **Image hotlinking**: Fanart.tv URLs (`assets.fanart.tv/...`) can be used directly. No additional auth needed to fetch the image itself.

## Internal Architecture

```
FanartTvProvider
├── FanartTvApi       — single endpoint call + JSON parsing
└── FanartTvModels    — DTO: FanartTvArtistImages (thumbnails, backgrounds, logos, banners, albumCovers, cdArt)
```

Constructor params:
- `projectKey: String` (or `projectKeyProvider: () -> String`)
- `httpClient: HttpClient`
- `rateLimiter: RateLimiter` — 100ms is fine
