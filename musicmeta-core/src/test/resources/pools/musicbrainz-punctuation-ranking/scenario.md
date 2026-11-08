# MusicBrainz punctuation ranking

These are modeled response envelopes built from unchanged rows in live captures
taken before this fix. The envelopes combine rows from different captures where
the tests need a smaller pool. They do not claim that the combined responses
were returned by MusicBrainz.

- `help-punctuation.json` contains:
  - `fa2b2af5-3ba2-4155-b1d9-45066322edc5` (`Help!`, The Beatles), copied from
    capture `01-help-beatles.json`, captured at `2026-11-08T10:31:44.910690+00:00`.
    Source URL: `https://musicbrainz.org/ws/2/recording?query=recording%3A%22Help%22+AND+artistname%3A%22The+Beatles%22&fmt=json&limit=100`.
  - `73f6f089-eda1-4092-87e7-8ae699000bd6` (`Help`, Bride), copied from capture
    `14-help-title.json`, captured at `2026-11-08T10:34:24.484832+00:00`.
    Source URL: `https://musicbrainz.org/ws/2/recording?query=recording%3A%22Help%22+AND+artistname%3A%22%22&fmt=json&limit=100`.
- `help-exact.json` contains the Beatles rows from capture `01-help-beatles.json`:
  - `fa2b2af5-3ba2-4155-b1d9-45066322edc5` (`Help!`), captured at
    `2026-11-08T10:31:44.910690+00:00`.
  - `f9c045cc-6703-4787-8145-8a29d4afd243` (`Help`), captured at
    `2026-11-08T10:31:44.910690+00:00`.
  Source URL for both rows: `https://musicbrainz.org/ws/2/recording?query=recording%3A%22Help%22+AND+artistname%3A%22The+Beatles%22&fmt=json&limit=100`.
- `sos.json` contains:
  - `31e2ea49-a493-4529-a64e-c1fa948cffcf` (`S.O.S.`, ABBA), copied from
    capture `05-sos-abba.json`, captured at `2026-11-08T10:32:38.482840+00:00`.
    Source URL: `https://musicbrainz.org/ws/2/recording?query=recording%3A%22SOS%22+AND+artistname%3A%22ABBA%22&fmt=json&limit=100`.
  - `3afebdda-23b3-4ead-9f2d-4144666234d2` (`SOS`, Bootleg ABBA), copied from
    capture `12-sos-canonical.json`, captured at `2026-11-08T10:34:04.123856+00:00`.
    Source URL: `https://musicbrainz.org/ws/2/recording?query=recording%3A%22SOS%22+AND+artistname%3A%22ABBA%22+AND+-comment%3A%2A&fmt=json&limit=100`.

The tests use these rows to prove candidate ordering. They do not measure live
MusicBrainz coverage, parser performance, or current upstream ordering.
