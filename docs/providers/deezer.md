# Deezer

What our code does with Deezer. For the API itself — endpoints, request shapes, error codes, rate
limits — follow the upstream link; that is authoritative and this is not.

| | |
|---|---|
| **Package** | `provider/deezer/` |
| **Provider ids** | `deezer`, `deezer-similar-albums` |
| **Upstream API docs** | https://developers.deezer.com/api |
| **Auth** | None |
| **Deviations from the house pattern** | `SimilarAlbumsProvider.kt` — a second public provider in the package. See below |

**Why this provider.** The widest no-key catalogue in the tree: ten capabilities from `DeezerProvider`
plus one from `SimilarAlbumsProvider`, and the only source of `ARTIST_RADIO`, `TRACK_PREVIEW` and
`SIMILAR_ALBUMS`. Artwork comes back in four fixed sizes without a URL trick.

## Deviation: a second provider class

`SimilarAlbumsProvider` is a public `EnrichmentProvider` in this package, registered separately by
`withDefaultProviders()` under its own id `deezer-similar-albums` — so it gets its own
`CircuitBreaker`, and can be enabled or disabled without touching `deezer`.

It exists because `SIMILAR_ALBUMS` is *derived*, not fetched: Deezer has no similar-albums endpoint.
`enrichSimilarAlbums` takes up to 5 related artists, pulls up to 3 albums each, scores every album by
the artist's rank (`1.0 − index/count × 0.9`) times an era multiplier against the seed album's year
(±5 years 1.2×, ±10 years 1.0×, beyond 0.8×), dedupes by lowercased title + artist, sorts by score and
caps at 20. That is up to **6 HTTP calls per request** and a scoring model with four constants in it.

Its own class comment says why it is not a `CompositeSynthesizer`: all the Deezer calls happen here
rather than inside a synthesizer, so it stays a plain provider that the engine schedules and
rate-limits like any other. It shares the package's `DeezerApi` — `withDefaultProviders()` constructs
one `DeezerApi` and hands it to both — so the two providers share a `RateLimiter`.

## What We Extract

One row per entry in `capabilities` across **both** provider classes in the package. The two lists are
compared by `ProviderFeatureDocsTest` on every `./check`.

| EnrichmentType | Request | Upstream call | What we keep |
|---|---|---|---|
| `ALBUM_ART` | `ForAlbum` | `/search/album?limit=5` | `cover_xl` → `big` → `medium` → `small`, first non-null; all four as `ArtworkSize` at 56/250/500/1000 |
| `ALBUM_METADATA` | `ForAlbum` | the same search | `nb_tracks`, `record_type`, `explicit_lyrics` |
| `ALBUM_TRACKS` | `ForAlbum` | `/search/album?limit=1`, then `/album/{id}/tracks` | title, position, duration |
| `ARTIST_PHOTO` | `ForArtist` | `/search/artist?limit=1` | `picture_xl` → … → `small`, plus all four as sizes |
| `ARTIST_DISCOGRAPHY` | `ForArtist` | `/search/artist`, then `/artist/{id}/albums?limit=50` | title, year, `record_type`, cover thumbnail, Deezer id |
| `ARTIST_TOP_TRACKS` | `ForArtist` | `/artist/{id}/top?limit=100` | title, artist, rank by position |
| `SIMILAR_ARTISTS` | `ForArtist` | `/artist/{id}/related?limit=20` | name and Deezer id; **`matchScore` is synthesised from list position** |
| `SIMILAR_TRACKS` | `ForTrack` | `/search/track?limit=5`, then `/track/{id}/radio?limit=25` | title, artist; `matchScore` synthesised the same way |
| `ARTIST_RADIO` | `ForArtist` | `/artist/{id}/radio?limit=radioLimit` (50) | a `RadioPlaylist` of tracks |
| `TRACK_PREVIEW` | `ForTrack` | `/track/{id}` or `/search/track` | the 30-second `preview` URL |
| `SIMILAR_ALBUMS` | `ForAlbum` | `/search/artist`, `/artist/{id}/related?limit=5`, then `/artist/{id}/albums?limit=3` ×5 | up to 20 scored albums — see the deviation section |

`ARTIST_RADIO`, `TRACK_PREVIEW` and `SIMILAR_ALBUMS` are priority 100 — all three uncontested, and
the last is on `SimilarAlbumsProvider` rather than `DeezerProvider`. `ARTIST_PHOTO` is 60,
`SIMILAR_ARTISTS` 30, and the remaining five 50.

**`matchScore` on `SIMILAR_ARTISTS` and `SIMILAR_TRACKS` is not Deezer's.** Deezer returns no score,
so `DeezerMapper` computes `1.0 − index/count × 0.9` from the array position. A consumer comparing it
against Last.fm's real `match` value is comparing a similarity measurement with a rank.

**Most artist-keyed capabilities short-circuit on a cached id.** `SIMILAR_ARTISTS`,
`ARTIST_TOP_TRACKS`, `ARTIST_RADIO`, `TRACK_PREVIEW` and `SIMILAR_ALBUMS` read `deezerId` from
`EnrichmentIdentifiers.extra` and skip the search when it is present — and every success writes it
back via `resolvedIdentifiers`. When the id is used, **the `ArtistMatcher.isMatch` verification is
skipped with it**, since there is no search result left to check.

Confidence is `fuzzyMatch(hasArtistMatch = true)`, 0.8, everywhere except `ARTIST_DISCOGRAPHY` and
`ALBUM_TRACKS`, which use 0.6 — correctly, since neither verifies the artist. `ALBUM_TRACKS` takes
`searchAlbums(query, 1).firstOrNull()` with no match check at all.

## What We DON'T Extract

Fields on results we already fetch:

| Field | Would give |
|---|---|
| `album.release_date` on a search result | Release date; we read it only inside `ARTIST_DISCOGRAPHY` |
| `album.genre_id` / `genres` | `GENRE` — Deezer declares no genre capability at all |
| `album.label`, `album.upc` | `LABEL`, and a barcode nothing else supplies |
| `artist.nb_fan`, `nb_album` | `ARTIST_POPULARITY` — Deezer's fan count is unused |
| `track.rank`, `track.bpm`, `track.gain` | Real popularity and audio features, in place of positional scores |
| `track.duration` on radio and top-track results | Runtime on `SIMILAR_TRACKS` and `RadioPlaylist` entries |
| `contributors`, `explicit_content_lyrics` | `CREDITS`, and a finer explicit flag than the boolean |

Endpoints we never call: `/chart`, `/genre`, `/editorial`, `/playlist/{id}`, `/podcast`, and every
`/user/**` path. `DeezerApi.searchAlbum` (singular) is dead code — no caller.

## Gotchas

- `docs/pitfalls.md` §2 — `searchCandidates` on `DeezerProvider` is one of the two worked examples:
  it swallows exceptions to return `emptyList()`, so it calls `ensureActive()` first. Removing that
  line makes a cancelled caller read as "the search found nothing".
- `docs/pitfalls.md` §3 — the parsers are `optString`/`optLong`/`optInt` throughout; a renamed cover
  field falls through the four-way `?:` chain to null and reads as an album with no art.
- `docs/pitfalls.md` §4 — no artist match, an empty tracklist and a missing preview URL are all
  `NotFound`, so they record breaker *success*.
- `docs/pitfalls.md` §5 — nothing declares an `identifierRequirement`, correctly: every path can
  start from a name, and `deezerId` is an optimisation rather than a precondition.

Ours: the four `ArtworkSize` widths (56/250/500/1000) are hardcoded in the mapper, not read from the
response. If Deezer changes what `cover_medium` renders at, the reported dimensions become wrong
while the URL stays valid.
