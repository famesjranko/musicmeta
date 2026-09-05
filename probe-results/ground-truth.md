# Ground truth: the cross-provider name pairs in the frozen workload that are one act

Frozen 2026-09-05, before any arm's grouping was measured (the control and Arm A had run; neither
unifies anything the name key does not, and neither contributed a pair below). Adjudicated by hand
against MusicBrainz — `User-Agent: musicmeta-probe-08-merger-dedup-key/0.1`, >= 1.3 s between
requests. **Not revised after seeing an arm's output.** A pair found later is reported separately in
`report.md` and does not enter the metrics.

## How the shortlist was built

Every cross-provider pair of entries inside one artist's workload whose `name.trim().lowercase()`
keys differ, and which any of these flagged: the two MBID-carrying providers agree on an MBID; the
ASCII-folded, punctuation-stripped names are equal; one name's token set is a (fuzzy) subset of the
other's; or `difflib` ratio >= 0.60. That yielded 87 pairs, every one of which was read by hand, and
the 15 that were not obviously two different acts were settled at MusicBrainz. The shortlist is a
net, not evidence: what settles each pair is the MusicBrainz record cited beside it.

## The pairs — same act

| # | Artist | Name A (provider) | Name B (provider) | MusicBrainz id | What settles it |
|---|---|---|---|---|---|
| 1 | fela-kuti | `Antibalas` (deezer) | `Antibalas Afrobeat Orchestra` (lastfm) | `33c2a158-a788-44e5-81ab-142d544a165b` | One artist, disambiguation "aka Antibalas Afrobeat Orchestra", which is its only alias |
| 2 | fela-kuti | `Fela Kuti & Afrika 70` (lastfm) | `Africa 70` (labs) | `dc45f2dc-ef36-4a7a-aa52-97495fca8ced` | Artist "Africa 70" carries the alias `Fela Kuti & Afrika 70`; the Labs entry already carries this MBID |
| 3 | fela-kuti | `Femi Kuti` (lastfm) | `Femi Anikulapo Kuti` (deezer) | `702d2b90-eef0-4354-b2c4-6366eba92b7f` | Artist "Femi Kuti" carries aliases `Femi Anikulapo-Kuti` and `Olufela Olufemi Anikulapo Kuti`; the Last.fm entry already carries this MBID |
| 4 | aphex-twin | `µ-Ziq` (lastfm) | `μ‐Ziq` (labs) | `aae5b930-c59c-4509-81a1-4e65e8f424e4` | One artist whose aliases hold both spellings; the names differ only in the mu codepoint (U+00B5 vs U+03BC) and the hyphen (U+002D vs U+2010) |
| 5 | boards-of-canada | `µ-Ziq` (lastfm) | `μ‐Ziq` (labs) | `aae5b930-c59c-4509-81a1-4e65e8f424e4` | Same pair, second artist's workload |
| 6 | changg | `Hứa Kim Tuyền` (lastfm) | `Hua Kim Tuyen` (deezer) | `51ace54d-6a30-4726-9af1-e6a16539d231` | One Vietnamese songwriter; the Deezer name is the same string with the diacritics dropped, and MusicBrainz resolves the undiacriticked query to this artist at score 100. The Last.fm entry already carries this MBID |

Six pairs, in four of the twelve artists' workloads. Pair 5 is the same two names as pair 4 in a
second artist's list and is counted separately, because unifying it is a separate event in a
separate merge.

## Adjudicated and *not* one act — the near misses an arm must not unify

| Artist | Name A | Name B | Why not |
|---|---|---|---|
| fleetwood-mac | `Tom Petty and The Heartbreakers` (lastfm) | `Tom Petty` (labs, `5ca3f318-…`) | Two MusicBrainz artists: the person and the group `f93dbc64-6f08-4033-bcc7-8a0bb4689849`. **The group carries `Tom Petty` as an alias**, so a pool test unifies them — a solo catalogue is not the band's |
| fleetwood-mac | `Paul McCartney & Wings` (lastfm) | `Paul McCartney` (deezer) | The first resolves to the group "Wings" (`d922d727-…`), which lists `Paul McCartney & Wings` as an alias; the second is the person |
| tigran-hamasyan | `Avishai Cohen` (lastfm, `17d78170-…`) | `Avishai Cohen Trio` (labs, `05da8a7c-…`) | Two MusicBrainz artists, person and group |
| tigran-hamasyan | `Brad Mehldau` (lastfm) / `Vijay Iyer` (lastfm) / `Yaron Herman` (deezer) | the same names + ` Trio` (labs) | Same shape: each trio is its own MusicBrainz group with its own MBID |
| burial | `Burial & Four Tet`, `Burial & Four Tet & Thom Yorke`, `Massive Attack vs Burial` (lastfm) | `Four Tet`, `Thom Yorke`, `Massive Attack` (labs) | Collaboration credits, each its own MusicBrainz entity |
| kendrick-lamar | `Kendrick Lamar & SZA` (lastfm) | `SZA` (labs) | Collaboration credit |
| changg | `Vũ Phụng Tiên` (lastfm, `382d25c8-…`) | `Vũ.` (deezer) | Different Vietnamese artists; the first has no alias resembling the second |
| boards-of-canada | `Lone` (lastfm) | `Two Lone Swordsmen` (deezer) | Different acts |
| radiohead | `The Smiths` (lastfm) | `The Smile` (deezer) | Different bands the string signal put next to each other |

## Same name, different act — what the *control* already fuses

Not pairs of different names, so outside metric 1, but they are the mirror image of the same
question and every arm is measured on them (metric 5). Each is one normalized name carrying two
MusicBrainz identities inside one merged group under the name key:

| Artist | Name | MusicBrainz ids | The two acts |
|---|---|---|---|
| sleep-token | `Bad Omens` | `eecada09-…`, `8834d8b5-…` | Metalcore band (2015) vs a 1960s Minnesota garage band |
| sleep-token | `Spiritbox` | `9c935736-…`, `a39ad456-…` | Canadian metalcore (2017) vs Dutch post-rock (2018) |
| sleep-token | `Loathe` | `56eb02c4-…`, `e9ea0fbc-…` | UK experimental metal (2014) vs Maltese death metal (1999) |
| tigran-hamasyan | `Sungazer` | `beb404c4-…`, `21006fdb-…` | New York electrojazz duo vs Colorado ambient/noise |

Four groups, all four genuinely two different acts.
