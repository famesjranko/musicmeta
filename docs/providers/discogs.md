# Discogs

What our code does with Discogs. For the API itself — endpoints, request shapes, error codes, rate
limits — follow the upstream link; that is authoritative and this is not.

| | |
|---|---|
| **Package** | `provider/discogs/` |
| **Provider ids** | `discogs` |
| **Upstream API docs** | https://www.discogs.com/developers |
| **Auth** | Personal token required — `discogs.token` / `DISCOGS_TOKEN`, see [README](../../README.md) |
| **Deviations from the house pattern** | None — the four files, as `CLAUDE.md` describes them |

**Why this provider.** Pressing-level detail nothing else in the tree has: catalogue numbers,
per-edition formats, and per-track engineering credits. Every priority is a fallback — nothing here
outranks MusicBrainz for the types they share.

## What We Extract

One row per entry in `DiscogsProvider.capabilities`. The two lists are compared by
`ProviderFeatureDocsTest` on every `./check`.

| EnrichmentType | Request | Upstream call | What we keep |
|---|---|---|---|
| `ALBUM_ART` | `ForAlbum` | `/database/search?type=release`, 5 results | `cover_image` as the URL, nothing else |
| `LABEL` | `ForAlbum` | the same search | first `label` |
| `RELEASE_TYPE` | `ForAlbum` | the same search | `format` |
| `ALBUM_METADATA` | `ForAlbum` | the same search, **plus** `/releases/{id}` | label, year, format, country, `catno`, `genres` + `styles`, `community.rating.average` |
| `ARTIST_PHOTO` | `ForArtist` | `/database/search?type=artist&per_page=1`, then `/artists/{id}` | `primary` image, or the first; `uri150` as thumbnail; every image as an `ArtworkSize` |
| `BAND_MEMBERS` | `ForArtist` | the same two calls | member `name` and `id` |
| `CREDITS` | `ForTrack` | `/releases/{id}` | track-level `extraartists`, falling back to release-level |
| `RELEASE_EDITIONS` | `ForAlbum` | `/masters/{id}/versions?per_page=100` | title, format, country, year, label, `catno` |

Priorities run 20 (`ALBUM_ART`, the lowest in the tree) to 50. Every result scores
`fuzzyMatch(hasArtistMatch = false)`, 0.6 — including the two that are genuine id lookups. That is a
hair above `filterByConfidence()`'s 0.5 floor.

**Two capabilities cannot start from a name.** `CREDITS` needs a `discogsReleaseId` and
`RELEASE_EDITIONS` a `discogsMasterId`, both read from `EnrichmentIdentifiers.extra`. Neither
declares an `identifierRequirement` — `IdentifierRequirement` has no Discogs member — so the engine
routes to them regardless and they return `NotFound` before making any call. The only thing that
seeds those extras is an earlier Discogs album result: `success()` attaches `resolvedIdentifiers`
carrying both ids on `ALBUM_ART`, `LABEL`, `RELEASE_TYPE` and `ALBUM_METADATA`.

**The artist check falls through.** The album search takes the first of five results whose
`title.substringBefore(" - ")` passes `ArtistMatcher.isMatch` — and on no match at all,
`?: releases.firstOrNull()` takes the first result anyway. A wrong-artist album is a `Success` at
0.6, not a `NotFound`. `searchArtist` is the same shape with `per_page=1` and no name check at all.
Recorded, not changed.

**`CREDITS` role categories are a substring ladder.** `DiscogsMapper.mapRoleCategory` tests ~30
lowercase substrings in order and returns `performance`, `production`, `songwriting` or null. First
match wins, so "Vocal Arrangement" is categorised `performance` on `vocal` before `arrang` is
reached, and any role matching none of the 30 arrives with a null category.

## What We DON'T Extract

Parsed from `/releases/{id}`, which `ALBUM_METADATA` and `CREDITS` already fetch, then dropped:

| Field | DTO field |
|---|---|
| `community.rating.count` | `DiscogsReleaseDetail.ratingCount` |
| `community.have` / `community.want` | `.haveCount`, `.wantCount` |
| tracklist positions and titles | `DiscogsTrackItem.position` — read only to match the requested title |
| member active/inactive flag | `DiscogsMember.active` |

`DiscogsMapper.toAlbumMetadataFromDetail` builds a `Metadata` from the community rating and has no
caller in main sources — only `DiscogsMapperTest` — because `enrichAlbumMetadataWithCommunity` uses
`baseMetadata.copy(communityRating = …)` instead.

From a search result: `thumb` (so `ALBUM_ART` returns no thumbnail and no `sizes`, unlike
`ARTIST_PHOTO`), `barcode`, `formats[].descriptions`, `resource_url`, `community`.

Endpoints we never call: `/artists/{id}/releases` (discography — MusicBrainz and iTunes cover it),
`/labels/{id}` and `/labels/{id}/releases`, the marketplace and inventory endpoints, and every user
collection endpoint. `ReleaseEdition.barcode` is set to null explicitly because
`/masters/{id}/versions` does not carry it; `/releases/{id}` does.

## Gotchas

- `docs/pitfalls.md` §5 — nothing declares an `identifierRequirement`, and for the six name-searched
  capabilities that is right. For `CREDITS` and `RELEASE_EDITIONS` it is a gap the enum cannot
  currently express.
- `docs/pitfalls.md` §3 — the parsers are `optString`/`optInt` throughout, so a moved field yields
  null and reads as a release with no label.
- `docs/pitfalls.md` §4 — a blank token, a missing extra id, an empty search and an empty credits
  list are all `NotFound`, so they record breaker *success*.

Ours: `isAvailable` is `tokenProvider().isNotBlank()`, re-read on every access;
`withDefaultProviders()` skips the provider entirely when no token is configured. The token travels
as a `token=` query parameter on every URL, so it appears in any log that records full URLs.
