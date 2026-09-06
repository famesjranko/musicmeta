# What tells two same-name similar artists apart?

Pre-registered A/B for `.scratch/features/issues/09-*`, plus one arm the inventory surfaced.
Everything below "Results" was produced by the arms; `inventory.md` and `plan.md` were frozen first,
in that order, and are unchanged. Both are committed on every arm branch under
`probe-results/09-disambig/`, since `.scratch/` is ignored by version control.

Base commit: `085ae2dd` (origin/main). Every arm branches from `probe/disambig-arm-control`, which is
`085ae2dd` plus the shared harness and fixtures and nothing else — recorded here rather than smoothed
over, because `plan.md` says "from origin/main".

| Arm | Branch | Property |
|---|---|---|
| Control | `probe/disambig-arm-control` | HEAD, plus instrumentation |
| A | `probe/disambig-arm-comment` | `SimilarArtist.disambiguation` from Labs' `comment` |
| B | `probe/disambig-arm-link` | `SimilarArtist.url` from Last.fm `url` / Deezer `link` / the MBID's MusicBrainz URL |
| C | `probe/disambig-arm-mblookup` | A, plus one `/ws/2/artist/{mbid}` per split-pair entry Labs did not describe |
| D | `probe/disambig-arm-mbbatch` | A, plus **one** `/ws/2/artist?query=arid:… OR …` per answer |

## Harness

One JUnit test per branch (`DisambigProbeTest.kt`), identical but for `ARM_NAME`, `armLabel()`,
`armLink()` and the three row constructors. It replays the twelve frozen fixture triples through the
real `LastFmMapper`/`DeezerMapper`/`ListenBrainzMapper`, groups with this branch's
`SimilarArtistMerger.groupArtists`, and writes `probe-results/<arm>.{md,json}`.

**No arm changes grouping, scoring or order**, and the harness proves it: every arm's per-artist
`mergedTop10` is identical to the control's, and every arm forms 1226 groups.

**Harness-can-fail evidence.** `the workload holds exactly the four known split pairs` states the
four before running. Mutating `groupArtists`' identifier branch back to a bare name key
(`val index = groupKey.indexOfFirst { it == key }`) turned it red — the assertion found zero split
pairs against four expected, and `run probe and write results` failed with it, group count dropping
1226 -> 1222. The file was restored from a copy taken before the mutation, never from a checkout;
the re-run reproduced the control's numbers exactly.

Arms C and D make no live call: they read the 2026-09-06 capture of all eight split-pair MBIDs
(`musicbrainz-capture/`, raw responses, `capture.sh` beside them) and **bill** every request they
would have made, one ledger line per call.

## The four split pairs

Re-derived at `085ae2dd`, matching `bugs/30`: `Bad Omens`, `Spiritbox`, `Loathe` (sleep-token),
`Sungazer` (tigran-hamasyan). Four name-key collisions in 1226 merged groups. Each is Labs-plus-maybe-
Deezer on one side and Last.fm alone on the other — necessarily so, since Labs and Last.fm are the
only MBID-carrying contributors.

## Results

### What a consumer would see, per split-pair entry

| Pair | Entry (MBID) | Control | Arm A | Arm B | Arms C/D |
|---|---|---|---|---|---|
| Bad Omens | `eecada09` (deezer+labs) | nothing | `metalcore/post-metal` | `musicbrainz.org/artist/eecada09…` | `metalcore/post-metal` |
| Bad Omens | `8834d8b5` (lastfm) | nothing | **nothing** | `last.fm/music/Bad+Omens` — **wrong act** | `60s garage rock band from Minnesota` |
| Spiritbox | `9c935736` (deezer+labs) | nothing | `Canadian metalcore` | `musicbrainz.org/artist/9c935736…` | `Canadian metalcore` |
| Spiritbox | `a39ad456` (lastfm) | nothing | **nothing** | `last.fm/music/Spiritbox` — **wrong act** | `Dutch post-rock` |
| Loathe | `56eb02c4` (labs) | nothing | `UK experimental metal` | `musicbrainz.org/artist/56eb02c4…` | `UK experimental metal` |
| Loathe | `e9ea0fbc` (lastfm) | nothing | **nothing** | `last.fm/music/Loathe` — **wrong act** | `Maltese death metal band` |
| Sungazer | `beb404c4` (labs) | nothing | `electrojazz duo from New York` | `musicbrainz.org/artist/beb404c4…` | `electrojazz duo from New York` |
| Sungazer | `21006fdb` (lastfm) | nothing | **nothing** | `last.fm/music/Sungazer` — unadjudicated | `Colorado-based psychadelic, ambient, noise` |

### The frozen metrics

| Metric | Control | A | B | C | D |
|---|---|---|---|---|---|
| 1. Entries with a distinguishing **string** (of 8) | 0 | **4** | 0 | **8** | **8** |
| 2. Entries with a distinguishing **link** (of 8) | 0 | 0 | **8**, all differing within a pair | 0 | 0 |
| 3. Pairs a consumer can tell apart (of 4) | 0 | **4, all `half`** = 2.0 | 4 nominally, 1 defensible | **4 whole** | **4 whole** |
| 4. **Wrong answers — decisive** | 0 | **0** | **3** | **0** | **0** |
| 5. Extra upstream calls, whole workload / worst enrich | 0 / 0 | **0 / 0** | 0 / 0 | 4 / **3** | **2 / 1** |
| 6. Coverage — merged groups gaining anything (of 1226) | 0 | 463 (37.8%) | **1226** (100%) | 467 (38.1%) | 467 (38.1%) |

### Metric 4, adjudicated

Arm B's three wrong answers are settled by MusicBrainz's own `url-rels`, captured 2026-09-06
(`musicbrainz-capture/*-urlrels.json`):

| Pair | Arm B hands the Last.fm entry | MusicBrainz says that page belongs to |
|---|---|---|
| Bad Omens | `last.fm/music/Bad+Omens` on `8834d8b5` (60s garage) | `eecada09` — the metalcore band, **the other group** |
| Spiritbox | `last.fm/music/Spiritbox` on `a39ad456` (Dutch post-rock) | `9c935736` — the Canadian metalcore band, **the other group** |
| Loathe | `last.fm/music/Loathe` on `e9ea0fbc` (Maltese death metal) | `56eb02c4` — the UK band, **the other group** |
| Sungazer | `last.fm/music/Sungazer` on `21006fdb` | neither MBID carries a `last.fm` relation — unadjudicated |

**This is not bad luck, it is the field's shape.** Last.fm builds `url` from the *name*, not the
entity: `https://www.last.fm/music/Bad+Omens` is `name` URL-escaped. Two Last.fm rows sharing a name
carry the same URL, so the field cannot distinguish a same-name pair even in principle, and where it
appears to it is because the *other* contributor supplied a different link. The moment Last.fm
returns both same-name rows itself, Arm B labels both entries with one destination.

Arms A, C and D take every string from the MusicBrainz record the group's own MBID names, and are
wrong only if MusicBrainz is.

## Decision-rule outcome

The rule: among arms with zero wrong answers and no change to any group, score or order, prefer the
arm distinguishing most pairs; tie-break on entries described, then on calls, then on data we already
fetch.

| Arm | Metric 4 | Order unchanged | Survives? | Pairs (metric 3) | Entries | Calls |
|---|---|---|---|---|---|---|
| Control | 0 | yes | yes | 0 | 0 | 0 |
| A | 0 | yes | **yes** | 2.0 (4 halves) | 4 | 0 |
| B | **3 — fail** | yes | **no** | — | — | — |
| C | 0 | yes | **yes** | **4.0** | **8** | 4 |
| D | 0 | yes | **yes** | **4.0** | **8** | 2 |

C and D tie on metrics 3 and 1; **rule 3 breaks the tie on calls, naming D**. The rule's tail clause
about a free half-answer versus a paid whole one fires, and is answered below.

**The rule names Arm D.** Arm A is the free half of it and is not in competition with it: D *is* A,
plus a lookup for what A could not reach.

## Recommendation

**Ship Arm A now; ship Arm D's lookup only if a half-answer proves insufficient in the field.**

The rule names D, and D is right that a whole answer beats a half one. But the two arms are not
alternatives, they are the same field with and without a fallback, and their costs are not
comparable:

- **A is free.** It reads a field already on a response we already fetch, on every enrich, forever.
  Its four strings are MusicBrainz's own text. `docs/pitfalls.md` — "Provider data and matching"
  records exactly this lesson from the work-credits fix: *the cheaper option was not found by
  thinking harder about the trade-off, it was found by reading the API's own include list.* Labs is
  already doing the MusicBrainz lookup for us; we were discarding the answer.
- **A leaves one entry of each pair blank, and a blank is not a wrong answer.** A consumer rendering
  "Loathe — UK experimental metal" beside "Loathe" can already tell them apart, which is the ticket's
  question. It cannot tell them what the second one *is*, which is a smaller complaint.
- **D costs a MusicBrainz request on the enrich path**, with everything that follows: the rate
  limiter, a new failure mode on an answer that currently has none, and a cache key for a lookup that
  is per-similar-entry rather than per-request. Two requests over twelve artists is cheap; the code
  that decides *when* to make them, and what to do when one times out, is not. The measurement sizes
  the traffic, not the complexity.

If the whole answer is wanted, **take D and never C**: identical output, half the requests, one per
answer instead of one per unknown MBID, and the gap widens with the number of splits (sleep-token: 1
call against 3).

### Compatibility cost, stated

Both A and D add one field to `SimilarArtist`, a public `@Serializable` Room-cached data class. From
the arms' own `apiDump`:

- **The `api/*.api` diff is not additive.** The primary constructor, `copy` and `copy$default`
  descriptors all change — `(…FLjava/util/List;)V` becomes `(…FLjava/util/List;Ljava/lang/String;)V` —
  and a new `component5`/`getDisambiguation` appears. Source-compatible for Kotlin callers using
  named arguments; **binary-breaking** for anything compiled against the old descriptors. That is the
  JVM-descriptor caveat in `docs/pitfalls.md` — "The published surface", and it makes this a `0.x.0`
  change needing a `### Breaking Changes` heading in `CHANGELOG.md`.
- **Room cache payloads stay readable.** The field is nullable with a `= null` default, so
  `kotlinx.serialization` decodes an already-persisted `SimilarArtist` that has no `disambiguation`
  key. No migration, no cache-clear note. Nothing gates this — the round-trip tests encode and decode
  with the same tree — so it is a claim to be checked by hand against a payload written before the
  change, not an assertion any check will defend.
- **The schema pin must grow.** `ListenBrainzApi.SCHEMA_PIN_TARGETS` watches only `artist_mbid`,
  `name` and `score` on the `labs similar artists` route. Arm A adds `[0].comment`, without which
  Labs could drop the field and no test in the repo would notice.

## Emergent options the arms surfaced

- **Arm D itself.** Not in the ticket. A batched `arid:` query returns every unknown artist's
  disambiguation in one request (verified live 2026-09-06,
  `musicbrainz-capture/batched-arid-query.json`), halving C's cost on this workload and cutting the
  worst enrich from three requests to one. Promoted to an arm and measured.
- **Labs also drops `type` and `gender`** (`Group`/`Person`, `Male`/`Female`). `type` alone separates
  the near-miss pairs `tech-debt/08`'s ground truth warns about — `Avishai Cohen` the person from
  `Avishai Cohen Trio` the group, `Tom Petty` from `Tom Petty and the Heartbreakers`. Free, on the
  same response, and it is a *different* ticket: those pairs are two entries a merge might wrongly
  unify, not two entries a consumer cannot tell apart. Worth a triage ticket, not an arm here.
- **Deezer's `nb_fan` is a tiebreak nobody asked for.** Bad Omens `9700940` has 164,456 fans; a
  same-name pair could be ordered by it. It is a popularity signal, not an identity one, and it would
  be a new reason for one entry to outrank another — out of scope for a labelling change, and named
  here only so the next reader knows it was seen and rejected.
- **`reference_mbid` on every Labs row** is the *requested* artist's MBID, echoed back. It answers a
  provenance question ("which request produced this row"), not an identity one.

## What this did not measure

Everything `plan.md` lists, plus:

- **Whether a consumer finds any of these strings useful.** MusicBrainz's text is taken as correct by
  definition. `Colorado-based psychadelic, ambient, noise` ships that upstream's typo verbatim, and no
  arm judged, cleaned or truncated a string.
- **Four split pairs, in two of twelve workloads, is a small sample.** Every conclusion about which
  side of a pair carries text rests on the structural argument (only Labs carries text, only Labs and
  Last.fm carry MBIDs), not on the four cases — but the four cases are all the evidence there is.
- **Live drift.** Labs' `comment` is unpinned today and one 2026-09-06 snapshot is all any arm has.
- **Arm B's Sungazer pair.** Neither MBID carries a `last.fm` relation, so whether that link is wrong
  is unknown; three of four adjudicated is enough to disqualify the arm, and the fourth stays open.
- **What arms C and D cost in production.** They count requests; they do not make them under a rate
  limiter, do not model a timeout or a cache, and do not touch `enrichTimeoutMs`. The complexity
  argument in the recommendation is a judgement, not a measurement, and is labelled as one.
- **Anything about the merge itself.** No arm changed a group, a score or an order, by design — this
  A/B is about what a group is labelled with, not what a group is.
