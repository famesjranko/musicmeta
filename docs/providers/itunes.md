# iTunes Provider

> Apple's public search API. Free, no auth required. Album art fallback with a URL trick for arbitrary image sizes. Aggressive rate limiting.

## API Overview

| | |
|---|---|
| **Base URL** | `https://itunes.apple.com` |
| **Auth** | None |
| **Rate Limit** | ~20 requests/minute (aggressive); we use 3000ms |
| **Format** | JSON |
| **Reference Docs** | https://developer.apple.com/library/archive/documentation/AudioVideo/Conceptual/iTuneSearchAPI/ |
| **API Key Required** | No |

## Endpoints We Use

### Album Search
```
GET /search?media=music&entity=album&term={query}&limit={n}
```

Query: free text, typically `"artist name album title"`.

Response:
```json
{
  "resultCount": 1,
  "results": [
    {
      "wrapperType": "collection",
      "collectionType": "Album",
      "collectionId": 1097862062,
      "collectionName": "OK Computer",
      "artistName": "Radiohead",
      "artistId": 657515,
      "artworkUrl100": "https://is1-ssl.mzstatic.com/.../100x100bb.jpg",
      "releaseDate": "1997-06-16T07:00:00Z",
      "primaryGenreName": "Alternative",
      "country": "USA",
      "trackCount": 12,
      "collectionPrice": 9.99,
      "currency": "USD",
      "collectionExplicitness": "notExplicit",
      "amgArtistId": 41092,
      "collectionViewUrl": "https://music.apple.com/us/album/..."
    }
  ]
}
```

## Artwork URL Trick

iTunes returns `artworkUrl100` — a 100x100 thumbnail. But the URL contains the size in the filename:

```
https://is1-ssl.mzstatic.com/.../100x100bb.jpg
```

Replace `100x100bb` with any size:

| Replacement | Result |
|-------------|--------|
| `250x250bb` | 250px |
| `500x500bb` | 500px |
| `1200x1200bb` | 1200px (our default) |
| `3000x3000bb` | Max resolution |

This gives us **arbitrary image sizes from a single API response**. We return the high-res URL as main artwork and the original 100x100 as thumbnail.

## What We Extract

| Field | Source | Notes |
|-------|--------|-------|
| Album title | `collectionName` | |
| Artist name | `artistName` | Verified via `ArtistMatcher.isMatch()` |
| Artwork URL | `artworkUrl100` → replaced to 1200x1200 | High-res via URL manipulation |
| Thumbnail URL | `artworkUrl100` | Original 100x100 |

## What We DON'T Extract (Available Data)

### From Current Response (ignored)

| Field | Where | Useful For |
|-------|-------|------------|
| `collectionId` | Result | Needed for lookup endpoint |
| `artistId` | Result | Needed for artist lookup |
| `amgArtistId` | Result | AllMusic cross-reference ID |
| `releaseDate` | Result | RELEASE_DATE (ISO 8601 format) |
| `primaryGenreName` | Result | GENRE |
| `country` | Result | COUNTRY (store region, not release country) |
| `trackCount` | Result | Validation |
| `collectionPrice` / `currency` | Result | Pricing info |
| `collectionExplicitness` | Result | Content rating |
| `collectionViewUrl` | Result | Apple Music link |

### Endpoints Not Yet Called

| Endpoint | Data | Useful For |
|----------|------|------------|
| `GET /search?entity=musicArtist&term={name}` | Artist search: artist name, genre, primary URL | Artist lookup |
| `GET /search?entity=song&term={query}` | Track search with preview URLs | Track lookup |
| `GET /lookup?id={collectionId}&entity=song` | All tracks in an album | ALBUM_TRACKS |
| `GET /lookup?id={artistId}&entity=album` | All albums by an artist | ARTIST_DISCOGRAPHY |
| `GET /lookup?amgArtistId={id}` | Lookup by AllMusic ID | Cross-reference |

## Gotchas & Edge Cases

- **Aggressive rate limiting**: ~20 requests/minute is very low. Our 3000ms (3-second) interval is conservative but necessary. Exceeding the limit returns a 403 or empty results without clear error messaging.
- **No rate limit headers**: iTunes doesn't return `Retry-After` or `X-RateLimit-*` headers. You just get 403'd or silently throttled.
- **Lowest confidence (0.65)**: Pure text search with no ID-based lookup. Artist matching helps but the result may still be wrong.
- **Low priority (40)**: Only tried after CAA (100) and Deezer (50) fail. This is the last-resort album art source.
- **`artworkUrl100` may be missing**: Some results have an empty or null `artworkUrl100`. We return `NotFound` in this case.
- **Search is US-biased**: The API defaults to the US iTunes Store. Results may differ by region. The `country` field in results reflects the store region, not the album's origin.
- **Release date is ISO 8601**: `"1997-06-16T07:00:00Z"` — we extract `take(4)` for just the year in search candidates.
- **URL size limits**: While `3000x3000bb` technically works, many album artworks are stored at lower native resolutions. Requesting larger just returns the max available.
- **mzstatic.com CDN**: Image URLs point to Apple's CDN. These are stable and cacheable.
- **AMG Artist ID**: `amgArtistId` is an AllMusic Guide identifier — useful for cross-referencing with AllMusic's database but we don't use it.

## Internal Architecture

```
ITunesProvider
├── ITunesApi       — album search + parsing
└── ITunesModels    — DTO: ITunesAlbumResult (collectionId, collectionName, artistName, artworkUrl, releaseDate, primaryGenreName, country)
```

Constructor params:
- `httpClient: HttpClient`
- `rateLimiter: RateLimiter` — **3000ms recommended** (iTunes is very rate-limit sensitive)
- `artworkSize: Int = 1200` — pixel size for URL replacement
