# Cover Art Archive Provider

> Primary source for album artwork. ID-based lookups via MusicBrainz release/release-group MBIDs — no fuzzy search, highest confidence.

## API Overview

| | |
|---|---|
| **Base URL** | `https://coverartarchive.org` |
| **Auth** | None |
| **Rate Limit** | None documented; we use 100ms between requests |
| **Format** | Image redirects (307 → archive.org) or JSON metadata |
| **Reference Docs** | https://musicbrainz.org/doc/Cover_Art_Archive/API |
| **API Key Required** | No |

## How It Works

CAA doesn't serve images directly. It returns **307 redirects** to Internet Archive (archive.org) where the actual images are hosted. Our `HttpClient.fetchRedirectUrl()` captures the redirect Location header without following it — that URL is the artwork URL we return.

## Endpoints We Use

### Front Cover by Release
```
GET /release/{mbid}/front-{size}
→ 307 redirect to https://archive.org/...
→ 404 if no artwork
```

Sizes: numeric pixel values. Common: `250`, `500`, `1200`. Original: omit size suffix.

### Front Cover by Release Group (fallback)
```
GET /release-group/{mbid}/front-{size}
→ 307 redirect or 404
```

Falls back to the "best" front cover across all releases in the group.

## What We Extract

| Field | Source | Notes |
|-------|--------|-------|
| Artwork URL | Redirect Location header | Full-size (default 1200px) |
| Thumbnail URL | Second request at 250px | Separate HTTP call |

## What We DON'T Extract (Available Data)

### JSON Metadata Endpoint (not currently called)

```
GET /release/{mbid}
→ JSON with ALL available images
```

Response structure:
```json
{
  "images": [
    {
      "types": ["Front"],
      "front": true,
      "back": false,
      "comment": "",
      "image": "http://archive.org/.../full.jpg",
      "thumbnails": {
        "250": "http://archive.org/.../250.jpg",
        "500": "http://archive.org/.../500.jpg",
        "1200": "http://archive.org/.../1200.jpg",
        "large": "http://archive.org/.../large.jpg",
        "small": "http://archive.org/.../small.jpg"
      },
      "approved": true,
      "id": 12345
    }
  ],
  "release": "https://musicbrainz.org/release/{mbid}"
}
```

This endpoint gives us:

| Field | Useful For |
|-------|------------|
| `images[].types` | All image types: "Front", "Back", "Booklet", "Medium", "Obi", "Spine", "Track", "Tray", "Sticker", "Poster", "Liner", "Watermark", "Raw/Unedited", "Matrix/Runout", "Top", "Bottom" |
| `images[].thumbnails` | All size variants in one response (avoids multiple redirect checks) |
| `images[].front` / `images[].back` | Quick boolean checks |
| `images[].comment` | Community notes about the image |
| Multiple images per type | Some releases have alternate front covers |

### Other Image Types Available

```
GET /release/{mbid}/back          → back cover
GET /release/{mbid}/back-{size}   → back cover at size
```

Any image type from the `types` array can be requested:
```
GET /release/{mbid}/{type}
GET /release/{mbid}/{type}-{size}
```

## Gotchas & Edge Cases

- **Requires MBID**: Cannot search by title/artist. Depends entirely on MusicBrainz identity resolution.
- **Release vs Release Group**: A "release" is a specific pressing (US CD, UK vinyl). A "release group" is the abstract album. If the specific release has no art, the release-group endpoint often has one from another pressing.
- **Two HTTP calls for artwork + thumbnail**: We currently make two separate redirect-check calls (one for full size, one for thumbnail). The JSON endpoint would give all sizes in one call.
- **404 = no artwork**: Not an error, just means nobody has uploaded art for this release.
- **Archive.org URLs are stable**: Once an image is on archive.org, the URL doesn't change. Safe to cache long-term.
- **No rate limit headers**: CAA doesn't document rate limits, but it's backed by archive.org infrastructure. Be respectful.
- **Size `1200` is our default**: High enough for most displays without being wasteful. Original images can be very large (4000px+).

## Internal Architecture

```
CoverArtArchiveProvider
└── CoverArtArchiveApi    — redirect-check calls + URL builders
```

Constructor params:
- `httpClient: HttpClient`
- `rateLimiter: RateLimiter` — 100ms is fine
- `artworkSize: Int = 1200` — pixel size for main artwork
- `thumbnailSize: Int = 250` — pixel size for thumbnails
