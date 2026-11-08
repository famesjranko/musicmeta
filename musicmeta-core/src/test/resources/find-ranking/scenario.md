# Find ranking response excerpts

Captured on 2026-11-07 before the ranking implementation, from the public JSON endpoints:

- `karma-pool.json`: MusicBrainz `/ws/2/recording`, `recording:"Karma Police"`, limit 100.
  First eight hits plus the first two disambiguated Radiohead hits from that same response.
- `karma-main.json`: same endpoint, `recording:"Karma Police" AND artistname:"Radiohead" AND -comment:*`, limit 100. First two hits.
- `creep-pool.json`: same endpoint, `recording:"Creep"`, limit 100. First three hits plus first four exact-title hits.
- `ok-pool.json`: MusicBrainz `/ws/2/release`, `release:"OK Computer"`, limit 100. First four hits.
- `karma-itunes.json`: iTunes `/search`, media music, entity song, term Karma Police, limit 25. First five hits.
- `ok-itunes.json`: iTunes `/search`, media music, entity album, term OK Computer, limit 25. First five hits.

Only the result array and these excerpts are retained; each result object is unchanged.
Tests may reverse arrays, combine captured pools, or select rows to isolate a ranking contract.
These captures establish response shape and the observed ordering, not current upstream coverage.

Additional captures on 2026-11-07, before changing track artist-hint acceptance:

- `creep-itunes.json`: iTunes `/search`, media music, entity song, term Creep, limit 25.
  First five results; the leading Radiohead result is `Creep (Acoustic)`.
- `creep-main.json`: MusicBrainz `/ws/2/recording`,
  `recording:"Creep" AND artistname:"Radiohead" AND -comment:*`, limit 100. First five results.

Result objects are unchanged; only the result-array excerpts are retained.
