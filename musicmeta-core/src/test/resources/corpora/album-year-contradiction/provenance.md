# album-year-contradiction

Captured live from MusicBrainz on **2026-08-25** by
`.scratch/album-mbid-contradiction/capture_albums.py`, committed beside the raw `albums.json` it
wrote. Re-run it before trusting these numbers again: a live capture decays.

## Population

The 99 artists are the same ones captured for `corpora/artist-name-contradiction` (PR #265): a
Last.fm chart, with each MBID resolved from MusicBrainz's own search rather than written by hand.
For each artist, up to two **studio** release groups — `primary-type` `Album` with no secondary
types, so no compilation, live album or soundtrack, whose years and track counts are a different
question.

`groups.json` is one row per release group: the group's `first-release-date` year, and every release
in it as a year and an audio track count. 181 groups, 3139 releases. Track counts are computed the
way `MusicBrainzParser.parseMedia` computes them — video media dropped by exact format name, tracks
summed across the rest — so the corpus counts what the shipped code counts.

## What it is evidence for

Every release in a row is a pressing a caller could legitimately own while having identified the
album correctly, because their local tags and the MBID they supplied rarely come from the same
source. So no release in a row may contradict its own row. That is the false-positive population,
and it is deliberately harsh: it includes deluxe reissues decades after the original.

Measured 2026-08-25, by `AlbumYearContradictionCorpusTest`, which re-runs both on every build:

| rule | false positives | population |
|---|---|---|
| year floor (shipped) | **0** | 3139 releases |
| track-count band (rejected) | **31729 (29%)** | 109604 same-group pairs |

The track-count rule was frozen before the capture, on the same terms, and lost on its own numbers.

## What it cannot show

Every year here comes from MusicBrainz, so a caller whose own tag is two or more years earlier than
MusicBrainz's first release is outside it. `.scratch/album-mbid-contradiction/spec.md` records that
limit, and why the rule's one-sidedness and its year of slack answer it rather than a tuned
threshold.

The catch rate is not measured here and is not a property of this corpus. Scored on synthetic
pairings of one album's identifier with another album's year, the rule reported 63 of 176; that is a
floor on a synthetic population, not a detection rate in the wild.
