# Search selection regressions

Live captures on 2026-11-07, before this change:

- `apostrophe.json`: excerpts from MusicBrainz `/ws/2/recording`, query
  `recording:"Don't Stop Believin'"`, limit 100. The Journey record
  `d4b485be-f2be-4c97-9315-c57ab013c537` has curly apostrophes and official album evidence;
  Chris Phillips has straight apostrophes. Complete selected result objects are unchanged.
- `apostrophe-canonical.json`: the same captured Journey object, used for the artist query.
- `apostrophe-itunes.json`: the first five results of iTunes song search for the straight title,
  limit 25. Complete selected result objects are unchanged.
- `marked.json`: excerpts of three objects from MusicBrainz recording search for `HUMBLE.`,
  limit 100: Kendrick Lamar's unmarked take, the `explicit` take
  `398bf241-7d57-4993-860b-1f9ef496c97f`, and an unmarked TEARY EYED FOX cover. Their release lists are omitted;
  this case tests artist and disambiguation order, not release evidence.
- `marked-canonical.json`: the unmarked Kendrick object from that same capture.
- `duplicates.json` and `canonical.json`: excerpts of the earlier captured Karma Police pools
  in `find-ranking/`, with two visibly identical main recordings and a marked live choice.

Controlled models, not live captures:

- `album-variants.json` clones the earlier captured Radiohead release schema and independently
  varies ID, year, country, and primary type to discriminate which fields are retained.
- `album-partial.json` models a live-qualified Radiohead album alongside the captured other
  artist's exact title. `album-exact.json` supplies the exact Radiohead title for the second query.
- `exact-scores.json` varies two captured recording schemas' IDs and scores, retaining identical
  title, artist and other presentation fields.
- `fuzzy.json` models the reported score-51 official-album `Sandman Enters` versus score-100
  `Enter Sandman` with a disambiguation. A live reproduction attempt returned HTTP 503;
  this fixture does not claim a live-capture chain.

The typography hint test constructs minimal iTunes-shaped rows to isolate U+2010/U+2011,
U+2026, curly double quotes, U+2032 and U+02BC.
The marked-album test gives the modelled `album-exact.json` row a different ID and an edition
disambiguation.
The fake signal tests model transport failures, stalls, unavailability, and breaker state.
Request-count tests reuse captured objects to model nonempty query arms; they do not claim captures
for the bracketed title or the unavailable album hint used to exercise those paths.
These captures and models establish offline contracts, not ongoing live API coverage.
