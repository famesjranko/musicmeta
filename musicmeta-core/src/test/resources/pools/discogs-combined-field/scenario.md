# discogs-combined-field

Reproduces the #210 Discogs defect: Discogs names an artist and a release title in one combined
`"Artist - Title"` search field, not two structured ones. The only hit for an album request naming
`Song` by `David Bowie` combines the requested artist with an unrelated title — `Alabama Song`
under the `David Bowie -` prefix. The pre-fix selection parsed and accepted the artist half alone;
post-fix `selectRelease` also requires `TitleMatcher.equivalent` on the parsed title half before
accepting (`DiscogsAlbumSelection.kt:75`), so no album type may select this release.

`ALBUM_ART`, `LABEL`, `RELEASE_TYPE` and `ALBUM_METADATA` all resolve through one shared
`DiscogsAlbumScope` per `enrich()` call (`DiscogsProvider.albumScope`'s KDoc: "so no two of them
search or select an album independently in one `enrich()` fan-out") — this pool's rider-2 check
reads that consistency off `requestedUrls`: the release search fires once for all four types, not
once each. `ALBUM_TRACKS` is not one of the four: Discogs declares no `ALBUM_TRACKS` capability
(`DiscogsProvider.kt`'s `capabilities` list), so the rider is checked over the types this provider
actually shares its scope across, not the literal three the rider names for a provider that has
all three.

## Provenance

`discogs-search.json` is trimmed from
`musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/provider/discogs/DiscogsProviderTest.kt:424-434`
(the fixture backing `enrich rejects an exact-artist candidate whose title is unrelated, for every
album type`, same file) — **that file's origin is unverified.** Its field names (`results`, `id`,
`title`, `label`, `year`, `cover_image`) match what `DiscogsApi`/`DiscogsMapper` read, but the
parser is not evidence for the pool. No field name or value was changed from the source fixture.

Discogs's actual field names are exercised against the live API by the daily `provider-drift.yml`
job (non-gating) — `V060EdgeTest.kt` registers and drives a real `DiscogsProvider` among the
`com.landofoz.musicmeta.e2e.*` glob — so a drifted field name would surface there, not from this
trim.
