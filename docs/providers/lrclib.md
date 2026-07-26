# LRCLIB

What our code does with LRCLIB. For the API itself — endpoints, request shapes, error codes, rate
limits — follow the upstream link; that is authoritative and this is not.

| | |
|---|---|
| **Package** | `provider/lrclib/` |
| **Provider ids** | `lrclib` |
| **Upstream API docs** | https://lrclib.net/docs |
| **Auth** | None |
| **Deviations from the house pattern** | None — the four files, as `CLAUDE.md` describes them |

**Why this provider.** It is the only lyrics source in the tree, and the only provider whose
capabilities are track-level and nothing else. Free, no key, community-submitted.

## What We Extract

One row per entry in `LrcLibProvider.capabilities`. The two lists are compared by
`ProviderFeatureDocsTest` on every `./check`.

| EnrichmentType | Request | Upstream call | What we keep |
|---|---|---|---|
| `LYRICS_SYNCED` | `ForTrack` | `/api/get`, then `/api/search` | `syncedLyrics`, `plainLyrics`, `instrumental` |
| `LYRICS_PLAIN` | `ForTrack` | `/api/get`, then `/api/search` | `syncedLyrics`, `plainLyrics`, `instrumental` |

**Both rows are the same request and the same payload.** `enrichTrack` never branches on `type`, so
asking for `LYRICS_PLAIN` returns synced lyrics too when the track has them, and asking for
`LYRICS_SYNCED` returns a plain-only result rather than `NotFound`. The comment in
`toEnrichmentResult` is explicit that this is deliberate: the caller decides whether plain is an
acceptable fallback. Requesting both types costs two identical round trips.

Two calls, in order, with different confidence:

1. `/api/get` with artist, track, and — when the request carries them — album and duration in
   *seconds*, converted from `durationMs`. A hit scores `ConfidenceCalculator.authoritative()`, 0.95.
2. On a miss, `/api/search` on artist and track only; we take `firstOrNull()` and score it
   `fuzzyMatch(hasArtistMatch = false)`, 0.6. Nothing re-checks that the first search result is the
   track that was asked for — it clears `filterByConfidence()`'s 0.5 floor on the API's ranking alone.

A result with `instrumental = false` and both lyrics fields blank becomes `NotFound`;
`instrumental = true` with no lyrics is a `Success`, which is the correct reading of the field.

## What We DON'T Extract

Parsed into `LrcLibResult` and then dropped by the mapper — the API returns them on every call we
already make:

| Field | Useful for |
|---|---|
| `id` | LRCLIB's own id; `GET /api/get/{id}` would re-fetch without a search |
| `trackName`, `artistName` | Verifying that the search fallback returned the right track |
| `albumName` | The same, at album level |
| `duration` | The same, and the strongest signal of a mismatched match |

Endpoints we never call: `GET /api/get/{id}`, and the write path — `POST /api/request-challenge` and
`POST /api/publish`, which need a proof-of-work solution and would make this a write client.

## Gotchas

- `docs/pitfalls.md` §3 — `parseResult` reads everything through `optString`/`optDouble`, so a
  renamed field yields `""`, and `""` is then dropped by the mapper's `takeIf { it.isNotBlank() }`.
  A response whose shape moved reads as a track with no lyrics, not as a failure.
- `docs/pitfalls.md` §4 — a non-`ForTrack` request, an empty search, and a blank-lyrics result all
  return `NotFound`, so they record breaker *success*.
- `docs/pitfalls.md` §5 — no capability declares an `identifierRequirement`, which is right here:
  LRCLIB is searched by name, never by MBID.

Ours, and not a pitfall: synced lyrics arrive as one LRC string (`[mm:ss.cc] line`). We do not parse
it; `EnrichmentData.Lyrics.syncedLyrics` hands the consumer the raw LRC body.
