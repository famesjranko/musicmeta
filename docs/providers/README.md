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

## What is checked, and what is not

`## What We Extract` in each doc lists `EnrichmentType` names in the first column of a table.
`ProviderFeatureDocsTest` compares that set against the `capabilities` each package declares at
runtime, on every `./check`, and fails in both directions — plus a package with no doc, a doc with no
package, and a provider that stops being registered. Everything else here is unenforced prose —
written knowing that, and knowing what unenforced prose about a moving package turned into last time.

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
