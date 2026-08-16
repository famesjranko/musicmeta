# deezer-loose-artist

Reproduces the #210 Deezer defect: the only right-artist hit for a request naming `Song` by
`David Bowie` is a wholly unrelated, differently-titled recording (`Song for Bob Dylan
(2015 Remaster)`) that happens to share nothing but the artist and a remaster qualifier. The
pre-fix provider accepted on artist match alone; post-fix it also requires
`TitleMatcher.equivalent` before accepting, so this pool must produce `NotFound`, not a confident
answer about the wrong song.

Both of Deezer's track-search queries (the advanced field query, then the plain keyword query)
build against `/search/track?q=`, so one stub answers each attempt the provider makes.

## Provenance

`deezer-search-track.json` is trimmed from
`musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/provider/deezer/DeezerApiSearchTrackTest.kt:277-280`
(the fixture backing `rejects a right-artist candidate whose title is a wholly unrelated
recording`, same file) — **that file's origin is unverified.** Its field names (`data`, `id`,
`title`, `artist`, `name`) match what `DeezerApi.searchTrack` reads, but the parser is not evidence
for the pool. One field was added beyond the source, not changed: `"duration": 245`. It is
load-bearing, not decorative — `PayloadAnswers.kt`'s `TrackMetadata` variant answers only when
`durationMs`, `albumTitle` or `disambiguation` is non-blank, and without it the engine's own
`demoteUnanswered` turns *any* candidate, right title or wrong, into `NotFound` before the
title-acceptance filter this pool exists to exercise is ever reached — masking the mutation this
scenario is built to catch rather than testing it. Measured directly: the mutation check recorded
in this child's commit went green with this scenario unchanged until `duration` was added.

Deezer's actual field names are exercised against the live API by the daily `provider-drift.yml`
job (non-gating) — `RealApiEndToEndTest.kt` and other `com.landofoz.musicmeta.e2e.*` files drive
real Deezer track/album search — so a drifted field name would surface there, not from this trim.
