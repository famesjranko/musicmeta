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

## Where We Are (v0.4.0)

### What Changed Since v0.1.0

v0.4.0 was a **Provider Abstraction Overhaul** — 5 phases, 15 plans, 31 tasks, shipped 2026-03-21.

Key structural improvements:
- **Typed identifier requirements** — `IdentifierRequirement` enum replaces boolean `requiresIdentifier`; provider chains skip providers precisely when specific identifiers are missing
- **Formalized identity resolution** — `isIdentityProvider` flag and `resolveIdentity()` method replace fragile GENRE/LABEL heuristic
- **Mapper pattern** — 11 `*Mapper.kt` files isolate DTO-to-EnrichmentData mapping; public API changes only touch mappers
- **Centralized API keys** — `ApiKeyConfig` + `Builder.withDefaultProviders()` for one-line engine setup
- **Standardized confidence** — `ConfidenceCalculator` with semantic scoring methods; zero hardcoded floats across all providers
- **Clean public API** — TTL in enum, extensible identifiers via `extra` map, `ErrorKind` enum, `HttpResult` sealed class

### Current Coverage (25 enrichment types)

| Category | Type | Providers | Depth |
|----------|------|-----------|-------|
| **Artwork** | ALBUM_ART | CAA, Fanart.tv, Deezer, iTunes | Good — 4 providers, multi-size support |
| | ALBUM_ART_BACK | CAA | Good — via JSON metadata endpoint |
| | ALBUM_BOOKLET | CAA | Good — via JSON metadata endpoint |
| | ARTIST_PHOTO | Wikidata, Fanart.tv, Wikipedia | Good — 3 providers (Wikipedia via page media-list) |
| | ARTIST_BACKGROUND | Fanart.tv | Thin — requires API key + MBID |
| | ARTIST_LOGO | Fanart.tv | Thin — requires API key + MBID |
| | ARTIST_BANNER | Fanart.tv | OK — requires API key + MBID |
| | CD_ART | Fanart.tv | Thin — requires API key + MBID |
| **Metadata** | GENRE | MusicBrainz, Last.fm | Good |
| | LABEL | MusicBrainz, Discogs | Good |
| | RELEASE_DATE | MusicBrainz | OK |
| | RELEASE_TYPE | MusicBrainz, Discogs | OK |
| | COUNTRY | MusicBrainz, Wikidata | Good — Wikidata adds P495 country of origin |
| | BAND_MEMBERS | MusicBrainz, Discogs | Good — artist-rels + artist endpoint |
| | ARTIST_DISCOGRAPHY | MusicBrainz, Deezer | Good — browse release-groups + artist albums |
| | ALBUM_TRACKS | MusicBrainz, Deezer | Good — media array + album tracks endpoint |
| | ALBUM_METADATA | Deezer, Discogs, iTunes | Good — trackCount, explicit, catalogNumber, rating |
| **Text** | ARTIST_BIO | Wikipedia, Last.fm | Good |
| | LYRICS_SYNCED | LRCLIB | Good |
| | LYRICS_PLAIN | LRCLIB | Good |
| **Relationships** | SIMILAR_ARTISTS | Last.fm | OK — requires API key |
| | SIMILAR_TRACKS | Last.fm | OK — via track.getSimilar |
| | ARTIST_LINKS | MusicBrainz | Good — all URL relation types parsed |
| **Statistics** | ARTIST_POPULARITY | Last.fm, ListenBrainz | Good — batch endpoints available |
| | TRACK_POPULARITY | Last.fm, ListenBrainz | Good — track.getInfo + batch recording endpoint |

### Provider Utilization

| Provider | Endpoints Used | Utilization | Notes |
|----------|---------------|-------------|-------|
| **MusicBrainz** | 9 | ~70% | artist-rels, release-group browse, media array, all URL relations |
| **Last.fm** | 4 methods | ~40% | artist.getinfo, artist.getsimilar, track.getSimilar, track.getInfo |
| **Fanart.tv** | 1 | ~50% | artist endpoint: all image types including banners |
| **Deezer** | 4 | ~50% | album search, artist search, artist albums, album tracks |
| **Discogs** | 3 | ~40% | search, artist lookup (members), album metadata |
| **Cover Art Archive** | 2 | ~60% | front/back/booklet via JSON metadata, multi-size thumbnails |
| **ListenBrainz** | 4 | ~30% | top recordings, batch recording popularity, batch artist popularity, top release groups |
| **iTunes** | 1 | ~25% | album search with metadata extraction (trackCount, genre, price) |
| **Wikidata** | 1 (5 properties) | ~15% | P18 image, P569/P570 dates, P495 country, P106 occupation |
| **Wikipedia** | 2 | ~50% | summary + page media-list for supplemental photos |
| **LRCLIB** | 2 | ~100% | fully utilized |

---

## What's Left

### Phase 3: New Capabilities (future)

These are the remaining items from the original roadmap, not yet implemented.

#### 3A. Credits & Personnel
**New type: `CREDITS`**

MusicBrainz recording/release relationships can identify: producer, engineer, mixer, mastering, composer, lyricist, performer (instrument). Discogs has detailed credits per track.

```
Credits(credits: List<Credit>)
Credit(name, role, type?, identifiers?)  // type = "producer", "performer:guitar", etc.
```

#### 3B. Release Editions
**New type: `RELEASE_EDITIONS`**

MusicBrainz groups releases by release-group. Could list all editions: original, deluxe, remaster, vinyl, etc. with country, date, format, barcode.

#### 3C. Artist Timeline
Combine MusicBrainz life-span + Wikidata dates + discography into a timeline: formed → first album → member changes → hiatus → reunion → latest release.

#### 3D. Genre Deep Dive
Last.fm tags + MusicBrainz tags often differ. Could merge/deduplicate and add confidence to each genre tag. Last.fm's `tag.gettopartists` could feed a "top artists in genre" feature.

---

### Tech Debt (from v0.4.0)

Minor items deferred during the milestone:

- `ErrorKind` enum exists but no provider categorizes errors yet (all default to UNKNOWN)
- `HttpResult`/`fetchJsonResult()` exists but no provider uses it yet (all use legacy nullable methods)
- SIMILAR_TRACKS only implemented by Last.fm (Deezer was descoped)
- ListenBrainz `getTopReleaseGroupsForArtist` + `toDiscography` mapper plumbing exists but not wired as a capability

---

## Priority Scorecard

Ranked by **impact to consumers × implementation effort** (updated for v0.4.0):

| # | Feature | New Types | Impact | Effort | Status |
|---|---------|-----------|--------|--------|--------|
| 1 | Multiple art sizes | Enhancement | High | Medium | ✅ Done (v0.4.0) |
| 2 | Album tracklist | ALBUM_TRACKS | High | Low | ✅ Done (v0.4.0) |
| 3 | Artist discography | ARTIST_DISCOGRAPHY | High | Medium | ✅ Done (v0.4.0) |
| 4 | Band members | BAND_MEMBERS | High | Medium | ✅ Done (v0.4.0) |
| 5 | Artist links | ARTIST_LINKS | Medium | Low | ✅ Done (v0.4.0) |
| 6 | Artist banner | ARTIST_BANNER | Medium | Very low | ✅ Done (v0.4.0) |
| 7 | Similar tracks | SIMILAR_TRACKS | Medium | Low | ✅ Done (v0.4.0) — Last.fm only |
| 8 | Wikidata enrichment | Enhancement | Medium | Low | ✅ Done (v0.4.0) |
| 9 | Album back/booklet art | Enhancement | Medium | Low | ✅ Done (v0.4.0) |
| 10 | Credits & personnel | CREDITS | High | High | Planned |
| 11 | Wikipedia media | Enhancement | Low | Medium | ✅ Done (v0.4.0) |
| 12 | Release editions | RELEASE_EDITIONS | Low | Medium | Planned |

---

## Summary

| Dimension | v0.1.0 | v0.4.0 | After Phase 3 |
|-----------|--------|--------|---------------|
| Enrichment types | 16 | 25 (+9) | 28 (+3) |
| Provider utilization | ~30% avg | ~50% avg | ~70% avg |
| "App-ready" artist page | Partial (photo, bio, genres, similar) | Full (+ members, discography, links, banner, dates, country) | Complete (+ credits) |
| "App-ready" album page | Partial (front art, genres, label, date) | Full (+ tracklist, back art, booklet, all sizes, metadata from 3 sources) | Complete (+ credits, editions) |
| "App-ready" now-playing | Partial (lyrics, album art) | Rich (+ similar tracks, multi-size art, track-level popularity) | Complete (+ credits) |

**The architecture and abstraction layer are done.** The mapper pattern, typed identifiers, and confidence standardization make adding new capabilities straightforward. Phase 3 items (credits, editions, timeline, genre) are the remaining gap to "complete" coverage.
