# Last.fm

What our code does with Last.fm. For the API itself — endpoints, request shapes, error codes, rate
limits — follow the upstream link; that is authoritative and this is not.

| | |
|---|---|
| **Package** | `provider/lastfm/` |
| **Provider ids** | `lastfm` |
| **Upstream API docs** | https://www.last.fm/api |
| **Auth** | API key required — `lastfm.apikey` / `LASTFM_API_KEY`, see [README](../../README.md) |
| **Deviations from the house pattern** | None — the four files, as `CLAUDE.md` describes them |

**Why this provider.** It is the only source in the tree for artist tags-as-genre and artist-level
similarity, and it holds the widest capability set of any single provider here: eight types across
all three request kinds. Everything it returns is community-scrobbled, so it is popular-artist rich
and long-tail poor.

## What We Extract

One row per entry in `LastFmProvider.capabilities`.

| EnrichmentType | Request | Upstream call | What we keep |
|---|---|---|---|
| `SIMILAR_ARTISTS` | `ForArtist` | `artist.getsimilar`, `limit=20` | `name`, `match` → `matchScore`, `mbid` |
| `GENRE` | `ForArtist` | `artist.getinfo` | `tags.tag[].name`, in response order, blanks dropped |
| `ARTIST_BIO` | `ForArtist` | `artist.getinfo` | `bio.summary` only, verbatim |
| `ARTIST_POPULARITY` | `ForArtist` | `artist.getinfo` | `stats.listeners`, `stats.playcount` |
| `ARTIST_TOP_TRACKS` | `ForArtist` | `artist.gettoptracks`, `limit=1000` | `name`, `artist.name`, `playcount`, `listeners`, `mbid`; `rank` from array position |
| `SIMILAR_TRACKS` | `ForTrack` | `track.getsimilar`, `limit=20` | `name`, `artist.name`, `match`, `mbid` |
| `TRACK_POPULARITY` | `ForTrack` | `track.getInfo` | `playcount`, `listeners` |
| `ALBUM_METADATA` | `ForAlbum` | `album.getinfo` | `tags.tag[].name`, and `tracks.track[]` length as `trackCount` |

Priorities are in the code, not restated here — they move, and `ProviderRegistry` is what reads them.
The two that are not the default `100`: `ARTIST_BIO` is `50` (Wikipedia outranks it at 100) and
`ALBUM_METADATA` is `40` (Deezer outranks it at 50; Discogs ties at 40).

Three things hold for every row:

- **No `identifierRequirement`.** Every capability is name-searched, so the engine will call this
  provider with nothing but a name — see `docs/pitfalls.md` §5 for when that is the wrong default.
- **Confidence is a constant `0.8`.** `ConfidenceCalculator.fuzzyMatch(hasArtistMatch = true)` is
  passed literally at every success site; nothing here measures whether the returned artist is the
  one asked for. It clears `filterByConfidence()`'s `0.5` floor unconditionally.
- **A tag becomes a `GenreTag` at confidence `0.3`,** fixed, for both `GENRE` and `ALBUM_METADATA`.

`ALBUM_METADATA` has one extra exit: `enrichAlbumMetadata` returns `NotFound` when both `genres` and
`trackCount` come back empty, so an album that exists upstream but carries neither reads as a miss.

## What We DON'T Extract

Parsed into a DTO and then dropped by the mapper — the cheapest to add, since the request already
happens and the field is already read:

| Field | Call | DTO field |
|---|---|---|
| Album `playcount`, `listeners` | `album.getinfo` | `LastFmAlbumInfo.playcount`, `.listeners` |
| Album `wiki.summary` | `album.getinfo` | `LastFmAlbumInfo.wiki` |
| Album `name`, `artist` | `album.getinfo` | `LastFmAlbumInfo.name`, `.artist` |
| Track `mbid` | `track.getInfo` | `LastFmTrackInfo.mbid` |

In responses we already fetch but never read:

| Field | Call | Useful for |
|---|---|---|
| `artist.bio.content` | `artist.getinfo` | Full bio; we take `summary` only |
| `artist.similar.artist[]` | `artist.getinfo` | Similar artists without the second `artist.getsimilar` call |
| `artist.url` | `artist.getinfo` | Last.fm profile link, for `ARTIST_LINKS` |
| `artist.image[]` | `artist.getinfo` | Artist photos. Widely reported as empty or dead since ~2020 — upstream behaviour, not verified here |
| `tag.count` / `tag.url` | `artist.getinfo` | Tag vote weight, which would let us rank tags rather than trust response order |

Endpoints we never call: `artist.gettopalbums`, `album.getTopTags`, `track.getTopTags`,
`tag.getTopArtists`, `tag.getTopAlbums`, `chart.getTopArtists`, `chart.getTopTracks`, and everything
under `geo.` and `user.`. Authenticated methods need the shared secret, which we never read.

## Gotchas

Nothing here is Last.fm-specific enough to restate — the general traps that bite this package are:

- `docs/pitfalls.md` §3 — `LastFmApi` reads every field through `optString`/`optJSONObject`, so a
  renamed upstream field yields `""` or an empty list rather than a failure. `parseArtistInfo`
  returning a `LastFmArtistInfo` with `name = ""` is indistinguishable from a real one.
- `docs/pitfalls.md` §2 — the four `catch (e: Exception)` sites in `LastFmProvider.enrich` all route
  to `mapError`, which deliberately does not special-case cancellation; `ProviderChain` settles it.
- `docs/pitfalls.md` §4 — a blank API key, a wrong request subtype, and an empty result list all
  return `NotFound`, not `Error`, and so record breaker *success*.

One thing that is ours: `isAvailable` is `apiKeyProvider().isNotBlank()`, re-read on every access, so
a key supplied late is picked up without rebuilding the engine. `withDefaultProviders()` never
constructs the provider at all when no key is configured, so the blank-key path only occurs when a
consumer registers it by hand.
