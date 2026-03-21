# Wikipedia Provider

> Free encyclopedia. Primary source for artist biographies. High quality for notable artists. Requires a Wikipedia title or Wikidata ID (resolved from MusicBrainz).

## API Overview

| | |
|---|---|
| **Base URL** | `https://en.wikipedia.org/api/rest_v1` |
| **Auth** | None |
| **Rate Limit** | Not strictly enforced; be respectful. We use 100ms. |
| **Format** | JSON |
| **Reference Docs** | https://en.wikipedia.org/api/rest_v1/ |
| **Wikimedia REST API Docs** | https://www.mediawiki.org/wiki/Wikimedia_REST_API |
| **API Key Required** | No |

## User-Agent Requirement

Like MusicBrainz, Wikimedia APIs expect a descriptive User-Agent. Not strictly enforced for light usage, but required per their terms. Our `DefaultHttpClient` sets this globally.

## Endpoints We Use

### Page Summary
```
GET /page/summary/{title}
```

Returns a concise summary of the Wikipedia article:

```json
{
  "type": "standard",
  "title": "Radiohead",
  "displaytitle": "Radiohead",
  "namespace": { "id": 0, "text": "" },
  "wikibase_item": "Q44191",
  "description": "English rock band",
  "extract": "Radiohead are an English rock band formed in Abingdon, Oxfordshire, in 1985. The band consists of Thom Yorke (vocals, guitar, piano), brothers Jonny Greenwood (lead guitar, keyboards) and Colin Greenwood (bass)...",
  "thumbnail": {
    "source": "https://upload.wikimedia.org/...",
    "width": 320,
    "height": 213
  },
  "originalimage": {
    "source": "https://upload.wikimedia.org/...",
    "width": 3888,
    "height": 2592
  },
  "content_urls": {
    "desktop": { "page": "https://en.wikipedia.org/wiki/Radiohead" },
    "mobile": { "page": "https://en.m.wikipedia.org/wiki/Radiohead" }
  }
}
```

### Wikidata Sitelink Resolution (internal)

When only a Wikidata ID is available (no direct Wikipedia title from MusicBrainz), the provider resolves it:

```
GET https://www.wikidata.org/w/api.php?action=wbgetentities&ids={wikidataId}&props=sitelinks&sitefilter=enwiki&format=json
```

Returns:
```json
{
  "entities": {
    "Q44191": {
      "sitelinks": {
        "enwiki": { "site": "enwiki", "title": "Radiohead" }
      }
    }
  }
}
```

## Title Resolution Strategy

1. Use `request.identifiers.wikipediaTitle` if available (from MusicBrainz URL relations)
2. If not, resolve from `request.identifiers.wikidataId` via sitelinks
3. If neither is available, return `NotFound`

## What We Extract

| Field | Source | Notes |
|-------|--------|-------|
| Bio text | `extract` | Plain text summary (usually 2-3 paragraphs) |
| Source label | Hardcoded "Wikipedia" | For attribution |
| Thumbnail URL | `thumbnail.source` | Small image from the article |

## What We DON'T Extract (Available Data)

### From Current Response (ignored)

| Field | Where | Useful For |
|-------|-------|------------|
| `description` | Page summary | Short one-line description ("English rock band") |
| `originalimage` | Page summary | Full-resolution article image (better than thumbnail) |
| `wikibase_item` | Page summary | Wikidata Q-ID (cross-reference) |
| `content_urls` | Page summary | Links to desktop/mobile Wikipedia pages |

### Endpoints Not Yet Called

| Endpoint | Data | Useful For |
|----------|------|------------|
| `GET /page/mobile-sections/{title}` | Article split into sections with headings | Structured bio: "Early life", "Career", "Discography", "Awards" |
| `GET /page/media-list/{title}` | All media files on the page | ARTIST_PHOTO — band photos, concert shots, album covers |
| `GET /page/related/{title}` | Related Wikipedia articles | Discovery / similar artists |
| `GET /page/html/{title}` | Full HTML content | Rich content parsing (infobox data, tables) |

### Wikipedia Infobox Data

Wikipedia band/artist articles have structured infoboxes containing:
- Origin, genres, years active, labels, associated acts, members, past members, website

This data is in the HTML but not in the REST API summary. Parsing it requires either:
- Fetching `/page/html/{title}` and parsing the infobox
- Using the MediaWiki API (`action=parse`) with `prop=wikitext` and extracting template parameters
- Or better: getting this data from Wikidata instead (structured, not HTML parsing)

## Gotchas & Edge Cases

- **Requires identifier**: No text search. Depends on MusicBrainz providing either `wikipediaTitle` or `wikidataId`.
- **Title encoding**: Wikipedia titles use underscores for spaces. Our code URL-encodes and replaces `+` with `%20`.
- **Disambiguation pages**: If MusicBrainz links to "Air (French band)" but there's also "Air (Japanese band)", the title must be exact. MusicBrainz usually provides the correct disambiguated title.
- **Extract quality**: The `extract` is a plain text rendering of the article lead section. It's usually well-written for notable artists but may be short for obscure ones.
- **Bio may contain HTML remnants**: The page/summary extract is supposed to be plain text, but occasional markup leaks through. Less of an issue than Last.fm.
- **English only**: We only query `en.wikipedia.org`. Non-English artists may have better articles in other language Wikipedias. Wikidata sitelinks could be used to find the best available language.
- **Two API calls for Wikidata fallback**: When only `wikidataId` is available, we make one call to Wikidata for sitelink resolution, then one to Wikipedia for the summary. This could be combined with the Wikidata provider's call.
- **Thumbnail is small**: `thumbnail.source` is typically 320px wide. `originalimage.source` has the full resolution but we don't extract it.
- **Not all artists have articles**: Obscure artists may not have a Wikipedia page at all.

## Internal Architecture

```
WikipediaProvider
├── WikipediaApi       — page summary fetch + parsing
└── WikipediaModels    — DTO: WikipediaSummary (title, extract, description, thumbnailUrl)
```

Constructor params:
- `httpClient: HttpClient` — shared (also used for Wikidata sitelink resolution)
- `rateLimiter: RateLimiter` — for Wikipedia calls
- `wikidataRateLimiter: RateLimiter` — separate limiter for the Wikidata sitelink call (default 100ms)
- `logger: EnrichmentLogger` — debug logging
