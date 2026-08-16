# itunes-album-pool

Reproduces the #210 iTunes defect: the only right-artist hit for an album request naming `Song` by
`David Bowie` is an unrelated Bowie collection (`The Rise and Fall of Ziggy Stardust`). The pre-fix
selection accepted on artist match alone; post-fix `itunesAlbumTitleTier` also rejects a
base-title mismatch (`ITunesAlbumSelection.kt:23`), so no album type may select this candidate.

`ALBUM_ART`, `ALBUM_METADATA` and `ALBUM_TRACKS` all resolve through one shared
`ITunesAlbumScope` per `enrich()` call (`ITunesProvider.albumScope`'s KDoc: "a call also asking
ALBUM_ART or ALBUM_METADATA resolves the same collection instead of ranking its own") — this
pool's rider-2 check reads that consistency off `requestedUrls`: the album search fires once for
all three types, not once each.

## Provenance

`itunes-search-album.json` is trimmed from
`musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/provider/itunes/ITunesProviderTest.kt:563-570`
(the fixture backing `enrich rejects a right-artist candidate whose title is unrelated, for all
three album types`, same file) — **that file's origin is unverified.** Its field names
(`resultCount`, `results`, `collectionId`, `collectionName`, `artistName`, `artworkUrl100`) match
what `ITunesApi`/`ITunesMapper` read, but the parser is not evidence for the pool. No field name or
value was changed from the source fixture.

iTunes's actual field names are exercised against the live API by the daily `provider-drift.yml`
job (non-gating) — `RealApiEndToEndTest.kt` and other `com.landofoz.musicmeta.e2e.*` files drive
real iTunes album search — so a drifted field name would surface there, not from this trim.
