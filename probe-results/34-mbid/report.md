# Can a wrong Last.fm similar-artist MBID be corrected? Not on this evidence.

Pre-registered A/B for `.scratch/bugs/issues/34-lastfm-similar-artist-mbid-names-the-wrong-act.md`.
`inventory.md` and `plan.md` were frozen first, in that order, and are unchanged; both are committed
on every arm branch under `probe-results/34-mbid/`, since `.scratch/` is not under version control.

Base commit for every arm: `00dfd1dc` (origin/main).

| Arm | Branch | Property |
|---|---|---|
| Control | `probe/lastfm-mbid-arm-control` | HEAD, plus instrumentation |
| B1 | `probe/lastfm-mbid-arm-corroborate-split` | url-rels corroboration, scoped to name collisions inside the merged list |
| B2 | `probe/lastfm-mbid-arm-corroborate-all` | the same corroboration, scoped to every Last.fm row carrying an MBID |
| C | `probe/lastfm-mbid-arm-genre` | genre/tag overlap with the requested artist as a suspicion signal; corrects nothing |

**Arm A, the ticket's own first arm, was not built, and that is a result.** Following a MusicBrainz
merge corrects nothing here: all 184 distinct ids Last.fm supplied over the frozen workload resolve,
and every one resolves to itself. No `404`, no merge, no redirect. A wrong Last.fm *artist* MBID is
a live id naming a live artist who is the wrong act — unlike a wrong Last.fm *recording* MBID, where
`docs/providers.md`'s staleness probe finds exactly the `404`s and merges this one does not.

## Harness

One JUnit test per branch (`LastFmMbidProbeTest.kt`), identical but for `ARM_NAME` and `applyArm`.
It replays both workloads raw fixtures through the real `LastFmMapper`/`DeezerMapper`/
`ListenBrainzMapper`, lets the arm rewrite a Last.fm rows MBID *before* the merge sees it, merges
with this branch's `SimilarArtistMerger`, and writes `probe-results/34-mbid/<arm>.json`. No arm
touches `groupArtists`, the scoring or the sort.

Arms make no live call. They read the 2026-09-06 captures (`musicbrainz-capture/`, `heldout/`, raw
bodies and the scripts that fetched them) and **bill** every request they would have made, one
ledger line per call.

**Harness-can-fail evidence, both mutations run, then restored from a copy taken beforehand.**

- Mutating `parseLastFm`s `mbidIndex` gate so every row is indexed, not only the ones carrying an
  MBID, turned `the workload holds exactly the rows the inventory adjudicated` red:
  `expected:<198> but was:<240>`.
- Mutating B1's rule so it takes its single same-name sibling *without* checking who owns the page
  turned the decisive metric non-zero: `metric2_wronglyChanged` 0 -> 1, on `tigran-hamasyan /
  Sungazer`, a row MusicBrainz is silent about. The metric is not vacuously zero; it fires when an
  arm changes a row it should not have.

Both restored files re-ran byte-identically under `--rerun-tasks`, since the Gradle cache will
otherwise serve a stale test run.

## Workloads

**Training — the twelve frozen artists** (`probe/dedup-arm-mbid`s fixtures, unchanged since
`tech-debt/08`): 240 Last.fm rows, 198 carrying an MBID, 8 ground-truth mismatches.

**Held-out — twelve artists chosen blind** and written down before the first request
(`heldout/artists.md`): BLACKPINK, Claude Debussy, A Tribe Called Quest, The Bad Plus, Trouble,
Кино, Alison Krauss, Four Tet, Shakira, The Zombies, Alvvays, Nico. Captured live 2026-09-06 from
the same three routes the frozen set uses. 238 rows, 214 carrying an MBID, 5 ground-truth
mismatches. Every arm ran against it only after `plan.md` was frozen and every arm had run against
the training set.

**The mismatch rate generalises.** 8/198 = 4.0% of MBID-carrying rows on the training set (5.8% of
the 138 MusicBrainz decides); 5/214 = 2.3% on the held-out set (3.2% of the 157 it decides). Same
order of magnitude, and it is the one number in this report that survives everything below.

## Results

| Metric | Set | Control | B1 | B2 | C |
|---|---|---|---|---|---|
| 1. Mismatched rows corrected | frozen (8) | 0 | **3** | **8** | 0 |
| | held-out (5) | 0 | **2** | **4** | 0 |
| 2. Correct rows wrongly changed — decisive | frozen | 0 | **0** | **0** | 0 |
| | held-out | 0 | **0** | **0** | 0 |
| 3. Extra requests, total / worst enrich | frozen | 0 / 0 | 8 / **6** | 346 / **51** | 10 / **1** |
| | held-out | 0 / 0 | 6 / **6** | 344 / **71** | 12 / **1** |
| 4. Top-10 positions moved vs control | frozen | 0 | 5 | 5 | 0 |
| | held-out | 0 | 7 | 10 | 0 |
| 5. C's precision — flagged rows that are mismatches | frozen | — | — | — | **2 of 10** |
| | held-out | — | — | — | **0 of 10** |
| 6. C's false-positive rate on correct rows | frozen | — | — | — | **7 of 130 (5.4%)** |
| | held-out | — | — | — | **6 of 152 (3.9%)** |

Read row 2 with the section that follows it. It is zero for a reason that does not survive scrutiny.

### Arm C is dead, and the held-out set is what killed it

On the training twelve, genre overlap looks like a weak-but-real signal: 2 of the 10 rows it flags
are genuine mismatches, at a cost of one batched request per enrich and a 5.4% false-positive rate.

On the held-out twelve it flags 10 rows and **none of them is a mismatch**. Precision 0%, recall 0%.
A signal that catches a quarter of the defect on the set it was measured on and none at all on the
next twelve artists is not a signal; it is the shape of two small samples. It cannot gate anything,
and nothing should be built on it.

### Metric 2 is zero because the rule and the ground truth are the same test

This is the finding that decides the ticket, and it is a defect in the measurement that the
measurement itself exposed.

`inventory.md` adjudicates a row by asking whether some *other* same-name MusicBrainz artist owns
the last.fm page the row links to. B2's rule is that same question. So B2 correcting every
ground-truth mismatch and changing no correct row is **definitional, not measured** — the arm and
the referee consult one table. B1 is the same rule over a smaller candidate set, so its zero is
worth no more.

Hand-adjudicating the twelve rows the arms actually changed, against what the requested artist s
list plainly means, gives a different picture:

| Set | Row | Supplied -> rewritten to | Hand verdict |
|---|---|---|---|
| frozen | sleep-token / Bad Omens | 60s garage rock -> metalcore/post-metal | correct |
| frozen | sleep-token / Spiritbox | Dutch post-rock -> Canadian metalcore | correct |
| frozen | sleep-token / Loathe | Maltese death metal -> UK experimental metal | correct |
| frozen | sleep-token / President | Spanish band -> UK metal band | correct |
| frozen | sleep-token / Save Us | (both undescribed) | unadjudicable |
| frozen | bjork / Arca | French band -> Venezuelan producer | correct |
| frozen | bjork / Eartheater | Swedish metal band -> Alexandra Drewchin | correct |
| frozen | bjork / Locust | aka Kyle Preston -> Mark van Hoen | correct |
| held-out | blackpink / Jennie | Finnish artist -> JENNIE of BLACKPINK | correct |
| held-out | blackpink / Everglow | Canadian alt-pop duo -> South Korean girl group | correct |
| held-out | blackpink / Lisa | *wai wai music resort* -> **LiSA, Japanese pop/rock singer** | **wrong — neither is BLACKPINK s Lisa** |
| held-out | trouble / Pentagram | **American doom metal band -> Turkish heavy metal band** | **wrong — the supplied id was right** |

**B2's true metric 2 on the held-out set is at least 1 and probably 2, out of 4 changes.** The
frozen set shows none of this; the held-out set shows it twice in twelve artists.

### Why the rule fails, in the upstream's own terms

Two captured facts, and neither is fixable by tuning:

**`last.fm/music/<Name>` is a name page, not an entity page.** Last.fm publishes one artist page per
name, so a MusicBrainz `last.fm` relation records a contributor s *opinion* about which act that
page is for. `Pentagram` is the proof: the American doom band Last.fm supplied for Trouble carries
no last.fm relation at all, while the Turkish band `8c2e4f17` carries both
`last.fm/music/Mezarkabul` **and** `last.fm/music/Pentagram`. One mis-filed relation is enough to
rewrite a correct id to a wrong one, confidently, at full score — the exact defect the ticket exists
to remove. `features/09` reached the same conclusion about the field from the other direction and
disqualified its own arm B for it; this probe reaches it again with a wider population.

**There is no safe case normalisation, and the choice changes the answer.** MusicBrainz stores the
URL as a contributor typed it. Matching case-exactly loses `Spiritbox` against MusicBrainz s stored
`SpiritBox` — a real correction. Case-folding wins that one and loses `Lisa`: MusicBrainz s `LiSA`
(Japanese singer) carries `last.fm/music/Lisa`, so folding hands BLACKPINK s list the wrong Lisa.
The arms fold, because not folding was measurably worse; the cost is on the table above.
For the same reason `GET /ws/2/url?resource=…` is not the instrument it looks like — it matches
byte-exactly and answered for **7 of the 66** pages tried.

### Even where the rule is right, B1 cannot be afforded

B1 is the cheap arm and its ledger is mostly zeros: 10 of the 12 frozen artists and 11 of the 12
held-out artists cost **nothing**, because the arm only asks where two merged entries already share
a name.

But its worst case is **6 sequential `GET /ws/2/artist/{mbid}?inc=url-rels` lookups** — sleep-token
on the training set, BLACKPINK on the held-out set — and `inc=` is not available on a search, so
they cannot be batched into the one request the engine already makes.
`DefaultEnrichmentEngine.DISAMBIGUATION_BUDGET_MS` is **3000 ms**, and MusicBrainz s limiter runs at
one request a second. Six lookups need at least six seconds. **B1 would time out and label nothing
on exactly the two artists that motivate this ticket**, while spending its budget to do so. Raising
the budget means a similar-artist merge can hold the fan-out for six-plus seconds behind a limiter
shared with every other MusicBrainz call in the run — which is the trade `#357` looked at and
declined when it chose one batched search over a lookup per entry.

B2 is worse by an order of magnitude: 51 requests on the worst training artist, **71** on the worst
held-out one.

## Decision-rule outcome

`plan.md`: an arm ships only if metric 2 is 0 on both sets, it corrects a majority of the
mismatches within its own scope on both sets, and its request cost is stated.

| Arm | Metric 2, as measured | Metric 2, hand-adjudicated | Majority in scope | Cost stated | Ships? |
|---|---|---|---|---|---|
| Control | 0 | 0 | — | 0 | — |
| B1 | 0 | 0 (5 changes, all correct) | yes, 5 of 5 | 6 worst | **no — cannot fit the 3 s budget** |
| B2 | 0 | **>=1 of 4 on held-out** | yes | 71 worst | **no — fails metric 2 and cost** |
| C | 0 | 0 (changes nothing) | no — 0 of 5 held-out | 1 | **no — no signal** |

**No arm qualifies. Nothing is implemented.** The ticket goes to `ready-for-human` with the options
and their measured costs.

## Recommendation

**Do not correct the id. Make the wrongness visible, and reconsider what the MBID is promised to
be.** Three options, in the order the numbers favour them:

1. **Ship nothing, and say so in `docs/providers.md`.** The measured rate is 4.0% of MBID-carrying
   Last.fm similar rows on one workload and 2.3% on another, and the corroboration that would fix it
   is an opinion filed by MusicBrainz contributors about a Last.fm *name* page. A consumer who is
   told that a `SimilarArtist` MusicBrainz id sourced from Last.fm names the wrong act a few percent
   of the time can decide what to do; a consumer handed a silently-rewritten id cannot. Cost: zero
   requests, one paragraph. This is what the numbers support.
2. **B1, if and only if the budget question is answered first.** It made 5 changes across 24
   artists and all 5 are right, and 21 of those 24 artists cost it nothing. It is unshippable as
   measured only because its worst case is 6 sequential rate-limited lookups against a 3-second
   budget. Capping it at the two ids of a *single* split pair (2 lookups, ~2 s) would fit — and
   would have corrected Bad Omens but not Loathe or Spiritbox on the same enrich. That is a
   different arm and it has not been measured.
3. **Drop the Last.fm MBID from a row whose id no evidence corroborates.** Not measured, and the
   ticket forbids the wholesale form. Named here because it is the only option that removes the
   wrong answer without asserting a replacement, and because `bugs/30` s reasoning cuts this way:
   an entry with no id is a score the caller can still add up; an entry with the wrong id is a
   pointer at an act that never earned it.

`#357` s disambiguation label already makes all eight training-set mismatches *visible* — "Bad Omens
— 60s garage rock band from Minnesota" beside Sleep Token is how this ticket was found. That is not
nothing, and it is already shipped.

## What this did not measure

- **Whether MusicBrainz is right.** Taken as ground truth throughout, and the `Pentagram` and
  `Solstice` rows are two places where it demonstrably is not — `Solstice` is adjudicated MISMATCH
  by this probe on a relation that two different artists both carry, and the supplied id was
  probably correct. The measured mismatch rates are therefore *approximately* right and individually
  unreliable, which is why the recommendation rests on the rate and not on any single row.
- **Production cost.** The arms count requests. They do not make them under the shared limiter, do
  not model a timeout, a breaker or a cache. The budget argument above is arithmetic against a
  constant in the source, not an observation of a live run.
- **Option 2 s narrower arm, or option 3 at all.** Both are named, neither is built.
- **The 60 training rows and 57 held-out rows MusicBrainz is silent about.** They are metric-2
  exposure only; no arm gets credit for leaving them alone, and no arm touched one.
- **Whether any consumer follows a `SimilarArtist` MusicBrainz id.** The ticket asserts the harm;
  this measured the wrongness.
- **Drift.** One 2026-09-06 snapshot of MusicBrainz, one of Last.fm. MusicBrainz gains url relations
  continually and Last.fm re-syncs its ids rarely, so both rates will move — and the scripts to
  re-derive them are committed beside the captures.
