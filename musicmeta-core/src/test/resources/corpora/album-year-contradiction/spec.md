# Album MBID contradiction — frozen rules, then measurement

Ticket: `.scratch/bugs/issues/08-same-artist-wrong-album-mbid-is-accepted.md`.
Branch: `fix/identifier-assertion-followups`, stacked on `fix/artist-mbid-provenance` (#265).

**Rules below are frozen before any corpus is scored.** Written 2026-08-25, before
`capture_albums.py` was run. Nothing here is tuned to the numbers.

## What is already free

`lookupRelease` asks `inc=artist-credits+labels+release-groups+tags+genres+media+recordings`. So a
release lookup by a caller's MBID already carries, at no extra request:

- `media[].tracks[]` -> `MusicBrainzRelease.tracks`, video media filtered out by `parseMedia`
- `date` -> the *pressing's* date, so a remaster carries the remaster's year
- the embedded `release-group` object, which carries `first-release-date` — **not currently parsed
  onto `MusicBrainzRelease`**; this work adds it

Same property as `contradictsSuppliedName` in #265: zero extra requests.

## The two arms

Both take the caller's `EnrichmentRequest.ForAlbum.trackCount` / `.year` and the release the caller's
MBID named. Both are scored on identical corpora with identical metrics. Neither is tuned after.

### RULE-Y — the year floor

> Fires iff `callerYear != null` and `rgFirstReleaseYear != null` and
> `callerYear < rgFirstReleaseYear - 1`.

Asymmetric **by construction**, not by tuning: an album cannot predate its own first release, so a
caller year earlier than the release group's first release is positive evidence of a different album.
A caller year *later* than it is any reissue, remaster or region pressing, and is not judgeable — so
the rule is silent there, and that silence costs it roughly half the catch rate up front.

The one-year slack absorbs region and calendar-boundary sloppiness in both the caller's tag and
MusicBrainz's own partial dates (`"1997"`, `"1997-05"`).

### RULE-T — the track-count band

> Fires iff `callerTrackCount != null` and `releaseTrackCount > 0` and
> `abs(callerTrackCount - releaseTrackCount) > max(2, releaseTrackCount / 5)`.

The band exists because the ticket already names the legitimate variation: a bonus disc, a hidden
track, a region variant. It is a guess at the size of that variation, and the corpus is what decides
whether the guess survives.

## Corpora

The realistic false-positive generator is **not** a randomly wrong year. It is the caller whose MBID
is right and whose local tags came from a *different pressing of the same album* — which is the
ordinary case, because a scanned folder and a chosen MBID rarely come from the same source.

- `same_group.json` — for each release group, the caller supplies release `R`'s MBID while their
  local metadata is release `R'`'s, a different release in the **same** group. Every firing here is
  a **false positive**: same album, right identifier.
- `identical.json` — caller metadata is `R`'s own. A firing here is a false positive too, and a
  worse one; this pair cannot be wrong.
- `cross_group.json` — `R`'s MBID against the metadata of a different release group **by the same
  artist**. This is the case the ticket exists to catch, and `contradictsSuppliedName` provably
  cannot see it (the artist agrees). Catch rate is measured here, and reported as a floor only.

## Decision rule, also frozen

Ship an arm iff it scores **zero** false positives across `same_group` and `identical` combined.
Catch rate does not rescue an arm that fires on a correct pair: the ticket's whole point is that a
false contradiction is much worse than a missed one, because it tells a caller their correct
identifier is wrong and nothing else in the response disagrees.

If both arms are clean, ship both, OR-ed. If neither is, ship neither and report the numbers — a
measured refusal closes the ticket just as well as a fix.

## What this deliberately does not do

- No title comparison. `04` avoided it, `docs/pitfalls.md` §7 records why, and the structured
  evidence is being measured first precisely so the title never has to be reached for.
- No `confidence`/`matchScore` penalty. `CONTRADICTED` carries the finding.
- No corroboration. Neither rule failing to fire says the identifier is right.

---

# Results, 2026-08-25

Captured after the rules above were frozen. `capture_albums.py` -> `albums.json`: 99 artists, 181
studio release groups, 3237 releases (3139 of them dated and with an audio track count).

| arm | false positives | caught | ship |
|---|---|---|---|
| RULE-Y (year floor) | **0 / 3139 releases**, 0 / 109604 same-group pairs | 63 / 176 (36%) | yes |
| RULE-T (track band) | **31729 / 109604 same-group pairs (29%)** | 89 / 176 (51%) | no |

RULE-T lost on the decision rule written before the capture, and it is not close: a fifth to a
third of correct albums accused, from deluxe editions, bonus discs and region variants. Its higher
catch rate bought nothing, exactly as the decision rule said it could not.

`score_arms.py` reports the sampled variant (20 pairs per group, seed 20260825): 592 / 2942 for
RULE-T, 0 for RULE-Y. The full pair set is in `AlbumYearContradictionCorpusTest`, which now re-runs
both rules on every build.

## One measurement is vacuous, and says so

`drift` — releases dated before their own group's `first-release-date - 1` — came back **0 / 3237**.
That is not evidence. MusicBrainz derives `first-release-date` as the minimum over the group, so the
count is zero by construction and the probe could not have found anything. It stays in
`score_arms.py` with this note rather than being deleted, because a future reader would otherwise
re-derive it and mistake it for a result.

So the failure mode it was meant to test is **unmeasured**: a caller whose own year tag is two or
more years earlier than MusicBrainz's first release. Every year in the corpus comes from
MusicBrainz, so the corpus cannot contain that caller.

Two things bound the cost rather than a threshold tuned to hide it:

- The rule is one-sided. A caller's *later* year never fires, which is where reissues, remasters and
  region pressings all live. It gives up roughly half the catch rate to buy that.
- A false positive drops the identifier and resolves the request by its name. For a correct album
  and artist that finds the same album again, so the caller loses a status flag's accuracy, not
  their data.

## What shipped

`unlessPredatingFirstRelease`, chained after `unlessDifferentArtist` on the same release lookup.
`inc=release-groups` was already on that request, so the date it reads costs nothing; only the
parser needed a new field (`releaseGroupFirstReleaseDate`).

Not shipped, and still open: an album by the same artist whose year the caller has *right*. The
ticket's fallback was a title comparison; nothing here recommends attempting it.
