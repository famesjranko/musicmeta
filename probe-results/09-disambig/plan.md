# Pre-registered A/B: what tells two same-name similar artists apart?

Ticket: `.scratch/features/issues/09-two-same-name-similar-artists-are-indistinguishable-to-a-consumer.md`
Base commit for every arm: `085ae2dd` (origin/main).
Frozen after `inventory.md` and **before any arm ran**. Nothing below "Metrics" may change once an
arm has run. `.scratch/` is gitignored, so this file is committed onto each arm's branch under
`probe-results/09-disambig/` — that copy is the freeze record.

## What is true at HEAD

`SimilarArtist` carries `name`, `identifiers`, `matchScore`, `sources`. Since `bugs/30` two acts
sharing a name arrive as two entries, correctly, and nothing on either entry says which act it is.

The inventory settles what is on the wire (see `inventory.md` for the evidence):

- **Labs** returns `comment`, which is MusicBrainz's `disambiguation` verbatim, and we drop it.
  It also drops `type`, `gender`, `reference_mbid`.
- **Last.fm** returns `url`, which is name-derived and therefore cannot distinguish a same-name
  pair; no text of any kind.
- **Deezer** returns `link`, `nb_fan`, `nb_album`, pictures; no MBID and no text.
- **The requested artist's MusicBrainz lookup carries nothing about a similar entry.**
- All four split pairs are Labs-versus-Last.fm, with Labs on exactly one side. An upstream-only
  string can therefore reach at most one of the two entries in a pair.

## Arms, one property each

| Arm | Branch | Property |
|---|---|---|
| Control | `probe/disambig-arm-control` | HEAD, plus instrumentation |
| A | `probe/disambig-arm-comment` | `SimilarArtist.disambiguation` filled from Labs `comment` only |
| B | `probe/disambig-arm-link` | `SimilarArtist.url` filled from Last.fm `url` / Deezer `link` / MusicBrainz URL built from the MBID |
| C | `probe/disambig-arm-mblookup` | A, plus one `/ws/2/artist/{mbid}` lookup for each split-pair entry still lacking a string |
| D | `probe/disambig-arm-mbbatch` | A, plus **one** `/ws/2/artist?query=arid:… OR arid:…` request per enrich covering every such entry |

**Every arm builds on the same grouping.** No arm may change `groupArtists`, the merge, the scores
or the order: this A/B is about what a group is *labelled* with, not about what a group is. An arm
that moves a score is a defect in the arm.

**Rule the merger must obey in every arm (from the ticket's constraints).** A group's string or link
must come from a member whose MBID equals the group's MBID. A same-name member carrying **no** MBID
may not supply one, because `groupArtists` attaches such a member by contributor order rather than by
evidence — it is exactly as likely to be the other act.

C and D are simulated offline from a one-off capture of the eight split-pair MBIDs taken 2026-09-06,
committed raw at `musicbrainz-capture/`. Their **call counts are counted, not estimated**: the arm
records every MBID it would have asked about.

## Workload — frozen

The same twelve artists as `tech-debt/08`, `probe/dedup-arm-mbid:musicmeta-core/src/test/resources/probe/fixtures/`.
Offline, deterministic. Do not re-capture, do not add artists. The four split pairs are the ones
`inventory.md` §2 re-derived at `085ae2dd`: Bad Omens, Spiritbox, Loathe (sleep-token), Sungazer
(tigran-hamasyan).

## Metrics — frozen

Counted per **entry** (8 entries across 4 split pairs) and per **pair** (4), because a pair whose two
entries are "named" and "bare" is distinguishable while only one of them is described.

1. **Entries with a distinguishing string** — of the 8, how many carry a non-empty description, and
   from which contributor.
2. **Entries with a distinguishing link** — of the 8, how many carry a URL, and whether the two URLs
   in a pair differ from each other.
3. **Pairs a consumer can tell apart** — of the 4, how many have *some* asymmetry a UI can render.
   A pair where one entry is described and the other is blank counts, and is reported as `half`.
4. **Wrong answers — decisive.** An entry labelled with the *other* act's text or destination. Any
   arm producing one loses to the control on that pair, whatever metrics 1-3 say, because the ticket
   states a wrong label is worse than none.
5. **Extra upstream calls per enrich** — counted over the twelve-artist workload: total, and worst
   case for a single artist.
6. **Coverage beyond the split pairs** — of all merged groups in the workload, how many gain a
   string or link. Not a decision metric; it is the size of the consumer-visible surface change.

## Decision rule — frozen

Among arms with **zero** wrong answers (metric 4) and no change to any group, score or order:

1. Prefer the arm distinguishing the most **pairs** (metric 3), counting a `half` as half a pair.
2. Tie-break on metric 1 (entries described), because a described entry is what a consumer reads.
3. Tie-break on metric 5, fewest extra calls.
4. If two arms remain tied, prefer the one whose data comes from a response we already fetch.

If every arm producing a full answer costs an extra upstream call, the report states the trade-off
explicitly rather than resolving it silently: a free half-answer and a paid whole one are different
products and the recommendation must name which is being recommended and why.

If no arm distinguishes more pairs than the control (0), the ticket closes `wontfix` with the count.

## What each arm may and may not do

**May:** add a nullable field to `SimilarArtist` with a default; read a field the parser already
receives; add an offline lookup table standing in for a live MusicBrainz call; add instrumentation
to the harness.

**May not:** change `groupArtists`, `mergeArtists`' scoring or its sort; change any provider's
`matchScore`; add a name-similarity test of any kind; use a same-name group member's MBID-less row
as a source of text or link; re-capture the workload fixtures; hand-write a disambiguation string.

## What this will not measure

- **Whether a consumer finds the string useful.** There is no ground truth for "a good
  disambiguation"; MusicBrainz's own text is taken as correct by definition.
- **Live drift.** Labs' `comment` is unpinned by the schema check (`inventory.md` §1) and could
  vanish without a test failing. One 2026-09-06 snapshot is all any arm has.
- **A fourth `SIMILAR_ARTISTS` contributor**, or any pair not present in these twelve artists. Four
  split pairs, all in two of the twelve workloads, is a small sample and the report says so.
- **The cache and API cost of shipping a field.** That is stated in the report as a compatibility
  cost, not measured.
- **Whether MusicBrainz's rate limit tolerates arm C or D in production.** The arms count calls; they
  do not make them under load.
