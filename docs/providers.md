# Providers

What the eleven packages under `provider/` take from their upstreams, and — mostly — what they
deliberately leave. Capabilities, endpoints and confidence values are **not** here, and neither are
the per-provider limiter intervals (`withDefaultProviders()`): they are one glance at the code
away, and a copy of them rots while it moves. §Rate limiting keeps the one-limiter-per-host
topology and where each interval's basis comes from.

**Nothing here is checked.** No mechanism verifies a word of it. Hand-verified against the packages
on **2026-07-26**; treat anything after that as a claim, not a fact.

## The providers

Auth keys and how to supply them are in [README.md](../README.md).

| Provider | Package | Auth | Upstream API docs | Why it is here |
|---|---|---|---|---|
| MusicBrainz | `musicbrainz` | none (User-Agent required) | [docs](https://musicbrainz.org/doc/MusicBrainz_API) | Identity backbone — `isIdentityProvider`, runs first, and the only `NotFound` that can carry `suggestions` |
| Cover Art Archive | `coverartarchive` | none | [docs](https://musicbrainz.org/doc/Cover_Art_Archive/API) | Only artwork source keyed on a release MBID rather than a name, and the only source of back cover, booklet and disc |
| Deezer | `deezer` | none | [docs](https://developers.deezer.com/api) | Widest no-key catalogue; only source of `ARTIST_RADIO`, `TRACK_PREVIEW`, `SIMILAR_ALBUMS` |
| iTunes | `itunes` | none | [docs](https://performance-partners.apple.com/search-api) | No-key album search with artwork at any size; the fallback when there is no MBID |
| LRCLIB | `lrclib` | none | [docs](https://lrclib.net/docs) | Only lyrics source |
| Wikidata | `wikidata` | none | [docs](https://www.wikidata.org/wiki/Wikidata:Data_access) | Structured claims keyed on a Q-id; our route to Commons imagery at any width |
| Wikipedia | `wikipedia` | none | [docs](https://en.wikipedia.org/api/rest_v1/) | Highest-confidence bio; English only |
| ListenBrainz | `listenbrainz` | optional token | [docs](https://listenbrainz.readthedocs.io/en/latest/users/api/) | MBID-keyed listen counts that cannot mismatch the artist; only source of `ARTIST_RADIO_DISCOVERY`, which is what the token gates |
| Last.fm | `lastfm` | API key | [docs](https://www.last.fm/api) | Widest capability set of any single provider; only source of tags-as-genre and artist similarity |
| Fanart.tv | `fanarttv` | project key | [docs](https://fanarttv.docs.apiary.io/) | Only source of artist backgrounds, logos and banners |
| Discogs | `discogs` | token | [docs](https://www.discogs.com/developers) | Pressing-level detail: catalogue numbers, editions, per-track credits |

## Deviations from the house pattern

`CLAUDE.md` states the four-file pattern. Two packages depart from it, and both departures are
deliberate:

- **`musicbrainz` adds three files.** `MusicBrainzEnricher.kt` holds the per-entity enrichment logic
  and the artist lookup cache, because `MusicBrainzProvider` would otherwise run to ~440 lines.
  `MusicBrainzParser.kt` holds every JSON → DTO conversion, which the other packages do inline in
  `*Api`; eleven capabilities across three entity types is more than an API client should carry.
  `MusicBrainzCreditParser.kt` serves `CREDITS` and `RELEASE_EDITIONS` only — those two read raw
  `JSONObject` rather than DTOs, since `lookupRecording` and `lookupReleaseGroup` return the response
  unparsed, and it owns the relation-type → role mapping.
- **`deezer` has a second public provider class.** `SimilarAlbumsProvider` registers under its own id
  `deezer-similar-albums`, so it gets its own `CircuitBreaker` and can be disabled without touching
  `deezer`. It exists because `SIMILAR_ALBUMS` is *derived* — Deezer has no such endpoint, so it
  fans out to related artists and scores their albums, up to six HTTP calls per request. Its class
  comment says why it is not a `CompositeSynthesizer`: keeping the calls in a plain provider means
  the engine schedules and rate-limits it like any other.

## Rate limiting

`withDefaultProviders()` builds **one `RateLimiter` per host**, as `RateLimiter`'s KDoc requires.
A limiter holds its mutex across the request itself, so a shared instance would make unrelated hosts'
round-trips sequential; rate limits are per-host and no host here asks to be throttled against
another's traffic. The two Deezer providers share one limiter because they share a host; Wikipedia
takes the Wikidata limiter for the Wikidata host it reaches, alongside its own. iTunes takes its
constructor default of `RateLimiter(3000)`.

The intervals live in that one function, each with a dated comment naming its basis — **published**,
**measured** from live rate-limit headers, or **judgement**. Exactly two rest on a number we read at
the source: MusicBrainz (1100ms, published 1 req/sec) and ListenBrainz (400ms, measured at 30
req/10s). Everything else is judgement, including Discogs at 1000ms — 60 req/min authenticated is the
figure in circulation, but the Discogs developer page 403s non-browser clients, so it is unverified.
Do not read a judgement figure here or there as a documented one; Last.fm's API terms publish no
figure at all, only "limits... in our sole discretion". The safety net is 429 → `Retry-After` →
backoff. A provider constructed directly takes whatever limiter the caller passes; nothing checks it
against this page.

## What we don't extract

The part no reading of the code can give you: fields that arrive in responses we already fetch and
are dropped, and endpoints we never call. A to-do list for whoever adds the next capability.

**MusicBrainz.** `isrcs` beyond the first (`toTrackMetadata` keeps one; a recording often has
several), `label-info[]` beyond the first (co-releases and reissues), `release.packaging`/`status`/
`quality`, `media[].format` (CD vs vinyl vs digital, per disc), `artist.aliases`/`sort-name` (which
`ArtistMatcher` would use), `artist.life-span.ended` (a split, distinct from having an end date),
`annotation`, `rating`. Never requested: `works`, `series`, `events`, `places`, `instruments`,
`genres` (the curated list, as opposed to the `tags` we read), `collections`, `inc=aliases`. No
cover-art call — that is the Cover Art Archive provider, keyed on the identifiers this one resolves.

**Cover Art Archive.** `/release/{mbid}` is fetched for every capability, so these cost only code:
`images[].comment` (which of several front covers this is), `.approved` (we take the first match
either way), `.id` (addresses one image at `/release/{mbid}/{id}-{size}`), `.back` (redundant with
`types`, but cheaper). Unmapped `types`: `Obi`, `Spine`, `Track`, `Tray`, `Sticker`, `Poster`,
`Liner`, `Watermark`, `Raw/Unedited`, `Matrix/Runout`, `Top`, `Bottom`. `firstOrNull` takes one image
per type, so alternate pressings' art is discarded. `/release-group/{id}` JSON is never called — only
its `front-{size}` redirect, which is why that path returns no `sizes`.

**Deezer.** On results we already fetch: `album.release_date` (read only inside
`ARTIST_DISCOGRAPHY`), `album.genre_id`/`genres` (Deezer declares no genre capability at all),
`album.label`, `album.upc` (a barcode nothing else supplies), `artist.nb_fan`/`nb_album`
(`ARTIST_POPULARITY`), `track.rank`/`bpm`/`gain` (real popularity and audio features, in place of the
positional scores the mapper synthesises), `track.duration` on radio and top-track results,
`contributors` (`CREDITS`), `explicit_content_lyrics` (finer than the boolean). Never called:
`/chart`, `/genre`, `/editorial`, `/playlist/{id}`, `/podcast`, every `/user/**`.

**iTunes.** `searchCandidates` returns an **empty** `EnrichmentIdentifiers` even though
`toSearchCandidate` has the `collectionId` in hand, so picking an iTunes candidate in a
disambiguation flow carries nothing forward and the next request re-searches. Never read on results
we fetch: `collectionViewUrl`, `collectionPrice`, `copyright`, `contentAdvisoryRating`,
`collectionExplicitness`, `amgArtistId`, `artistViewUrl`; from a track lookup, `previewUrl` (Deezer
serves `TRACK_PREVIEW`), `discNumber`, `discCount`, `trackPrice`, `isStreamable`. Entities never
searched: `song`, `musicVideo`, `podcast`, `audiobook`.

**LRCLIB.** Parsed into `LrcLibResult` and dropped: `id` (`GET /api/get/{id}` would re-fetch without
a search), `trackName`/`artistName`/`albumName`/`duration` — all four would verify that the search
fallback returned the right track, and `duration` is the strongest mismatch signal. Never called:
`GET /api/get/{id}`, and the write path (`POST /api/request-challenge`, `POST /api/publish`), which
needs a proof-of-work solution and would make this a write client.

**Wikidata.** We request five properties, so everything else needs another call: P136 genre and P264
record label (`GENRE`, `LABEL` — as Q-ids, needing the label lookup `COUNTRY_MAP` and
`OCCUPATION_MAP` hand-roll), P527/P361 has-part and part-of (`BAND_MEMBERS`, and group membership),
P571/P576 inception and dissolution (bands, as opposed to the P569/P570 person dates we read), P856
and P2002/P2003/P2013 (`ARTIST_LINKS`), P434/P4404/P4407/P8052 MusicBrainz ids (reverse identity),
P1902/P1728/P1953 Spotify, AllMusic and Discogs ids, P373 Commons category, P1303 instrument, P166
awards. Never called: `wbgetentities` for labels and descriptions — which is what would retire both
hardcoded maps — the REST API at `/w/rest.php/wikibase/v1/`, and SPARQL. Note `provider/wikipedia/`
*does* call `wbgetentities` on this same host, for sitelinks, on its own rate limiter.

**Wikipedia.** From `/page/summary`, fetched for every `ARTIST_BIO`: `description` (the "English rock
band" gloss — parsed, then dropped), `originalimage` (we take the ~320px thumbnail), `extract_html`,
`wikibase_item` (the Q-id, which would skip a resolution step elsewhere), `type` (the only
programmatic way to notice we landed on a disambiguation page), `revision`/`tid`, `content_urls`.
From `/page/media-list`: every image after the first, and each item's `caption`, `srcset` and
thumbnail sizes. Never called: `/page/html/{title}` and MediaWiki `action=parse`, where the infobox
lives — origin, years active, labels, members, which we take from Wikidata instead. `BASE_URL`
hardcodes `en.wikipedia.org`, so no other language is ever queried.

**ListenBrainz.** On responses we already fetch: `artist_name` on a release group (parsed into
`ListenBrainzTopReleaseGroup.artistName`, dropped by the mapper), `listen_count` on a release group
(ranking for `ARTIST_DISCOGRAPHY`, which currently returns response order with no counts),
`caa_id`/`caa_release_mbid` (cover art for a discography entry without a second provider),
`release_name`/`release_mbid` on a top recording (which album a top track came from). Never called:
`/1/stats/**`, `/1/user/**`, `/1/metadata/**`, `/1/similar-users`, and every submit endpoint.

**Last.fm.** Parsed into a DTO and dropped by the mapper — cheapest to add, since both the request
and the read already happen: album `playcount`/`listeners`, album `wiki.summary`, album
`name`/`artist`, track `mbid`. Fetched but never read: `artist.bio.content` (we take `summary`),
`artist.similar.artist[]` (similar artists without the second `artist.getsimilar` call), `artist.url`
(`ARTIST_LINKS`), `artist.image[]` (widely reported empty since ~2020 — upstream behaviour, not
verified here), `tag.count`/`tag.url` (vote weight, which would let us rank tags rather than trust
response order). Never called: `artist.gettopalbums`, `album.getTopTags`, `track.getTopTags`,
`tag.getTop*`, `chart.getTop*`, everything under `geo.` and `user.`. Authenticated methods need the
shared secret, which we never read.

**Fanart.tv.** Keys in a response we already fetch: `musiclogo` (the SD logo — we read `hdmusiclogo`
only, so an artist with just an SD logo reads as having none), `hdmusicbanner` (next to the
`musicbanner` we do read), `likes` (parsed into `FanartTvImage.likes` and ignored — the ranking
signal for every list, discarded at the point it arrives), `id` (used as an `ArtworkSize` label and
nowhere else; it addresses a specific image upstream), `lang`/`disc`/`size` on `cdart`,
`name`/`mbid_id` on the artist object (verification that the id resolved to the artist we meant),
`albums` on the artist document (album art keyed by release group — deliberately unread, since it
cannot be attributed to the album asked for without the release group id the album endpoint needs
anyway).
Everything after the first image of each type is dropped except as an unlabelled `sizes` entry.

**Discogs.** Parsed from `/releases/{id}` — which `ALBUM_METADATA` and `CREDITS` already fetch — then
dropped: `community.rating.count`, `community.have`/`want`, tracklist positions and titles (read only
to match the requested title), member active/inactive flag. From a search result: `thumb` (so
`ALBUM_ART` returns no thumbnail and no `sizes`, unlike `ARTIST_PHOTO`), `barcode`,
`formats[].descriptions`, `resource_url`, `community`. Never called: `/artists/{id}/releases`
(MusicBrainz and iTunes cover discography), `/labels/{id}` and its releases, marketplace, inventory,
and every user collection endpoint. `ReleaseEdition.barcode` is explicitly null because
`/masters/{id}/versions` does not carry it — `/releases/{id}` does.
