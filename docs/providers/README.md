# Provider feature docs

One file per package under `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/`,
answering what **our code** does with that provider: why it is here, what we take, what we
deliberately leave, and where the implementation departs from the house pattern.

**This is not an API reference.** Endpoints, request shapes, auth flows, error codes and rate limits
belong to the third party and are authoritative at the source, so each doc carries an
`Upstream API docs` link in its header table instead of a copy that goes stale. An earlier version of
this directory held those copies; they sat untouched for four months while the packages moved weekly,
and by the time they were read again one of them was still reporting a fixed `http://` bug as open.

The house four-file pattern is stated once, in `CLAUDE.md`, and not repeated here. These docs carry
**deviations** from it, not the pattern itself — a provider that follows it says so in one line.

## Nothing here is checked

**No mechanism verifies any of this.** A test that compared each `## What We Extract` table against
the package's declared `capabilities` was built and then deleted as not worth its keep; if a doc and
its package disagree, only a reader will notice.

Every table was verified against its package by hand on **2026-07-26** — endpoints, limits, `inc=`
parameters and POST-vs-GET, all eleven, no corrections needed. That date is the warranty. Capability
declarations changed in 29 commits over the four months to that date, so treat anything older than a
glance at `provider/<name>/` as a claim rather than a fact, and re-check the package before relying
on a row.

## Rate limiting

`withDefaultProviders()` builds **one** `RateLimiter(100)` and hands that same instance to nine
providers — Cover Art Archive, Wikidata, Wikipedia, Deezer (and its `DeezerApi`, so
`deezer-similar-albums` too), ListenBrainz, LRCLIB, Last.fm, Fanart.tv and Discogs. It is
mutex-guarded, so those nine share a single 10 req/s budget rather than getting 10 req/s each, and
one busy provider can consume all of it. Two providers sit outside that: MusicBrainz gets its own
`RateLimiter(1100)`, and iTunes falls through to its constructor default of `RateLimiter(3000)`.
Wikipedia additionally holds a separate limiter for the Wikidata host it reaches.

`RateLimiter`'s own KDoc says "Each provider should have its own RateLimiter instance." The default
wiring does not do that. Recorded, not changed.

**No upstream limit is stated here.** The previous generation of these docs carried a
limits-at-a-glance table; it was wrong about our own settings, and the third-party numbers in it are
not checkable from this repo — Last.fm's API terms publish no figure at all, only "limits... in our
sole discretion". Follow each doc's upstream link.

## The providers

| Provider | Auth | Notable |
|---|---|---|
| [musicbrainz](musicbrainz.md) | none | Identity backbone — three extra files, its own rate limiter, the only `suggestions` path |
| [coverartarchive](coverartarchive.md) | none | MBID-keyed artwork, including back cover, booklet and disc |
| [deezer](deezer.md) | none | Widest no-key catalogue; a second provider class for `SIMILAR_ALBUMS` |
| [itunes](itunes.md) | none | Artwork at any size via a URL substitution; the slowest rate limiter |
| [lrclib](lrclib.md) | none | The only lyrics source |
| [wikidata](wikidata.md) | none | Structured claims; one request serves both capabilities |
| [wikipedia](wikipedia.md) | none | Highest-confidence bio; reaches the Wikidata API for sitelinks |
| [listenbrainz](listenbrainz.md) | optional token | The token gates `ARTIST_RADIO_DISCOVERY` and nothing else |
| [lastfm](lastfm.md) | key | Widest capability set of any single provider |
| [fanarttv](fanarttv.md) | project key | The only source of artist backgrounds, logos and banners |
| [discogs](discogs.md) | token | Pressing-level detail: catalogue numbers, editions, per-track credits |

API keys and how to supply them are in [README.md](../../README.md), which is more complete than this
directory ever was.
