# Held-out workload — chosen 2026-09-06, before any arm ran and before any rule was frozen

Twelve artists none of the Phase 1 numbers were derived from, picked for spread across genre, era
and script rather than for anything the frozen twelve showed. Written down before the first capture
request, so the list cannot have been steered by what the captures returned.

| # | Artist | Why it is on the list |
|---|---|---|
| 1 | BLACKPINK | K-pop |
| 2 | Claude Debussy | classical composer |
| 3 | A Tribe Called Quest | 90s hip-hop |
| 4 | The Bad Plus | jazz trio |
| 5 | Trouble | metal band whose name is an ordinary English word |
| 6 | Кино | non-Latin script |
| 7 | Alison Krauss | country |
| 8 | Four Tet | electronic producer |
| 9 | Shakira | Latin pop |
| 10 | The Zombies | 60s band |
| 11 | Alvvays | indie act, one-word name |
| 12 | Nico | solo artist whose name several bands also carry |

Captured the same three ways the frozen workload was: Last.fm `artist.getSimilar` (limit 20),
ListenBrainz Labs `similar-artists` on the artist's MBID, Deezer `/artist/{id}/related`. Raw bodies,
with the query and the date, in `fixtures/<slug>/`.
