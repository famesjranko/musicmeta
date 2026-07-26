# MusicBrainz

What our code does with MusicBrainz. For the API itself — endpoints, request shapes, error codes,
rate limits — follow the upstream link; that is authoritative and this is not.

| | |
|---|---|
| **Package** | `provider/musicbrainz/` |
| **Provider ids** | `musicbrainz` |
| **Upstream API docs** | https://musicbrainz.org/doc/MusicBrainz_API |
| **Auth** | None — but a descriptive User-Agent is required, and `DefaultHttpClient` supplies it |
| **Deviations from the house pattern** | Three extra files: `MusicBrainzEnricher.kt`, `MusicBrainzParser.kt`, `MusicBrainzCreditParser.kt`. See below |

**Why this provider.** It is the identity backbone: `isIdentityProvider = true`, so it runs first and
its `resolvedIdentifiers` are what let the Cover Art Archive, Fanart.tv, Wikidata, Wikipedia and
ListenBrainz be called at all. It is also the only provider whose `NotFound` can carry `suggestions`,
which short-circuits the entire fan-out — see `enrich()` in `engine/DefaultEnrichmentEngine.kt`.

`withDefaultProviders()` gives it `RateLimiter(1100)` — its own limiter, against upstream's 1 req/sec.
Every other provider shares a 100ms one.

## Deviation: three extra files

`CLAUDE.md` states the house four-file pattern. This package adds three files to it:

| File | What it does | Why it is separate |
|---|---|---|
| `MusicBrainzEnricher.kt` | All per-entity enrichment logic, plus the artist lookup cache | `MusicBrainzProvider` would otherwise be ~440 lines. It routes by request subtype and hands off; the Enricher decides search-vs-lookup, ranks candidates and builds every result |
| `MusicBrainzParser.kt` | Every JSON → DTO conversion for search and lookup responses | The other packages parse inline in `*Api`. Eleven capabilities across three entity types and two response shapes each is more than an API client should hold |
| `MusicBrainzCreditParser.kt` | `CREDITS` and `RELEASE_EDITIONS` only: recording `artist-rels`/`work-rels`, and release-group `releases` | These two read **raw `JSONObject`**, not DTOs — `lookupRecording` and `lookupReleaseGroup` return the response unparsed, unlike every other call in the package. It also owns the relation-type → role mapping |

`MusicBrainzEnricher` holds a `ConcurrentHashMap<String, MusicBrainzArtist>` guarded by a `Mutex`,
because `BAND_MEMBERS`, `ARTIST_LINKS` and `GENRE` all need the same `artist/{mbid}?inc=…` response.
It is **never evicted and unbounded** — one entry per artist MBID, for the lifetime of the provider
instance. Recorded, not changed.

## What We Extract

One row per entry in `MusicBrainzProvider.capabilities`. The two lists are compared by
`ProviderFeatureDocsTest` on every `./check`.

| EnrichmentType | Identifier | Upstream call | What we keep |
|---|---|---|---|
| `GENRE` | — | `release`/`artist`/`recording` search, or `…/{mbid}` lookup | `tags` plus their counts as `GenreTag` |
| `LABEL` | — | the same | `label-info[0].label.name` |
| `RELEASE_DATE` | — | the same | `date` |
| `RELEASE_TYPE` | — | the same | `release-group.primary-type` |
| `COUNTRY` | — | the same | `country` |
| `ALBUM_TRACKS` | — | `release/{mbid}?inc=…media+recordings` | title, position, duration per track |
| `BAND_MEMBERS` | — | `artist/{mbid}?inc=tags+url-rels+artist-rels` | member name, MBID, instruments, begin/end; a `Person` with no members maps to itself |
| `ARTIST_DISCOGRAPHY` | — | `release-group?artist={mbid}` | release-group title, year, type, MBID |
| `ARTIST_LINKS` | — | `artist/{mbid}?inc=…url-rels` | every URL relation, by type |
| `CREDITS` | `MUSICBRAINZ_ID` | `recording/{mbid}?inc=artist-rels+work-rels` | credit name, role, role category |
| `RELEASE_EDITIONS` | `MUSICBRAINZ_RELEASE_GROUP_ID` | `release-group/{mbid}?inc=releases` | per-release title, format, country, date, barcode |

**The first five rows are one result, not five.** `buildAlbumResult` and `buildArtistResult` ignore
`type` entirely and return the whole `EnrichmentData.Metadata` — genres, label, date, type, country,
barcode, disambiguation — so asking for `COUNTRY` returns the label too, and asking for all five
costs five identical requests. Only `ALBUM_TRACKS`, the three `ARTIST_NEW_TYPES` and the two
identifier-gated types take their own path.

**Confidence tracks how we got there.** An MBID in the request means a direct lookup at
`idBasedLookup()`, 1.0. A search means `searchScore(best.score)` — MusicBrainz's own 0–100 score
divided by 100 — and anything under `minMatchScore` (80 by default) is rejected as `NotFound` rather
than returned at low confidence.

**`NotFound` here can carry `suggestions`**, up to 3, built from either the strict search results or
a fuzzy retry. That is the disambiguation path, and per `CLAUDE.md` an identity `NotFound` with
suggestions stops the whole provider fan-out.

`pickBestArtist` does not take the top-scored candidate. It ranks exact-name-with-tags above
exact-name above has-tags above everything else, then takes the first — so a lower-scored exact match
beats a higher-scored near-miss.

Beyond `capabilities`, this provider also implements `searchCandidates` (albums and artists;
`ForTrack` returns an empty list) and `resolveIdentity`, which simply calls `enrich(request, GENRE)`
for its identifiers.

## What We DON'T Extract

`RELATION_DEPENDENT_TYPES` in `MusicBrainzEnricher` lists `ARTIST_PHOTO`, `ARTIST_BIO`,
`ARTIST_BACKGROUND` and `ARTIST_LOGO` and triggers a full lookup for them — but **the provider
declares none of those capabilities**, so the engine never routes them here and that branch of
`enrichAlbum` is unreachable in practice. Recorded, not changed.

Parsed and dropped, or present in a response we already fetch:

| Field | Would give |
|---|---|
| `isrcs` beyond the first | `toTrackMetadata` keeps `isrcs.firstOrNull()`; a recording often has several |
| `release.packaging`, `status`, `quality` | Physical format and data-quality signals |
| `media[].format` | CD vs vinyl vs digital, per disc |
| `artist.aliases`, `sort-name` | Alternate names for matching, which `ArtistMatcher` would use |
| `artist.life-span.ended` | Whether a band split, distinct from having an end date |
| `label-info[]` beyond the first | Co-releases and reissues |
| `annotation`, `rating` | Editorial notes and community ratings |

Endpoints and `inc=` values we never request: `works`, `series`, `events`, `places`, `instruments`,
`genres` (the curated list, as opposed to the `tags` we do read), `collections`, and the whole
`/ws/2/…?inc=aliases` surface. There is no cover-art call here — the Cover Art Archive provider
handles that, keyed on the identifiers this one resolves.

## Gotchas

- `docs/pitfalls.md` §5 — nine capabilities declare no `identifierRequirement`, which is right: this
  is the provider that *produces* identifiers, so requiring one would deadlock identity resolution.
  The two that do declare one genuinely cannot start from a name.
- `docs/pitfalls.md` §3 — `MusicBrainzParser` is `optString`/`optJSONObject` throughout. A renamed
  field yields an empty tag list or a null label and reads as a sparse release.
- `docs/pitfalls.md` §4 — a below-threshold score, an empty tracklist and a missing release group are
  all `NotFound`, so they record breaker *success*. That matters more here than anywhere: this
  provider runs first on every request.
- `docs/pitfalls.md` §2 — the single `catch` is in `MusicBrainzProvider.enrich`, wrapping all three
  `Enricher` entry points, and routes to `mapError`.
