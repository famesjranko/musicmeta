# Two same-name similar artists are correct and indistinguishable to a consumer

Status: ready-for-human
Type: prototype
Area: engine, providers

Since `bugs/30` (PR #351, `6b29123f`), two acts sharing a name arrive as two `SimilarArtist`
entries. Verified live in demo-web on 2026-09-06: Sleep Token shows `Bad Omens` at rank 1.00 and
again at rank 0.14, `Loathe` twice at 0.18, `Spiritbox` at 0.74 and 0.26. Each pair is two
MusicBrainz artists. The rows are right and a consumer cannot tell them apart: `SimilarArtist`
carries `name`, `identifiers`, `matchScore`, `sources` and nothing that names *which* act.

## The question

**What do the upstreams already return for a similar-artist entry that we drop on the floor, and
does any of it distinguish the pairs we now split?** Owner's framing: prefer a metadata-source
answer over a new lookup. Candidates to inventory, per provider, from the frozen fixtures
(`probe/dedup-arm-mbid`, `src/test/resources/probe/fixtures/`, 12 artists x 3 providers):

- ListenBrainz Labs `similar-artists`: the raw rows may carry `comment` (MusicBrainz's
  disambiguation), `type`, `gender`, `country`, `reference_mbid`. The mapper reads `name`,
  `artist_mbid`, `score` only.
- Last.fm `artist.getSimilar`: `mbid`, `url`, `image[]`. The mapper reads `name`, `mbid`, `match`.
- Deezer `artist/{id}/related`: `id`, `link`, `picture*`, `nb_album`, `nb_fan`. No MBID. The
  mapper reads `name`, `id`, and derives a score from position.
- MusicBrainz artist lookup: `disambiguation`, `type`, `area`, `life-span` — already fetched for
  the requested artist, never for a similar entry. A per-entry lookup is the expensive fallback
  (`tech-debt/08` measured 827 names across twelve artists).

## Arms, one property each

- **A. Upstream-carried disambiguation** — surface whatever a contributor already returns
  (Labs `comment`, at least). Metric: of the split pairs in the workload, how many get a
  non-empty distinguishing string from at least one contributor, and from which.
- **B. Upstream-carried link** — surface the provider's own entity URL (Last.fm `url`, Deezer
  `link`, MusicBrainz URL from the MBID). Distinguishes by destination, not by text.
- **C. MusicBrainz lookup per split pair only** — fetch `disambiguation` for the two MBIDs of a
  same-name pair, and only then. Cost metric: lookups per enrich over the workload, against C's
  ceiling of one per call today.
- **Control** — HEAD.

Metrics frozen before any arm runs: (1) split pairs with a distinguishing string, (2) split pairs
with a distinguishing link, (3) extra upstream calls per enrich, (4) what a wrong answer looks like
— an entry given the *other* act's disambiguation is worse than none, so count that too.

## Constraints

- `SimilarArtist` is public and `@Serializable` and Room-cached: a new field is a `0.x.0` change,
  an `api/*.api` diff, and a cache-compatibility question (`CLAUDE.md`, "The published surface").
  A nullable field with a default keeps old payloads readable; say so in the report.
- The merger keeps `group.first().name`; a disambiguation must come from the same act as the
  MBID the group carries, never from a same-name contributor with no MBID.
- Report lands in `.scratch/features/prototypes/09-similar-artist-disambiguation/`, arms committed
  to `probe/disambig-arm-*` branches, no production change.

## Answer

Measured 2026-09-06 at `085ae2dd`. Report:
`.scratch/features/prototypes/09-similar-artist-disambiguation/report.md`, with `inventory.md`,
`plan.md` and the raw MusicBrainz capture beside it. Arms on `probe/disambig-arm-{control,comment,
link,mblookup,mbbatch}`, pushed; each carries `probe-results/` and a copy of the frozen plan and
inventory.

**The upstreams already answer it, and we were dropping the answer.** ListenBrainz Labs'
similar-artists rows carry `comment`, which is MusicBrainz's `disambiguation` verbatim, and the
parser reads only `artist_mbid`, `name` and `score`. That field alone labels 463 of 1226 merged
groups and one entry of every split pair, for zero extra requests. The other entry of every pair is
Last.fm's, and Last.fm returns no text at all — a structural ceiling, since Labs and Last.fm are the
only MBID-carrying contributors and only Labs describes anything.

- **Arm A (Labs `comment`)** — 4 of 8 split-pair entries named, 0 wrong, 0 extra calls.
- **Arm B (upstream link)** — disqualified. Last.fm's `url` is built from the *name*, not the entity,
  so it handed three of the four pairs the **other act's** page; MusicBrainz's own `url-rels` settle
  each one.
- **Arm C (a lookup per split-pair entry)** — 8 of 8 named, 4 requests over the workload, 3 in the
  worst enrich.
- **Arm D (emergent: one batched `arid:` query per answer)** — byte-identical output to C for 2
  requests over the workload, 1 in the worst enrich. Not in this ticket; the inventory surfaced it
  and the plan's rule promoted it.

The frozen rule names **D**. The recommendation is **ship A first** — it is free, on a response
already fetched, and a blank second entry is not a wrong answer — and add D's lookup only if the half
answer proves insufficient. Take D over C in every case: same strings, half the traffic.

**Cost to a consumer, for either:** a nullable `disambiguation` on `SimilarArtist` keeps Room
payloads readable, but the `api/*.api` diff moves the constructor and `copy` descriptors, so it is
binary-breaking and needs a `### Breaking Changes` line. `ListenBrainzApi.SCHEMA_PIN_TARGETS` must
also grow `[0].comment`, which nothing watches today.
