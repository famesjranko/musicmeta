# musicbrainz-undated-release-group

One `release-group?artist=<mbid>` browse — the single request `ARTIST_DISCOGRAPHY` makes of
MusicBrainz, and the only route into a discography that carries `first-release-date`.

**Why this pool exists.** The engine orders a discography by year and places undated albums after
every dated one, keeping the provider's order within a year and across the undated block. Both
halves rest on claims about the payload that a hand-written fixture cannot be evidence for: that
MusicBrainz really does return release groups with `first-release-date` absent, that it sends the
absence as an empty string rather than a missing key, and that the browse order is not already
chronological — a fixture that arrived sorted would let the sort be deleted and still pass.

## Provenance

Captured live from
`https://musicbrainz.org/ws/2/release-group?artist=148ddea2-6839-4354-8e2c-5dfadf136b7f&type=album%7Cep%7Csingle&fmt=json&limit=100&offset=0`
on **2026-09-05** — Tenacious D, 46 release groups, 10 of them with an empty `first-release-date`.

Trimmed, never edited. Removed: 39 of the 46 release groups; each kept group's `disambiguation`,
`primary-type-id`, `secondary-types` and `secondary-type-ids`; and the top-level `release-group-count`
and `release-group-offset`. No field name or value was changed, and the seven kept groups are in the
relative order MusicBrainz returned them.

**The order MusicBrainz sent is the point.** Those seven run 2006, 1994, undated, 2001, 2002,
undated, 2002 — ascending release-group MBID, which is what the browse actually orders by and what
carries no date, title or type signal. Two groups share 2002 (`D Homemade` before `D Fun Pak`), so
the pool also pins that a within-year tie keeps the provider's order rather than being broken on
title, which would put `D Fun Pak` first.

This route is not in `SCHEMA_PIN_TARGETS`, so no daily job re-checks that these field names are still
live — unlike the other MusicBrainz pools in this directory, this pool's field names are warranted
only by the capture above.
