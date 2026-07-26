# Wikipedia

What our code does with Wikipedia. For the API itself — endpoints, request shapes, error codes, rate
limits — follow the upstream link; that is authoritative and this is not.

| | |
|---|---|
| **Package** | `provider/wikipedia/` |
| **Provider ids** | `wikipedia` |
| **Upstream API docs** | https://en.wikipedia.org/api/rest_v1/ |
| **Auth** | None |
| **Deviations from the house pattern** | None — the four files, as `CLAUDE.md` describes them |

**Why this provider.** It outranks Last.fm for `ARTIST_BIO` (100 against 50) because the extract is
editorial prose rather than a scrobbler's summary, and it is the highest-confidence bio in the tree
at `authoritative()`, 0.95. English only.

## What We Extract

One row per entry in `WikipediaProvider.capabilities`. The two lists are compared by
`scripts/checks/check_provider_capabilities.py` on every `./check`.

| EnrichmentType | Identifier | Upstream call | What we keep |
|---|---|---|---|
| `ARTIST_BIO` | `WIKIPEDIA_TITLE` | `/page/summary/{title}` | `extract` as the text, `thumbnail.source`, source label `"Wikipedia"` |
| `ARTIST_PHOTO` | `WIKIPEDIA_TITLE` | `/page/media-list/{title}` | first surviving image's `original.source`, `width`, `height` |

`WIKIPEDIA_TITLE` is satisfied by *either* `wikipediaTitle` or `wikidataId` — see
`ProviderChain.hasRequiredIdentifiers()`. With only a `wikidataId`, `resolveFromWikidata` first calls
`wbgetentities&props=sitelinks&sitefilter=enwiki` **on the Wikidata API, from this provider**, on its
own `wikidataRateLimiter`, before any Wikipedia call happens. That is a second host reached from
`provider/wikipedia/`, and it duplicates a call `provider/wikidata/` already makes.

`ARTIST_PHOTO` is priority 30 — the lowest photo source we have, below Fanart.tv, Deezer and
Wikidata — and scores `fuzzyMatch(hasArtistMatch = false)`, 0.6, because nothing verifies that the
first image on an article depicts the artist. `parseMediaList` is what stands in for that check: it
keeps `type == "image"` only, drops `.svg`, drops any title containing `icon` or `logo`, drops
anything under 100px wide, and `enrichArtistPhoto` then takes `firstOrNull()` — first in article
order, not best.

`getPageSummary` returns null when `extract` is blank, so a stub article reads as `NotFound`.

## What We DON'T Extract

From `/page/summary`, already fetched for every `ARTIST_BIO`:

| Field | Useful for |
|---|---|
| `description` | The one-line "English rock band" gloss — parsed into `WikipediaSummary.description`, then dropped by the mapper |
| `originalimage` | Full-resolution lead image; we take the ~320px `thumbnail` instead |
| `extract_html` | The same text with markup, for consumers that render rather than display |
| `wikibase_item` | The Wikidata Q-id, which would let us skip a resolution step elsewhere |
| `type` | `standard` / `disambiguation` / `no-extract` — the only programmatic way to notice we landed on a disambiguation page |
| `revision`, `tid` | Cache invalidation keys |
| `content_urls` | Desktop and mobile article links, for `ARTIST_LINKS` |

From `/page/media-list`, already fetched for every `ARTIST_PHOTO`: every image after the first, and
each item's `caption`, `srcset` and thumbnail sizes.

Endpoints we never call: `/page/html/{title}` and the MediaWiki `action=parse` path, which is where
the infobox lives (origin, years active, labels, members) — structured data we take from Wikidata
instead. Non-English Wikipedias are never queried; `BASE_URL` hardcodes `en.wikipedia.org`.

## Gotchas

- `docs/pitfalls.md` §3 — `optString`/`optJSONObject` throughout. A `thumbnail` object present
  without a `source` yields `thumbnailUrl = ""` rather than null, because `?.optString("source")`
  returns the empty default and nothing filters it; the mapper passes that into
  `Biography.thumbnailUrl`.
- `docs/pitfalls.md` §4 — a missing title, a blank extract, and an empty media list are all
  `NotFound`, so they record breaker *success*.
- `docs/pitfalls.md` §5 — both capabilities declare `WIKIPEDIA_TITLE`, correctly: there is no text
  search here, and without an identifier from MusicBrainz the provider has nothing to look up.

Ours: **`resolveFromWikidata` runs outside `enrich`'s error handling.** The two enrich helpers each
have their own `try`/`mapError`, but the sitelink call does not, so an exception there escapes to
`ProviderChain`, which turns it into a plain `EnrichmentResult.Error` — a breaker failure without
`mapError`'s `ErrorKind` classification. Recorded, not changed.
