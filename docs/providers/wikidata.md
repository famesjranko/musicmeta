# Wikidata

What our code does with Wikidata. For the API itself — endpoints, request shapes, error codes, rate
limits — follow the upstream link; that is authoritative and this is not.

| | |
|---|---|
| **Package** | `provider/wikidata/` |
| **Provider ids** | `wikidata` |
| **Upstream API docs** | https://www.wikidata.org/wiki/Wikidata:Data_access |
| **Auth** | None |
| **Deviations from the house pattern** | None — the four files, as `CLAUDE.md` describes them |

**Why this provider.** Structured claims instead of prose or user tags, keyed on a Q-id that
MusicBrainz hands us, so both capabilities score `authoritative()`, 0.95. It is also our only route
to Wikimedia Commons imagery at an arbitrary width.

## What We Extract

One row per entry in `WikidataProvider.capabilities`. The two lists are compared by
`ProviderFeatureDocsTest` on every `./check`.

| EnrichmentType | Identifier | Upstream call | What we keep |
|---|---|---|---|
| `ARTIST_PHOTO` | `WIKIDATA_ID` | `wbgetclaims`, `property=P18\|P569\|P570\|P495\|P106` | P18 filename → a Commons `Special:FilePath` URL at `imageSize`, default 1200 |
| `COUNTRY` | `WIKIDATA_ID` | the same single call | P495 country, **and** P569 birth date, P570 death date, P106 occupation |

**One request serves both.** `getEntityProperties` asks for all five properties at once and `enrich`
picks from the result, so asking for both types costs two identical round trips rather than one each
of two shapes.

Two things about `COUNTRY` that its name does not say:

- It carries four fields, not one. `WikidataMapper.toMetadata` fills `country`, `beginDate`,
  `endDate` and `artistType` on a single `EnrichmentData.Metadata`.
- It **succeeds when `country` is null**, as long as any one of the other three is present. A
  consumer asking for `COUNTRY` can get a `Success` whose country is absent.

`selectClaim` takes the first claim of rank `preferred`, falling back to the array's first entry — so
a `deprecated` claim can still win if it is first and nothing is preferred. `buildCommonsUrl`
underscores spaces, URL-encodes, and appends `.png` for `svg`, `tif` and `tiff` so Commons rasterises
them.

**Q-ids leak through when unmapped.** `COUNTRY_MAP` holds 14 countries and `OCCUPATION_MAP` five
occupations, both applied as `MAP[qid] ?: qid`. Anything outside those lists reaches the consumer as
the raw `"Q…"` string in `Metadata.country` or `Metadata.artistType`.

## What We DON'T Extract

The five properties above are all we request, so everything else needs another call. Wikidata's
music-relevant claims that no provider in the tree reads:

| Property | Would give |
|---|---|
| P136 genre, P264 record label | `GENRE`, `LABEL` — as Q-ids, needing the same label lookup the maps above hand-roll |
| P527 has-part, P361 part-of | `BAND_MEMBERS`, and which groups an artist belongs to |
| P571 / P576 inception and dissolution | Band formation and breakup, distinct from the P569/P570 person dates we do read |
| P856 official website, P2002 / P2003 / P2013 socials | `ARTIST_LINKS` |
| P434, P4404, P4407, P8052 MusicBrainz ids | Reverse identity resolution |
| P1902, P1728, P1953 Spotify / AllMusic / Discogs ids | Cross-provider identity |
| P373 Commons category, P1303 instrument, P166 awards | More imagery, and detail nothing else supplies |

Endpoints we never call: `wbgetentities` for labels and descriptions — which is what would turn a
Q-id into a name and retire both hardcoded maps — the newer REST API at `/w/rest.php/wikibase/v1/`,
and the SPARQL endpoint. Note that `provider/wikipedia/` *does* call `wbgetentities` on this same
host, for sitelinks, on its own rate limiter.

## Gotchas

- `docs/pitfalls.md` §3 — every hop is `optJSONObject`/`optString`, so a claim structure that moves
  yields null and reads as "this artist has no image", not as a failure.
- `docs/pitfalls.md` §4 — a blank `wikidataId`, a null claims object, and an entity with none of the
  five properties are all `NotFound`, so they record breaker *success*.
- `docs/pitfalls.md` §5 — both capabilities declare `WIKIDATA_ID`, correctly: there is no text search
  here.

Ours: `getArtistImageUrl` is dead code — a one-line wrapper over `getEntityProperties` with no caller.
