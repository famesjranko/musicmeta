# Album candidate ordering

Captured before implementation on 2026-11-07 from MusicBrainz release search.
The complete response bodies are retained byte-for-byte, not reconstructed rows.
User-Agent: `MusicEnrichmentEngine/1.0 ( https://github.com/famesjranko/musicmeta )`,
the demo's `EnrichmentConfig.userAgentWithContact(CONTACT)` value.
Requests were sequential, spaced at least 1.3 seconds apart; 503 attempts were
retried after 3 seconds. No lookup or release-group request was made.

- `thriller-title.json`: 15:48:43 UTC, 100 of 385 hits.
  URL: `https://musicbrainz.org/ws/2/release?query=release%3A%22Thriller%22+AND+artistname%3A%22%22&fmt=json&limit=100`
  SHA-256: `cae537cfb4793da08a4cd7edecb1e4a39952d3d0182079e51ccf9dcd6aa00937`
- `thriller-artist.json`: 15:48:51 UTC, 100 of 108 hits.
  URL: `https://musicbrainz.org/ws/2/release?query=release%3A%22Thriller%22+AND+artistname%3A%22Michael+Jackson%22&fmt=json&limit=100`
  SHA-256: `edfe02bdb16e86b296542f94dba1e6be79e5921fc2e9394720074ae9670ded66`

These are the exact queries `MusicBrainzApi.releaseSearchUrl` sends for title-only
and supplied-artist candidate searches, at `CANONICAL_SEARCH_LIMIT` (100).
Neither Thriller response contains `release-group.first-release-date` on any row. Dates
are pressing dates (`date`), with year, month or day precision, blank or absent.
Earliest pressing in the returned pool is the available original-release signal;
it cannot establish a worldwide first release outside that pool.

Tests select unchanged rows and reverse their order. Field-omission probes remove
one field from a captured row to isolate incomplete primary-type evidence;
they are explicitly modelled omissions, not additional upstream observations.
The title-only tests supply a stubbed iTunes artist hint directly to the provider;
no live iTunes response is asserted. No scheduled check revalidates this pool.

Additional capture before implementation, at 15:52:43 UTC on the same date:
`beatles-artist.json`, the complete 100-hit response for
`https://musicbrainz.org/ws/2/release?query=release%3A%22The+Beatles%22+AND+artistname%3A%22The+Beatles%22&fmt=json&limit=100`.
Same User-Agent and spacing. SHA-256: `c9a33584c2a8107d0030dd0d29852479c51bf8bf6b114b8aad85a9942265c167`.
This supplies unchanged same-title, same-artist official album and compilation
rows: the compilation dates to 1967, before the 1968 album, so date alone would
prefer the wrong release type.

## Credit variation and date precision captures

Copied byte-for-byte from the reviewer's complete raw response envelopes before
the follow-up implementation. Each body has `created`, `count`, `offset` and the
full returned `releases` array; none is a reduced row selection. The capture
script uses the same User-Agent above, with 1.3 seconds between requests and
3 seconds before retries. URLs and row counts come from `live-meta.json`; that
file does not contain timestamps. The timestamps below are the raw responses'
MusicBrainz `created` values, not independently recorded wall-clock fetch times.

- `getz-gilberto.json`: `2026-11-07T16:40:19.994Z`, 46 of 46 hits.
  URL: `https://musicbrainz.org/ws/2/release?query=release%3A%22Getz%5C%2FGilberto%22+AND+artistname%3A%22Stan+Getz%22&fmt=json&limit=100`
  SHA-256: `3aeee247f9b9948a97a34815be49395515a2b03147f9d80bfd66cb636f2a988a`
- `ok-computer.json`: `2026-11-07T16:33:57.155Z`, 39 of 39 hits.
  URL: `https://musicbrainz.org/ws/2/release?query=release%3A%22OK+Computer%22+AND+artistname%3A%22Radiohead%22&fmt=json&limit=100`
  SHA-256: `b2a6c0dcd91bb8a5fe5693cb31b91ad67efde75d7f308dede0aa5d567dcc2560`
- `rumours.json`: `2026-11-07T16:33:53.736Z`, 90 of 90 hits.
  URL: `https://musicbrainz.org/ws/2/release?query=release%3A%22Rumours%22+AND+artistname%3A%22Fleetwood+Mac%22&fmt=json&limit=100`
  SHA-256: `13dc4ef678eb4194e2a554d5f2d333f0fd61e10903dd6de62f05529d67065a99`

The credit-boundary tests deliberately replace or select artist credits and
release-group ids from captured rows to isolate grouping rules. These model
boundary cases, not additional upstream observations. The date-policy unit test uses
explicit boundary dates on a captured DTO; the live-pool date regressions use
unchanged response bodies. No test claims the modelled cases were seen live.

## Combined-group boundary models

The component-order tests derive three rows from the unchanged Getz/Gilberto
capture: A and B use the duet credit, C uses the guest credit. MBIDs and dates
are modelled as A/1980, B/1990 and C/1970; B receives another release-group id
and either Single or Album as its primary type. These are counterfactual pools,
not captured combinations. The full duet-credit request puts C in a weaker
supplied-artist tier; an individual Stan Getz request puts all three in one tier.
The supplied and hinted Jobim tests use unchanged rows `1cb544a7` and `2dd57364`.
The completeness tests use unchanged Thriller rows for missing date and status;
the missing-type case deliberately omits the original's primary type.
A further two-row model selects the duet and a solo credit in a different group,
with Gilberto as a stubbed hint, to isolate the first-artist link across hint tiers.


## Guest-credit single and full-credit tier captures

Copied byte-for-byte from the reviewer's live MusicBrainz release-search responses,
captured on 2026-11-08 around 06:09 UTC, before this rule change. Queries come from
`/tmp/rv419/queries.txt`; timestamps below are the responses' MusicBrainz `created`
values. Each file retains the full response envelope and returned release array.

- `encore.json`: `2026-11-08T06:09:01.941Z`, 16 of 16 hits.
  Query: `release:"Encore" AND artistname:"Eminem"`.
  URL: `https://musicbrainz.org/ws/2/release?query=release%3A%22Encore%22+AND+artistname%3A%22Eminem%22&fmt=json&limit=100`
  SHA-256: `74e4543ba7ffb097f884ea90938ab220121d68f0193cebaae3435134011f9966`
- `ellalouis.json`: `2026-11-08T06:08:51.758Z`, 69 of 69 hits.
  Query: `release:"Ella and Louis" AND artistname:""`.
  URL: `https://musicbrainz.org/ws/2/release?query=release%3A%22Ella+and+Louis%22+AND+artistname%3A%22%22&fmt=json&limit=100`
  SHA-256: `431291aaa9df7985d4929c1a40822b095b8f113ba6eb733caab7df1dc114bf53`

Encore's single `e8d6a3b0` shares Eminem as its first artist with the albums,
but adds Dr. Dre and 50 Cent in a different release group. The three request
shapes replay this same artist-filtered pool; they do not claim that a live
title-only query returns it. Ella and Louis includes `52486b56` with an ampersand
credit and `3f1385f1` with a hyphen credit for the same two artist ids. The full
ampersand request and stubbed hint must preserve the stronger matching tier.
Hints are stubbed directly at the provider; no live iTunes response is asserted.
No scheduled check revalidates these captures.
