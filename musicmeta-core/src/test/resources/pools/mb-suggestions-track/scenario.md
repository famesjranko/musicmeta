# mb-suggestions-track

Reproduces the #210 global-veto defect: when MusicBrainz's own canonical recording search misses
and offers near-miss suggestions, the pre-fix engine skipped every other provider's fan-out
entirely and stamped a manufactured `NotFound(provider = "engine", suggestions = ...)` onto every
requested type instead of asking them. Post-fix, MusicBrainz's suggestions live once at
`EnrichmentResults.identity`, and every other eligible provider still runs its own search and
reports its own honest verdict under its own provider id.

A request for `Enter Sandman` / `Metallica` (no identifier) ties MusicBrainz's canonical,
`-comment:*`-filtered recording pool to two candidates that score below the acceptance floor (so
`canonicalPool` returns a non-empty-but-rejected list rather than an empty one — deliberately: an
empty canonical list would fall through to the unfiltered `shallowPool`, which shares this pool's
`recording?query` fragment and would re-introduce the very candidates this pool holds back for the
*suggestions* step only). The unfiltered `recording?query` search — reached only from the
miss-suggestion path, since the title carries no bracket or dash qualifier for the dash-fallback
step to retry — answers with two near-miss recordings, which become
`EnrichmentResults.identity.suggestions`.

## Provenance

Both `musicbrainz-recording-canonical.json` (the `WEAK_POOL` shape: two below-floor candidates) and
`musicbrainz-recording-suggestions.json` (the `ALL_VARIANTS_POOL` shape, trimmed to two candidates
with the `releases` sub-object removed, since `MusicBrainzTrackEnrichment`'s
`MusicBrainzRecording.toCandidate` reads only `id`,
`score`, `title`, `artist-credit` and `disambiguation`) are trimmed from
`musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/provider/musicbrainz/MusicBrainzCanonicalRecordingSearchTest.kt:202-234`
(the `WEAK_POOL` and `ALL_VARIANTS_POOL` constants backing
`a miss suggests from the pool a consumer chooses out of, not the filtered one`, same file) —
**that file's origin is unverified.** Its field names (`id`, `score`, `title`, `disambiguation`)
match what `MusicBrainzParser.parseRecording` reads, but the parser is not evidence for the pool.
No field name or value was changed from the source fixture. `artist-credit` was later backfilled
into both pools and into `WEAK_POOL`/`ALL_VARIANTS_POOL` together, in the same change, so the two
stayed in lockstep rather than drifting apart — the origin remains unverified either way.

MusicBrainz's actual field names are exercised against the live API by the daily
`provider-drift.yml` job (non-gating) — `RealApiEndToEndTest.kt` drives real `forTrack` name-search
requests among the `com.landofoz.musicmeta.e2e.*` glob — so a drifted recording-search field name
would surface there, not from this trim.

No fixture is stubbed for LRCLIB: its search endpoint is deliberately left unanswered, so its own
honest `NotFound(provider = "lrclib")` — not a manufactured `NotFound(provider = "engine")` — is
what proves it was actually asked rather than short-circuited.
