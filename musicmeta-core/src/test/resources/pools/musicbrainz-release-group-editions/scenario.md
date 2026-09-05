# musicbrainz-release-group-editions

One `release?release-group=<mbid>` browse — the single request `RELEASE_EDITIONS` makes, and the
only route into the album types that is keyed on the release-*group* id rather than the release id.

**Why this pool exists.** `enrichAlbumEditions` guards a caller-supplied release-group id against
the caller's own artist and against the caller's own year, and then answers with each release's
format, label and catalogue number. Both rest on claims about the payload that a hand-written
fixture cannot be evidence for: that a browse carries the group's `artist-credit` and
`first-release-date` inside every release rather than at the top level, and that `inc=labels+media`
fills the three `ReleaseEdition` fields at all. So this one is a real response.

## Provenance

Captured live from `https://musicbrainz.org/ws/2/release?release-group=5c14fd50-a2f1-3672-9537-b0dad91bea2f&fmt=json&inc=release-groups+artist-credits+labels+media&limit=100`
on **2026-08-25** — Radiohead, *Hail to the Thief*, 27 releases, `first-release-date` `2003-05-26`.

Trimmed, never edited. Removed: 25 of the 27 releases; the top-level `release-count` and
`release-offset`; each kept release's `asin`, `cover-art-archive`, `disambiguation`, `packaging`,
`packaging-id`, `quality`, `release-events`, `status-id` and `text-representation`; every `media`
key but `format`; every `label-info` key but `catalog-number` and the label's `name`; every
artist object key but `id` and `name`; the release group's `disambiguation`, `primary-type`,
`primary-type-id`, `secondary-types` and `secondary-type-ids`; and the second release's
`release-group` object entirely. No field name or value was changed.

**The guard fields are per-release now.** A browse answers with releases and never with the group
at top level, so the group's `id`, `title`, `first-release-date` and `artist-credit` are read off
`releases[0].release-group` — kept here on the first release and dropped from the second, which is
what makes the pool prove that one copy is enough.

The two kept releases differ in both fields the old route could never carry: a Cassette on Capitol
Records (`C4 7243 5`) and a 12" Vinyl on XL Recordings (`XLLP785`).

This exact request is exercised against the live API by the daily `provider-drift.yml` job
(non-gating): the pin is built from the same release group and asserts every field
`parseReleaseBrowse` reads off the first release, the group carried inside it included.
