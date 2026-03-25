# Provider Reference Docs

Per-provider documentation for all external APIs used by musicmeta. Each doc covers the API surface, auth requirements, rate limits, what we use, what's available but untapped, and gotchas.

## Quick Reference

| Provider | Auth | Rate Limit | Doc |
|----------|------|------------|-----|
| [MusicBrainz](musicbrainz.md) | User-Agent only | 1 req/sec | Identity backbone — MBIDs, Wikidata/Wikipedia links |
| [Cover Art Archive](coverartarchive.md) | None | Gentle | Album art via MBID redirect to archive.org |
| [Last.fm](lastfm.md) | API key | 5 req/sec | Similar artists, genres, bios, popularity |
| [Fanart.tv](fanarttv.md) | Project key | Gentle | Artist photos, backgrounds, logos, banners, CD art |
| [Deezer](deezer.md) | None | 10 req/sec | Album art, artist photos, discography, tracklists, radio, top tracks, similar artists/tracks, similar albums, track previews |
| [Discogs](discogs.md) | Token | 60 req/min | Labels, pressings (+ untapped: band members, credits) |
| [ListenBrainz](listenbrainz.md) | Optional token | Gentle | Popularity, similar artists, discography, top tracks, radio discovery (token required for LB Radio) |
| [LRCLIB](lrclib.md) | None | Gentle | Synced + plain lyrics |
| [Wikidata](wikidata.md) | None | Gentle | Artist photos (+ untapped: dates, genres, members, links) |
| [Wikipedia](wikipedia.md) | None | Gentle | Artist biographies |
| [iTunes](itunes.md) | None | ~20 req/min | Album art fallback via URL size trick |

## Auth Summary

**No key needed (8 providers):** MusicBrainz, Cover Art Archive, Deezer, ListenBrainz, LRCLIB, Wikidata, Wikipedia, iTunes

**Optional key (1 provider):**
- **ListenBrainz** — free user token from https://listenbrainz.org/settings/ (unlocks LB Radio / `ARTIST_RADIO_DISCOVERY`; all other capabilities work without auth)

**Key required (3 providers):**
- **Last.fm** — free API key from https://www.last.fm/api/account/create
- **Fanart.tv** — free project key from https://fanart.tv/get-an-api-key/
- **Discogs** — personal token from https://www.discogs.com/settings/developers

## Rate Limits at a Glance

| Provider | Limit | Our Setting | Notes |
|----------|-------|-------------|-------|
| MusicBrainz | 1/sec (strict) | 1100ms | Returns 503 if exceeded |
| iTunes | ~20/min (strict) | 3000ms | Silent throttle or 403 |
| Last.fm | 5/sec | 200ms | Generous |
| Discogs | 60/min (auth) | 100ms | Per-minute, not per-second |
| Deezer | 50/5sec | 100ms | |
| LRCLIB | Gentle | 200ms | |
| Others | Gentle | 100ms | |
