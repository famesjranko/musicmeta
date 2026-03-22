# musicmeta — Gap Analysis & Roadmap

> Where we are, where we're going, and what it takes to get there.

---

## The Goal

An open-source music metadata engine that any app can drop in to get **everything** about an artist, album, or track:

- **Artwork**: all types (album front/back, artist photo, banner, logo, fanart, CD art, booklet) at all sizes (thumb → hero)
- **Metadata**: artist → members → discography → albums → tracks, with labels, dates, countries, genres
- **Text content**: biographies, lyrics (synced + plain)
- **Relationships**: similar artists, similar tracks
- **Statistics**: popularity scores, rankings, listen counts
- **Links**: social media, websites, streaming profiles
- **Credits**: producers, performers, composers, engineers
- **Recommendations**: discovery and radio features built on enrichment data
  - *Similar Artists* — multi-provider similarity (Last.fm, ListenBrainz, Deezer)
  - *Similar Tracks* — track-level "you might also like"
  - *Similar Albums* — synthesized from similar artists + genre + era
  - *Radio/Mix* — seed-based playlist generation (Deezer radio, ListenBrainz)
  - *Credit-Based Discovery* — "more from this producer/composer" via credits data
  - *Genre Discovery* — genre affinity neighbors via confidence scores
  - *Listening-Based* — collaborative filtering recommendations (user-scoped)
- **Developer Experience**: convenience API that makes integration effortless
  - *Profile methods* — `engine.artistProfile("Radiohead")` returns a structured object with all fields pre-extracted
  - *AlbumProfile / TrackProfile* — same pattern for albums and tracks
  - *Type-safe requests* — only valid types for each entity kind, no wasted calls
  - *Cleaner data model* — split overloaded Metadata into focused types
  - *Smart defaults* — request the right types automatically based on entity kind
- **Catalog Awareness**: recommendations filtered by what the user can actually play
  - *Catalog Provider* — pluggable interface answering "does the user have access to X?"
  - *Local Library* — scan local files, match by title/artist/fingerprint
  - *Streaming Services* — Spotify, YouTube Music, Apple Music, Tidal catalog checks
  - *Filtering Modes* — unfiltered (pure discovery), available-only, available-first (ranked)
  - *Availability Scoring* — recommendations ranked by how accessible they are to this user

---

## Where We Are (v0.5.0)

### What Changed Since v0.4.0

v0.5.0 was **New Capabilities & Tech Debt Cleanup** — 6 phases, 16 plans, shipped 2026-03-22.

Key additions:
- **HttpResult/ErrorKind uniform adoption** — All 11 providers migrated from nullable `fetchJson` to typed `HttpResult` with `ErrorKind` classification (NETWORK, PARSE, AUTH, UNKNOWN)
- **Credits & Personnel** — New `CREDITS` type from MusicBrainz recording artist-rels/work-rels (priority 100) and Discogs release extraartists (priority 50), with `roleCategory` grouping (performance, production, songwriting)
- **Release Editions** — New `RELEASE_EDITIONS` type from MusicBrainz release-group releases (priority 100) and Discogs master versions (priority 50)
- **Artist Timeline** — New `ARTIST_TIMELINE` composite type that auto-resolves ARTIST_DISCOGRAPHY + BAND_MEMBERS, synthesizes life-span + discography + member changes into chronological events. First composite enrichment type in the engine.
- **Genre Enhancement** — `GenreTag` with per-tag confidence scores, `GenreMerger` for multi-provider deduplication/scoring, `ProviderChain.resolveAll()` for mergeable types. GENRE now collects from all 4 providers instead of short-circuiting.
- **Provider Coverage Expansion** — Last.fm album.getinfo (ALBUM_METADATA), iTunes lookup endpoints (ALBUM_TRACKS, ARTIST_DISCOGRAPHY), Fanart.tv album-specific art, ListenBrainz similar artists, Discogs community rating/have/want data.

### Current Coverage (28 enrichment types)

| Category | Type | Providers | Depth |
|----------|------|-----------|-------|
| **Artwork** | ALBUM_ART | CAA, Fanart.tv, Deezer, iTunes | Good — 4 providers, multi-size, Fanart.tv album-first |
| | ALBUM_ART_BACK | CAA | Good — via JSON metadata endpoint |
| | ALBUM_BOOKLET | CAA | Good — via JSON metadata endpoint |
| | ARTIST_PHOTO | Wikidata, Fanart.tv, Wikipedia | Good — 3 providers |
| | ARTIST_BACKGROUND | Fanart.tv | Thin — requires API key + MBID |
| | ARTIST_LOGO | Fanart.tv | Thin — requires API key + MBID |
| | ARTIST_BANNER | Fanart.tv | OK — requires API key + MBID |
| | CD_ART | Fanart.tv | OK — album-specific endpoint added |
| **Metadata** | GENRE | MusicBrainz, Last.fm, Discogs, iTunes | **Excellent** — 4 providers merged via GenreMerger with confidence scores |
| | LABEL | MusicBrainz, Discogs | Good |
| | RELEASE_DATE | MusicBrainz | OK |
| | RELEASE_TYPE | MusicBrainz, Discogs | OK |
| | COUNTRY | MusicBrainz, Wikidata | Good |
| | BAND_MEMBERS | MusicBrainz, Discogs | Good |
| | ARTIST_DISCOGRAPHY | MusicBrainz, Deezer, ListenBrainz, iTunes | **Excellent** — 4 providers |
| | ALBUM_TRACKS | MusicBrainz, Deezer, iTunes | Good — 3 providers |
| | ALBUM_METADATA | Deezer, Last.fm, Discogs, iTunes | **Excellent** — 4 providers with community data |
| | CREDITS | MusicBrainz, Discogs | **NEW** — recording rels + extraartists with roleCategory |
| | RELEASE_EDITIONS | MusicBrainz, Discogs | **NEW** — release-group releases + master versions |
| **Text** | ARTIST_BIO | Wikipedia, Last.fm | Good |
| | LYRICS_SYNCED | LRCLIB | Good |
| | LYRICS_PLAIN | LRCLIB | Good |
| **Relationships** | SIMILAR_ARTISTS | Last.fm, ListenBrainz | Good — 2 providers (ListenBrainz added) |
| | SIMILAR_TRACKS | Last.fm | OK — via track.getSimilar |
| | ARTIST_LINKS | MusicBrainz | Good — all URL relation types parsed |
| **Statistics** | ARTIST_POPULARITY | Last.fm, ListenBrainz | Good — batch endpoints available |
| | TRACK_POPULARITY | Last.fm, ListenBrainz | Good |
| **Composite** | ARTIST_TIMELINE | TimelineSynthesizer | **NEW** — auto-resolves sub-types, synthesizes chronological events |

### Provider Utilization

| Provider | Endpoints Used | Utilization | Change from v0.4.0 |
|----------|---------------|-------------|---------------------|
| **MusicBrainz** | 11 | ~85% | +2 (lookupRecording, lookupReleaseGroup) |
| **Last.fm** | 5 methods | ~50% | +1 (album.getinfo) |
| **Fanart.tv** | 2 | ~67% | +1 (album-specific endpoint) |
| **Deezer** | 4 | ~50% | — |
| **Discogs** | 5 | ~65% | +2 (getReleaseDetails, getMasterVersions) |
| **Cover Art Archive** | 2 | ~60% | — |
| **ListenBrainz** | 6 | ~43% | +2 (ARTIST_DISCOGRAPHY wired, getSimilarArtists) |
| **iTunes** | 3 | ~43% | +2 (lookupAlbumTracks, lookupArtistAlbums) |
| **Wikidata** | 1 (5 properties) | ~15% | — |
| **Wikipedia** | 2 | ~50% | — |
| **LRCLIB** | 2 | ~100% | — |

**Average utilization: ~57%** (up from ~48% in v0.4.0)

---

## What's Left

### Remaining Gaps (no planned milestone)

Most original roadmap items are now shipped. What remains:

- **SIMILAR_TRACKS** only implemented by Last.fm (Deezer was descoped in v0.4.0)
- **itunesArtistId** not stored in resolvedIdentifiers — re-searches on every discography call (minor perf tech debt from v0.5.0)
- **ForAlbum credits aggregation** — CREDITS only supports ForTrack; aggregating per-track credits for an album deferred
- **Generic CompositeType registry** — ARTIST_TIMELINE handled specifically; no generic framework yet
- **Genre taxonomy hierarchy** — parent/child genre relationships not modeled
- **Last.fm artist.gettoptracks** — could enhance ARTIST_POPULARITY with top track names/scrobble counts

### The Catalog Awareness Problem

Recommendations without context are noise. If we recommend 50 radio tracks and the user has 2 of them in their library, that's a bad experience. The value of a recommendation depends entirely on whether the user can act on it.

**The spectrum of user contexts:**

| User Type | Catalog | Recommendation Value |
|-----------|---------|---------------------|
| Local-only (FLAC hoarder) | Fixed local files | Only useful if recommended tracks are in their library |
| Spotify/YT Music subscriber | ~100M tracks | Almost everything is available — filtering less critical |
| Mixed (local + streaming) | Local + service catalog | Prefer local, fall back to streaming |
| Offline/airplane mode | Cached subset | Only downloaded/cached content matters |

**Possible approach — `CatalogProvider` interface:**

```
CatalogProvider {
    fun isAvailable(title, artist, album?): CatalogMatch?
    fun search(query): List<CatalogMatch>
}

CatalogMatch(available: Boolean, source: String, uri: String?, confidence: Float)
```

Implementations: `LocalLibraryCatalog`, `SpotifyCatalog`, `YouTubeMusicCatalog`, etc.

**Filtering modes:**
- **Unfiltered** — pure discovery, show everything (default, backward compatible)
- **Available-only** — only return recommendations the user can play right now
- **Available-first** — rank available items higher, still show unavailable as "you might want to add this"

**Open questions:**
- Does catalog awareness belong in musicmeta-core or in a separate module (musicmeta-catalog)?
- Should it be a post-filter on recommendations, or should it influence which providers/endpoints to call?
- How to handle streaming service APIs that require user auth tokens? (Spotify Web API needs OAuth)
- Is fingerprint-based matching (AcoustID/Chromaprint) in scope for local library matching?

This is the hardest unsolved design problem for the recommendations feature. The metadata and recommendation engines can be built independently, but their value multiplies when catalog-aware.

### Provider Coverage Gaps

Endpoints with diminishing returns (niche, write APIs, deprecated):
- Wikidata: ~85% unused but remaining properties are niche (occupation subtypes, genre claims)
- ListenBrainz: ~57% unused but remaining are CF recommendations, charts, sitewide stats
- LRCLIB: publish endpoint (write API, not relevant)
- Wikipedia: deprecated mobile-sections endpoint

---

## Priority Scorecard

Ranked by **impact to consumers × implementation effort** (updated for v0.5.0):

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
| 10 | Credits & personnel | CREDITS | High | High | ✅ Done (v0.5.0) — MB + Discogs |
| 11 | Wikipedia media | Enhancement | Low | Medium | ✅ Done (v0.4.0) |
| 12 | Release editions | RELEASE_EDITIONS | Low | Medium | ✅ Done (v0.5.0) — MB + Discogs |
| 13 | Artist timeline | ARTIST_TIMELINE | Medium | Medium | ✅ Done (v0.5.0) — Composite type |
| 14 | Genre confidence scores | Enhancement | Medium | Medium | ✅ Done (v0.5.0) — GenreMerger |
| 15 | Provider coverage expansion | Enhancement | Medium | Low | ✅ Done (v0.5.0) — 5 providers deepened |
| 16 | HttpResult/ErrorKind adoption | Enhancement | Low | Medium | ✅ Done (v0.5.0) — all 11 providers |

---

## Summary

| Dimension | v0.1.0 | v0.4.0 | v0.5.0 |
|-----------|--------|--------|--------|
| Enrichment types | 16 | 25 (+9) | 28 (+3) |
| Provider utilization | ~30% avg | ~48% avg | ~57% avg |
| Engine concepts | Provider chains | + Typed identifiers, mapper pattern, confidence calculator | + Composite types, mergeable types, GenreMerger |
| "App-ready" artist page | Partial (photo, bio, genres, similar) | Full (+ members, discography, links, banner, dates, country) | **Complete** (+ credits, timeline, merged genres with confidence) |
| "App-ready" album page | Partial (front art, genres, label, date) | Full (+ tracklist, back art, booklet, all sizes, metadata from 3 sources) | **Complete** (+ credits, editions, 4-source metadata, community data) |
| "App-ready" now-playing | Partial (lyrics, album art) | Rich (+ similar tracks, multi-size art, track-level popularity) | **Complete** (+ credits, similar artists from 2 sources) |

**The "app-ready" story is complete.** A music app using musicmeta now has everything it needs for artist pages, album pages, and now-playing screens. The architecture supports three enrichment patterns: standard provider chains, composite synthesis (ARTIST_TIMELINE), and multi-provider merging (GENRE). All original Phase 3 roadmap items (credits, editions, timeline, genre) are shipped.

### How close to the goal?

| Goal Category | Coverage | Assessment |
|--------------|----------|------------|
| Artwork | 7 types, 4 providers for album art | ✅ **Complete** |
| Metadata | 9 types including credits + editions | ✅ **Complete** |
| Text content | Bios + synced/plain lyrics | ✅ **Complete** |
| Relationships | Similar artists (2), similar tracks, links | ✅ **Complete** |
| Statistics | Artist + track popularity from 2 sources | ✅ **Complete** |
| Links | All MusicBrainz URL relation types | ✅ **Complete** |
| Credits | Performers, producers, composers, engineers | ✅ **Complete** |
| Recommendations | Similar artists + tracks exist; 5 modules not started | 🟡 **Foundation only** |
| Developer Experience | Engine works, consumer API is power-user only | ❌ **Not started** |
| Catalog Awareness | Not started — design problem unsolved | ❌ **Not started** |

**7/10 goal categories complete.** Metadata enrichment is done. Three categories remain: Recommendations, Catalog Awareness, and Developer Experience.

#### Recommendation Module Status

| Module | Status | Foundation |
|--------|--------|------------|
| Similar Artists | 🟡 Partial — 2 providers, Deezer `/artist/{id}/related` untapped | SIMILAR_ARTISTS type exists |
| Similar Tracks | 🟡 Partial — Last.fm only, Deezer radio untapped | SIMILAR_TRACKS type exists |
| Similar Albums | ❌ Not started | Could synthesize from similar artists + genre + era |
| Radio/Mix | ❌ Not started | Deezer `/artist/{id}/radio` available |
| Credit-Based Discovery | ❌ Not started | CREDITS data exists, needs discovery queries |
| Genre Discovery | ❌ Not started | GenreMerger scores exist, needs affinity matching |
| Listening-Based | ❌ Not started | ListenBrainz CF endpoints available (user-scoped) |

#### Developer Experience Status

The engine works well, but the consumer API is a power-user API. Developers building music apps have to manually request types, cast results, and handle the Map pattern. A convenience layer would make the library feel effortless for the 80% use case.

| Issue | Impact | Status |
|-------|--------|--------|
| Verbose result handling (Map + casting) | High — every consumer writes boilerplate | ❌ Not started |
| No high-level profile methods | High — "give me an artist page" is the #1 use case | ❌ Not started |
| No type safety per request kind | Medium — ForArtist + LYRICS_SYNCED = wasted call | ❌ Not started |
| Metadata class overloaded | Medium — 15 nullable fields, unclear which are populated | ❌ Not started |
| "All types" returns noisy NotFounds | Low — 11 NotFounds for ForAlbum is confusing | ❌ Not started |

---

## Planned Milestones

### v0.6.0 — Recommendations Engine
Build discovery features on top of the enrichment data. Add Deezer radio/related endpoints, ListenBrainz CF recommendations, synthesized similar albums, credit-based discovery, and genre affinity matching. Depends on existing SIMILAR_ARTISTS, SIMILAR_TRACKS, CREDITS, GenreMerger infrastructure.

### v0.7.0 — Developer Experience
Add a convenience layer that makes musicmeta feel effortless for the 80% use case. High-level `artistProfile()` / `albumProfile()` / `trackProfile()` methods returning structured objects with all fields pre-extracted. Type-safe request scoping. Cleaner data model. The low-level `enrich()` API stays for power users. This is a thin layer on top of what already works — no engine changes needed.

### v0.8.0+ — Catalog Awareness (exploratory)
The hardest unsolved problem. Recommendations are only valuable if the user can play them. Needs a `CatalogProvider` interface that answers "does the user have access to this?" with implementations for local libraries, Spotify, YouTube Music, etc. Filtering modes: unfiltered (discovery), available-only, available-first. May live in a separate module (`musicmeta-catalog`). Design depends on learnings from v0.6.0 recommendations.
