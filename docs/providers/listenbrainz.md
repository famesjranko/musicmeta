# ListenBrainz

What our code does with ListenBrainz. For the API itself — endpoints, request shapes, error codes,
rate limits — follow the upstream link; that is authoritative and this is not.

| | |
|---|---|
| **Package** | `provider/listenbrainz/` |
| **Provider ids** | `listenbrainz` |
| **Upstream API docs** | https://listenbrainz.readthedocs.io/en/latest/users/api/ |
| **Auth** | Optional token — `LISTENBRAINZ_TOKEN`, see [README](../../README.md). Unlocks `ARTIST_RADIO_DISCOVERY` only |
| **Deviations from the house pattern** | None — the four files, as `CLAUDE.md` describes them |

**Why this provider.** Open listen counts keyed on MBIDs rather than a name search, which makes it
the only popularity source that cannot mismatch the artist, and the only source in the tree for
`ARTIST_RADIO_DISCOVERY`. Every result scores `authoritative()`, 0.95.

## What We Extract

One row per entry in `ListenBrainzProvider.capabilities`. The two lists are compared by
`scripts/checks/check_provider_capabilities.py` on every `./check`.

| EnrichmentType | Identifier | Upstream call | What we keep |
|---|---|---|---|
| `ARTIST_POPULARITY` | `MUSICBRAINZ_ID` | `POST /1/popularity/artist`, then `GET /1/popularity/top-recordings-for-artist/{mbid}` | `total_listen_count`, `total_user_count`; on the fallback, a ranked `topTracks` list instead |
| `TRACK_POPULARITY` | `MUSICBRAINZ_ID` | `POST /1/popularity/recording` | `total_listen_count`, `total_user_count` |
| `ARTIST_TOP_TRACKS` | `MUSICBRAINZ_ID` | `GET /1/popularity/top-recordings-for-artist/{mbid}` | title, artist, album, duration, listen and listener counts, recording MBID, rank by position |
| `ARTIST_DISCOGRAPHY` | `MUSICBRAINZ_ID` | `GET /1/popularity/top-release-groups-for-artist/{mbid}` | release-group name and MBID |
| `SIMILAR_ARTISTS` | `MUSICBRAINZ_ID` | `GET /1/explore/lb-radio/artist/{mbid}/similar` | up to 20: `artist_name`, `artist_mbid`, `score` |
| `ARTIST_RADIO_DISCOVERY` | none | `GET /1/explore/lb-radio?prompt=artist:(…)&mode=` | JSPF playlist: title, creator, album, duration, recording / artist / release MBIDs |

**`ARTIST_RADIO_DISCOVERY` is registered conditionally.** `capabilities` is a `buildList` that adds
it only when `authToken != null`, so without a token the engine never routes the type here — the one
per-capability auth gate in the tree. Everything else works unauthenticated.

Priorities split two ways: `ARTIST_POPULARITY`, `ARTIST_TOP_TRACKS` and `ARTIST_RADIO_DISCOVERY` are
100; `TRACK_POPULARITY`, `ARTIST_DISCOGRAPHY` and `SIMILAR_ARTISTS` are 50, behind Last.fm and
MusicBrainz respectively.

Two shapes worth knowing:

- **`ARTIST_POPULARITY` returns two different payloads.** The batch endpoint gives scalar listen and
  listener counts. When it comes back empty, the fallback returns `Popularity` carrying only a ranked
  `topTracks` list and no counts at all — the same `EnrichmentType`, a structurally different result.
- **`ARTIST_RADIO_DISCOVERY` takes the MBID when present and the artist *name* otherwise**, wrapping
  either in the prompt `artist:(…)`. It is the only capability here that will run on a name, which is
  why it declares `IdentifierRequirement.NONE`. It also requires a `ForArtist` request, and
  `config.radioDiscoveryMode` (`easy` / `medium` / `hard`, default `easy`) chooses the mode.

## What We DON'T Extract

The `top-recordings-for-artist` and `top-release-groups-for-artist` responses are already fetched:

| Field | Would give |
|---|---|
| `artist_name` on a release group | Parsed into `ListenBrainzTopReleaseGroup.artistName` and dropped by the mapper |
| `listen_count` on a release group | Ranking for `ARTIST_DISCOGRAPHY`, which currently returns albums in response order with no counts |
| `caa_id` / `caa_release_mbid` | Cover art for a discography entry without a second provider |
| `release_name`, `release_mbid` on a top recording | Which album a top track came from, beyond the plain `albumName` |

Endpoints we never call: `/1/stats/**` (sitewide and per-user charts), `/1/user/**` (listens, playing
now, follows), `/1/metadata/**` (recording, release and artist metadata lookup), `/1/similar-users`,
and every submit endpoint. The `count` parameter on `getSimilarArtists` defaults to 20 and no caller
passes anything else.

## Gotchas

- `docs/pitfalls.md` §3 — this package is the pitfall's worked example: 0.9.0 read `track_name` where
  ListenBrainz sends `recording_name`, so every `TopTrack` title shipped as `""` until 0.9.1. The
  parsers still use `optString`/`optLong` throughout, so the same class of break stays silent.
- `docs/pitfalls.md` §5 — five of six declare `MUSICBRAINZ_ID`; the sixth declares `NONE` because it
  genuinely runs on a name. That split is deliberate, not an oversight.
- `docs/pitfalls.md` §4 — a blank MBID, an empty batch response and an empty playlist are all
  `NotFound`, so they record breaker *success*.

Ours: `getRadio` returns `emptyList()` when `authToken` is null rather than failing, so a provider
constructed without a token but registered by hand — bypassing the `buildList` gate — reports
`NotFound` for radio rather than an auth error.
