# Fanart.tv

What our code does with Fanart.tv. For the API itself — endpoints, request shapes, error codes, rate
limits — follow the upstream link; that is authoritative and this is not.

| | |
|---|---|
| **Package** | `provider/fanarttv/` |
| **Provider ids** | `fanarttv` |
| **Upstream API docs** | https://fanarttv.docs.apiary.io/ |
| **Auth** | Project key required — `fanarttv.apikey` / `FANARTTV_API_KEY`, see [README](../../README.md) |
| **Deviations from the house pattern** | None — the four files, as `CLAUDE.md` describes them |

**Why this provider.** It is the only source in the tree for `ARTIST_BACKGROUND`, `ARTIST_LOGO` and
`ARTIST_BANNER`, and it holds priority 100 on all three plus `CD_ART`. Everything is keyed on a
MusicBrainz id, so every result scores `authoritative()`, 0.95.

## What We Extract

One row per entry in `FanartTvProvider.capabilities`. The two lists are compared by
`ProviderFeatureDocsTest` on every `./check`.

| EnrichmentType | Identifier | Upstream call | JSON key we read |
|---|---|---|---|
| `ARTIST_PHOTO` | `MUSICBRAINZ_ID` | `/v3/music/{mbid}` | `artistthumb` |
| `ARTIST_BACKGROUND` | `MUSICBRAINZ_ID` | `/v3/music/{mbid}` | `artistbackground` |
| `ARTIST_LOGO` | `MUSICBRAINZ_ID` | `/v3/music/{mbid}` | `hdmusiclogo` |
| `ARTIST_BANNER` | `MUSICBRAINZ_ID` | `/v3/music/{mbid}` | `musicbanner` |
| `ALBUM_ART` | `MUSICBRAINZ_ID` | `/v3/music/albums/{releaseGroupMbid}`, then `/v3/music/{mbid}` | `albumcover` |
| `CD_ART` | `MUSICBRAINZ_ID` | the same two | `cdart` |

`ALBUM_ART` is priority 30 — below the Cover Art Archive and iTunes — while `CD_ART` is 100, above
the Cover Art Archive's 50. `ARTIST_PHOTO` is 80.

In every case `enrichFromImages` takes `firstOrNull()` of the list: **response order, not rank**.
`FanartTvImage.likes` is parsed on every image and never read, so the community vote that would
choose between twelve artist photos is discarded at the point it arrives.

`FanartTvMapper.toArtwork` fills `sizes` only when more than one image exists, and those
`ArtworkSize` entries carry Fanart's opaque image `id` as their `label` with null `width`/`height` —
they are alternates, not resolutions.

Two things to know about the album path:

- The `/v3/music/albums/{releaseGroupMbid}` call happens only when the request carries a release
  *group* id, and returns null to signal fall-through rather than `NotFound`.
- **The fall-through then calls the artist endpoint with whatever `musicBrainzId` holds**, which on a
  `ForAlbum` request is the release id, not an artist id. That path is unlikely to resolve. Even
  where it does, `parseArtistImages` merges `albumcover` across *every* album under the artist, so
  its first entry may belong to a different release. Recorded, not changed.

## What We DON'T Extract

Keys present in a response we already fetch:

| Key | Would give |
|---|---|
| `musiclogo` | The SD logo; we read `hdmusiclogo` only, so an artist with just an SD logo reads as having none |
| `hdmusicbanner` | The HD banner, next to the `musicbanner` we do read |
| `likes` | Parsed into `FanartTvImage.likes`, then ignored — the ranking signal for every list above |
| `id` | Used as an `ArtworkSize` label and nowhere else; it addresses a specific image upstream |
| `lang`, `disc`, `size` on `cdart` | Which disc of a set, and the rendered size |
| `name`, `mbid_id` on the artist object | Verification that the id resolved to the artist we meant |

Everything after the first image of each type is dropped except as an unlabelled `sizes` entry.

## Gotchas

- `docs/pitfalls.md` §5 — all six declare `MUSICBRAINZ_ID`, correctly: there is no name search here
  at all, and without an id the provider would burn a rate-limited request to return `NotFound`.
- `docs/pitfalls.md` §3 — `extractImages` is `optJSONArray`/`optString` throughout, so a renamed key
  yields an empty list and reads as "this artist has no logo".
- `docs/pitfalls.md` §4 — a blank key, a missing mbid, a 404 and an empty image list are all
  `NotFound`, so they record breaker *success*. That is right for the last two and arguable for a
  blank key.

Ours: `isAvailable` is `projectKeyProvider().isNotBlank()`, re-read on every access, so a key set
late is picked up without rebuilding the engine. `withDefaultProviders()` skips the provider entirely
when no key is configured.
