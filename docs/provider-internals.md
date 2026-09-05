# Provider internals

What our code does with each provider's response: deviations from the house pattern, name
resolution and ranking, the two genre surfaces kept apart, and what a response carries that we
deliberately don't extract. What a provider requires from a caller and gives back, and the terms it
comes with, is `docs/providers.md`.

**Nothing here is checked.** No mechanism verifies a word of it. Hand-verified against the packages
on **2026-08-14**; treat anything after that as a claim, not a fact. §What we don't extract is the
part that rots fastest — most of what it lists is a to-do, and a to-do that gets done reads as a
still-open one until someone re-reads the code.

## Deviations from the house pattern

`CLAUDE.md` states the four-file pattern. Two packages depart from it, and both departures are
deliberate:

- **`musicbrainz` adds twelve files.** `MusicBrainzEnricher.kt` routes a request on to
  `MusicBrainzArtistEnrichment.kt`, `MusicBrainzAlbumEnrichment.kt` and
  `MusicBrainzTrackEnrichment.kt`, which hold the per-entity enrichment logic and the memos that fold
  the lookups one request's types repeat into one call each — per *call*, not per provider, so
  `forceRefresh` reaches upstream (§12 of `pitfalls.md` has why that matters).
  `MusicBrainzSearchOutcome.kt` holds what all three share: the suggestions a miss offers, the name
  route a hit reports, and the qualifier ladder the album and track searches both walk.
  Inlining them would put `MusicBrainzProvider` near 900 lines.
  `MusicBrainzParser.kt` holds every JSON → DTO conversion, which the other packages do inline in
  `*Api`; fourteen capabilities across three entity types is more than an API client should carry.
  `MusicBrainzCreditParser.kt` serves `CREDITS` and `RELEASE_EDITIONS` only — those two read raw
  `JSONObject` rather than DTOs, since `lookupRecording` and `browseReleaseGroupReleases` return the response
  unparsed, and it owns the relation-type → role mapping. Writer credits live on the *work*, not the
recording, so the recording lookup asks for `work-level-rels` — without it the work arrives as an
id-and-title stub and every songwriter is silently absent. The bytes are paid on every recording
lookup whether or not `CREDITS` was asked for, because varying `inc=` per requested type would put
two response shapes under one cache key. `MusicBrainzQualifierFallback.kt` strips a
  title's trailing qualifier group, and is the only one of the last three that album *and* track
  search share; `MusicBrainzReleaseRanking.kt` (ranking a search pool into one release) and
  `MusicBrainzTitleFolding.kt` (folding a title MusicBrainz stores under symbols no caller can type)
  serve the album path alone — track ranking deliberately reuses `pickBestRecording` rather than
  growing a second tie-break primitive. Folding also gates its own fallback: an album search whose
  pool comes back empty runs the symbol-title browse only when some differently-spelled title could
  fold to the one asked for — the title itself folds, or its folded form contains the image of a
  folded symbol. Folding never edits a letter, so nothing a plain typo could reach is in that browse;
  such a title skips it and returns the same `AMBIGUOUS` verdict without spending the calls.
  The remaining two, `MusicBrainzEditions.kt` and `MusicBrainzSuppliedIdentifier.kt`, each state in
  their own KDoc why they stand apart: the first is the one album type keyed on the release-*group*,
  the second is the check every caller-supplied identifier passes through.
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

## Resolving an artist by name, and by its other names

An artist search asks for `artist:"…" OR alias:"…"`. The alias half is not redundant: `artist:"…"`
alone does not reach the alias index, and MusicBrainz keeps localised names, former names and
misspellings there. Measured 2026-08-12, `artist:"Cold Play"` returns 0 hits and `alias:"Cold Play"`
returns Coldplay. An unfielded query would reach both and is far too loose — the same probe returned
4152 hits for the bare words. It stays one request either way, and every hit carries its own
`aliases` array without an `inc=` parameter.

What the name matched then ranks the pool and scales the confidence, so `identityMatchScore` says
*how* an artist was identified rather than only that it was: the artist's own name, then an alias
MusicBrainz marks primary or locale-tagged, then a "Search hint" — the typo-catchers it keeps for its
own indexer. `engine/NameMatchTier.kt` holds the tiers and is deliberately source-agnostic, so a
second upstream publishing alternative names does not grow a second matching rule beside it.

Discogs' artist detail — already fetched for `ARTIST_PHOTO` and `BAND_MEMBERS`, so this costs no
request — carries `realname` and `namevariations`, other names Discogs files on that artist itself.
A candidate whose record holds the requested name among them is reported at the canonical tier
rather than an alias one: an alias-pool tier is MusicBrainz's claim that two names are one entity,
and a record naming the entity as the request asked needs no second source to say so. Same-name
only, as a pool match is. Their absence claims nothing in the other direction — the lists are the
credit spellings contributors filed, not a complete alias set — so an uncorroborated candidate keeps
the tier its pool match earned and is never rejected on the strength of a missing entry.

## Two genre surfaces, kept apart

Lookups request `tags+genres`. `genres` is MusicBrainz's controlled vocabulary — 2184 names as of
2026-08-12 — and a subset of the same response's `tags` carrying the same vote counts; `tags` is
everything anyone typed, which for Coldplay includes "british", "parlophone" and "rock and indie". So
curated names are marked `GenreTag.curated`, carry the higher confidence, and lead the list, while
community tags keep their vote-weighted signal behind them. A search hit carries vote-weighted `tags`
but no `genres` array at all — there is no `inc=` on a search to ask with — so an artist resolved by
name reads its curated genres off the lookup the same call already makes for URL relations. Where
that lookup does not happen, the hit's tags are marked `curated = null`: unknown, not uncurated, so
the engine refetches rather than caching a claim nobody checked.

`GenreMerger.ALIASES` survives as the Last.fm/Deezer spelling folder — neither publishes a controlled
list — but must never fold one genre onto a *different* one, because the curated vocabulary
distinguishes them.

## Editions of an album

`RELEASE_EDITIONS` browses `/release?release-group=…` rather than looking the release group up. The
lookup cannot answer it: `labels` is not a valid `inc` on the release-group resource, so no lookup
can carry an edition's label or catalogue number, and the `releases` array it embeds carries no
`media` either — those are three of the fields a `ReleaseEdition` promises. The browse asks for
`release-groups`, `artist-credits`, `labels` and `media` instead, which is what fills `format` (the
first medium's), `label` and `catalogNumber` (the first `label-info` entry's) beside `title`,
`country`, `year` and `barcode`. It costs bytes rather than requests: 54,556 against the old
lookup's 21,932 over a 27-release group (measured 2026-08-25).

## What we don't extract

The part no reading of the code can give you: fields that arrive in responses we already fetch and
are dropped, and endpoints we never call. A to-do list for whoever adds the next capability.

**MusicBrainz.** `isrcs` beyond the first (`toTrackMetadata` keeps one; a recording often has
several), `label-info[]` beyond the first (co-releases and reissues), `release.packaging`/`quality`,
`artist.life-span.ended` (a split, distinct from having an end date), `annotation`.
`media[].format` *is* read, but only to drop video discs
from a tracklist and to label a `RELEASE_EDITIONS` entry — never surfaced per disc; `sort-name` is
read too, to reorder a Person's "Last, First". Never requested: `works`, `series`, `events`,
`places`, `instruments`, `collections`. No cover-art call — that is the Cover Art Archive provider, keyed on
the identifiers this one resolves. The `cover-art-archive.front` flag embedded in a release is
dropped as well: only a release lookup carries that object, a `/release?query=` hit has none
(checked live 2026-08-22), and a search candidate's thumbnail URL was the one thing it was ever read
for. So a search candidate carries no thumbnail; asking per candidate would cost a rate-limited
lookup each.
Reading `area`/`begin-area` and their `iso-3166-1-codes` as a fallback for a missing `country` was
considered and declined on 2026-09-03: a lookup response already fills `country` from the area
hierarchy, and a `/release?query=`/`/artist?query=` hit's `area` carries no `iso-3166-1-codes` at
all (probed 2026-09-03), so the fallback would be redundant on the one endpoint and unusable on the
other. `country` is passed through untouched rather than run through the Discogs conversion, because
MusicBrainz already writes codes: an alpha-2 where the release names one country, and its own
pseudo-codes otherwise — `XE` (Europe), `XW` (Worldwide), `XC` (Czechoslovakia), and the withdrawn
`YU`. Those reach a consumer as written, on `Metadata.country`, `ReleaseEdition.country` and
`SearchCandidate.country` alike. `XE` and `XW` alone are 36% of every release MusicBrainz holds
(2.07M of 5.75M, counted 2026-09-04), so a consumer that renders `country` as a flag or a code needs
a branch for them.

**Cover Art Archive.** `/release/{mbid}` is read by every capability and fetched once per call — a
failure included, so a dead endpoint costs one attempt budget, not four. These cost only code:
`images[].comment` (which of several front covers this is), `.approved` (we take the first match
either way), `.id` (addresses one image at `/release/{mbid}/{id}-{size}`), `.back` (redundant with
`types`, but cheaper). Unmapped `types`: `Obi`, `Spine`, `Track`, `Tray`, `Sticker`, `Poster`,
`Liner`, `Watermark`, `Raw/Unedited`, `Matrix/Runout`, `Top`, `Bottom`. `firstOrNull` takes one image
per type, so alternate pressings' art is discarded. `/release-group/{id}` JSON is never called — only
its `front-{size}` redirect, which is why that path returns no `sizes`.

**Deezer.** `searchTrack`'s name-search pool for `TRACK_PREVIEW`/`TRACK_METADATA` is accepted before
it is ranked: a candidate must equal the requested title, or share its normalized base with an
equivalent whole qualifier (`Song - Live` accepted against `Song (Live)`, never against studio
`Song`), before `rankTracks` orders the accepted pool by artist quality, album containment and
unrequested markers (`TitleMatcher`, `docs/pitfalls.md` §7). An exact Deezer track id bypasses name
acceptance entirely. `GET /album/{id}` now backs `ALBUM_METADATA`, filling `label`, `barcode` (from `upc`,
a barcode nothing else supplies) and `releaseDate`; `album.genre_id`/`genres` is read live but
deliberately not parsed — 12/12 probed albums (2026-08-12) returned exactly one coarse editorial
tag, a strict parent of Last.fm's vote-weighted tags, so Deezer declares no `GENRE` capability.
`album.release_date` from the *search* hit is still read only inside `ARTIST_DISCOGRAPHY`. On
results we already fetch: `track.rank`/`bpm`/`gain` (real popularity and audio features, in place
of the positional scores the mapper synthesises), `track.duration` on radio and top-track results,
`contributors` (`CREDITS` — every clean probed result had one contributor, role "Main"; needs its
own probe on credits-heavy albums before this becomes a capability),
`explicit_content_lyrics` (finer than the boolean).
`artist.nb_fan`/`nb_album` are read, but only as the artist search's tie-break — neither reaches
`ARTIST_POPULARITY`. Cheaper to add than it was: that type is merged now, so a Deezer fan count
would arrive as one more `PopularitySignal` beside the others rather than having to beat them.
Never called:
`/chart`, `/genre`, `/editorial`, `/playlist/{id}`, `/podcast`, every `/user/**`. `search/album`'s
pool is accepted on both artist and album title, not artist alone, before `ALBUM_ART`,
`ALBUM_METADATA` and `ALBUM_TRACKS` share the ranked result (`docs/pitfalls.md` §7).

**iTunes.** Never read on results
we fetch: `collectionViewUrl`, `collectionPrice`, `copyright`, `contentAdvisoryRating`,
`collectionExplicitness`, `amgArtistId`, `artistViewUrl`; from a track lookup, `previewUrl` (Deezer
serves `TRACK_PREVIEW`), `discNumber`, `discCount`, `trackPrice`, `isStreamable`. Entities never
searched: `song`, `musicVideo`, `podcast`, `audiobook`. `lookup?isrc=` looks like the ISRC
equivalent of `lookup?upc=` and is not: it returns `200 resultCount: 0` for an ISRC known to be in
catalogue, so it is silently unsupported rather than broken (probed 2026-08-12) — never called.
As with Deezer, the name-search pool behind `ALBUM_ART`, `ALBUM_METADATA` and `ALBUM_TRACKS` is
accepted on `collectionName` as well as artist and shared as one ranked result; the exact
`itunesCollectionId` and `lookup?upc=` paths bypass that acceptance entirely (`docs/pitfalls.md` §7).
`country` is parsed and dropped: it names the storefront the request was served from, not the
release, so it is the same value on every result (`USA` for a German act's albums, probed
2026-09-03) and `Metadata.country`/`SearchCandidate.country` are left null rather than filled with
it.

**LRCLIB.** `id` is parsed and dropped (`GET /api/get/{id}` would re-fetch without a search).
`trackName`/`artistName` are checked before a `/api/search` fallback candidate is selected —
`LrcLibAcceptance.accepts` rejects a wrong title or wrong artist rather than trusting search-result
order — and `albumName`/`duration` rank the accepted pool, read into `TRACK_METADATA`'s album title
and `durationMs` when the winner carries them. `LYRICS_SYNCED`, `LYRICS_PLAIN` and `TRACK_METADATA`
share one lookup-and-selection outcome per call (`LrcLibTrackScope`, `docs/pitfalls.md` §7). Never
called: `GET /api/get/{id}` directly, and the write path (`POST /api/request-challenge`,
`POST /api/publish`), which needs a proof-of-work solution and would make this a write client.

**Wikidata.** One `wbgetentities&props=claims` call returns every claim on the entity, so what is
left below costs code and no request. Read: P18, P569, P570, P495, P106, P856 (the sole
`ARTIST_LINKS` entry), and the external-id claims P434, P1953, P1902 and P2850, which fill
identifiers rather than a capability — the last two are absent on most long-tail acts, which is
normal. Dropped: P136 genre and P264 record label (`GENRE`, `LABEL` — as Q-ids, needing the label
lookup `COUNTRY_MAP` and `OCCUPATION_MAP` hand-roll), P527/P361 has-part and part-of
(`BAND_MEMBERS`, and group membership), P571/P576 inception and dissolution (bands, as opposed to
the P569/P570 person dates we read), P2002/P2003/P2013 social handles, P4404/P4407/P8052 further
MusicBrainz ids, P1728 AllMusic id, P373 Commons category, P1303 instrument, P166
awards. `wbgetentities` is how the claims themselves are fetched (`props=claims`), but never with
`props=labels`/`descriptions` — which is what would retire both hardcoded maps. Retiring them was
considered and declined on 2026-08-12: a label lives on the referenced Q-id, so it costs a second
batched call, and an audit of the then-19 entries across both maps found 16 identical to the live
label, 2 deliberate abbreviations (Q30 "US", Q145 "UK") a swap would change for every US or UK
artist, and 1 wrong (Q211, rekeyed to Latvia with Czech Republic moved to its own Q213 entry,
adding a 20th). `COUNTRY_MAP` holds ISO 3166-1 alpha-2 codes, which is what
`EnrichmentData.Metadata.country` promises, so the "US"/"UK" abbreviations that argument preserved
are now `US` and `GB` and the name entries are codes; a label lookup would be the wrong shape for
it either way, since a label is a name. A Q-id the map does not hold yields **null** — the country
entity's own P297 alpha-2 claim sits on that entity, not on the artist's, so deriving the code
instead of hardcoding it costs the second call the 2026-08-12 argument declined. Never called: the
REST API at `/w/rest.php/wikibase/v1/`, and SPARQL. Note `provider/wikipedia/` *also* calls
`wbgetentities` on this host, for sitelinks, on its own rate limiter.

**Wikipedia.** Two surfaces. The bio comes from the Action API
(`action=query&prop=extracts|pageimages|pageprops&exintro&explaintext`), one request carrying the
lead text, the ~320px thumbnail and the page properties. Parsed and dropped from it:
`wikibase-shortdesc` (the "English rock band" gloss), `wikibase_item` (the Q-id, which would skip a
resolution step elsewhere), `pageid`. `pageprops.disambiguation` is read and acted on — a
disambiguation page yields `NotFound`, never a "Genesis may refer to:" biography. Images come from
REST `/page/media-list`: every image after the lead one, and each item's `caption` and
`showInGallery`. That response carries **no original-file URL and no
height** — only rendered thumbnails — so `Artwork.url` is the largest scale the article offers,
`Artwork.width` is that rendering's width read from the URL's `NNNpx-` segment, `Artwork.sizes`
lists every scale, and `Artwork.height` is always null. Scales are chosen by each entry's own
`scale` field, not by array position. Where no item is flagged `leadImage`, the first surviving
image in article order wins. `utm_*` tracking parameters are stripped from every URL we ship.
Never called: `/page/html/{title}` and
`action=parse`, where the infobox lives — origin, years active, labels, members, which we take from
Wikidata instead. Both hosts are hardcoded `en.wikipedia.org`, so no other language is ever queried.

**`rest_v1` wind-down, read 2026-08-12.** A RESTBase sunset programme exists and is largely
executed: [T314025 "[EPIC] Migrate PCS service away from restbase"](https://phabricator.wikimedia.org/T314025)
and [T314764 "Branch out mobileapps for restbase deprecation"](https://phabricator.wikimedia.org/T314764)
are both Resolved, and MediaWiki's [Page Content Service](https://www.mediawiki.org/wiki/Page_Content_Service)
page describes PCS as deployed behind the REST API gateway "as part of RESTBase sunset". What that
programme moved is the service behind the endpoints, not the endpoints themselves: **no sunset date
for the third-party `/api/rest_v1/` paths was found**, and no `Deprecation`, `Sunset` or `Warning`
header appeared on any live response (`scripts/probes/wikipedia-surface-probe.sh`, HEADERS
section). `/page/media-list` stays on `rest_v1` because nothing else lists an article's images with
a lead-image flag: `/w/rest.php/v1/…/links/media` carries neither ordering nor a lead marker
(probed 2026-08-12). That one dependency is the watch item — the extract no longer rides `rest_v1`
at all. Re-run the probe before treating this as current.

**ListenBrainz.** On responses we already fetch, as captured **2026-09-03**: a release group's
`artist.artists[0].name` (parsed into `ListenBrainzTopReleaseGroup.artistName`, dropped by the
mapper), its `total_listen_count` (parsed, also dropped — ranking for `ARTIST_DISCOGRAPHY`, which
currently returns response order with no counts), and its `release_group.date` and
`release_group.type`, which would fill the `year` and `type` a `DiscographyAlbum` from this provider
leaves null. `caa_id`/`caa_release_mbid` are there too — cover art for a discography entry without a
second provider — but nested inside `release_group` and `release` rather than beside the mbid.
Also `release_mbid` on a top recording (its album's identity, as opposed to the `release_name` a top
track now carries). On a Labs similar-artists row, captured **2026-09-05**: `comment` (MusicBrainz's
disambiguation line), `type` and `gender` — enough to tell two same-named artists apart in a UI,
though `SimilarArtist` has nowhere to put them — and `reference_mbid`, which echoes the artist we
asked about. Never called:
`/1/stats/**`, `/1/user/**`, `/1/similar-users`, and every submit endpoint. Two of the unused are
worth knowing before reaching for a third-party call. `/1/metadata/recording/` is a keyless batch
lookup — up to 1000 recording MBIDs to titles, artists, lengths and release info in one request —
so any source that yields MBIDs without titles costs two requests, not one per track.
`/1/lb-radio/artist/{mbid}` is deliberately left, not overlooked: it answers when
`/1/explore/lb-radio` does not, but it is LB Radio's candidate *pool* rather than its playlist.
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
`/masters/{id}/versions` does not carry it — `/releases/{id}` does. `database/search`'s combined
`"Artist - Title"` field is safely split on the boundary that matches both the requested artist and
title, not the first artist-plausible one, then the result is accepted on the parsed album title as
well as the artist before `ALBUM_ART`, `LABEL`, `RELEASE_TYPE` and `ALBUM_METADATA` share it
(`docs/pitfalls.md` §7). The artist half may credit several artists, joined by `", "`; a request
naming one of them is accepted at that artist's own tier, matching a credited name whole and never
partially (`docs/pitfalls.md` §33). `country` is free text: a country name is normalised to the ISO 3166-1
alpha-2 code `Metadata.country` reports (`UK` included, which is not an ISO code), while a value
naming no *current* ISO country — a multi-country region (`Europe`, `Scandinavia`, `UK & Europe`) or
a historical state (`Yugoslavia`, `Czechoslovakia`) — has no code and is passed through as Discogs
wrote it rather than dropped. Discogs' own literal `Unknown` is the one exception: it names no place
at all, so it is read as absent before that conversion runs, at both this route and
`/masters/{id}/versions`. `ReleaseEdition.country` follows the same rule from the same conversion, so
a master's versions and its album metadata cannot disagree about the wording of one country. Passing
the residue through rather than nulling it was measured, not assumed, on 2026-09-04: over a
188-album spread of decades and regions, 25.9% of the Discogs `country` values a lookup resolved
named no current ISO country, and 11.6% of MusicBrainz's did. 2.4 of those 25.9 points were the
`Unknown` sentinel itself, now read as absent rather than counted among the residue kept above.
