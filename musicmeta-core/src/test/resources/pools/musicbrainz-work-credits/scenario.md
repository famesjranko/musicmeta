# musicbrainz-work-credits

Pins the `bugs/01` defect: `CREDITS` lost every songwriter, composer and lyricist that MusicBrainz
models on the *work* rather than the recording — for every recording, on every call.

`RECORDING_LOOKUP_INC` asked for `work-rels`, which returns the work as a **stub**: id and title,
no `relations`. `MusicBrainzCreditParser` then read `work.relations` and `?: continue`d past the
miss, so the loop that adds writer credits never ran once. Adding `work-level-rels` inlines the
work's own relations into the same response, at no extra request.

## Why the fragment is `work-level-rels`

The manifest matches on the one substring the fixed request adds. A request that does not ask for
`work-level-rels` matches no stub at all, so `MusicBrainzWorkCreditsTest` fails for the reason the
bug exists rather than by asserting on a parsed value — the request shape *is* the defect.

Two fixtures, one per `inc=`, is not possible here: `UpstreamPools` rejects overlapping fragments,
and the old `inc` string is a prefix of the new one, so any fragment matching the old also matches
the new. The pre-fix response is kept as probe evidence instead, outside the shipped tree, at
`.scratch/musicbrainz-work-credits/prototypes/`.

## Provenance

`musicbrainz-recording-work-inlined.json` was captured live on **2026-08-24** from:

```
https://musicbrainz.org/ws/2/recording/cab6f522-4fdf-41bb-b3af-5b93b899d062?fmt=json
  &inc=artist-rels+work-rels+artists+releases+release-groups+isrcs+tags+genres+ratings+work-level-rels
```

That is recording `cab6f522-4fdf-41bb-b3af-5b93b899d062`, "Karma Police" — the same recording the
original 2026-08-11 finding was verified against. The response was 10,600 bytes; what is stored here
is trimmed to `id`, `title` and `relations`, the only keys the credits path reads. The dropped keys
(`releases`, `release-groups`, `tags`, `genres`, `ratings`, `isrcs`) are parsed by other code with
its own fixtures, and duplicating them here would create a second copy nobody maintains.

Nothing in this file was hand-written or adjusted to match our parser. The chain back to the live
capture is the probe in `.scratch/musicbrainz-work-credits/prototypes/probe_inc_arms.py`, which is
re-runnable — the byte counts and writer names in its `results.md` decay, the recipe does not.

**Contrast with `MusicBrainzParserTest.RECORDING_WITH_WORK_REL`**, which is hand-written JSON whose
`work` object carries a nested `relations` array. No pre-fix MusicBrainz response looked like that,
so that fixture covered the writer branch, stayed green, and proved nothing for the whole time the
feature was broken. It is now accurate for a `work-level-rels` response, but this pool is the one
with a verified chain to the wire.
