# Streaming (progressive) results

`enrich()` waits for every requested type to settle before returning one `EnrichmentResults`.
`enrichProgressive()` runs the same resolution pipeline but emits a snapshot each time a type
settles, so a UI can paint fast types — a cache hit, a single-provider lookup — while slow ones are
still in flight. One further snapshot can arrive ahead of all of them: when identity resolution was
attempted and reaches its verdict before any type has settled, that verdict is emitted on its own,
with `raw` empty and every requested type still pending. A run that never attempts resolution — a
trusted identifier, identity disabled, no identity provider — emits no such snapshot. First useful
paint arrives well ahead of the complete answer: measured against the
demo over live providers on a cold cache in August 2026, two artists, one run each, the first
snapshot painted at 2.3s and 5.6s against 6.4s and 13.3s for every requested type to settle. Those
are single runs over third-party APIs, not a distribution — treat the ratio as the signal and expect
your own gap to depend on which types you request and how the providers behind them respond.

## Basic usage

```kotlin
engine.enrichProgressive(
    EnrichmentRequest.forArtist("Portishead"),
    setOf(EnrichmentType.ARTIST_BIO, EnrichmentType.ARTIST_PHOTO, EnrichmentType.SIMILAR_ARTISTS),
).collect { snapshot ->
    updateUI(snapshot.biography()?.text ?: "", snapshot.artistPhoto(), emptyList())
}
```

Each `snapshot` is a complete, valid `EnrichmentResults` — the same type you get back from `enrich()`,
never a partial diff. Every accessor (`snapshot.biography()`, `snapshot.albumArt()`, `snapshot.result(type)`)
works identically on an intermediate snapshot and on the terminal one.

## Deriving what is still pending

Nothing new was added to `EnrichmentResults` for this. A caller derives pending types itself — stand
in your own UI calls for `showSpinner`/`showEmpty`/`showResult` below:

```kotlin
fun showSpinner(type: EnrichmentType) = Unit
fun showEmpty(type: EnrichmentType) = Unit
fun showResult(type: EnrichmentType, result: EnrichmentResult) = Unit
```

```kotlin
engine.enrichProgressive(
    EnrichmentRequest.forArtist("Portishead"),
    setOf(EnrichmentType.ARTIST_BIO, EnrichmentType.SIMILAR_ARTISTS),
).collect { snapshot ->
    val stillLoading = snapshot.requestedTypes - snapshot.raw.keys
    for (type in snapshot.requestedTypes) {
        when {
            type in stillLoading -> showSpinner(type)
            snapshot.raw[type] == null -> showEmpty(type) // settled with no answer
            else -> showResult(type, snapshot.raw.getValue(type))
        }
    }
}
```

`requestedTypes - raw.keys` is the whole rule: a type in that difference is still resolving; a type
in neither `raw` nor that difference does not exist (only possible if it was never requested); a type
present in `raw` has settled, whether as `Success`, `NotFound`, `Error` or `RateLimited`.

**Only the last emission ever derives `requestedTypes - raw.keys` as empty.** (An identity-verdict
emission is the opposite case: `raw` itself is empty and the difference is everything requested.)
An intermediate emission where every requested type
already has a `raw` entry does not happen — the settlement that would complete the requested-types
set is suppressed entirely, and the terminal snapshot, built after post-processing (catalog
filtering, provenance stamping, stale-cache resolution) has run for every type, takes that
emission's place instead. So a collector that stops early on the first "nothing pending" read is
reading the real terminal snapshot, not a look-alike.

## What can still change mid-stream

A settled type's value is stable enough to render, but not frozen: cache write-back and other
post-processing that runs after a type's own emission can mean a later, unrelated call sees a
different value than what an early snapshot showed for it. There is also no ordering contract between
types — two runs of the same request can settle types in a different sequence, so do not build UI
that assumes, say, `ARTIST_BIO` always arrives before `SIMILAR_ARTISTS`.

Two values exist specifically to be read mid-stream:

- **`snapshot.identity.status` can be `CanonicalStatus.RESOLVING`** on an intermediate emission —
  identity resolution runs concurrently with everything else and has not necessarily finished when a
  fast type's own snapshot goes out. It is never `RESOLVING` on `enrich()`'s return or on this
  stream's terminal emission; by then resolution has always finished.
- **A recommendation-type `Success` can carry `isCatalogDegraded = true`**, meaning this call's own
  `CatalogProvider` threw and that type reached you unranked rather than filtered. This can be true
  on the terminal emission too, not just an intermediate one — it is not a "still settling" signal,
  it is a "settled, but filtering failed" signal. See [configuration.md](configuration.md) for
  `CatalogProvider`, and `EnrichmentResult.Success.isCatalogDegraded`'s KDoc for why this is
  call-scoped, not a stored fact.

```kotlin
engine.enrichProgressive(
    EnrichmentRequest.forArtist("Portishead"),
    setOf(EnrichmentType.SIMILAR_ARTISTS),
).collect { snapshot ->
    if (snapshot.identity.status == CanonicalStatus.RESOLVING) {
        showSpinner(EnrichmentType.ARTIST_BIO) // identity hasn't settled yet, keep waiting
    }
    val similar = snapshot.result(EnrichmentType.SIMILAR_ARTISTS)
    if (similar is EnrichmentResult.Success && similar.isCatalogDegraded) {
        showEmpty(EnrichmentType.SIMILAR_ARTISTS) // stand-in: an "unranked" indicator in a real UI
    }
}
```

## Batches

`enrichBatchProgressive` is `enrichBatch`'s progressive counterpart, composed from
`enrichProgressive`: each request in the list gets its own cumulative-snapshot stream, filled in as
that request's types settle, and requests are still processed one at a time (`enrichBatch`'s existing
sequential bound — concurrent fan-out across the whole batch would multiply upstream traffic the same
way collecting one `enrichProgressive` stream repeatedly would).

```kotlin
engine.enrichBatchProgressive(
    listOf(
        EnrichmentRequest.forArtist("Portishead"),
        EnrichmentRequest.forArtist("Massive Attack"),
    ),
    setOf(EnrichmentType.ARTIST_PHOTO, EnrichmentType.SIMILAR_ARTISTS),
).collect { (request, snapshot) ->
    val artist = (request as EnrichmentRequest.ForArtist).name
    updateUI(artist, snapshot.artistPhoto(), emptyList())
}
```

The same derivation rule (`snapshot.requestedTypes - snapshot.raw.keys`), cadence, and
complete-and-cache contract described above apply per request, unchanged.

## Cancellation is complete-and-cache, not abort-and-forfeit

This applies to `enrich()` too, not only to a streamed collection: `enrich()` is
`enrichProgressive(...).last()` over the same resolution path, so cancelling the coroutine that
called `enrich()` detaches from the fan-out exactly as cancelling a collector below does.

Cancelling collection — `take(1)`, leaving composition, a coroutine scope closing — detaches your
collector from the fan-out already under way. That fan-out is not aborted: it keeps running,
unattended, until it settles or `EnrichmentConfig.enrichTimeoutMs` expires, bounded to the one run for
that exact request/types/`forceRefresh` combination. Cancelling and re-issuing the same call
repeatedly does not multiply upstream traffic — every occurrence coalesces onto the one in-flight run.
The run's cache write-back still happens, so a subsequent equivalent call is typically a cache hit,
not a re-fetch.

There is no per-call abort — cancelling only detaches you, so a detached run still spends
rate-limiter and circuit-breaker budget while nobody is watching it, and `close()` is the only way
to actually stop in-flight work, engine-wide, never for one call or request.

This trades a small amount of work continuing after you stop watching it for cheap, correct
re-collection. The cost is bounded to whatever is genuinely still in flight when you cancel — never
unbounded — but it is real work, and it needs an owner at shutdown:

```kotlin
engine.close()
```

Call `close()` when you are done with the engine entirely (app shutdown, a `ViewModel`'s
`onCleared()`), not per collection. It is a hard shutdown, not a drain: any detached run still in
flight is abandoned rather than waited for, and — like a timed-out run — writes nothing back. A
still-attached collector, or a call issued after `close()` that this engine had never seen before, is
released with every requested type present: types that had already settled keep their real result,
and every type that had not becomes `EnrichmentResult.Error` with `ErrorKind.ENGINE_CLOSED` — the same
per-type completeness `enrich()` already gives you on a timeout, just a different `ErrorKind`. A fully
cached call never reaches this at all: it returns its cache hit and succeeds normally even after
`close()`, without ever registering a detached run — so the check below assumes `ARTIST_BIO` for
this artist was not already cached.

```kotlin
val afterClose = engine.enrich(EnrichmentRequest.forArtist("Portishead"), setOf(EnrichmentType.ARTIST_BIO))
val bio = afterClose.result(EnrichmentType.ARTIST_BIO)
check(bio is EnrichmentResult.Error && bio.errorKind == ErrorKind.ENGINE_CLOSED)
```

## Third-party `EnrichmentEngine` implementations

`enrichProgressive` and `enrichBatchProgressive` are defaulted interface methods. An `EnrichmentEngine`
that overrides only `enrich()` gets a working default for both: `enrichProgressive` emits exactly one,
terminal snapshot (`enrich()`'s own return, wrapped), and `enrichBatchProgressive` emits exactly one
terminal snapshot per request in turn. Both defaults are correct, just not incremental — a caller
cannot assume more than one emission per request from any `EnrichmentEngine` it did not build itself.
