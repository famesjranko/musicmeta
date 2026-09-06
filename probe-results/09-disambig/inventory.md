# Inventory: what the three SIMILAR_ARTISTS contributors already return

Phase 1 of `.scratch/features/issues/09-*`. Written **before** `plan.md` and before any arm. The
question this answers is the owner's: *what is already on the wire that we drop on the floor?*

Sources: the frozen fixtures at `probe/dedup-arm-mbid:musicmeta-core/src/test/resources/probe/fixtures/`
(12 artists x 3 providers, captured 2026-09-05 for `tech-debt/08`); the mappers and parsers at
`085ae2dd`; one live re-capture of each upstream's shape on 2026-09-06 to confirm the fixture trims
nothing.

## 1. Every field each contributor's rows carry, and what we read

### ListenBrainz Labs — `GET labs.api.listenbrainz.org/similar-artists/json`

Row shape, union over both fixtures and over a live 2026-09-06 capture (100/100 rows carry all
seven keys in every case):

| Field | Example | Read by `ListenBrainzApi.parseSimilarArtist`? |
|---|---|---|
| `artist_mbid` | `9c935736-7530-41e4-b776-1dbcf534c061` | **yes** — `identifiers.musicBrainzId` |
| `name` | `Spiritbox` | **yes** |
| `score` | `213` | **yes** — session count, rescaled by the mapper |
| `comment` | `Canadian metalcore` | **no — dropped** |
| `type` | `Group` / `Person` | **no — dropped** |
| `gender` | `null` / `Male` | **no — dropped** |
| `reference_mbid` | the *requested* artist's MBID | **no — dropped** |

`comment` is **MusicBrainz's `disambiguation` field, verbatim**. Checked against a live
`/ws/2/artist/{mbid}` lookup for all four Labs-carried split-pair MBIDs on 2026-09-06: the strings
are byte-identical (`metalcore/post-metal`, `Canadian metalcore`, `UK experimental metal`,
`electrojazz duo from New York`). Labs is already doing the MusicBrainz lookup for us and we throw
the answer away.

`comment` is present-but-empty (`""`) for artists MusicBrainz has not disambiguated. Across the full
twelve-artist workload, **463 of 1226 merged groups (37.8%)** would receive a non-empty string from
this field alone.

**The schema pin does not watch any of the four dropped fields.** `ListenBrainzApi.SCHEMA_PIN_TARGETS`
pins the `labs similar artists` route with `requiredPaths = ["[0].artist_mbid", "[0].name",
"[0].score"]`. If Labs stopped sending `comment` tomorrow, nothing in this repo would notice. Any arm
that ships this field must add it to that pin.

### Last.fm — `artist.getSimilar`

| Field | Example | Read by `LastFmApi.parseSimilarArtists`? |
|---|---|---|
| `name` | `Bad Omens` | **yes** |
| `match` | `"0.900919"` | **yes** — `matchScore` |
| `mbid` | `8834d8b5-…` (absent on 4 of 20 sleep-token rows) | **yes** |
| `url` | `https://www.last.fm/music/Bad+Omens` | **no — dropped** |
| `image[]` | 5 sizes, all the grey placeholder in these fixtures | **no — dropped** |
| `streamable` | `"0"` | **no — dropped** |

**`url` is derived from the name, not from the entity.** `https://www.last.fm/music/Bad+Omens` is
literally `name` URL-escaped. Two Last.fm rows sharing a name would therefore carry the *same* URL,
so this field is structurally incapable of distinguishing a same-name pair, and it can point at the
wrong act (see §3). No disambiguation text of any kind.

### Deezer — `GET /artist/{id}/related`

| Field | Example | Read by `DeezerApi.getRelatedArtists`? |
|---|---|---|
| `id` | `9700940` | **yes** |
| `name` | `Bad Omens` | **yes** |
| `link` | `https://www.deezer.com/artist/9700940` | **no — dropped** (derivable from `id`) |
| `picture`, `picture_small/medium/big/xl` | CDN URLs | **no — dropped** |
| `nb_album` | `23` | **no — dropped** |
| `nb_fan` | `164456` | **no — dropped** |
| `radio` | `true` | **no — dropped** |
| `tracklist` | API URL | **no — dropped** |
| `type` | `"artist"` (constant) | **no — dropped** |

**No MBID and no free-text description at all.** `link` is entity-keyed, so it does distinguish two
Deezer artists — but Deezer's related list never returns two rows with the same name in this
workload, so it never has a pair to distinguish. `nb_fan`/`nb_album` are a popularity signal, not an
identity one: they would let a consumer guess which act is the famous one, and nothing more.

The mapper derives its score from list position (`(count - index) / count`); Deezer publishes no
similarity figure.

## 2. The four split pairs, raw, from every contributor that carries them

Split pairs recomputed at `085ae2dd` with a faithful offline model of `SimilarArtistMerger.groupArtists`
over the frozen workload: **exactly four name-key collisions across 1226 merged groups**, matching
`bugs/30`. (A fifth candidate, `fleetwood-mac / Tom Petty and the Heartbreakers`, is *not* a split:
the Last.fm row carries no MBID, so it falls back to the name key and joins the Labs group.)

### sleep-token / Bad Omens

| Group | Contributor | raw row |
|---|---|---|
| 1 (`eecada09-…`) | deezer | `{"id": 9700940, "name": "Bad Omens", "link": "https://www.deezer.com/artist/9700940", "nb_album": 23, "nb_fan": 164456}` |
| 1 | labs | `{"artist_mbid": "eecada09-acfc-472d-ae55-e9e5a43f12d8", "name": "Bad Omens", "comment": "metalcore/post-metal", "type": "Group", "gender": null, "score": 114}` |
| 2 (`8834d8b5-…`) | lastfm | `{"name": "Bad Omens", "mbid": "8834d8b5-72a4-4a6e-9d35-3a041b8579fa", "match": "0.900919", "url": "https://www.last.fm/music/Bad+Omens"}` |

### sleep-token / Spiritbox

| Group | Contributor | raw row |
|---|---|---|
| 1 (`9c935736-…`) | deezer | `{"id": 13321745, "name": "Spiritbox", "link": "https://www.deezer.com/artist/13321745", "nb_album": 23, "nb_fan": 75401}` |
| 1 | labs | `{"artist_mbid": "9c935736-7530-41e4-b776-1dbcf534c061", "name": "Spiritbox", "comment": "Canadian metalcore", "type": "Group", "gender": null, "score": 213}` |
| 2 (`a39ad456-…`) | lastfm | `{"name": "Spiritbox", "mbid": "a39ad456-a697-4f32-aa36-c107f654d318", "match": "0.524585", "url": "https://www.last.fm/music/Spiritbox"}` |

### sleep-token / Loathe

| Group | Contributor | raw row |
|---|---|---|
| 1 (`56eb02c4-…`) | labs | `{"artist_mbid": "56eb02c4-1f16-4613-8bb3-b4a752283fc3", "name": "Loathe", "comment": "UK experimental metal", "type": "Group", "gender": null, "score": 147}` |
| 2 (`e9ea0fbc-…`) | lastfm | `{"name": "Loathe", "mbid": "e9ea0fbc-ccc7-4e98-9290-0a41aa848fa2", "match": "0.323256", "url": "https://www.last.fm/music/Loathe"}` |

No Deezer row: Deezer's sleep-token related list does not name Loathe.

### tigran-hamasyan / Sungazer

| Group | Contributor | raw row |
|---|---|---|
| 1 (`beb404c4-…`) | labs | `{"artist_mbid": "beb404c4-9d0b-4042-a56c-aad7673c677d", "name": "Sungazer", "comment": "electrojazz duo from New York", "type": "Group", "gender": null, "score": 57}` |
| 2 (`21006fdb-…`) | lastfm | `{"name": "Sungazer", "mbid": "21006fdb-2e22-4950-91ed-8575a1a96b58", "match": "0.894555", "url": "https://www.last.fm/music/Sungazer"}` |

No Deezer row.

## 3. Which dropped field distinguishes each pair

**The shape is the same in all four cases, and it is a half-answer.**

| Pair | Group 1 gets | Group 2 gets | Distinguishes? |
|---|---|---|---|
| Bad Omens | Labs `comment` = `metalcore/post-metal` | nothing (Last.fm carries no text) | **half** — one entry named, one bare |
| Spiritbox | Labs `comment` = `Canadian metalcore` | nothing | **half** |
| Loathe | Labs `comment` = `UK experimental metal` | nothing | **half** |
| Sungazer | Labs `comment` = `electrojazz duo from New York` | nothing | **half** |

**Every split pair is Labs-versus-Last.fm, and Labs is always on one side only.** That is not a
coincidence of this workload: Labs and Last.fm are the only two contributors carrying MBIDs, so a
split can only ever be Labs-vs-Last.fm, and only Labs carries text. The ceiling for a
purely-upstream-carried string is therefore **one of the two entries in every split pair, never
both** — 4 of 8 entries.

**No dropped field distinguishes the Last.fm side.** Its `url` is name-derived and identical for
both members of a pair; `image[]` is the grey placeholder on every row of these fixtures;
`streamable` is `"0"` throughout.

**The Last.fm URL is worse than nothing on Bad Omens.** Last.fm's row carries MBID
`8834d8b5-…`, which MusicBrainz calls *"60s garage rock band from Minnesota"*, while
`https://www.last.fm/music/Bad+Omens` is the page for the modern metalcore band — the *other*
group's act. A link arm would label the garage band with the metalcore band's destination. This is
exactly the "an entry given the other act's text is worse than none" failure the ticket's metric 4
exists to count.

## 4. Does the requested artist's own MusicBrainz lookup carry anything about similar entries?

**No.** `MusicBrainzApi.lookupArtistWithRels` requests
`inc=tags+genres+aliases+ratings+url-rels+artist-rels`. Those relations are the *requested* artist's
own — member-of-band, collaboration, url links — and MusicBrainz has no similar-artist concept at
all. The response carries a `disambiguation` for exactly one artist, the one asked about, which is
never a similar entry. Confirmed against the shipped fixture
`musicmeta-core/src/test/resources/pools/musicbrainz-artist-wikidata-and-members/musicbrainz-artist-lookup.json`
(keys: `id`, `name`, `type`, `tags`, `relations`; relation types: `member of band`) and against the
live route.

So there is nothing to harvest from a response we already have. A disambiguation for a similar entry
has to come from Labs' `comment` or from a new MusicBrainz request.

## 5. The Labs endpoint's full response schema, live

Re-fetched 2026-09-06 (The Weeknd, `b7539c32-…`, the same algorithm constant the library sends): 100
rows, all seven keys on every row, `comment` populated. **The frozen fixture trims nothing** — what
the fixtures show is the whole payload.

## 6. What this makes possible — the arms the inventory says are live

- **A (upstream-carried disambiguation)** is live. Labs `comment` exists, is MusicBrainz's own
  string, and is dropped today. Ceiling: one of two entries per split pair.
- **B (upstream-carried link)** is live but weak, and demonstrably capable of a wrong answer: two of
  three link sources are name-derived or absent, and the Last.fm one points at the wrong act on Bad
  Omens.
- **C (MusicBrainz lookup per split pair)** is live. All eight split-pair MBIDs carry a non-empty
  `disambiguation`, captured 2026-09-06 (raw responses in `musicbrainz-capture/`).
- **D, emergent and not in the ticket.** MusicBrainz answers a batched identifier query:
  `/ws/2/artist?query=arid:X OR arid:Y OR …&fmt=json` returned all four missing disambiguations in
  **one** request on 2026-09-06 (`musicbrainz-capture/batched-arid-query.json`). That collapses C's
  per-MBID cost to one call per enrich, and only when a split exists. Credible, so it becomes an arm.

Nothing here rules an arm out, so all four are built.
