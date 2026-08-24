# Artist name-contradiction corpus

Live captures from MusicBrainz, taken **2026-08-25, before the rule they score was written**.

`names_correct.json` — 99 artists from Last.fm's `chart.gettopartists`. The name and the MBID both
come from Last.fm, so each pair is correct by construction: **every contradiction reported here is a
false positive.** Captured by `.scratch/artist-mbid-provenance/capture_names.py`.

`names_script.json` — 7 hand-picked cross-script, diacritic, stylised and ampersand artists, where a
naive comparison is likeliest to cry wolf. The supplied name is what a caller would plausibly type;
the MBID is looked up from MusicBrainz's own canonical spelling rather than written down here, so no
invented constant can drift. Also all-false-positive territory.

Each row carries what `/ws/2/artist/{mbid}?inc=aliases` returned: `name`, `sort_name`, `aliases` and
`alias_sorts`. `ARTIST_LOOKUP_INC` already asks for `aliases`, so this is the response the enricher
makes anyway — the rule reads no field the library does not already have.

**Not in this corpus:** Korean. `방탄소년단` returned no MBID from a search on that spelling, so the
Hangul case is untested rather than passing.

`credits.json` — one real release per chart artist, recording the **artist-credit** MusicBrainz
prints on it beside the name a caller would have typed. Captured 2026-08-25 by
`.scratch/artist-mbid-provenance/capture_credits.py`, before the album and track guards were
written. A credit is not an artist name: MusicBrainz credits real releases to more than one act
(`Madison Acid and TV Girl` on a TV Girl release), and a release lookup carries **no aliases** at
all, so this surface is weaker evidence than the artist path's and is scored on its own.
