# Providers

What each of the eleven packages under `provider/` requires from a caller and gives back, and the
terms it comes with. What our code does with a response — deviations from the house pattern, name
resolution and ranking, the two genre surfaces, and what a response carries that we deliberately
don't extract — is `docs/provider-internals.md`.

Capabilities, endpoints and confidence values are **not** here, and neither are the per-provider
limiter intervals (`withDefaultProviders()`): they are one glance at the code away, and a copy of
them rots while it moves. §Rate limiting keeps the one-limiter-per-host topology and where each
interval's basis comes from.

**Nothing here is checked.** No mechanism verifies a word of it. Hand-verified against the packages
on **2026-08-14**; treat anything after that as a claim, not a fact.

The Auth column below restates `ProviderCatalogEntry.keyRequirement` for each provider in
`ProviderCatalog.entries`, which is pin-tested against the code. The restatement itself is
hand-verified like the rest of this file, not checked.

## The providers

Auth keys and how to supply them are in [README.md](../README.md).

| Provider | Package | Auth | Upstream API docs | Why it is here |
|---|---|---|---|---|
| MusicBrainz | `musicbrainz` | none (User-Agent required) | [docs](https://musicbrainz.org/doc/MusicBrainz_API) | Identity backbone — `isIdentityProvider`, runs first, and the only `NotFound` that can carry `suggestions`. Also the only rating source for `ARTIST_POPULARITY`/`TRACK_POPULARITY` |
| Cover Art Archive | `coverartarchive` | none | [docs](https://musicbrainz.org/doc/Cover_Art_Archive/API) | Only artwork source keyed on a release MBID rather than a name, and the only source of back cover, booklet and disc |
| Deezer | `deezer` | none | [docs](https://developers.deezer.com/api) | Widest no-key catalogue; only source of `ARTIST_RADIO`, `TRACK_PREVIEW` |
| Deezer Similar Albums | `deezer` | none | [docs](https://developers.deezer.com/api) | Only source of `SIMILAR_ALBUMS`; an artist-derived approximation — Deezer has no album-similarity endpoint, so results are albums by similar artists, era-weighted |
| iTunes | `itunes` | none | [docs](https://performance-partners.apple.com/search-api) | No-key album search with artwork at any size; `lookup?upc=` resolves a known barcode as an identity match, replacing the search |
| LRCLIB | `lrclib` | none | [docs](https://lrclib.net/docs) | Only lyrics source |
| Wikidata | `wikidata` | none | [docs](https://www.wikidata.org/wiki/Wikidata:Data_access) | Structured claims keyed on a Q-id; our route to Commons imagery at any width |
| Wikipedia | `wikipedia` | none | [docs](https://www.mediawiki.org/wiki/API:Main_page) | Highest-confidence bio; English only |
| ListenBrainz | `listenbrainz` | optional token | [docs](https://listenbrainz.readthedocs.io/en/latest/users/api/) | MBID-keyed listen counts that cannot mismatch the artist; only source of `ARTIST_RADIO_DISCOVERY`, which is what the token gates; the only `SIMILAR_ARTISTS` source whose every answer carries an MBID |
| Last.fm | `lastfm` | API key | [docs](https://www.last.fm/api) | Widest capability set of any single provider; only source of tags-as-genre and artist similarity |
| Fanart.tv | `fanarttv` | project key | [docs](https://fanarttv.docs.apiary.io/) | Only source of artist backgrounds, logos and banners |
| Discogs | `discogs` | token | [docs](https://www.discogs.com/developers) | Pressing-level detail: catalogue numbers, editions, per-track credits |

## Provenance self-reporting

Every capability below is declared with `identifierRequirement = NONE` — MusicBrainz canonical
resolution is optional for all of them — but some still have an *exact-id* branch that runs instead
of a name search whenever the request already carries that id. A capability's requirement alone
cannot tell that branch apart from the search fallback, so each of these `enrich()` paths sets
`EnrichmentResult.Success.provenance` itself — `LookupProvenance.PROVIDER_NATIVE_ID` for a branch
keyed on that provider's own id space, `LookupProvenance.EXTERNAL_CATALOG_ID` for a branch keyed on
a UPC/barcode — whenever it took the id branch, and leaves it unset on the name-search branch for
the engine to classify from canonical status.

| Provider | Type(s) | Id branch |
|---|---|---|
| Deezer | `TRACK_PREVIEW`, `TRACK_METADATA` | A Deezer track id already on the request |
| Deezer | `ARTIST_TOP_TRACKS`, `SIMILAR_ARTISTS`, `ARTIST_RADIO` | A Deezer artist id already on the request |
| iTunes | `ALBUM_TRACKS` | A stored `itunesCollectionId`, or a UPC/barcode lookup |
| iTunes | `ALBUM_ART`, `ALBUM_METADATA` | A UPC/barcode lookup |
| iTunes | `ARTIST_DISCOGRAPHY` | A stored iTunes artist id |
| Discogs | `CREDITS` | A stored `discogsReleaseId` — the only route this type has; there is no name-search fallback |
| Discogs | `RELEASE_EDITIONS` | A stored `discogsMasterId` — the only route this type has; there is no name-search fallback |

A UPC/barcode is an external catalogue identifier, not a MusicBrainz id and not either provider's
own id space, so it carries `LookupProvenance.EXTERNAL_CATALOG_ID` rather than `PROVIDER_NATIVE_ID`.

## Routes ListenBrainz has withdrawn before

All five ListenBrainz routes we call answered on **2026-09-03**, verified through the library rather
than by status code. Three of them had not: ListenBrainz disabled `/1/explore/lb-radio` and both
`/1/popularity/top-*-for-artist/` routes around **2026-06-30**, and every request to them returned
`500` ahead of auth and parameter validation, so nothing about a request distinguished itself.

What that cost while it lasted is what a repeat would cost: `ARTIST_RADIO_DISCOVERY` has
ListenBrainz as its only provider and goes dark with `/1/explore/lb-radio`, while
`ARTIST_DISCOGRAPHY` and `ARTIST_TOP_TRACKS` lose one source of several and still answer from
Deezer and Last.fm. `ARTIST_POPULARITY` and `TRACK_POPULARITY` were unaffected throughout — they
ride the batch `POST /1/popularity/artist` and `POST /1/popularity/recording`, which stayed up.

Nothing re-probes these on a schedule, so the date above is the last time anyone looked, not a
guarantee about today.

## ListenBrainz Labs, and the algorithm this library pins

`SIMILAR_ARTISTS` from ListenBrainz comes from `labs.api.listenbrainz.org`, not from
`api.listenbrainz.org`. Labs is ListenBrainz's experimental deployment: a different host with its
own availability, no documented stability promise, and no token. Every other ListenBrainz
capability rides the main API, but they are not isolated from each other: the two share one rate
limiter, so a slow Labs answer is time the main API's routes do not get, and one circuit breaker,
which is per *provider* rather than per route. Five Labs errors with no main-API success in between
open that breaker, and for the next 60 seconds `ARTIST_POPULARITY`, `TRACK_POPULARITY`,
`ARTIST_DISCOGRAPHY`, `ARTIST_TOP_TRACKS` and `ARTIST_RADIO_DISCOVERY` are skipped too — a Labs
outage is briefly a ListenBrainz outage.

The route takes an `algorithm` parameter whose permitted values are an enum published nowhere but
the route's own error body. This library pins one member — session-based over a 7500-day window at
the full 100-result limit, the combination that still answers for a long-tail artist. Asking for a
member the route no longer accepts is a `400`, and this library turns that into an
`EnrichmentResult.Error`, deliberately: the same route answers an artist it holds no data for with
an empty list, so treating a rejection as "no similar artists" would hide a retired algorithm as a
silent, permanent absence of results. If `SIMILAR_ARTISTS` starts erroring from `listenbrainz`
alone while Last.fm and Deezer keep answering, that is the shape to expect.

Results are scored, unbounded session counts, and the route answers with up to a hundred rows where
Last.fm answers with twenty — expect a merged `SIMILAR_ARTISTS` list to be markedly longer than it
was before this provider joined the type (Radiohead: 31 entries to 109). A
The score this provider hands the merger is that count scaled against the highest in the same
answer and then halved, so its top row enters the merge at `0.5`. The halving is deliberate and is
a contract: the merger sums each provider's figure, so at full scale a row this provider alone
picked would outrank two providers agreeing on an artist neither ranked first. At half scale a Labs
row lifts an artist another provider also picked, and cannot outrank two providers that agree. The
figures are not comparable between two answers, nor against Last.fm's own similarity number — and
the `SimilarArtist.matchScore` a consumer reads is the merger's rescaled rank, not this one.

## Editions of an album

`limit=100` is MusicBrainz's browse maximum and the documented bound on the answer. A release group
holding more than 100 releases is truncated, deliberately, rather than paged over — a consumer
counting editions is counting at most a hundred of them.

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

**An MBID MusicBrainz holds that names something else** is the third case, and the only one the
caller is told about. Every route that reaches an entity from a caller's identifier compares the
artist it came back credited to against the artist the request named, and reports only confident
disagreement: a supplied name matching any name MusicBrainz holds for that entity — aliases and
search hints included, since matching one can only ever prevent a report — is no contradiction, and
neither is a pair of names in scripts that cannot be compared at all. An album carries a second,
structured check on the same terms. An album cannot predate its own first release, so a request
`year` two or more years earlier than the release group's `first-release-date` is positive evidence
of a different album; it is one-sided on purpose, since a *later* year is any reissue, remaster or
region pressing and is not judgeable, and it costs no request because `inc=release-groups` already
carries the date.

Either check drops the identifier as `Absent`, on exactly the terms of one MusicBrainz holds nothing
under: the request falls back to the name it carries, and the contradiction is marked on the call
*before* that fallback runs, so recovering by name never hides the bad identifier. The call then
reports `CanonicalStatus.CONTRADICTED`, which outranks the `RESOLVED` the fallback would otherwise
have earned. The disowned identifier stays on `IdentityResolution.identifiers` — a name fallback
resolves an entity, not an identifier — so read the status before trusting that field, and never
pass it on to the next call. `RELEASE_EDITIONS` runs both checks against a supplied release-*group*
id and holds no name route to recover by, so when one fires it reports and answers nothing.

**An identifier and no name at all** is the same rule taken to its end. `EnrichmentRequest.forTrackByMbid`
and its siblings leave the names blank for identity resolution to fill from the entity it looked up
(`docs/how-it-works.md`, Step 3); a blank one MusicBrainz could not fill is answered `NotFound`
rather than searched for, because whatever ranks first for `recording:""` must never become the
request's entity. The engine holds the same line for every *other* provider, skipping the
name-search ones entirely for a request that names no entity — MusicBrainz's own guard covers only
MusicBrainz. **A blank artist with a real title** is the same refusal one step earlier: MusicBrainz
widens rather than refuses `artist:""`, so whatever ranks first for a bare title is an arbitrary
same-titled release. An album request holding one returns its candidate pool as `suggestions` under
`CanonicalStatus.AMBIGUOUS` instead of resolving; a track request answers `NotFound` without
spending a search at all. Whitespace counts as blank. `EnrichmentEngine.discoverMbidEntityType` is the same absence read the other way:
the three entity types probed in order until one answers, which is the only way to learn what a bare
MBID names.

This is not a nicety. These identifiers come from third parties in practice, Last.fm's having been
bulk-imported and never re-synced: measured 2026-08-12 over `track.getSimilar`
(`scripts/probes/lastfm-mbid-staleness-probe.sh`, `SOURCE=similar`), **1212 of the 1710 recording MBIDs
MusicBrainz answered for were held under no entity type at all** — the rate tracks how obscure the
track is, and the same probe over the chart head is far kinder.

For a track the cost of treating such an identifier as authoritative was scoped to the MusicBrainz
types alone: identity resolution is where a track request meets its MBID, and a `NotFound` carrying
suggestions changes only the top-level canonical metadata — every other eligible provider still
runs, best-effort (`TrackIdentifierMissFanOutTest`, `IdentitySuggestionFanOutTest`). Suggestions are
for a name that resolves to nothing; no identifier path raises them.

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

## Terms, licences, attribution

What each provider's terms said on **2026-08-12** — verify before relying on any of it. This is not
a compliance promise and not legal advice: it is a dated snapshot of public terms pages, collected
once, with no mechanism keeping it current. musicmeta takes no standing position on any provider's
terms; a consumer enabling a provider is bound by that provider's terms, not by anything below.

| Provider | Commercial | Licence | Attribution | Source | Date |
|---|---|---|---|---|---|
| MusicBrainz | Non-commercial free; commercial plans required | Core CC0; supplementary CC BY-NC-SA 3.0 (field split unresolved) | None for CC0; credit + ShareAlike for supplementary | musicbrainz.org/doc/About/Data_License; musicbrainz.org/doc/MusicBrainz_API | 2026-08-12 |
| Cover Art Archive | Not stated | Per-image copyright, rights holders retain; no licence field in API | Not specified | coverartarchive.org; musicbrainz.org/doc/Cover_Art_Archive/API | 2026-08-12 |
| ListenBrainz | Could not verify | Could not verify | Could not verify | listenbrainz.readthedocs.io/en/latest/users/api/index.html | 2026-08-12 |
| Wikidata | Allowed, free, unauthenticated | CC0 (exact wording not verbatim-confirmed) | None required | wikidata.org/wiki/Wikidata:Data_access | 2026-08-12 |
| Wikipedia | Allowed, free; UA policy binding | Text CC BY-SA 4.0 / GFDL; media per-file | Required: link, stable copy, or author list + licence notice | en.wikipedia.org/wiki/Wikipedia:Reusing_Wikipedia_content | 2026-08-12 |
| Deezer | **Prohibited** — strictly non-commercial, incl. indirect revenue | Reproduction forbidden without express authorisation | Trademark Guidelines incorporated (content unverified) | developers.deezer.com/termsofuse | 2026-08-12 |
| iTunes/Apple | Conditional (badge, streaming-only, promo rules) | Search JSON caching encouraged; preview-media restriction unverified | "provided courtesy of iTunes" when previews shown | performance-partners.apple.com/search-api | 2026-08-12 |
| LRCLIB | Could not verify — no terms published at all | Could not verify; MIT covers server code only | Could not verify | lrclib.net; github.com/tranxuanthang/lrclib | 2026-08-12 |
| Last.fm | **Non-commercial only**; commercial needs written agreement | Copy/adapt/distribute, capped at 100 MB total storage; no sub-licensing, no DRM | "must credit Last.fm and include links to the Last.fm site" | last.fm/api/tos | 2026-08-12 |
| Fanart.tv | Could not verify — Cloudflare 403 | Could not verify | Could not verify | fanart.tv/terms-of-use/ (403) | 2026-08-12 |
| Discogs | Allowed; may not fee-gate free API content without permission | Dumps CC0; image carve-out could not be verified | "This application uses Discogs' API but is not affiliated with, sponsored or endorsed by Discogs" | support.discogs.com/hc/en-us/articles/360009334593; discogs.com/developers | 2026-08-12 |

**MusicBrainz is the sharpest case.** It is on the path of nearly every `enrich()` call — the
identity provider, not one optional source among many — and the hosted web service is
non-commercial free, with commercial plans expected for a revenue-bearing consumer. Its core data
is CC0, but the terms page never enumerates which fields are "core" versus the CC BY-NC-SA 3.0
"supplementary" set; whether folksonomy tags/genres fall on one side or the other is unresolved,
and it is the highest-value open question here, since it decides whether `genre_merger`'s output
carries CC BY-NC-SA obligations — attribution *and* non-commercial, not just provenance.

**"Could not verify" rows are a finding, not a gap papered over.** Fanart.tv's terms page 403s a
non-browser client; ListenBrainz's terms page is JS-only; LRCLIB publishes no terms instrument at
all (its MIT licence covers the server code, not the lyrics it serves — those are third-party
copyrighted works regardless). Apple is only partially verified: performance-partners.apple.com's
Search API page is the source for the row above, but Apple's governing ToS document 404s, so the
preview-media caching rule could not be confirmed against it.

**Deezer is strictly non-commercial**, including indirect revenue, with no separate-agreement
escape hatch on its terms page. It ships enabled by default, and the README's audience is any
Android or JVM app, commercial included — a consumer who leaves Deezer on in a commercial product
is relying on a provider whose terms forbid that use.

**Last.fm's Reasonable Usage Cap covers more than storage** — the clause reads "any use, storage,
publication, distribution, communication, making available or otherwise" of Last.fm data, capped
at 100 MB total, plus header-driven caching. `EnrichmentCache` persists Last.fm payloads for the
enrichment type's TTL — up to 90 days for `GENRE` — without measuring cumulative Last.fm bytes or
reading Last.fm's response headers; a consumer relying on the cache to stay under the cap has
nothing in musicmeta enforcing it.

**Wikipedia's text is CC BY-SA and asks for a licence notice plus a link, a stable copy, or an
author list per reuse.** musicmeta caches and serves the text with no such notice, article URL, or
author list retained anywhere a consumer could render it.

**Cover Art Archive images are copyrighted per image by their respective rights holders**, and the
API carries no licence field to propagate even if musicmeta wanted to surface one.

The same facts ship as data: `ProviderPolicies` maps a provider id to a `ProviderPolicy` carrying
this table's row plus, for Last.fm, Discogs, iTunes and Wikipedia, the notice text to render. A
merged result names a merger, not an upstream, so the join key is each item's `sources` list
(`GenreTag`, `SimilarArtist`, `SimilarTrack`, `TopTrack`) looked up in the registry.

## User-Agent and contact information

Two of the shipped providers' policies require the User-Agent to identify a human. Read
2026-08-12, same caveats as the table above.

**MusicBrainz**: "you must have a meaningful user-agent string" — "there needs to be enough
information in the User-Agent string for us to contact the maintainers". Suggested form
`Application name/<version> ( contact-url )`. Requests without it are treated as anonymous and
share one throttled pool, alongside the per-IP limit of roughly one request per second.
**Wikimedia** (Wikipedia and Wikidata): the policy requires `<client name>/<version> (<contact
information>)`, and generic user agents "may be blocked" with a 403.

musicmeta ships `EnrichmentConfig.DEFAULT_USER_AGENT = "MusicEnrichmentEngine/1.0"`, which meets
neither. The default is kept so zero-config still runs, not because it complies: a consumer who
leaves it is the anonymous, blockable case above, on their own IP.

Two ways to comply, and the first is preferred:

```kotlin
EnrichmentEngine.Builder().contact("https://example.com/myapp")   // MusicEnrichmentEngine/1.0 ( https://example.com/myapp )
EnrichmentEngine.Builder().config(EnrichmentConfig(userAgent = "MyApp/1.0 (me@example.com)"))
```

A `userAgent` set on the config wins and `contact()` is then ignored. Supplying neither logs one
warning through the engine's `EnrichmentLogger` at `build()` time, when any of `musicbrainz`,
`wikipedia` or `wikidata` is registered — once per engine, never per request.

`build()` warns from what the wire will carry, so the two ways to compose a compliant string that
never reaches a provider are warned about too, once each:

- `contact()` called *after* `withDefaultProviders()`, which already built the client with the
  contactless default. Call `contact()` first.
- a client passed to `httpClient()`, which `contact()` cannot reach. Set the User-Agent on that
  client — `DefaultHttpClient` takes it as its first constructor argument.

`ProviderPolicies["wikipedia"].commercialUseNote` carries the Wikimedia clause as data.

## Rate limiting

`withDefaultProviders()` builds **one `RateLimiter` per host**, as `RateLimiter`'s KDoc requires.
A limiter holds its mutex across the request itself, so a shared instance would make unrelated hosts'
round-trips sequential; rate limits are per-host and no host here asks to be throttled against
another's traffic. The two Deezer providers share one limiter because they share a host; Wikipedia
takes the Wikidata limiter for the Wikidata host it reaches, alongside its own. iTunes takes its
constructor default of `RateLimiter(3000)`. Since 2026-08-12, `ALBUM_METADATA` also calls
`GET /album/{id}`. Requested alone it doubles Deezer's cost (1 request → 2: search, then detail).
Requested alongside `ALBUM_TRACKS` (or `ALBUM_ART`) in one `enrich()` call, `ProviderCallScope`
shares the search between them, so the total is unchanged at 3 requests either way — was two
searches plus `/tracks`, is now one shared search plus `/tracks` plus `/album/{id}`. The sharing
does not cross separate `enrich()` calls.

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
