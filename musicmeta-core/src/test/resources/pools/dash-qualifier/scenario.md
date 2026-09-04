# dash-qualifier

Reproduces the #210 dash-qualifier defect: a track request spelling its reissue qualifier with a
dash (`Starman - 2012 Remaster`) must resolve through `MusicBrainzQualifierFallback`'s dash-form
step to the stripped `Starman` search, and — because a bare stripped title routinely ties a studio
recording against a live one — must pick the studio recording, not the live one, via the
blank-disambiguation tie-break (`MusicBrainzTrackEnrichment.pickBestRecording`'s `blankDisambiguation`
tier).

The original dash-decorated title's own search (`recording:"Starman - 2012 Remaster"...`) is left
entirely unstubbed on purpose, the same way `lrclib-first-result/scenario.md` leaves the LRCLIB
exact-match endpoint unstubbed: `FakeHttpClient` returns a 404 for an unstubbed URL, which
`bodyOrThrowTransient()` collapses to an empty list — the same "no exact hit" outcome a stubbed
empty body would produce, so the resolution falls through to the dash-fallback's stripped-title
search, `recording:"Starman"...`, which this pool answers.

## Provenance

`musicbrainz-recording-search.json` is composed from two in-repo fixtures, not trimmed from one —
record that plainly rather than letting the two sources read as a single trim:

- the `rec-studio` candidate (id, score, title, length, artist-credit) is trimmed from
  `musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/provider/musicbrainz/MusicBrainzQualifierFallbackIntegrationTest.kt:422-425`
  (the fixture backing `track dash-form qualifier fallback resolves via the stripped base title`,
  same file) — **that file's origin is unverified.** Its field names (`id`, `score`, `title`,
  `length`, `artist-credit`) match what `MusicBrainzParser.parseRecording` reads, but the parser is
  not evidence for the pool. `length` is load-bearing, not decorative: `PayloadAnswers.kt`'s
  `TrackMetadata` variant answers only when `durationMs`, `albumTitle` or `disambiguation` is
  non-blank, and the studio candidate deliberately carries no `disambiguation` (that absence is
  what the blank-disambiguation tie-break rewards) — omitting `length` too would make the engine's
  own `demoteUnanswered` turn a correct resolution back into `NotFound`.
- the `rec-live-1` candidate's shape (id naming, the `disambiguation` field carrying a
  `"live, <date>: <venue>"` phrase) is trimmed from
  `musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/provider/musicbrainz/MusicBrainzCanonicalRecordingSearchTest.kt:216-234`
  (`ALL_VARIANTS_POOL`, same file) — **that file's origin is unverified.** The date/venue text
  itself is not reused verbatim: the source's event was Metallica's, which does not describe a
  David Bowie recording, so a plausible Bowie tour date/venue was substituted while the field
  itself, and the fact that it is what makes `pickBestRecording`'s `blankDisambiguation` tier
  reject the candidate, are unchanged from the source shape.

MusicBrainz's actual field names are exercised against the live API by the daily `provider-drift.yml`
job (non-gating): `RealApiEndToEndTest.kt` drives real `forTrack` name-search requests (e.g.
`"Creep"`/`"Radiohead"`), and `MergedRecordingMbidE2ETest.kt`/`ProviderValidationTest.kt` also cover
MusicBrainz recording lookups, among the `com.landofoz.musicmeta.e2e.*` glob — so a drifted
recording-search field name would surface there, not from this trim.
