# Providers

What the eleven packages under `provider/` take from their upstreams, and — mostly — what they
deliberately leave. Capabilities, endpoints and confidence values are **not** here, and neither are
the per-provider limiter intervals (`withDefaultProviders()`): they are one glance at the code
away, and a copy of them rots while it moves. §Rate limiting keeps the one-limiter-per-host
topology and where each interval's basis comes from.

**Nothing here is checked.** No mechanism verifies a word of it. Hand-verified against the packages
on **2026-08-12**; treat anything after that as a claim, not a fact. §What we don't extract is the
part that rots fastest — most of what it lists is a to-do, and a to-do that gets done reads as a
still-open one until someone re-reads the code.

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

- **`musicbrainz` adds six files.** `MusicBrainzEnricher.kt` holds the per-entity enrichment logic
  and the memos that fold the lookups one request's types repeat into one call each — per *call*,
  not per provider, so `forceRefresh` reaches upstream (§12 of `pitfalls.md` has why that matters).
  Inlining it would put `MusicBrainzProvider` near 900 lines.
  `MusicBrainzParser.kt` holds every JSON → DTO conversion, which the other packages do inline in
  `*Api`; twelve capabilities across three entity types is more than an API client should carry.
  `MusicBrainzCreditParser.kt` serves `CREDITS` and `RELEASE_EDITIONS` only — those two read raw
  `JSONObject` rather than DTOs, since `lookupRecording` and `lookupReleaseGroup` return the response
  unparsed, and it owns the relation-type → role mapping. `MusicBrainzQualifierFallback.kt` strips a
  title's trailing qualifier group, and is the only one of the last three that album *and* track
  search share; `MusicBrainzReleaseRanking.kt` (ranking a search pool into one release) and
  `MusicBrainzTitleFolding.kt` (folding a title MusicBrainz stores under symbols no caller can type)
  serve the album path alone — track ranking deliberately reuses `pickBestRecording` rather than
  growing a second tie-break primitive.
- **`deezer` has a second public provider class.** `SimilarAlbumsProvider` registers under its own id
  `deezer-similar-albums`, so it gets its own `CircuitBreaker` and can be disabled without touching
  `deezer`. It exists because `SIMILAR_ALBUMS` is *derived* — Deezer has no such endpoint, so it
  fans out to related artists and scores their albums, up to six HTTP calls per request. Its class
  comment says why it is not a `CompositeSynthesizer`: keeping the calls in a plain provider means
  the engine schedules and rate-limits it like any other.

## Resolving a track by name

A track request naming **no album** is resolved out of a pool MusicBrainz has already filtered with
`-comment:*` — its own index expression of "carries no disambiguation", which is the same signal the
ranking's fourth tier applies. Doing it upstream is the point: downstream, the tier can only rank
what the page already let through, and for a heavily-covered title that is nothing at all.

A request that *does* name an album, and whose album term finds recordings, is left alone. An album is
the better narrowing term, and the two do not compose: the filter deletes candidates, so a recording
that is marked *and* on the requested album would be gone before the ranking's album-match tier,
which outranks its disambiguation tier, could prefer it.

An album term that finds **nothing** is a different case, and a reachable one — the query matches
release titles while the ranking matches release-*group* titles, so an empty hinted pool is not
evidence the album is absent. The hint-less retry asks for both pools, filtered and unfiltered, and
ranks their union: the filtered one for the depth above, the unfiltered one so a marked take on the
requested album is still there for the album-match tier to prefer. One extra request, on that path
only.

A request whose **own title ends in a bracketed group** takes the unfiltered ladder whole. Such a
title is how a consumer asks for a variant by name, and MusicBrainz routinely repeats the variant in
the disambiguation as well as the title — so the filter deletes precisely the recording that was
asked for, leaves the pool full, and answers with the studio take. The test is structural rather than
a vocabulary of variant words, so a canonical title that merely ends in brackets takes that ladder
too; that is the safe direction, degrading to the pool that shipped before the filter existed rather
than to a different recording.

The page is MusicBrainz's own maximum and not a number to raise — above it the search does not clamp,
it silently serves the default 25, so a raise would shrink the pool rather than widen it. A test
asserts that ceiling for exactly that reason. It is a ceiling and not a guarantee: some titles have a
filtered pool larger than it, and where a given recording lands inside a pool shifts between
identical calls. A filtered pool that comes back empty falls back to the unfiltered ladder, at one
extra request on the miss path; a track whose canonical recording is marked while other takes are not
is not covered by that fallback, and is no worse than before rather than fixed.

The measurements behind all of this live on `MusicBrainzApi.CANONICAL_SEARCH_LIMIT`, with
`scripts/probes/recording-pool-filter-probe.sh` as the recipe. They are not repeated here on purpose:
figures decay as MusicBrainz's catalogue grows, and a copy rots while it moves.

Neither surface a consumer *chooses* from is filtered: not `searchCandidates`, and not the
suggestions on a `NotFound`. Both exist for picking a version, and one narrowed to canonical
recordings cannot answer "I want the Moscow one" — so a miss searches unfiltered to build them,
which is the one request that path costs.

The pool is searched **once per `enrich()`**, not once per type. That is a correctness rule before it
is a cost one: MusicBrainz does not order identical searches identically, and the ranking keeps the
first maximum among ties, so a second search of the same query can pick a different recording and
leave the identity a consumer reads naming one recording while its payload describes another.

## Identifiers a caller supplies

MusicBrainz treats an MBID on the request as the entity to describe rather than a hint, for tracks as
for albums and artists. It is looked up, not searched for, at `idBasedLookup()` confidence, and an
entity MusicBrainz **holds** is never traded for a search hit however well that hit ranks — including
when the lookup body arrives unparseable. Answering with a *different* recording, release or artist
is worse than answering with none.

An MBID MusicBrainz holds **nothing** under is the case that is not a miss, and the same rule covers
all three entity types. It names nothing, so there is no entity an answer could be unfaithful to, and
the request resolves the way one carrying no identifier at all resolves: by name. `MusicBrainzLookup`
is where that line lives — `Absent` may fall back, `Unreadable` may not — and `MusicBrainzApi`'s
lookups return it rather than a bare null so the two cannot be confused at a call site.

This is not a nicety. These identifiers come from third parties in practice, Last.fm's having been
bulk-imported and never re-synced: measured 2026-08-11 over `track.getSimilar`
(`scripts/probes/lastfm-mbid-staleness-probe.sh`, `SOURCE=similar`), **51 of the 103 recording MBIDs
MusicBrainz answered for were held under no entity type at all**. Re-run it before quoting that;
the rate tracks how obscure the track is, and the same probe over the chart head is far kinder.

For a track the cost of treating such an identifier as authoritative was the *whole call*, not just
the MusicBrainz types: identity resolution is where a track request meets its MBID, and a `NotFound`
carrying suggestions tells the engine to skip the provider fan-out
(`TrackIdentifierMissFanOutTest` pins both halves). Suggestions are for a name that resolves to
nothing; no identifier path raises them.

The lookup itself is spent once per call however many types ask — a miss included, so a dead
identifier costs one request and not one per type — and that holds when the request carries a
release-group id too and identity resolution never runs. `CREDITS` is the one type read off the
lookup alone: every miss there is bare, because a search hit is not an answer it could use, and a
recording MusicBrainz holds that credits nobody answers "this track, and it credits nobody" rather
than "did you mean a different track?". `ARTIST_DISCOGRAPHY` browses rather than looks up, so it
learns of no absence to recover from; in a full `enrich()` it never sees a dead identifier anyway,
because identity resolution has replaced it by then.

One MBID is exempt, and it is the one the engine put there itself. Identity resolution runs before
the fan-out and merges the recording *its own search* picked into the request, so every type then
sees an identifier that was absent when the call began. The enricher remembers what it resolved by
search this call and keeps searching for those, which is what stops a name-only request quietly
changing which release-group answers it. An MBID from anywhere else — a caller's, a foreign identity
provider's — reads as external.

## Rate limiting

`withDefaultProviders()` builds **one `RateLimiter` per host**, as `RateLimiter`'s KDoc requires.
A limiter holds its mutex across the request itself, so a shared instance would make unrelated hosts'
round-trips sequential; rate limits are per-host and no host here asks to be throttled against
another's traffic. The two Deezer providers share one limiter because they share a host; Wikipedia
takes the Wikidata limiter for the Wikidata host it reaches, alongside its own. iTunes takes its
constructor default of `RateLimiter(3000)`.

The intervals live in that one function, each with a dated comment naming its basis — **published**,
**measured** from live rate-limit headers, or **judgement**. Exactly three rest on a number we read
at the source: MusicBrainz (1100ms, published 1 req/sec), ListenBrainz (400ms, measured at 30
req/10s) and Discogs (1100ms, measured at `x-discogs-ratelimit: 60` authenticated — the developer
page 403s non-browser clients, but the live response headers carry the figure).
Do not read a judgement figure here or there as a documented one; Last.fm's API terms publish no
figure at all, only "limits... in our sole discretion". The safety net is 429 → `Retry-After` →
backoff. A provider constructed directly takes whatever limiter the caller passes; nothing checks it
against this page.

Discogs also gates the whole provider behind a token (`requiresApiKey = true`), kept deliberately: a
tokenless tier exists at 25 req/min (probed 2026-08-12), but shipping that as musicmeta's default
would make the library the reason a shared IP gets throttled.

## What we don't extract

The part no reading of the code can give you: fields that arrive in responses we already fetch and
are dropped, and endpoints we never call. A to-do list for whoever adds the next capability.

**MusicBrainz.** `isrcs` beyond the first (`toTrackMetadata` keeps one; a recording often has
several), `label-info[]` beyond the first (co-releases and reissues), `release.packaging`/`quality`,
`artist.aliases` (which `ArtistMatcher` would use), `artist.life-span.ended` (a split, distinct from
having an end date), `annotation`, `rating`. `media[].format` *is* read, but only to drop video discs
from a tracklist and to label a `RELEASE_EDITIONS` entry — never surfaced per disc; `sort-name` is
read too, to reorder a Person's "Last, First". Never requested: `works`, `series`, `events`,
`places`, `instruments`, `genres` (the curated list, as opposed to the `tags` we read),
`collections`, `inc=aliases`. No cover-art call — that is the Cover Art Archive provider, keyed on
the identifiers this one resolves; the `cover-art-archive.front` flag embedded in a release is read,
and is what puts a thumbnail URL on a search candidate.

**Cover Art Archive.** `/release/{mbid}` is fetched for every capability, so these cost only code:
`images[].comment` (which of several front covers this is), `.approved` (we take the first match
either way), `.id` (addresses one image at `/release/{mbid}/{id}-{size}`), `.back` (redundant with
`types`, but cheaper). Unmapped `types`: `Obi`, `Spine`, `Track`, `Tray`, `Sticker`, `Poster`,
`Liner`, `Watermark`, `Raw/Unedited`, `Matrix/Runout`, `Top`, `Bottom`. `firstOrNull` takes one image
per type, so alternate pressings' art is discarded. `/release-group/{id}` JSON is never called — only
its `front-{size}` redirect, which is why that path returns no `sizes`.

**Deezer.** On results we already fetch: `album.release_date` (read only inside
`ARTIST_DISCOGRAPHY`), `album.genre_id`/`genres` (Deezer declares no genre capability at all),
`album.label`, `album.upc` (a barcode nothing else supplies),
`track.rank`/`bpm`/`gain` (real popularity and audio features, in place of the
positional scores the mapper synthesises), `track.duration` on radio and top-track results,
`contributors` (`CREDITS`), `explicit_content_lyrics` (finer than the boolean).
`artist.nb_fan`/`nb_album` are read, but only as the artist search's tie-break — neither reaches
`ARTIST_POPULARITY`. Never called:
`/chart`, `/genre`, `/editorial`, `/playlist/{id}`, `/podcast`, every `/user/**`.

**iTunes.** Never read on results
we fetch: `collectionViewUrl`, `collectionPrice`, `copyright`, `contentAdvisoryRating`,
`collectionExplicitness`, `amgArtistId`, `artistViewUrl`; from a track lookup, `previewUrl` (Deezer
serves `TRACK_PREVIEW`), `discNumber`, `discCount`, `trackPrice`, `isStreamable`. Entities never
searched: `song`, `musicVideo`, `podcast`, `audiobook`.

**LRCLIB.** Parsed into `LrcLibResult` and dropped: `id` (`GET /api/get/{id}` would re-fetch without
a search), `trackName`/`artistName` — both would verify that the search fallback returned the right
track, which nothing currently does. `albumName` and `duration` *are* read, into
`TRACK_METADATA`'s album title and `durationMs`. Never called:
`GET /api/get/{id}`, and the write path (`POST /api/request-challenge`, `POST /api/publish`), which
needs a proof-of-work solution and would make this a write client.

**Wikidata.** We request five properties, so everything else needs another call: P136 genre and P264
record label (`GENRE`, `LABEL` — as Q-ids, needing the label lookup `COUNTRY_MAP` and
`OCCUPATION_MAP` hand-roll), P527/P361 has-part and part-of (`BAND_MEMBERS`, and group membership),
P571/P576 inception and dissolution (bands, as opposed to the P569/P570 person dates we read), P856
and P2002/P2003/P2013 (`ARTIST_LINKS`), P434/P4404/P4407/P8052 MusicBrainz ids (reverse identity),
P1902/P1728/P1953 Spotify, AllMusic and Discogs ids, P373 Commons category, P1303 instrument, P166
awards. `wbgetentities` is how the claims themselves are fetched (`props=claims`), but never with
`props=labels`/`descriptions` — which is what would retire both hardcoded maps. Never called: the
REST API at `/w/rest.php/wikibase/v1/`, and SPARQL. Note `provider/wikipedia/` *also* calls
`wbgetentities` on this host, for sitelinks, on its own rate limiter.

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
`release_mbid` on a top recording (its album's identity, as opposed to the `release_name` a top
track now carries). Never called:
`/1/stats/**`, `/1/user/**`, `/1/similar-users`, and every submit endpoint. Two of the unused are
worth knowing before reaching for a third-party call. `/1/metadata/recording/` is a keyless batch
lookup — up to 1000 recording MBIDs to titles, artists, lengths and release info in one request —
so any source that yields MBIDs without titles costs two requests, not one per track.
`/1/lb-radio/artist/{mbid}` is deliberately left, not overlooked: it answers where
`/1/explore/lb-radio` is disabled, but it is LB Radio's candidate *pool* rather than its playlist.
It is unordered (a JSON object keyed by artist MBID), non-deterministic (`ORDER BY RANDOM()`, so two
identical requests differ), and curated only by the popularity band the caller picks. Serving
`RadioPlaylist` from it would mean owning the assembly troi does upstream, and caching one random
draw for the type's TTL.

**Last.fm.** Parsed into a DTO and dropped by the mapper — cheapest to add, since both the request
and the read already happen: album `playcount`/`listeners` (the artist and track equivalents both
reach `Popularity`; the album pair does not), album `name`/`artist`, track `mbid`. Album
`wiki.summary` is no longer among them — it is `ALBUM_DESCRIPTION`'s Last.fm source.
Fetched but never read: `artist.bio.content` (we take `summary`),
`artist.similar.artist[]` (similar artists without the second `artist.getsimilar` call), `artist.url`
(`ARTIST_LINKS`), `artist.image[]` (widely reported empty since ~2020 — upstream behaviour, not
verified here), `tag.count`/`tag.url` (vote weight, which would let us rank tags rather than trust
response order). Never called: `artist.gettopalbums`, `album.getTopTags`, `track.getTopTags`,
`tag.getTop*`, `chart.getTop*`, everything under `geo.` and `user.`. Authenticated methods need the
shared secret, which we never read.

**Fanart.tv.** Keys in a response we already fetch: `musiclogo` (the SD logo — we read `hdmusiclogo`
only, so an artist with just an SD logo reads as having none), `hdmusicbanner` (next to the
`musicbanner` we do read), `id` (used as an `ArtworkSize` label and
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
