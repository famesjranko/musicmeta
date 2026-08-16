# musicbrainz-artist-wikidata-and-members

A request for artist `Radiohead` finds one search hit that already carries a `wikidata` relation, so
`enrichArtist`'s `needsRelations` check (`best.wikidataId == null && best.wikipediaTitle == null`) is
false and identity resolution answers from the search hit alone — no full artist lookup. A second,
unrelated requested type (`BAND_MEMBERS`) still needs the full lookup, which MusicBrainz's own
memoization keys separately from the search, so it costs a second upstream call. That lookup response
carries a `member of band` relation for `BAND_MEMBERS` to resolve.

**Why this pool exists.** It is the fixture half of the enrich-timeout-drops-provenance regression:
the search hit's `country` field feeds an identity-resolution `Success` that identity write-through
copies straight into `COUNTRY` (`DefaultEnrichmentEngine.kt`'s `resolveIdentity`, gated
`type !in mergeableTypes`), and the second, delayed call is what lets a tight `enrichTimeoutMs` expire
mid-fan-out — after `COUNTRY` is already written, before `stampProvenance` runs. Two calls, one
undelayed and one paying `RateLimiter`'s ~1100ms interval, is exactly the shape the regression needs;
a single-call scenario has nothing left in flight for a timeout to interrupt.

## Provenance

Both fixtures are trimmed from constants already in the repo, at
`musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/provider/musicbrainz/MusicBrainzProviderTest.kt`:
`musicbrainz-artist-search.json` from `ARTIST_SEARCH_WITH_WIKIDATA` (`:970-985`), and
`musicbrainz-artist-lookup.json` from `ARTIST_LOOKUP_WITH_MEMBERS` (`:1009-1034`). **That source
file's origin is unverified** — its field names (`id`, `name`, `score`, `type`, `country`, `tags`,
`relations`, `direction`, `artist`, `attributes`, `begin`, `ended`) match what `MusicBrainzParser.kt`
reads, but the parser is not evidence for the pool, the same caveat every other pool in this directory
records. No field name or value was changed from the source constants; only whitespace was
reformatted (both were already minimal — one candidate, one lookup body).

MusicBrainz's actual field names are exercised against the live API by the daily
`provider-drift.yml` job (non-gating). Verified directly rather than assumed: 6 files under
`musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/e2e/` (the glob that job runs,
`com.landofoz.musicmeta.e2e.*`) reference MusicBrainz —
`RealApiEndToEndTest.kt`, `V060EdgeTest.kt`, `MergedRecordingMbidE2ETest.kt`,
`ProviderValidationTest.kt`, `ProviderDriftClosedLoopE2ETest.kt` and `E2ETestFixture.kt` (a shared
fixture, not a test class of its own) — so a drifted MusicBrainz field name is caught there, same
argument `lrclib-qualifier-match/scenario.md` makes for LRCLIB.

No `artist/<mbid>` stub beyond `art1` is needed: this scenario resolves exactly one artist and its
lookup is requested by no other id.
