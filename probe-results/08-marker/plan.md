# Phase 1 measurement plan — `features/08` (Discogs' trailing `*` name-variation marker)

Frozen 2026-09-06, **before any capture was taken and before any number was read**. Nothing above
the "Results" heading may change once a capture exists.

Ticket: `.scratch/features/issues/08-discogs-marks-a-name-variation-credit-with-an-asterisk.md`.
Base commit: `686021c4` (origin/main).

## Which string the credit test actually reads (settled by reading the code, not by capture)

`DiscogsApi.searchReleases` parses `/database/search?type=release` hits and keeps only
`obj.optString("title")` — Discogs' combined `"Artist - Title"` display string. Nothing reads
`artists[]`, `artists_sort`, `anv` or `join`: `parseReleaseResults` touches `title`, `label`,
`year`, `country`, `cover_image`, `type`, `catno`, `genre`, `style`, `id`, `master_id` and nothing
else, and `DiscogsApi.releaseDetails` (the call that does return an `artists` array) is fetched by
id *after* selection and never feeds a name test. So the only credit string any name test sees is
the search hit's `title`, and the artist half of it after `stripDiscogsDisambiguator` is what
`creditNameTier` compares.

## Population

`P` = every artist-side string `parseDiscogsRelease` hands to `creditNameTier` — one per ` - `
boundary of a hit's `title`, disambiguator-stripped — together with each `", "` part of it, since a
part is separately compared by `creditNameTier`'s split path.

## Metrics

| # | Metric |
|---|---|
| A | Share of `P` whose string (or comma part) ends in `*` |
| B | Of those, how many are rejected by the credit test today **and** accepted once one trailing `*` is stripped — a request that actually fails on the marker |
| C | Occurrences of a non-trailing (interior) `*` anywhere in `P` — the strings a strip must never touch |

`B` is computed by the shipped code, from a test source set calling the shipped matcher directly.
No Python re-implementation of the matcher is trusted.

## Sources

1. Every Discogs search body already in `musicmeta-core/src/test/resources`.
2. A live sample of 30 `type=release` searches spread over the artists frozen in
   `.scratch/bugs/prototypes/34-lastfm-similar-mbid/` (workload plus held-out), plus the non-Latin
   artists of the `features/01` workload — a Latin-only sample cannot see this defect, because
   `ArtistMatcher.normalize` maps `*` to a space for any name with a Latin-alphanumeric form, and
   only a name with none falls through to the raw comparison the marker can break. Both halves of
   the sample are reported separately. Discogs' 60 req/min is respected.

## Decision rule (frozen)

- **If A < 1% AND B == 0** — close the ticket `wontfix`, recording A, B, C and the field finding.
  The ticket asks for exactly this outcome at negligible incidence.
- **Otherwise** — fix: strip **one trailing `*` only**, at the single site that owns Discogs' string
  conventions, test-first against a fixture captured from a real response carrying the marker, plus
  a test that an interior `*` survives. Re-measure wrong matches on the `features/01` harness before
  and after; a rise in wrong matches rejects the fix regardless of A.

## Results

(filled after the captures — nothing above this line changes)
