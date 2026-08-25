# lrclib-first-result

Reproduces the #210 LRCLIB defect: the `/api/search` pool's first hit is a wrong-entity match, and
the pre-fix provider took it by position. A request for `Song` / `David Bowie` finds `Alabama Song`
at index 0 of the search pool; the correct answer for `LYRICS_SYNCED`, `LYRICS_PLAIN` and
`TRACK_METADATA` is `NotFound`, not `Alabama Song`.

## Provenance

`lrclib-search.json` is trimmed from
`musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/provider/lrclib/LrcLibProviderTest.kt:422-425`
(the `ALABAMA_SONG_JSON` constant backing `search fallback rejects a wholly unrelated title for
every capable type`, same file) — **that file's origin is unverified.** It is an existing in-repo
raw string, not a captured response with a known request/response record; its field names
(`trackName`, `artistName`, `albumName`, `duration`, `instrumental`, `syncedLyrics`, `plainLyrics`)
match what `LrcLibApi.kt` reads, but the parser is not evidence for the pool — a fixture author and
a parser author sharing the same wrong field name would agree with each other undetected. No field
name or value shape was changed from the source fixture. `syncedLyrics`/`plainLyrics` were already
the placeholder `"x"` in the source, satisfying the legal constraint (no real lyrics text). Trimmed
2026-08-16: whitespace only, already minimal (one candidate).

LRCLIB's actual field names are exercised against the live API by the daily `provider-drift.yml`
job, which runs the `com.landofoz.musicmeta.e2e.*` glob (LRCLIB is referenced by 6 files there) —
non-gating by design (a failed scheduled run emails the maintainer; it gates no merge). That live
coverage, not this trim, is
what would surface a drifted field name; a provider with no live e2e coverage would need this same
note to say its derived pool's field names rest on nothing.

No `/api/get` (exact-match) stub is needed: `FakeHttpClient` returns
`HttpResult.ClientError(404, "No response configured")` for an unstubbed URL, and
`bodyOrThrowTransient()` collapses a `ClientError` to `null` — the same "no exact hit" outcome an
explicit empty-body stub would produce, so the provider falls through to the search pool above.

`lastfm-track-info.json` is trimmed from `TRACK_INFO_JSON` in
`musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/provider/lastfm/LastFmProviderTest.kt:608-617`
— **that file's origin is unverified**, same caveat as above. Last.fm's field names are exercised
against the live API by the same daily `provider-drift.yml` job (3 files under
`com.landofoz.musicmeta.e2e` reference Last.fm), non-gating. Used unchanged (values included — the
scenario's `TRACK_POPULARITY` rider does not depend on matching "Karma Police"/"Radiohead" to the
scenario's own "Song"/"David Bowie" request; `FakeHttpClient` matches by URL substring only, and
`EnrichmentData.Popularity` carries no title/artist for the identity rule to check). Its purpose on
this fixture is the provenance rider: Last.fm is one of the 8 (of 12
registered) providers that never set `LookupProvenance` on a `Success`, so requesting
`TRACK_POPULARITY` alongside the LRCLIB types gives the composed scenario one genuine `Success`
with `provenance == null` to exercise `every Success has provenance != null` against.
