# musicbrainz-release-group-editions

One `release-group/<mbid>?inc=releases+artist-credits` lookup — the single request `RELEASE_EDITIONS`
makes, and the only route into the album types that is keyed on the release-*group* id rather than
the release id.

**Why this pool exists.** `enrichAlbumEditions` guards a caller-supplied release-group id against the
caller's own artist and, since this pool was added, against the caller's own year. The year guard
rests on one claim about the payload: a release-group lookup carries `first-release-date` at the top
level, without any `inc=` asking for it. A hand-written fixture cannot be evidence for that claim, so
this one is a real response.

## Provenance

Captured live from `https://musicbrainz.org/ws/2/release-group/5c14fd50-a2f1-3672-9537-b0dad91bea2f?fmt=json&inc=releases+artist-credits`
on **2026-08-25** — Radiohead, *Hail to the Thief*, `first-release-date` `2003-05-26`.

Trimmed, never edited. Removed: the group's `disambiguation`, `primary-type-id`,
`secondary-types`/`secondary-type-ids` (all empty or unread), the artist object's `sort-name`,
`type`, `type-id`, `country` and `disambiguation`, and 23 of the 25 releases. Each kept release keeps
only the fields `MusicBrainzCreditParser.parseReleaseGroupDetail` reads (`id`, `title`, `date`,
`country`, `barcode`) plus `status`; their `disambiguation`, `packaging`, `quality`,
`release-events`, `text-representation` and per-release `artist-credit` are dropped. No field name
or value was changed.

**A real response carries less than the parser looks for.** `parseReleaseGroupDetail` reads `media`
for an edition's format and `label-info` for its label and catalogue number, and neither is present
here: `media` needs `inc=media` and `label-info` needs `inc=labels`, and `lookupReleaseGroup` asks
for neither. So every `ReleaseEditions` entry MusicBrainz answers with has a null format, label and
catalogue number in production. The hand-written `RELEASE_GROUP_WITH_RELEASES_JSON` in
`MusicBrainzProviderTest.kt` carries both fields and so asserts a payload upstream does not send.
That is a separate defect from the guard this pool exists for, and is left standing here rather than
papered over by a fixture that agrees with the parser.

MusicBrainz's field names are exercised against the live API by the daily `provider-drift.yml` job
(non-gating), the same argument the other MusicBrainz pools in this directory make.
