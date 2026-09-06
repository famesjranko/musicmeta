# Pre-registered A/B: can a wrong Last.fm similar-artist MBID be corrected without wrong answers?

Ticket: `.scratch/bugs/issues/34-lastfm-similar-artist-mbid-names-the-wrong-act.md`
Base commit for every arm: `00dfd1dc` (origin/main).
Frozen after `inventory.md` and **before any arm ran, and before the held-out set's ground truth was
captured**. Nothing below "Metrics" may change once an arm has run. `.scratch/` is gitignored, so
this file and `inventory.md` are committed onto each arm's branch under `probe-results/34-mbid/` —
that copy is the freeze record.

## What Phase 1 settled, and what it removes from this plan

- **8 of 198 MBID-carrying Last.fm rows name a different act** — 4.0% of the rows, 5.8% of the 138
  MusicBrainz decides either way. One of them is Last.fm's top-scoring similar artist for Björk.
- **No id is stale.** 184 of 184 resolve, and every one resolves to itself: no `404`, no merge, no
  redirect. **The ticket's arm A is therefore not live and is not built.** There is nothing to
  follow. This is recorded as a result, not skipped as an oversight.
- **Five of the eight are not split pairs.** No other contributor names them, so nothing in the
  merged list contradicts them and the engine's existing split-pair batch cannot see them.
- **The decisive signal cannot ride the request the engine already makes.** `inc=url-rels` is not
  available on a search, so `artistDisambiguationSearchUrl`'s batch can never carry it.

## Arms, one property each

| Arm | Branch | Property |
|---|---|---|
| Control | `probe/lastfm-mbid-arm-control` | HEAD, plus instrumentation |
| B1 | `probe/lastfm-mbid-arm-corroborate-split` | url-rels corroboration, **scoped to name collisions inside the merged list** |
| B2 | `probe/lastfm-mbid-arm-corroborate-all` | the same corroboration, **scoped to every Last.fm row carrying an MBID** |
| C | `probe/lastfm-mbid-arm-genre` | genre/tag overlap with the requested artist as a **suspicion signal only** — corrects nothing |

B1 and B2 differ in exactly one thing: how wide the candidate set is. C changes no id at all; it is
measured to answer whether it could cheaply *gate* what B spends.

### The corroboration rule, stated once and shared by B1 and B2

Last.fm's `url` is `last.fm/music/<name>` URL-escaped — name-derived, and `features/09` proved it
cannot tell a same-name pair apart *on its own*. It is used here as one half of a comparison whose
other half is MusicBrainz's, never as a verdict:

For a Last.fm row with page `P` (its `url`, normalised: host dropped, `/music/` prefix dropped,
percent- and `+`-unescaped, lowercased) and supplied id `M`, over a candidate set `S`:

1. If MusicBrainz's `url-rels` for `M` contain `P`, the row is **corroborated**. Nothing changes.
2. Otherwise, if **exactly one** candidate `F` in `S`, `F != M`, has `P` in its own `url-rels`, the
   row's MBID is **replaced by `F`**.
3. Otherwise — no candidate carries `P`, or more than one does — the row is **left alone**.

Rule 3 is the whole safety argument, and it is why an absent relation can never change anything:
MusicBrainz's silence leaves the row as HEAD has it.

- **B1's `S`** is the other merged entries sharing the row's name key. Cost: one
  `GET /ws/2/artist/<mbid>?inc=url-rels` per distinct MBID in a name-collision set.
- **B2's `S`** is every MusicBrainz artist whose name or alias equals the row's name. Cost: one
  `artist:"name"` search per distinct row name, plus one url-rels lookup per same-name candidate.

### Arm C, stated once

One batched `arid:` search over every Last.fm-supplied MBID in the merged list, capped at the
shipped `DISAMBIGUATION_BATCH_LIMIT` of 25 — one request, the shape the engine already sends. A row
is **flagged suspect** when the tag set MusicBrainz returns for its id shares no member with the
requested artist's own genre-and-tag pool. Both sides are the upstream's own vocabulary; no word
list is written, and none may be.

C flags; it never corrects. Its numbers are precision on the mismatch set and false-positive rate on
the correct set, and nothing else.

## Workloads — frozen

**Training: the twelve frozen artists**, `probe/dedup-arm-mbid:musicmeta-core/src/test/resources/probe/fixtures/`.
Offline, deterministic, unchanged since `tech-debt/08`. Ground truth is `inventory.md`'s
adjudication: 8 MISMATCH rows, 130 MATCH rows, 60 rows MusicBrainz is silent about.

**Held-out: twelve artists chosen blind**, listed in `heldout/artists.md` and captured live on
2026-09-06 before this plan was written: BLACKPINK, Claude Debussy, A Tribe Called Quest, The Bad
Plus, Trouble, Кино, Alison Krauss, Four Tet, Shakira, The Zombies, Alvvays, Nico. 238 Last.fm rows,
214 carrying an MBID.

**The held-out set may not be looked at until every arm has run against the training set and this
plan is unchanged.** Its ground truth is captured by the same three scripts, with no hand
adjudication anywhere. An arm that wins on the training twelve and loses on the held-out twelve does
not ship, and the report gives both sets their own columns.

## Metrics — frozen

Counted per **row**, over each workload separately.

1. **Mismatched rows corrected** — of the ground-truth MISMATCH rows, how many the arm rewrites to
   the id MusicBrainz names as the page's owner.
2. **Correct rows wrongly changed — decisive, must be 0.** A row whose supplied id is ground-truth
   MATCH, or a row MusicBrainz is silent about, that the arm rewrites at all. Any arm scoring
   non-zero loses whatever metric 1 says: the ticket states a wrong id at full score is the defect,
   so trading eight of them for one new one is not a fix.
3. **Extra upstream requests** — counted, never estimated: the arm records every request it would
   make. Reported as total over the twelve and worst case for a single artist.
4. **Top-10 movement against the control** — how many top-10 positions move, per workload and in
   total. Not a decision metric; it is the size of the consumer-visible change.

Arm C is scored on 2 and 3 only, plus its own two rates:

5. **C's precision** — of the rows C flags, the share that are ground-truth MISMATCH.
6. **C's false-positive rate** — of the ground-truth MATCH rows, the share C flags.

## Decision rule — frozen

An arm ships only if **all** of these hold:

1. Metric 2 is **0 on the training twelve and 0 on the held-out twelve**.
2. It corrects a majority of the ground-truth mismatches *within its own scope* on both sets.
3. Its metric 3 is stated in requests per enrich, and the report says plainly what that costs on a
   1 req/s limiter — the cost is not traded away silently.

Among arms that qualify, prefer the one correcting more rows; tie-break on fewer requests.

**If no arm qualifies, nothing is implemented.** The report gives the numbers, the ticket goes to
`ready-for-human` with the options and their measured costs, and the recommendation says which
option the numbers favour without pretending the gate was cleared.

## What each arm may and may not do

**May:** read Last.fm's `url`, which `parseSimilarArtists` receives and drops; consult an offline
table standing in for a live MusicBrainz lookup, provided every consultation is billed as a request;
change the MBID on a row before the merge sees it; add instrumentation.

**May not:** change `groupArtists`, `mergeArtists`' scoring or its sort; compare two *names* to
decide anything; drop a Last.fm MBID because it is uncorroborated; maintain a genre, style or
keyword list of any kind; re-capture either workload's fixtures; hand-adjudicate a single row.

## What this will not measure

- **Whether MusicBrainz is right.** Its `url-rels` are ground truth by definition throughout.
- **Production cost.** The arms count requests. They do not make them under the shared 1 req/s
  limiter, do not model a timeout, a breaker or a cache, and do not touch `enrichTimeoutMs`. Any
  claim about complexity in the recommendation is a judgement and will say so.
- **The 60 rows MusicBrainz is silent about.** They can only ever be metric-2 exposure, never metric
  1, and no arm gets credit for leaving them alone.
- **Whether a consumer follows the MBID at all.** The ticket asserts the harm; this measures the
  wrongness.
- **Anything about `SIMILAR_TRACKS`**, which carries Last.fm recording ids with a different and
  already-measured failure mode.
