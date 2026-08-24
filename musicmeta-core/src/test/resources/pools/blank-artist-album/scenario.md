# blank-artist-album

An album request carrying a title and **no artist**. MusicBrainz ignores an empty `artistname:`
term rather than rejecting it, so the search widens instead of failing: this pool's five candidates
are all titled `Greatest Hits`, all scored 100, and belong to four unrelated artists. The real
response reports `"count": 13987`.

Nothing downstream can recover from that. The release ranking's artist tier compares against the
requested artist, which is blank, so it scores every candidate alike and goes inert; the remaining
tiers — release type, status, score, edition band, year — then crown a winner that no evidence ties
to the caller's album, and it is reported as a resolved album at a search hit's confidence.

The pool is what proves the answer is not merely unranked but **absent**: whichever of these five
wins, the caller who meant a fifth artist's `Greatest Hits` gets a different record described to
them in full — genre, tracks, artwork and all.

## Provenance

Captured live from `https://musicbrainz.org/ws/2/release?query=release:"Greatest Hits" AND
artistname:""&fmt=json&limit=25` on 2026-08-25 — the exact query
[MusicBrainzApi.buildQuery] builds for a blank artist. **Trimmed to the first 5 of 25 returned
releases; no field name or value was changed**, and `count` is the upstream's own figure for the
whole result set, not the trimmed one.
