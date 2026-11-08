# Candidate artist-ranking regressions

These are modelled pools, not live captures. The two source pools predate this fix:
`MusicBrainzWrongArtistTrackRankingTest.POOL_TIED_WRONG_ARTIST_FIRST` and
`POOL_TIERED_WRONG_ARTIST_WINS`. The review reproduction supplied JSON copies of those pools.
Their structured credits and release shapes follow the repository's earlier modelled fixtures;
their chain back to a complete live search response is unverified.

- `tied-tracks.json` copies the tied Queen/David Bowie and Joss Stone recording pool.
- `supplied-tracks.json` copies the tiered pool and adds a modelled qualified Queen title to pin
  exact-title priority above artist preference.
- `canonical-tracks.json` contains that added unmarked recording for the filtered query.
- `supplied-albums.json` projects the tiered pool's release objects into a modelled release search,
  carrying each recording's title, score, structured credits and disambiguation onto its release.
- `tribute-tracks.json` replaces the cover's artist credit with a modelled Killer Queen act.

These tests prove ranking contracts across the real parser and provider, not live API coverage.
