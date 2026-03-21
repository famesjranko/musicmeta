# musicmeta — Gap Analysis & Roadmap

> Where we are, where we're going, and what it takes to get there.

---

## The Goal

An open-source music metadata engine that any app can drop in to get **everything** about an artist, album, or track:

- **Artwork**: all types (album front/back, artist photo, banner, logo, fanart, CD art, booklet) at all sizes (thumb → hero)
- **Metadata**: artist → members → discography → albums → tracks, with labels, dates, countries, genres
- **Text content**: biographies, lyrics (synced + plain)
- **Relationships**: similar artists, similar albums, similar tracks
- **Statistics**: popularity scores, rankings, listen counts
- **Links**: social media, websites, streaming profiles
- **Credits**: producers, performers, composers, engineers

---

## Where We Are (v0.1.0)

### Strengths

The **architecture is solid** and ready to scale:

- 11 providers wired with priority chains, circuit breakers, rate limiting
- Identity resolution pipeline (MusicBrainz → MBID/Wikidata/Wikipedia → precise downstream lookups)
- Fan-out concurrency, confidence filtering, configurable everything
- Clean provider abstraction — adding capabilities = implementing an interface
- Pure Kotlin/JVM core, optional Android module

### Current Coverage (16 enrichment types)

| Category | Type | Providers | Depth |
|----------|------|-----------|-------|
| **Artwork** | ALBUM_ART | CAA, Fanart.tv, Deezer, iTunes | Good — 4 providers, but single URL per result |
| | ARTIST_PHOTO | Wikidata, Fanart.tv | OK |
| | ARTIST_BACKGROUND | Fanart.tv only | Thin — requires API key + MBID |
| | ARTIST_LOGO | Fanart.tv only | Thin — requires API key + MBID |
| | CD_ART | Fanart.tv only | Thin — requires API key + MBID |
| **Metadata** | GENRE | MusicBrainz, Last.fm | Good |
| | LABEL | MusicBrainz, Discogs | Good |
| | RELEASE_DATE | MusicBrainz | OK |
| | RELEASE_TYPE | MusicBrainz, Discogs | OK |
| | COUNTRY | MusicBrainz | OK |
| **Text** | ARTIST_BIO | Wikipedia, Last.fm | Good |
| | LYRICS_SYNCED | LRCLIB | Good |
| | LYRICS_PLAIN | LRCLIB | Good |
| **Relationships** | SIMILAR_ARTISTS | Last.fm only | Thin — requires API key |
| **Statistics** | ARTIST_POPULARITY | Last.fm, ListenBrainz | OK |
| | TRACK_POPULARITY | Last.fm, ListenBrainz | Fragmented |

### What Each Provider Actually Uses vs. What's Available

This is where the biggest opportunity is — **most of the data we need is already accessible from providers we integrate, we just don't extract it yet**.

| Provider | Endpoints Called | Endpoints Available | Utilization |
|----------|----------------|-------------------|-------------|
| **MusicBrainz** | 5 | 12+ | ~40% — ignoring relations (members, credits), release-group browsing (discography), media (tracklists) |
| **Last.fm** | 2 methods | 10+ | ~20% — only artist.getinfo + artist.getsimilar; missing album/track info, top tracks/albums, track.getSimilar |
| **Fanart.tv** | 1 | 1 (but more image types) | ~80% — good extraction, missing language/likes ranking |
| **Deezer** | 1 | 8+ | ~12% — album art search only; artist, tracklist, related artists all available |
| **Discogs** | 1 | 7+ | ~15% — search only; missing artist members, full release lookup, genres/styles |
| **Cover Art Archive** | 2 (redirects) | 5+ | ~30% — front cover only; back, booklet, tray, full image listing available |
| **ListenBrainz** | 1 | 6+ | ~15% — top recordings only; artist stats, genre popularity available |
| **iTunes** | 1 | 4+ | ~25% — album search only; ignoring artist/track search, AMG IDs |
| **Wikidata** | 1 (P18 only) | 100+ properties | ~5% — only fetching image; birth/death dates, nationality, occupation all available |
| **Wikipedia** | 1 | 6+ | ~15% — summary only; sectioned content, all media, related articles available |
| **LRCLIB** | 2 | 2 | ~100% — fully utilized |

---

## What Needs to Be Done

### Phase 1: Unlock Existing Provider Data (highest ROI)

These require **no new providers** — just extracting more from APIs we already call or adding endpoints to existing providers.

#### 1A. Band Members & Artist Relationships
**New type: `BAND_MEMBERS`**

| Source | How | Effort |
|--------|-----|--------|
| MusicBrainz | Parse `relations` array in artist lookup (already fetched with `inc=url-rels`, need `inc=artist-rels`) | Medium |
| Discogs | New endpoint: `GET /artists/{id}/members` | Medium |

Data model needed:
```
BandMembers(members: List<BandMember>)
BandMember(name, role?, activePeriod?, musicBrainzId?)
```

#### 1B. Artist Discography
**New type: `ARTIST_DISCOGRAPHY`**

| Source | How | Effort |
|--------|-----|--------|
| MusicBrainz | New endpoint: browse `/release-group?artist={mbid}&type=album` | Medium |
| Deezer | New endpoint: `GET /artist/{id}/albums` | Low |

Data model needed:
```
Discography(albums: List<DiscographyAlbum>)
DiscographyAlbum(title, year?, type?, musicBrainzId?, thumbnailUrl?)
```

#### 1C. Album Tracklist
**New type: `ALBUM_TRACKS`**

| Source | How | Effort |
|--------|-----|--------|
| MusicBrainz | Parse `media` array in release lookup (data is already returned, just ignored) | Low |
| Deezer | New endpoint: `GET /album/{id}/tracks` | Low |

Data model needed:
```
Tracklist(tracks: List<TrackInfo>)
TrackInfo(title, position, durationMs?, musicBrainzId?, isrc?)
```

#### 1D. Similar Tracks & Similar Albums
**New types: `SIMILAR_TRACKS`, `SIMILAR_ALBUMS`**

| Source | How | Effort |
|--------|-----|--------|
| Last.fm | New methods: `track.getSimilar`, `album.getSimilar` (same API, new methods) | Low |
| Deezer | New endpoint: `GET /artist/{id}/related` (for artist-level, supplements tracks) | Low |

#### 1E. Artist Banner/Hero Images
**New type: `ARTIST_BANNER`**

| Source | How | Effort |
|--------|-----|--------|
| Fanart.tv | Already returned in API response as `musicbanner` — just need to wire it to a new type | Very low — data is already parsed in `extractUrls`, just not mapped to a type |

#### 1F. Multiple Art Sizes per Result
**Enhancement to `EnrichmentData.Artwork`**

Currently each artwork result returns a single URL. The APIs return multiple sizes:

| Source | Available Sizes |
|--------|----------------|
| Cover Art Archive | 250px, 500px, 1200px, full |
| Deezer | small (56px), medium (250px), big (500px), xl (1000px) |
| iTunes | Any size via URL template (`{n}x{n}bb`) |
| Fanart.tv | Full resolution + community variants |

Enhancement:
```
Artwork(
    url: String,                          // best/default
    sizes: List<ArtworkSize>? = null,     // all available
    thumbnailUrl: String? = null,
)
ArtworkSize(url, width, height, label?)   // label = "small", "medium", "xl", etc.
```

Effort: Medium — need to update each provider's artwork mapping.

#### 1G. Artist Links (Social, Websites)
**New type: `ARTIST_LINKS`**

| Source | How | Effort |
|--------|-----|--------|
| MusicBrainz | Already fetched with `inc=url-rels` — just need to parse all URL relation types (currently only Wikidata/Wikipedia extracted) | Low |

MusicBrainz URL relations include: official website, bandcamp, soundcloud, youtube, spotify, apple music, instagram, twitter/X, facebook, discogs, allmusic, setlist.fm, etc.

Data model:
```
ArtistLinks(links: List<ExternalLink>)
ExternalLink(type: String, url: String, label?: String)  // type = "official", "spotify", "youtube", etc.
```

#### 1H. More from Wikidata
Currently only fetching P18 (image). Low-effort additions:

| Property | Code | Use |
|----------|------|-----|
| Birth date | P569 | Artist profile |
| Death date | P570 | Artist profile |
| Nationality | P27 | Artist profile |
| Occupation | P106 | Artist type detail |
| Commons category | P373 | More images |

These can enrich the existing `Metadata` data class (it already has `beginDate`, `endDate`, `artistType`).

---

### Phase 2: Deepen Existing Types

#### 2A. Back Cover & Booklet Art
**New types: `ALBUM_ART_BACK`, `ALBUM_BOOKLET`** (or flags on ALBUM_ART)

Cover Art Archive's JSON endpoint (`/release/{id}`) returns all image types with thumbnails. Currently we only request `front-{size}`.

#### 2B. Album-Level Metadata from More Sources
Deezer search results already return `rating`, `fans`, `nb_tracks`, `record_type`, `genres`, `release_date` — all ignored. iTunes returns `trackCount`, `primaryGenreName`. Wiring these in strengthens the metadata for albums.

#### 2C. Track-Level Popularity
ListenBrainz has per-recording stats (`/1/recording/{mbid}/`). Last.fm has `track.getInfo` with playcount/listeners. Currently `TRACK_POPULARITY` is fragmented — a dedicated chain with these endpoints would solidify it.

#### 2D. Wikipedia Sectioned Content
The REST API has `/page/mobile-sections/{title}` — could extract structured sections like "Early life", "Career", "Discography", "Awards". Richer bio data without parsing HTML.

#### 2E. Wikipedia Page Media
`/page/media-list/{title}` returns all images on the article — band photos, album covers, concert shots. Could supplement the thin `ARTIST_PHOTO` coverage.

---

### Phase 3: New Capabilities

#### 3A. Credits & Personnel
**New type: `CREDITS`**

MusicBrainz recording/release relationships can identify: producer, engineer, mixer, mastering, composer, lyricist, performer (instrument). Discogs has detailed credits per track.

```
Credits(credits: List<Credit>)
Credit(name, role, type?, musicBrainzId?)  // type = "producer", "performer:guitar", etc.
```

#### 3B. Release Editions
**New type: `RELEASE_EDITIONS`**

MusicBrainz groups releases by release-group. Could list all editions: original, deluxe, remaster, vinyl, etc. with country, date, format, barcode.

#### 3C. Artist Timeline
Combine MusicBrainz life-span + Wikidata dates + discography into a timeline: formed → first album → member changes → hiatus → reunion → latest release.

#### 3D. Genre Deep Dive
Last.fm tags + MusicBrainz tags often differ. Could merge/deduplicate and add confidence to each genre tag. Last.fm's `tag.gettopartists` could feed a "top artists in genre" feature.

---

## Priority Scorecard

Ranked by **impact to consumers × implementation effort**:

| # | Feature | New Types | Impact | Effort | Providers Involved |
|---|---------|-----------|--------|--------|--------------------|
| 1 | Multiple art sizes | Enhancement | High | Medium | CAA, Deezer, iTunes, Fanart.tv |
| 2 | Album tracklist | ALBUM_TRACKS | High | Low | MusicBrainz (data already returned), Deezer |
| 3 | Artist discography | ARTIST_DISCOGRAPHY | High | Medium | MusicBrainz, Deezer |
| 4 | Band members | BAND_MEMBERS | High | Medium | MusicBrainz, Discogs |
| 5 | Artist links | ARTIST_LINKS | Medium | Low | MusicBrainz (data already returned) |
| 6 | Artist banner | ARTIST_BANNER | Medium | Very low | Fanart.tv (data already parsed) |
| 7 | Similar tracks/albums | SIMILAR_TRACKS, SIMILAR_ALBUMS | Medium | Low | Last.fm |
| 8 | Wikidata enrichment | Enhancement | Medium | Low | Wikidata |
| 9 | Album back/booklet art | Enhancement | Medium | Low | CAA |
| 10 | Credits & personnel | CREDITS | High | High | MusicBrainz, Discogs |
| 11 | Wikipedia sections/media | Enhancement | Low | Medium | Wikipedia |
| 12 | Release editions | RELEASE_EDITIONS | Low | Medium | MusicBrainz |

---

## Summary

| Dimension | Now | After Phase 1 | After Phase 2 | After Phase 3 |
|-----------|-----|---------------|---------------|---------------|
| Enrichment types | 16 | 23 (+7) | 23 (deepened) | 26 (+3) |
| Provider utilization | ~30% avg | ~60% avg | ~75% avg | ~85% avg |
| "App-ready" artist page | Partial (photo, bio, genres, similar) | Full (+ members, discography, links, banner, popularity) | Rich (+ sections, media, credits) | Complete |
| "App-ready" album page | Partial (front art, genres, label, date) | Full (+ tracklist, back art, all sizes, similar) | Rich (+ credits, editions) | Complete |
| "App-ready" now-playing | Partial (lyrics, album art) | Better (+ similar tracks, sizes) | Rich (+ credits, track stats) | Complete |

**The architecture is the hard part and it's done.** Most of what remains is wiring up data that's already available from providers you already integrate. Phase 1 is largely "parse more fields from API responses you already receive."
