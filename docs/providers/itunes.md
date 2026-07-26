# iTunes

What our code does with the iTunes Search API. For the API itself — endpoints, request shapes, error
codes, rate limits — follow the upstream link; that is authoritative and this is not.

| | |
|---|---|
| **Package** | `provider/itunes/` |
| **Provider ids** | `itunes` |
| **Upstream API docs** | https://performance-partners.apple.com/search-api |
| **Auth** | None |
| **Deviations from the house pattern** | None — the four files, as `CLAUDE.md` describes them |

**Why this provider.** A no-key album search that returns artwork at any size from one URL, so it is
the fallback when MusicBrainz has no MBID and the Cover Art Archive therefore cannot be asked. It is
also the most aggressively rate-limited provider in the tree: `RateLimiter(3000)` by constructor
default, which `withDefaultProviders()` takes, against roughly 20 requests a minute upstream.

## What We Extract

One row per entry in `ITunesProvider.capabilities`. The two lists are compared by
`scripts/checks/check_provider_capabilities.py` on every `./check`.

| EnrichmentType | Request | Upstream call | What we keep |
|---|---|---|---|
| `ALBUM_ART` | `ForAlbum` | `/search?entity=album&limit=5` | `artworkUrl100`, rewritten to `artworkSize` (1200) plus 250/500/1000/3000 variants |
| `ALBUM_METADATA` | `ForAlbum` | the same search | `trackCount`, `primaryGenreName`, `country`, `releaseDate` |
| `ALBUM_TRACKS` | `ForAlbum` | `/lookup?id={collectionId}&entity=song`, after a search if needed | `trackName`, `trackNumber`, `trackTimeMillis` |
| `ARTIST_DISCOGRAPHY` | `ForArtist` | `/search?entity=musicArtist`, then `/lookup?id={artistId}&entity=album` | `collectionName`, `releaseDate` year, `artworkUrl100`, `collectionId` |

Every priority is low — 40 for art, 30 for the rest — so iTunes wins only when the sources above it
return nothing.

**The artwork trick.** iTunes hands back a 100×100 thumbnail URL and every other size is a string
substitution: `ITunesMapper.toArtwork` replaces `100x100bb` with `{size}x{size}bb`. If Apple ever
changes that URL segment, the substitution silently no-ops and every size in `ArtworkSize` becomes
the same 100px image — a successful, wrong result. `thumbnailUrl` is deliberately the untouched
100×100 URL.

**Artist matching is real here,** unlike most providers: `ALBUM_ART`, `ALBUM_METADATA` and the search
path of `ALBUM_TRACKS` all take the first of five results that passes `ArtistMatcher.isMatch`, and
score it `fuzzyMatch(hasArtistMatch = true)`, 0.8. No match at all is `NotFound`, not the first row.

**Two capabilities feed identifiers back.** `ALBUM_TRACKS` and `ARTIST_DISCOGRAPHY` return
`resolvedIdentifiers` carrying `itunesCollectionId` / `itunesArtistId` as `EnrichmentIdentifiers`
extras. On a later request that already carries the id, both skip the search entirely and score
`idBasedLookup()`, 1.0 — the only path in this package that reaches deterministic confidence.

## What We DON'T Extract

`searchCandidates` is implemented for `ForAlbum` (five results, `SearchCandidate.score` hardcoded to
70 rather than derived from anything) but **returns `EnrichmentIdentifiers()` — empty**. Picking an
iTunes candidate in a disambiguation flow therefore carries no `collectionId` forward, so the very
next request re-searches. `ITunesMapper.toSearchCandidate` has the id in hand when it does this.

Fields on a result we already fetch and never read: `collectionViewUrl`, `collectionPrice`,
`copyright`, `contentAdvisoryRating`, `collectionExplicitness`, `amgArtistId`, and `artistViewUrl`.
From a track lookup: `previewUrl` (Deezer supplies `TRACK_PREVIEW` instead), `discNumber`,
`discCount`, `trackPrice`, `isStreamable`.

Entities we never search: `song`, `musicVideo`, `podcast`, `audiobook`. `ITunesApi.searchAlbum`
(singular) is dead code — no caller.

## Gotchas

- `docs/pitfalls.md` §3 — `parseAlbumResult` is `optString`/`optLong`/`optInt` throughout, with
  `takeIfNotEmpty()` turning a moved field into null rather than `""`. A renamed `artworkUrl100` reads
  as an album with no art.
- `docs/pitfalls.md` §2 — `searchCandidates` is the worked example: it swallows exceptions to return
  `emptyList()`, so it calls `currentCoroutineContext().ensureActive()` first. That guard is load
  bearing; the function does not suspend again.
- `docs/pitfalls.md` §4 — no artist match, an empty tracklist and a missing `collectionId` are all
  `NotFound`, so they record breaker *success*.
- `docs/pitfalls.md` §5 — nothing declares an `identifierRequirement`, correctly: this is a name
  search, and the `itunes*Id` extras are an optimisation rather than a precondition.

Ours: the `100x100bb` substitution and the hardcoded `listOf(250, 500, 1000, 3000)` are the two
places where a size the API stops serving becomes a broken URL we report as a `Success`.
