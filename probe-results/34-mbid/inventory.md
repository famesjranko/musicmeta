# Inventory: how often does Last.fm's similar-artist MBID name the wrong act?

Phase 1 of `.scratch/bugs/issues/34-lastfm-similar-artist-mbid-names-the-wrong-act.md`. Written
**before** `plan.md` and before any arm. The question is the ticket's own: the defect is proven for
one row; what is its *rate*, and is a wrong id a **stale** one MusicBrainz can resolve mechanically
or a **plain wrong** one that needs corroboration?

Population: every row Last.fm's `artist.getSimilar` returned for the twelve frozen workload artists
that carries an `mbid` — **198 rows of 240, 184 distinct ids**, from
`probe/dedup-arm-mbid:musicmeta-core/src/test/resources/probe/fixtures/<artist>/lastfm.json`.
Captures taken 2026-09-06, raw, in `musicbrainz-capture/`; every script that made them is beside
them and every id's body and headers are kept.

## What was asked of MusicBrainz, and why each question

| Capture | Route | What it settles |
|---|---|---|
| `artists/<mbid>.json` | `GET /ws/2/artist/<mbid>?inc=url-rels+tags+genres` | Does the id still exist, does it still name *itself*, and which last.fm page does MusicBrainz say its artist owns |
| `names/<n>.json` | `GET /ws/2/artist?query=artist:"<name>" OR alias:"<name>"` | How many artists MusicBrainz holds under the row's own name — is the id even contested |
| `artists/<candidate>.json` | the same lookup, for each same-name candidate | Which of the same-name artists owns the page, when the supplied id does not |
| `urls/<page>.json` | `GET /ws/2/url?resource=<page>&inc=artist-rels` | Tried first, and **kept only as a record of why it is not the instrument** — see "What did not work" |

The adjudication is Last.fm's own `url` against MusicBrainz's own `url-rels`, and nothing else. No
name comparison decides any verdict here: the two things compared are an identifier and a URL.

## The rate

| Verdict | Rows | Share of 198 |
|---|---|---|
| **MATCH** — the supplied id's own url-rels name the very page the row links to | 130 | 65.7% |
| **MISMATCH** — a *different* same-name MusicBrainz artist owns that page | **8** | **4.0%** |
| UNCONTESTED — the id owns no last.fm page, and MusicBrainz holds no other artist under that name | 47 | 23.7% |
| UNADJUDICATED — the name is contested but no candidate carries the page | 13 | 6.6% |

**Of the 138 rows MusicBrainz decides either way, 8 are wrong — 5.8%.** The two figures bound the
truth: 4.0% is the floor (every unadjudicated row assumed correct), and the 60 rows MusicBrainz is
silent about are the reason there is no single number. The 47 UNCONTESTED rows are silence of a
useful kind: MusicBrainz holds exactly one artist under that name, so no other act *could* be the
one the page belongs to. The 13 UNADJUDICATED rows are the honest gap.

### The eight

| Workload | Row | Last.fm's `match` | Supplied id | MusicBrainz says the page belongs to |
|---|---|---|---|---|
| bjork | Arca | **1.00 — the top row** | `c58ea103` *French band created by S. Chauveau and J. Cambon* | `f7625f34` *Venezuelan producer & composer* |
| bjork | Eartheater | 0.617 | `bde8e7a4` *metal band from Sweden, previously known as Dr. Acetone* | `7f5a2d26` *multi-instrumentalist and vocalist Alexandra Drewchin* |
| bjork | Locust | 0.313 | `3d3f144f` *aka Kyle Preston* | `046a16c2` *Mark van Hoen (IDM, ambient)* |
| sleep-token | Bad Omens | 0.901 | `8834d8b5` *60s garage rock band from Minnesota* | `eecada09` *metalcore/post-metal* |
| sleep-token | President | 0.705 | `5b4978bf` *Spanish Band* | `7449cc7e` *UK metal band* |
| sleep-token | Spiritbox | 0.525 | `a39ad456` *Dutch post-rock* | `9c935736` *Canadian metalcore* |
| sleep-token | Loathe | 0.323 | `e9ea0fbc` *Maltese death metal band* | `56eb02c4` *UK experimental metal* |
| sleep-token | Save Us | 0.320 | `43fe275d` | `b72a4b0a` |

The ticket names three of these. The other five are new, and one of them is Last.fm's **highest-
scoring similar artist for Björk**.

## Stale or wrong? Wrong — there is no redirect to follow

**Zero of the 184 ids are gone and zero have been merged away.** Every id answered `200`, and every
answer carried back the id it was asked about: `body["id"] == requested` in 184 of 184 cases. Not one
`404`, not one lookup resolving to a survivor.

That kills the mechanically-resolvable half of the ticket's question before an arm is built. A wrong
Last.fm artist MBID is **not** a stale pointer into a MusicBrainz merge; it is a live id naming a
live artist who is a different act. Following a redirect corrects nothing here because there are no
redirects. Correcting one of these needs corroboration — something outside the id that says which
act was meant.

**This is the opposite of what the same question returned for *recording* ids.** `docs/providers.md`
records `scripts/probes/lastfm-mbid-staleness-probe.sh`: 1212 of 1710 Last.fm recording MBIDs still
resolve, and the failures there are `404`s and merges. Artist ids behave differently — they all
resolve, and the failure is a correctly-formed pointer at the wrong artist. A finding about one kind
of Last.fm MBID is not a finding about the other.

## Where the harm is visible, and where it is not

Of the eight, only **three are split pairs** — a second entry under the same name reaches the merged
list from another contributor, which is the case `bugs/30` and `#357` built for:

| Row | Also named by Labs | Also named by Deezer |
|---|---|---|
| Bad Omens | yes, `eecada09` *metalcore/post-metal* | yes |
| Spiritbox | yes, `9c935736` *Canadian metalcore* | yes |
| Loathe | yes, `56eb02c4` *UK experimental metal* | no |
| Arca, Eartheater, Locust, President, Save Us | **no** | **no** |

So the engine's existing `SimilarArtistDisambiguation.undescribedSplitPairMbids` — which spends its
one batched `arid:` request only where two merged entries share a name — **cannot see five of the
eight**. Those five are single entries carrying a wrong id at whatever rank Last.fm gave them, with
nothing in the merged list to contradict them. Any fix scoped to split pairs has a ceiling of 3 of 8
on this workload, and that ceiling is a property of the scope, not of the signal.

For the three that are split pairs, the correct id is **already in the merged list**, supplied by
Labs. That is the cheapest thing this inventory found: the answer is in the payload, and what is
missing is the evidence to prefer one id over the other.

## What each upstream carries that could corroborate, and what it costs

| Signal | Where it comes from | Extra requests | Reaches |
|---|---|---|---|
| MusicBrainz `url-rels` for the supplied id | `GET /ws/2/artist/<mbid>?inc=url-rels` | **one lookup per id** — `inc=` is not available on a search, so this cannot be batched | all 8, decisively, when MusicBrainz is not silent |
| MusicBrainz `url?resource=` reverse lookup | `GET /ws/2/url?resource=<page>&inc=artist-rels` | one per page | 7 of 66 tried — see below |
| `disambiguation`, `type`, vote-weighted `tags` for a batch of ids | `GET /ws/2/artist?query=arid:X OR arid:Y…` — **the request the engine already makes** | 0 marginal, within the existing batch | describes; does not adjudicate |
| Labs `comment` on the same-name entry | already on the wire (`#357`) | 0 | the 3 split pairs only |
| Last.fm `url` | already on the wire, dropped by `parseSimilarArtists` | 0 | it is one half of the comparison, never the verdict |

`inc=url-rels` on a *search* does not exist, so the decisive signal is exactly the one that cannot
ride the existing batched request. That is the cost this ticket turns on.

## What did not work, recorded so it is not tried again

**`GET /ws/2/url?resource=<page>` is not the instrument it looks like.** It answered 200 for **7 of
the 66** pages tried and `404` for the rest, and the 404s are not evidence: MusicBrainz stores the
URL as a contributor typed it, and the match is byte-exact. `https://www.last.fm/music/Spiritbox`
returns `404` while the id `9c935736` plainly carries `https://www.last.fm/music/SpiritBox` — one
capital letter. Every conclusion here therefore comes from the forward lookup, which compares
normalised pages, and the reverse capture is kept only as the record of why.

**A missing relation is not a verdict.** 68 rows carry an id with no last.fm relation at all. Read
as "suspect", that rule flags 68 rows to correct 8 — it would change 60 rows MusicBrainz never said
anything against, which is the wrong answer the ticket's own constraint exists to forbid. Naming
that false-positive population *before* measuring is what makes the number in the table above
meaningful.

## What this did not measure

- **Whether MusicBrainz is right.** Its `url-rels` are taken as ground truth by definition; a
  contributor filing the wrong last.fm link would move a row from MATCH to MISMATCH or back, and
  nothing here would notice.
- **Whether a consumer follows the MBID.** The ticket's cost argument is that some do. This counts
  wrong ids, not harm.
- **Anything about the 13 UNADJUDICATED rows.** Among them are `Hiromi` and `Sungazer`, both of
  which earlier work adjudicated by hand as genuinely two acts. Hand adjudication is deliberately
  not carried over: this file's numbers rest on one mechanical test, and mixing in a judgement would
  make them unreproducible.
- **Drift.** One 2026-09-06 snapshot. MusicBrainz gains url relations continually, so the
  UNCONTESTED and UNADJUDICATED counts will fall over time — in either direction.
- **Any workload but these twelve.** Eight mismatches concentrated in two of twelve artists is a
  small and lumpy sample, which is why a held-out twelve was captured (`heldout/`) and why no arm may
  be tuned on it.
