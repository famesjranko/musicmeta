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

Measured 2026-09-06 on `686021c4`. Nothing above this line was changed after the first capture.

### The field finding

Only the release search's combined `title` can carry the marker. `parseReleaseResults` keeps
`title`, `label`, `year`, `country`, `cover_image`, `type`, `catno`, `genre`, `style`, `id`,
`master_id`; `/database/search?type=release` returns no `artists` array, and `releaseDetails` —
which does — is fetched by id after selection and feeds no name test. **0 of 1722 `type=artist`
hits across the 30 sample names carry a `*` anywhere**, so `discogsArtistName`, `bugs/29`'s one
accessor, never handles a string with the marker and cannot own the rule. `creditNameTier` does.

### Metric A — incidence

| | |
|---|---|
| Population (artist-side strings plus comma parts) | 2868 |
| **Trailing `*`** | **375 (13.08%)** |
| Interior `*` | 48 |

The interior 48 are names a strip must not touch: `Αλεξίου* • Μάλαμας* • Ιωαννίδης*` (release
`6762453`), `Кино = Group "Kino"* = Cinema*` (`7987243`).

### Metric B, and the supplement B2

| | |
|---|---|
| B — marked strings the shipped test rejects for the *searched* artist and accepts with the marker off | **0 of 363** |
| B2 — marked names that reject a request naming *that credited name exactly* | **29 of 366 (7.9%)** |

B is 0 for a mechanical reason: `ArtistMatcher.normalize` maps every non-`[a-z0-9 ]` character to a
space, so for a name with a Latin-alphanumeric form the marker is already invisible (`AFX*` →
`afx`). Only a name that normalizes to nothing falls through to `rawSameName`, which compares raw
text — every one of the 29 B2 cases is non-Latin. They include `東京事変* - 教育` (release `2899025`),
this harness's own B1 cell, and `Χάρις Αλεξίου, Αντώνη Βαρδή*` (`11837914`), the ticket's sighting.
That sighting no longer fails on its own: `features/06`'s comma split rescues it, because the
request names the unmarked part. What is left is a request naming the marked name.

### The decision the frozen rule gives

A is 13.08%, so the rule reads "otherwise — fix". The ticket's "one-in-a-thousand" premise was
wrong by two orders of magnitude.

### Harness, before and after

`probe-before.tsv` and `probe-after.tsv`, live, same workload, 39 cells:

| Arm | MATCH | REJECT | **WRONG** |
|---|---|---|---|
| Before | 23 | 14 | **0** |
| After | 23 | 14 | **0** |

Cell-for-cell identical (one `MATCH-UNTRACED` in both). Wrong matches did not rise, which is what
the decisive metric asks; the marker cells are not reachable on this workload, because Discogs
answers its non-Latin album queries with an empty pool. The gain is B2's 29 strings, not this table.

`probe-before-discarded-mb-failures.tsv` is a **discarded** first baseline: transient MusicBrainz
failures left several cells without an alias pool (27 MusicBrainz requests against the clean run's
16), so it understates the before arm. It is kept only as the record of why the run was repeated.
The retry wrapper added to the harness after it is present in both arms above.

### What this did not measure

- Discogs' other display conventions on the same field — ` = ` transliteration joins
  (`Кино = Kino*`) and ` • ` credit joins — visible in the captures, untouched.
- Whether the marker should also come off the string the choice *reports*
  (`DiscogsAlbumChoice.artist`, which reaches no consumer field) or off `artistQuality`'s input.
- Any pool but a `type=release` search.
- A counter *under* a marker (`Кино (2)*`): 0 of 4308 captured hits carry that shape, and the
  ordering defect it exposes was found by reading, not by capture.
