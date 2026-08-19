# lrclib-qualifier-match

A request for `Starman - 2012 Remaster` / `David Bowie` finds a search hit that spells the same
qualifier with parentheses — `Starman (2012 Remaster)`. LRCLIB's qualifier-fallback matching accepts
the equivalent delimiter syntax, so `LYRICS_SYNCED` is a genuine `Success`, not a decline.

**Why this pool exists.** Unlike `lrclib-first-result` (a deliberately *wrong*-entity hit, so every
LRCLIB type declines), this pool's hit is the *right* entity. That makes `LYRICS_SYNCED` a real,
single-provider `Success` resolved through the engine's non-merged chain path
(`IdentityHelper.kt`'s `stampProvenanceOne`, not a merger's `weakestProvenance`), which is the
only way to observe `stampProvenanceOne` actually running: a merger's own `?: LookupProvenance.FUZZY_NAME`
fallback makes a merged result's provenance non-null regardless of whether the stamp ran.

## Provenance

`lrclib-search.json` is trimmed from
`musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/provider/lrclib/LrcLibProviderTest.kt:359-360`
(the search fixture inline in `search fallback accepts equivalent qualifier delimiter syntax`, same
file) — **that file's origin is unverified**, same caveat `lrclib-first-result/scenario.md` records:
its field names (`trackName`, `artistName`, `albumName`, `duration`, `instrumental`, `syncedLyrics`,
`plainLyrics`) match what `LrcLibApi.kt` reads, but the parser is not evidence for the pool. No
field name or value shape was changed from the source fixture; `syncedLyrics`/`plainLyrics` were
already the placeholder `"remastered lyrics"` in the source, not real lyric text, satisfying the
legal constraint. Trimmed 2026-08-16: whitespace only, already minimal (one candidate).

LRCLIB's actual field names are exercised against the live API by the daily `provider-drift.yml`
job (non-gating), same as `lrclib-first-result` records.

No `/api/get` (exact-match) stub is needed, for the same reason `lrclib-first-result` gives: an
unstubbed URL falls through to the search pool above.
