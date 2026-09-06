# similar-artist-disambiguation

The two routes a `SimilarArtist.disambiguation` can come from: ListenBrainz Labs' `similar-artists`,
whose rows carry MusicBrainz's `comment`, and the batched MusicBrainz `arid:` search that names the
entries Labs left undescribed.

**Why this pool exists.** Both halves of the field rest on claims about live payloads that no
hand-written fixture can be evidence for:

- that Labs really sends `comment`, and really sends it **blank for a large share of rows** — which
  is what decides the pin grammar. A `[0].comment` pin would report drift whenever the route
  reordered onto an undescribed neighbour, so the pin uses `[*]`, and `labs-similar-artists-bjork`
  is the live capture proving that `[0]` alone would flap;
- that one `artist?query=arid:A OR arid:B…` search returns every named artist's `disambiguation` in
  a single response, which is the whole cost argument for the batch over a lookup per id.

## Provenance

**`labs-similar-artists-radiohead.json`** — captured live from
`https://labs.api.listenbrainz.org/similar-artists/json?artist_mbids=a74b1b7f-71a5-4011-9441-d0b5e4122711&algorithm=session_based_days_7500_session_300_contribution_5_threshold_10_limit_100_filter_True_skip_30`
on **2026-09-06**, for the frozen A/B in
`.scratch/features/prototypes/09-similar-artist-disambiguation/`. 100 rows, 45 of them carrying a
non-blank `comment`.

Trimmed to the first six rows and re-indented; no key or value was altered and the six are in the
order Labs returned them. Radiohead is the artist the `labs similar artists` schema pin is aimed at,
so this is that pin's own route answering for its own subject. Its first row *is* described, which
is why the second capture is here.

**`labs-similar-artists-bjork.json`** — the same route and algorithm on the same day, for
`f22942a1-6f70-4f48-866e-238cb2308fbd`. 100 rows, 47 with a non-blank `comment`, and **the first
three rows undescribed**. Trimmed to the first six rows and re-indented, values verbatim, order
preserved.

This is the capture that settles the grammar. It is a healthy response — Labs is sending `comment`,
and the mapper reads it correctly for the rows that have one — and a pin written `[0].comment` calls
it drift. `[*].comment` does not, and still fails if the field leaves the payload.

**`musicbrainz-arid-batch.json`** — captured live from
`https://musicbrainz.org/ws/2/artist?query=arid:… OR …&fmt=json` on **2026-09-06** by
`.scratch/features/prototypes/09-similar-artist-disambiguation/musicbrainz-capture/capture.sh`,
naming the four split-pair MBIDs that ListenBrainz Labs did not describe. Re-indented; no key or
value altered.

All four asked-for ids came back, each with its own `disambiguation`, in one request — the
measurement behind taking the batch over a lookup per id (2 requests over the twelve-artist
workload against 4, and 1 in the worst enrich against 3).

Every capture predates the code it is evidence for: they were taken for the pre-registered A/B that
chose this design, not written to agree with it.
