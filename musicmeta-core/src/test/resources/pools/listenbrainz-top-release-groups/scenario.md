# listenbrainz-top-release-groups

One `popularity/top-release-groups-for-artist/<mbid>` response — the single request
`ARTIST_DISCOGRAPHY` makes of ListenBrainz.

**Why this pool exists.** ListenBrainz is the discography provider that carries no dates at all, so
it is the one that proves the engine's year ordering costs a dateless provider nothing: every album
it sends is undated, the sort is therefore the identity, and its listen-count-descending order has
to arrive at the caller intact. A hand-written fixture could not be evidence for either half — that
the payload really has no date field anywhere the mapper could reach, and that the upstream order is
a meaningful one worth preserving rather than an arbitrary one.

## Provenance

Captured live from
`https://api.listenbrainz.org/1/popularity/top-release-groups-for-artist/ac9a487a-d9d2-4f27-bb23-0f4686488345`
on **2026-09-05** — Lil Wayne, 1206 release groups, `total_listen_count` descending throughout.

Trimmed, never edited. Removed: 1200 of the 1206 entries; every key the parser does not read
(`release`, `release_color`, `tag`, the `release_group` object's `caa_id`, `caa_release_mbid`,
`date`, `rels` and `type`, the artist credit id, and each artist's `area`, `artist_mbid`,
`begin_year`, `gender`, `join_phrase`, `rels` and `type`). No field name or value was changed, and
the six kept entries are in the relative order ListenBrainz returned them.

**The upstream does carry a date, on `release_group.date`, and nothing reads it.** The parser has no
field for it and `ListenBrainzTopReleaseGroup` has nowhere to put it, so `DiscographyAlbum.year` is
null for every ListenBrainz entry by construction — which is why that date is trimmed here like any
other unread key.

This route is exercised against the live API by the daily `provider-drift.yml` job (non-gating),
pinned on this same artist and on the two paths `toDiscography` reads — `release_group_mbid` and the
nested `release_group.name`. The rest of the kept keys are warranted only by the capture above.
