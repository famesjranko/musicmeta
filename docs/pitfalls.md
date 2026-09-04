# Pitfalls

Each of these cost a release, an issue, or a backfilled `### Breaking Changes` entry. `CLAUDE.md`
carries the one-line rule; this file carries the worked example and the reason. Read the entry before
touching the thing it names.

Pitfalls are grouped under `## Area` headings; `CLAUDE.md`'s Read-first table routes each area.
A pitfall's number is permanent — code comments cite `docs/pitfalls.md §N` — so a new pitfall
takes the next free number and goes under whichever area fits, in any order. If no area fits,
add one and give it a routing row in `CLAUDE.md`.

## Area — Traps in the pipeline

Read `enrich()` in `engine/DefaultEnrichmentEngine.kt` — it is the map, and this list is not
exhaustive. Paths are relative to `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/`.

- `CacheGuard.kt` degrades a throwing cache to a miss, but public `invalidate()`,
  `is`/`markManuallySelected()` and `getIncludingExpired()` are unguarded.
- Canonical identity resolution, provider eligibility, and cache eligibility are three different
  facts. An identity `NotFound` carrying `suggestions` does not veto the fan-out — every provider
  still runs its own `ProviderChain` eligibility check — and a chain that skipped a provider for a
  missing identifier (`ChainExecution.identifierIncomplete`) must not be negative-cached even under
  a `RESOLVED` identity, since a provider that was never asked cannot speak for the chain. The
  same reasoning bars any `NotFound` a `CatalogFilterMode` produces by emptying a `Success`
  (`RunSession.filterEmptied`; the stale-substitute case rides on `RunSession.staleDerived`, set
  when the substitution itself fires): that emptiness is a fact about the local catalog, never a
  provider's own answer, whether the `Success` it emptied was this call's own live fan-out, a
  `STALE_IF_ERROR` substitute, or a fresh cache hit re-filtered on a later call. A
  `CompositeSynthesizer` inherits the same problem one layer removed: it reads its dependencies
  through `SettlementBoard.await`, which hands back a dependency's finalized value — a stale
  substitute or a filter-emptied `NotFound` — with no marker of where that value came from, so
  both facts are propagated onto the composite type in `synthesizeComposite` (one tainted
  dependency among fresh ones is enough) or its own `NotFound` negative-caches too.
- A cache read that reports a live entry as a *miss* so the write-back can heal it
  (`engine/PayloadAnswers.kt`) must be able to converge: the re-fetch has to write the fact whose
  absence caused the miss. `hasUnknownGenreCuration` once keyed on any `Metadata` payload, but
  MusicBrainz's degraded mapping writes `curated = null` on every fetch it cannot ask on, so
  `LABEL`/`RELEASE_DATE`/`RELEASE_TYPE`/`COUNTRY` re-missed on every call for their whole TTL —
  ~4 live MusicBrainz round trips per warm album read. Scope a heal to the entries whose readers
  actually consume the missing fact, and check what the healing fetch will really write. One
  non-converging case is kept deliberately: an `ALBUM_METADATA` entry whose winning provider is a
  MusicBrainz search hit re-misses until a lookup-path fetch wins — the healing there is worth its
  narrow residual.
- One `http/CircuitBreaker.kt` per provider id, shared across every chain.
- A `CompositeSynthesizer`'s `dependencies` are resolved even when the caller did not ask for them,
  and **how** they are resolved follows from their own registration, never from the request. A
  dependency with a `ResultMerger` is merged across every provider that answers; a dependency that
  is itself a composite is synthesized. Deriving either from the requested set is the defect this
  once had in both directions: `GENRE_DISCOVERY` requested alone took `GENRE` from the highest-
  priority provider rather than the merger (6 related genres against the merged 14), and a
  composite dependency of a composite settled `NotFound("no_provider")` because it fell through
  `resolveRegularType`, which finds no chain for a type no provider serves. The same request
  returning a different answer depending on what else was in it is the smell.
- `compositeSubTypesOf` walks the dependency graph transitively, and both `SettlementBoard`
  construction and `streamResolveTypes`' scheduling read that one walk. They must agree exactly:
  the board's keys are what `SettlementBoard.await` can be called for, and `await` is
  `deferreds.getValue(type)`, which throws `NoSuchElementException` on a key the board does not
  carry — not a `NotFound`. That exception escapes a `launch {}` child of the fan-out's
  `coroutineScope`, cancels every sibling type, and lands in the straggler stamp.
  `CompositeSynthesizer.dependencies` is a consumer property that nothing stops answering
  differently on two reads, which is why the engine snapshots the graph once at construction rather
  than recomputing it per access.
- Every consumer callback the engine invokes is guarded, but the guards `catch (e: Exception)` —
  `StrategyGuard`, `ProviderChain`, `CacheGuard`. A `Throwable` that is not an `Exception` passes
  all of them: a `NoClassDefFoundError` from an optional dependency the consumer's build omitted is
  the realistic one. It reaches `runProgressiveFanOut`'s `finally`, which is shared with
  `close()`, so the unsettled types must be stamped from *why* the run stopped and not from the
  fact that it stopped — `ENGINE_CLOSED` for a cancellation, `UNKNOWN` carrying the cause otherwise.
  "Every callback is guarded" is not "nothing can throw".
- A cyclic `CompositeSynthesizer.dependencies` graph has no resolution order, so
  `DefaultEnrichmentEngine`'s `init` refuses it and `Builder.build()` throws. Under an await-driven
  fan-out the alternative is types that never settle and a run that only ends at its deadline.
- `withTimeoutOrNull(enrichTimeoutMs)` returns null for *that* deadline only; a nested one
  propagates. An expiry does not discard results already fetched, but the write-back is skipped, so
  a timed-out run caches nothing. The stale fallback and write-back sit outside the timed block.
  §6 below holds the worked example.
- `filterByConfidence()` demotes a `Success` below `minConfidence` (0.5) to `NotFound`; its sibling
  `demoteUnanswered()` demotes one whose payload does not `answers()` its type, at any confidence,
  and runs on the cache read too — where an unanswered entry counts as a *miss*, so one written by
  an older build is refetched and healed rather than pinned for its TTL (§8).

## Area — The published surface

## 1. The published surface only grows by appending

One law, three blast radii. `make api-check` is the guard, and **reading the `.api` diff is the
point** — not regenerating the baseline until it passes.

```kotlin
// WRONG — v0.9.2 did this to the profile extensions, in a *patch* release
suspend fun EnrichmentEngine.albumProfile(title: String, artist: String, mbid: String? = null,
    identifiers: EnrichmentIdentifiers? = null,        // inserted mid-list
    types: Set<EnrichmentType> = DEFAULT_ALBUM_TYPES)
// caller from v0.9.1: albumProfile("OK Computer", "Radiohead", null, myTypes)
//   → myTypes now binds to `identifiers`
```

**Every mid-list default already in the tree is frozen, deliberately** — `identifiers` in
`EnrichmentEngineExtensions.kt`, `genreTags` at position 2 of `EnrichmentData.Metadata`,
`SimilarArtist.identifiers` before `matchScore`, `SimilarAlbum.year` before `artistMatchScore`, and
`TopTrack`'s four defaults before `rank`. Reordering any of them *now* is a second break. Do not
"fix" them to match the rule below; apply that rule to new parameters and fields, which go last
with a default.

- **Persisted data** — name-based JSON survives reordering, but *replacing or removing* a field
  breaks what consumers already stored: v0.4.0 swapped `SimilarArtist.musicBrainzId` for
  `identifiers` and broke every Room cache entry in the field. Any payload change asks whether
  `CHANGELOG.md` needs a cache-clear note. Round-trip tests cannot catch this — they encode and
  decode with the same code (`VERIFICATION.md`; goldens unwritten).
  Retyping a field `String?` → `Int?` is the exception that looks like a break and is not:
  kotlinx.serialization 1.7.3 decodes a quoted number into a numeric field even with `isLenient`
  false, so a stored `"year":"1997"` still reads. Only a value that was never numeric fails to
  decode, and `RoomEnrichmentCache` reports that row as a miss rather than letting the exception
  out. `RoomEnrichmentCacheTest`'s quoted-year test is what holds this; a kotlinx bump can revoke
  it, so re-read that test before assuming a retype is free.
- **Source callers** — positional arguments rebind silently. The demo canary proves consumers still
  *compile*, not that argument order held; v0.9.2 was caught only by a type mismatch, and a `String?`
  inserted between two `String?`s would have compiled green and wrong.
- **ABI** — removing a parameter breaks even when provably dead. `DefaultEnrichmentEngine` carried an
  unused `httpClient` under a detekt `UnusedPrivateProperty` suppression precisely because deleting it
  from a public constructor was the break; it went only once the class itself went `internal` (#48).
  **The tooling recommends this mistake** — detekt sees a dead property, not a frozen signature. The
  live case is `OkHttpEnrichmentClient(client, userAgent)`: publishing it *is* `musicmeta-okhttp`, so
  it can never be narrowed out of the way, and a parameter that goes dead there costs a documented
  minor to remove. Check whether a baseline finding sits on a published signature
  (`*/api/*.api`) before acting on it.

### Constructor order on a public data class

Required parameters first, defaulted parameters after them, `identifiers` and `sources` last. A new
field is appended last with a default whatever the existing order is — mid-list is where the trap
above fires. A type that is breaking for another reason takes this order in the same break; a type
that is not breaking is never reordered for it, because the reorder *is* a break.

Positional construction of a data class with more than three parameters is not a supported use.
Every example in the docs uses named arguments, and the demo canary proves that a positional caller
still compiles, not that its arguments still bind where they did.

### What counts as breaking

Removing or renaming public classes, functions or parameters; changing a return type; reordering
non-named parameters; changing enum or sealed variants a consumer may `when` over. Prefer an overload
or a defaulted parameter appended last; deprecate with `@Deprecated(ReplaceWith(...))` for at least
one minor. On the JVM, adding a parameter anywhere to a function with defaults changes the method
descriptor, so appending is the source-compatible floor, not a full ABI guarantee.

Adding a **method with a default body to a public interface** is source-compatible and binary
*incompatible*, and `apiCheck` will not tell you. This build sets no `-Xjvm-default`, and Kotlin
2.1.0 defaults it to `disable`, so the method dumps as `abstract` on the interface with the body in
a generated `DefaultImpls` class, indistinguishable from a plainly abstract method in the dump. A
consumer's implementation compiled against the previous release does not carry the new override, so
calling it throws `AbstractMethodError` until they recompile — while the `.api` diff shows only an
addition and every check stays green. `HttpClient` carried two such defaults
(`fetchJsonResult(String, Map)`, `fetchRedirectUrlResult`) until they went with the nullable methods
they papered over; `HttpClient$DefaultImpls` is gone from `musicmeta-core.api` as a result, and that
block was the only place the distinction was ever visible for this interface. Other interfaces still
have theirs. Kotlin 2.2 flips that compiler default, so re-verify this on a
toolchain bump — `apiDump` would show any `DefaultImpls` disappearing. Any public interface a
consumer implements is exposed to this, so a defaulted addition to one needs the same
`### Breaking Changes` line a removal would get.

Generics are erased from the dump the same way. A type parameter, its variance and its bound leave
no line to read: `CacheEnvelope<out T : EnrichmentResult>` dumps as a bare `CacheEnvelope`, and
`EnrichmentResults.get`'s `inline fun <reified T : EnrichmentData>` carries no trace of `T`.
Narrowing a bound, flipping variance, or removing `reified` from an inline function is a source
break that moves no `.api` line, so any change to one is reviewed from the `.kt`.

The surface was narrowed to the four-role boundary in v0.10.0 (#5). `CircuitBreaker`,
`MusicBrainzParser` and the built-in mergers/synthesizers are `internal`, so a refactor confined to
them leaves `apiCheck` green. `RateLimiter` is public only because it is a parameter of nearly every
provider constructor.

One synthesizer fact escapes that boundary: `DEFAULT_SYNTHESIZER_DEPENDENCIES` publishes the
built-in synthesizers' `type`→`dependencies` graph as a `Map`, so a consumer can credit a
synthesized result. The synthesizer *objects* stay `internal` (renaming them is still green), but
the graph's contents are now an observable contract — removing a built-in synthesizer, or changing
a `dependencies` set, is a behaviour break `apiCheck` cannot see (the getter descriptor is
unchanged), the same class of trap as the `@Serializable` cache types. Such a change needs a
`### Breaking Changes` judgement, not a silent `### Changed`.

## Area — Errors, cancellation, and timeouts

## 2. `catch (e: Exception)` in a suspend function eats cancellation

```kotlin
// WRONG — swallows our caller's cancellation
try { cache.get(key, type) } catch (e: Exception) { logger.warn(TAG, e.message); null }

// ALSO WRONG — rethrows a cancellation that was never ours
try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { fallback }

// RIGHT
try { block() }
catch (e: Exception) {
    currentCoroutineContext().ensureActive()   // throws only if *this* job was cancelled
    logger.warn(TAG, "…"); fallback
}
```

Swallowing defeats `withTimeout(enrichTimeoutMs)`, and the resulting `Error` makes `ProviderChain`
record a breaker **failure** — an expiry counted against a provider that never failed. The blanket
rethrow fails the other way: a `CancellationException` can come from inside a provider's own
`withTimeout` while our job is healthy, and rethrowing cancels its siblings.

Every consumer-implementable interface needs the RIGHT form verbatim. It is live in `ProviderChain`,
`CacheGuard`, `StrategyGuard`, `CatalogFilter`, `DefaultEnrichmentEngine`, `ITunesProvider` and
`DeezerProvider`.
`guardedStrategy` is `suspend` purely to reach the job, since `ResultMerger.merge` and
`CompositeSynthesizer.synthesize` are not. `EnrichmentLogger` is the one consumer-implementable
interface guarded *without* `ensureActive()` — its two methods are not `suspend`, so cancellation
cannot be delivered into them and a `CancellationException` there can only be one the consumer's
logger built itself. `EnrichmentLogger.guarded()` holds the reasoning; the wrapper is applied at
`EnrichmentEngine.Builder.logger` so no call site repeats it (#71). Enforced by behaviour, not a rule — a textual rule was
written and deleted because the remediation it printed was itself the defect (`VERIFICATION.md`). Read `EnrichCacheFailureTest`, `EnrichStrategyFailureTest` and
`ProviderChainCancellationTest` before writing a cancellation test of your own. A provider's own
`catch` calls `mapError(type, e)` and deliberately does not special-case `CancellationException`.

**Never reason that a swallowed cancellation is harmless because "it re-asserts at the next
suspension point"** — cancellation is cooperative and a suspend function may never suspend again.

## 4. `Error` and `NotFound` are not interchangeable

```kotlin
if (url == null) return EnrichmentResult.Error(type, id, "no artwork")  // WRONG — not a failure
if (url == null) return EnrichmentResult.NotFound(type, id)            // RIGHT
```

`ProviderChain` records a breaker *failure* on `Error` and a *success* on `NotFound`, so mislabelling
opens the breaker against a healthy provider and `STALE_IF_ERROR` starts serving stale data. Reserve
`Error`/`RateLimited` for transport and protocol problems; `mapError()` classifies those into the
right `ErrorKind`. Nothing can check this — only you know what the response meant.

The same confusion has a chain-level shape: a `NotFound` synthesised because no provider *answered*
claims the data does not exist. `ProviderChain` therefore reports its own `Error` when every eligible
provider was skipped for an open breaker, and `resolveAll` returns `ChainResults`, not a bare list —
the successes alone cannot tell a merger's caller that nobody succeeded because nobody was asked. A
signature that can only carry the happy path is how the failure went missing the first time.

## 6. Catching `TimeoutCancellationException` by type cannot tell whose deadline fired

```kotlin
// WRONG — shipped until #55; any nested withTimeout lands here too
try { withTimeout(config.enrichTimeoutMs) { … } }
catch (_: TimeoutCancellationException) { /* stamp every unfinished type ErrorKind.TIMEOUT */ }

// RIGHT — null means *this* deadline and nothing else; a nested expiry propagates
val completed = withTimeoutOrNull(config.enrichTimeoutMs) { …; true } ?: false
```

The type carries no identity, so a consumer's `CatalogProvider` running its own `withTimeout` was
reported as `enrichTimeoutMs` expiring, from provider `"engine"` — sending them to tune a number
that was never the problem. `withTimeoutOrNull` discriminates because it compares the exception's
coroutine with its own. `CatalogProvider` was the live case because its call was the one
consumer-implementable one the engine made unguarded; it now reaches `ensureActive()` like the
cache, merge strategies and providers (§2), degrading that type to unfiltered results.

**A timed-out `results` map is a mix, not a prefix.** `applyCatalogFiltering()` rewrites entries one
type at a time inside the deadline, so an expiry mid-loop leaves some types filtered and some raw,
and the timeout backfill only fills types that are *missing* — a half-filtered `Success` carries no
marker at all. That mix is fine to return and was never fine to cache: the write-back ran outside
the deadline and persisted it under the primary *and* name-alias keys, so every later lookup was a
hit that skipped filtering (#56). Anything added inside that block inherits the same shape, which is
why the guard is on the write-back rather than on catalog filtering.

## 20. `scope.launch` on an already-cancelled `Job` silently never runs its block

```kotlin
// WRONG — if scope's Job is already cancelled, this CompletableDeferred never completes
scope.launch { deferred.complete(buildResult()) }

// RIGHT — decide before launching whether the block will ever run
if (scope.isActive) scope.launch { deferred.complete(buildResult()) } else deferred.complete(fallback())
```

`launch`'s default `CoroutineStart.DEFAULT` schedules the block for later; on an already-cancelled
parent it is scheduled and immediately cancelled without ever starting, so no line inside it runs —
not even a `finally`. `ProgressiveRunRegistry.attachOrStart` hit this for real: a call arriving after
`close()` cancelled `detachedScope` raced `scope.launch { body(run) }`, and when it lost, the fresh
run's `terminal` `CompletableDeferred` was never completed — the collector attached to it hung
forever, the opposite of what `close()` promises. The fix is `attachOrStart` deciding under its own
lock whether `scope` is already closed *before* choosing to launch at all, and building the
abandoned-run snapshot itself when it is, rather than trusting the launch to run.

## 21. Consumer-reachable suspending work under a shared mutex can deadlock a limited-parallelism dispatcher against itself

A `Mutex` held across a suspension point is fine in general — coroutines, unlike threads, don't
deadlock on a mutex just by suspending while holding it. The trap is specific to a dispatcher with
bounded parallelism (`Dispatchers.Default`'s thread pool is finite): if the suspending call under the
lock can itself schedule work back onto that same dispatcher and wait for it, every thread in the
pool can end up parked waiting for the lock while the one coroutine that could release it is starved
of a thread to run on. `ProgressiveRunRegistry`'s `mutex` reached exactly this shape once, when the
lock's critical section briefly included `onClosed`'s abandonment work — which calls
`DefaultEnrichmentEngine.applyStaleCacheToType`, and through it `EnrichmentCache.getIncludingExpired`,
a suspending call into consumer code with no bound on what it does. The fix is architectural, not a
bigger pool: `mutex` now guards only the non-suspending registration decision (read `closed`, check
`runs`, insert, launch) — everything that can suspend into consumer code, including `onClosed`, runs
after `withLock` returns. A shared mutex must never wrap a call whose suspension point you do not
control. This is also why `close()` — not a suspend function, so it can only wait on `markClosed`'s
mutex via `runBlocking` — is safe today: with the critical section non-suspending, `runBlocking`
waits, at most, for a few non-suspending lines to finish, never for a suspending call to complete.
Re-check that holds before ever adding a suspending call back to that critical section, or a
same-dispatcher `runBlocking` caller can wedge forever with no thread left to resume the holder.

## 26. Cancellation cannot reach a thread blocked in socket I/O — the deadline must ride the socket

`withTimeoutOrNull(enrichTimeoutMs)` cancels the coroutine; the thread under it stays blocked in
`HttpURLConnection`'s connect/read or OkHttp's `execute()` until the *transport's own* timeout
fires. Measured (2026-08-25, `.scratch/enrich-deadline-not-a-bound/prototypes/`, committed on the
two `prototype/enrich-deadline-arm-*` branches): a 2 s deadline returned at the transport's 10 s,
and `runInterruptible` is inert — the interrupt is delivered and *ignored* by
`sun.nio.ch.NioSocketImpl`, proven with a control that interrupted a `Thread.sleep` fine. Do not
reach for interruption again; it measures dead.

What holds the deadline is arithmetic: read `enrichDeadlineRemainingMs()` immediately before every
blocking leg and clamp the transport's connect/read (JDK) or per-call `callTimeout` (OkHttp) down
to it. Two traps inside that:

- **A JDK-followed redirect chain re-spends the first leg's timeouts per hop** — three
  individually-fast hops walked a 3 s deadline to 7.5 s, unmoved by the clamp. `DefaultHttpClient`
  therefore follows redirects itself, reclamping per hop (and keeping JDK semantics: 301/302/303
  re-issue as GET, a POST's 307/308 surfaces, cross-protocol surfaces, 20-hop cap).
- **Zero means "no timeout" to every JDK and OkHttp knob**, so an exhausted budget expressed as
  `0` disables the very bound it should impose. Clamp to at least 1 ms.

`EnrichDeadlineBoundTest` (core) and `OkHttpEnrichDeadlineBoundTest` pin the end-to-end invariant;
`DeadlineBlackholeProbeTest` behind `-Dinclude.probe=true` is the field reproduction (SYN
blackhole), kept off CI because a network that answers TEST-NET-1 with an RST turns it vacuous.


## 29. `enrichTimeoutMs` is spent on the fan-out's dispatcher, which `runTest` does not own

`enrich()` is `enrichProgressive().last()`, and that fan-out — the `withTimeoutOrNull` enforcing
`enrichTimeoutMs` included — is launched on the engine's detached scope, `Dispatchers.Default` by
default. A `runTest` body therefore never virtualizes the budget: it is wall-clock time, and so is
every `delay` inside the fan-out, a `RateLimiter`'s waits included.

Two ways that reaches a test as a flake, both invisible to the class run alone:

- **A small budget is a wall-clock assertion.** With `enrichTimeoutMs = 100`, a 150 ms real stall
  anywhere in the fan-out expires the engine's own deadline and every unsettled type is stamped
  `Error(TIMEOUT, "engine")` — which is exactly what a test asserting whose deadline fired is
  written to forbid.
- **The pool is shared with the rest of the suite.** Complete-and-cache means a run outlives the
  test that started it by design, so a neighbour's fan-out is still holding `Dispatchers.Default`
  when the next class starts. Saturating that pool made `EnrichDeadlineBoundTest` return in
  13154 ms against a 1200 ms deadline; on a dispatcher of its own, under the same saturation, the
  same call read 1620 ms. (Recipe: six busy-loop shells pinned to `Dispatchers.Default`'s worker
  count, then the class alone; the numbers decay, the shape does not.)

So a test whose subject is a deadline hands the engine a `detachedDispatcher` it owns:
`StandardTestDispatcher(testScheduler)` when the budget should be virtual, a dedicated pool when the
measurement must stay real. Every composed-stack test already has one: `TestStack.build` gives each
stack a two-thread pool of its own, so the shared-pool half above cannot reach a `harness/*` test.
The budget stays wall-clock — a scenario still costs its real rate-limiter waits — and a test that
wants it virtual has to say so. Distinct from §17, which is the mirror image — there a timeout is
virtual when the work it bounds is real, so it fires unconditionally; here it is real when the test
was written as if it were virtual.


## Area — Provider data and matching

- A MusicBrainz `inc=` that names a relationship gets you the **link**, not the linked entity's own
  relations. `inc=work-rels` returns each work as a stub — id and title, no `relations` array — and
  the sub-entity's relations need `work-level-rels` (or `recording-level-rels`,
  `release-group-level-rels`) named as well. `CREDITS` asked for `work-rels`, read
  `work.relations`, and `?: continue`d past a field that was never going to be there, so every
  songwriter, composer and lyricist was dropped for every recording on every call from the day it
  shipped. The two-line reading — "we ask for work-rels, so we have the work's rels" — is wrong and
  looks right. Verify an `inc=` against a live response before trusting what it returns; the probe
  in `.scratch/musicbrainz-work-credits/prototypes/` is the shape of that check.
- The fix cost no extra request, but the ticket had been open for a fortnight costed at one extra
  `/work/{mbid}` lookup per recording — a rate-limit budget nobody wanted to spend. **The cheaper
  option was not found by thinking harder about the trade-off, it was found by reading the API's
  own include list.** Price the options against the upstream's documented surface before treating a
  cost as fixed.
- A lookup table whose miss falls through as `?: rawUpstreamId` ships the upstream's own identifier
  as data. Wikidata's `COUNTRY_MAP` did exactly that, so any artist whose P495 country was not one
  of its 15 entries — most countries — reached consumers as `country == "Q212"`. A miss means the
  value is unknown, so the fallback is **null**; an identifier is never a plausible default for the
  thing it identifies. The same table also held `US` and `UK` beside `France` and `Germany`, mixing
  two representations of one field, and nothing could catch it because the field promised none:
  a representation promise (code versus name, and which standard) belongs in the model's KDoc,
  where both the provider writing it and the consumer reading it can be held to the same sentence.
- Before promising a representation, count the writers, because one `Metadata` field is filled by
  every provider that can answer for it. `country` reads as a MusicBrainz-and-Wikidata question and
  is not: iTunes and Discogs both wrote the same field, Discogs in English names and region labels
  like "Europe", and a multi-country MusicBrainz release writes `XE`/`XW`. `EnrichmentResults`
  falls back from the dedicated type to `ALBUM_METADATA`, so all of them surface through one
  accessor. `grep -n 'country = ' provider/` is the check, and the equivalent grep is worth running
  for any field whose KDoc is about to state a format.
- A field that parses cleanly and validates cleanly can still be the wrong quantity. iTunes' album
  `country` is a well-formed ISO alpha-3 code, and it is the **storefront** the request was served
  from — the same value on every result, `USA` for a German act's albums, because the client sends
  no `country=`. Converting it to alpha-2 would have made a constant wrong answer look like a
  release fact and let it win a merge. Before mapping an upstream field onto one of ours, check a
  record whose correct value you already know and confirm the field *moves*; a value that never
  varies is describing the request, not the entity.

## 3. `org.json` returns a default for a missing key — it does not fail

```kotlin
// WRONG — shipped in 0.9.0; ListenBrainz sends recording_name, not track_name
title = item.optString("track_name", "")        // every TopTrack title was ""

// RIGHT — read the field the API actually sends, and treat blank as absent
val mbid = item.optString("recording_mbid").takeIf { it.isNotBlank() } ?: continue
albumName = item.optString("release_name").takeIf { it.isNotBlank() }
```

No exception, no `NotFound` — empty strings enriched, cached and persisted, needing a 0.9.1 fix plus a
"clear your cache" migration note. A provider test must assert against a fixture copied from a real
response; that is what pins the field name. `provider-drift.yml` runs the `e2e` suite against live
APIs on a schedule, so a moved field surfaces indirectly — it is not a field watcher and never gates
a merge.

## 22. Two endpoints of one API do not carry the same fields

```kotlin
// WRONG — shipped until 0.13.0; a /release?query= hit has no cover-art-archive object at all
private fun extractHasFrontCover(release: JSONObject): Boolean {
    val coverArt = release.optJSONObject("cover-art-archive") ?: return false   // states "no art"
}

// RIGHT — absent means the response could not say, which is not the same as "no"
private fun extractHasFrontCover(release: JSONObject): Boolean? {
    val coverArt = release.optJSONObject("cover-art-archive") ?: return null
}
```

MusicBrainz sends `cover-art-archive` on `/release/{mbid}` and never on `/release?query=`, so every
search-derived release reported `false`: measured false on 853 of 853 pooled candidates and wrong on
156 of 200 checked against the Cover Art Archive. `MusicBrainzProvider` gated a search candidate's
thumbnail on it, so that thumbnail was never produced and `docs/providers.md` documented a promise
the code could not keep. Making the flag honest left it with no reader that could ever see a value:
a field only one endpoint can populate is dead the moment the caller reads it on the other one, so
the field, the guard and the `thumbnailSize` parameter feeding it are gone.

§3 is the same defaulting mistake on a field the endpoint does send; this is the harder version,
because the field is right and the *endpoint* cannot answer. Nothing catches it: the code and the
fixtures agreed with each other, three of them carrying a hand-written `cover-art-archive` inside a
search payload upstream never sends. A fixture copied from a real response of **the endpoint the
caller actually uses** is what pins this — a lookup capture proves nothing about a search path.

## 5. A capability's `identifierRequirement` defaults to `NONE`

```kotlin
// WRONG — Cover Art Archive is coverartarchive.org/release/{mbid}; it cannot work without one
ProviderCapability(type = EnrichmentType.ALBUM_ART, priority = 100)
// RIGHT
ProviderCapability(EnrichmentType.ALBUM_ART, priority = 100,
    identifierRequirement = IdentifierRequirement.MUSICBRAINZ_ID)
```

`hasRequiredIdentifiers()` is the only thing keeping an ID-only provider from being called with
nothing to look up. Undeclared, it burns a rate-limited request and returns `NotFound` — which
records breaker **success**, so a provider that never works looks healthy while a lower-priority
fallback wins the type.

**Do not infer the answer from the surrounding lines.** Two-thirds of this tree's capabilities
correctly declare nothing, because their providers are name-search APIs (Deezer, Discogs, iTunes,
Last.fm, LrcLib). ID-keyed providers declare on every entry (Cover Art Archive, Fanart.tv, Wikidata,
Wikipedia, ListenBrainz). MusicBrainz declares on exactly 2 of 11 — `CREDITS` and `RELEASE_EDITIONS`
— because it searches by name for the rest. The only test is whether *this* capability can resolve
without the identifier.

## 7. A search API's hit 0 is a ranking, not an answer

```kotlin
// WRONG — shipped until #110; Deezer ranks an empty "Radiohead" (0 albums, 470 fans) above id 399
val url = "$BASE_URL/search/artist?q=$encoded&limit=1"
val artist = data.getJSONObject(0)              // caller's ArtistMatcher check passes: name is exact

// RIGHT — fetch a pool, keep the name matches, rank by name quality, break ties on popularity
.bestArtistMatch(name, tieBreak = compareBy({ it.optLong("nb_fan") }, { it.optLong("nb_album") })) {
    it.optString("name", "")
}
```

`bestArtistMatch` (beside `ArtistMatcher` in `engine/`) is that chain, shared by all three
name-search providers. What stays at the call site is what is genuinely per API: the name field, any
cleanup of it, and whether there is a popularity `tieBreak` at all.

A name check on one hit cannot separate a ghost from the real artist when both names are exact — it
only rejects a wrong name, and every duplicate-name entry survives it. `limit=1` also throws away
the evidence that would decide it, so the check has to sit where the pool is, in the shared search
call, not in the six callers that each re-verify the single hit they were handed.

Filtering on `isMatch` alone is not enough to order the survivors, because `isMatch` is deliberately
loose at the bottom — bare containment, then 50% token overlap. For "Bad Company" it accepts both
"Bad Company Live In Concert" and "Bad Bunny", so sorting that pool on `nb_fan` hands it to Bad
Bunny. Rank on `matchQuality` first and let popularity break ties only *within* a rank: a
popularity signal must never outrank name quality, only settle candidates whose names are equally
good.

**The tiebreak must be a signal the payload actually sends, and "same name" is not always one.**
`ITunesApi.searchArtist` and `DiscogsApi.searchArtist` got the same treatment, and neither could
copy the Deezer tail: an iTunes `musicArtist` result carries no popularity field at all (only
`artistId`, `artistName`, genre and sometimes an AllMusic id), and a Discogs artist result carries
none either (`id`, `title`, `uri`, `thumb`, `cover_image`, `resource_url` — the have/want/rating
counts are on *release* results). So both rank on `matchQuality` alone and let equal names fall back
to the provider's own order, which `bestArtistMatch` gives for free by keeping the first maximum.

Discogs adds a trap on top: its name field is **`title`**, not `name`, and it carries a `" (n)"`
homonym counter that is **arbitrary** — the bare name goes to whoever was catalogued first.

```
q=Bad Company → 261941 "Bad Company (3)"   the Paul Rodgers rock band, at hit 0
                  2017 "Bad Company"       a UK drum & bass group, at hit 1
```

Ranking the bare name as the exact match therefore picks the *wrong* artist — a regression the
`per_page=1` code it replaced did not have, caught only because the live payload was pulled before
the code was written. `DiscogsApi` strips a trailing ` (n)` before matching so homonyms tie and
Discogs' order settles them. Fact-check the payload before porting a selection rule between
providers; the shape that makes it work is per API.

**Album search has the same defect one level up, and the track-level fix is not the album fix.**
`DeezerAlbumScope`, `ITunesAlbumScope`, and `DiscogsAlbumScope` must accept a candidate on the album
title, not merely the artist. Copying LRCLIB's strict `TitleMatcher.equivalent` (§7's track policy)
is not safe here: a bare `Hunky Dory` request live-returns only `Hunky Dory (2015 Remaster)`, so
whole-title equality rejects the one edition a provider actually has. Deezer and iTunes each declare
their own `titleTier` function beside their `selectAlbum` — `TitleMatcher` supplies only the
comparison vocabulary (`parse`, `equivalent`, `isEditionDecoration`), never the acceptance decision,
so one provider's tolerance can never leak into another's. Each provider's `titleTier` gives one
narrow tolerance: a bare request (no qualifier at all) may accept a candidate whose only qualifier is
provider-added edition decoration — a remaster suffix, nothing else. `Live`, `Remix`, `Deluxe`,
`Anniversary`, and box-set qualifiers stay identity-bearing and are never admitted by a bare request,
and a qualifier the caller *did* supply still must match exactly; `titleTier` only ever loosens the
"no qualifier at all" case. Discogs's pressing search has no measured decoration convention, so it
stays at full-title equivalence and declares no tier at all. Rank accepted candidates by tier first,
then artist quality, then any edition evidence the payload actually carries (Deezer's `nbTracks`,
iTunes's `trackCount`/`releaseDate`, Discogs's `year`, each against the matching request field) —
never by provider order until every other signal ties, or a materially different edition (an 8-track
album versus a 137-track deluxe box) can outrank the one the request actually asked for.

**That ordering presumes a pool already filtered to artist matches, and MusicBrainz's direct search
is the one place that presumption fails.** `DeezerApi.rankTracks` puts artist quality at tier 2
because its pool was filtered by `ArtistMatcher.isMatch` before ranking ever started (§7's own fix);
`MusicBrainzEnricher.pickBestRecording` and `MusicBrainzReleaseRanking.pickBestRelease` rank a pool
their direct search never filtered by artist at all, so `ArtistMatcher.matchQuality` has to lead
their comparators — title and edition tiers cannot be trusted to settle a pool that may hold a
wrong-artist hit tied or ahead on every other signal. Follow "tier first, then artist" only where the
pool is already artist-filtered; rank artist first wherever it is not.

**A Lucene score measures relevance, so an accepted hit is not a name match and cannot stamp
`LookupProvenance.EXACT_NAME` on its own.** MusicBrainz answers a truncated title as a full phrase
match: 25 of 25 live album requests with the title's last word removed (`release:"Hail to the"`)
returned the full album at score 99-100, all of them accepted by the `minMatchScore` floor and none
title-equivalent (probe recipe and full pair list: `.scratch/exact-name-provenance/`, 2026-08-25 —
re-run before building on the number; the pinned regression is
`MusicBrainzSearchTitleProvenanceTest`). `MusicBrainzEnricher` compares the requested title against the hit's own with
`TitleMatcher.equivalent` and reports `FUZZY_NAME` when they differ — the same shape the artist route
already had via `NameMatchTier.CANONICAL`. The engine cost was one layer further out: a search route
that reported *nothing* was read by `identityNameEvidence` as "matched", so the whole fan-out
inherited `EXACT_NAME` from a title nobody had compared. A route now reports itself, and an
unreported one vouches for nothing.

Combined-field search results carry a second trap: a provider that names both artist and album in
one display string (Discogs's `"Artist - Title"`) cannot be safely split at the first delimiter,
because either half may itself contain that delimiter. Stopping at the first boundary whose
artist-side merely passes the loose artist floor picks a false split when the real artist name
itself contains the delimiter. The safe parse tries every boundary and prefers the one where *both*
the artist and title sides match the request, falling back to an artist-only match only when no
boundary clears both sides.

## 24. Ranking a pool the request could not narrow produces a confident wrong answer

```kotlin
// WRONG — an empty artist term does not narrow the search, it widens it
val releases = api.searchReleases(title, artist)          // artist = "" -> 13,987 candidates
MusicBrainzReleaseRanking.pickBestRelease(releases, minMatchScore, artist = artist)

// RIGHT — a pool nothing in the request can narrow is candidates, not an answer
if (artist.isBlank()) return AlbumSearchResult(release = null, originalPool = releases)
```

MusicBrainz ignores an empty `artistname:` term rather than rejecting it, so a blank artist does not
fail the search — it removes the only constraint that made the title identifying. Measured live,
`release:"Greatest Hits"` alone returns 13,987 candidates against 37 with the artist, and the top 25
are routinely all tied at score 100.

Every tier that then decides is one that cannot see the caller. The artist tier has nothing to
compare against and scores every candidate alike; release type, status, score, edition band and year
pick a winner out of an arbitrary slice. **A ranking says which candidate is best in the pool. It
never says the pool holds the right one.**

The measurement that matters is not how the ranking behaves but whether the answer is present at
all: over two 20-album samples the correct release was **absent from the 25-candidate window in 13
and 7 rows**. No re-ranking rule can reach an answer that was never returned, which is why
`ArtistMatcher`-based gates, pool-ambiguity thresholds and confidence downgrades all fail here in
different ways.

So the guard belongs where the evidence is missing, not where the ranking runs: `artistBlanksNameSearch`
refuses the *identity claim* and the pool is handed back as `suggestions`. Suggestions are guesses a
caller chooses between — **never promote one to a resolution because it ranked first.** Pitfall 7 is
the same trap one level down: there, hit 0 of a pool is a ranking rather than an answer; here, the
whole pool is.

The track path is refused without even searching. A recording title alone opens a pool of tens of
thousands of takes, covers and live versions, and the recording the caller meant was in the returned
window for 4 of 10 distinctive titles and **0 of 9 ambiguous ones** — so the request buys candidates
that do not contain the answer.

## 8. `confidence` scores identification, not the payload

```kotlin
// WRONG — a tagless recording matched at score 100 becomes GENRE Success, confidence 1.0, all fields null
val best = recordings.firstOrNull { it.score >= minMatchScore } ?: return NotFound(type, providerId)
return Success(type, MusicBrainzMapper.toTrackMetadata(best), providerId,
    ConfidenceCalculator.searchScore(best.score))
```

The two signals are independent, and the identity score is the wrong one to reach for: it is
*highest* exactly when the entity matched perfectly and happened to carry nothing. So
`filterByConfidence()` cannot demote an empty result, and lowering the score to express a thin
payload only mislabels a match that was in fact perfect. `answers()` in `engine/PayloadAnswers.kt` is
the second gate, and the engine applies it to everything that can reach a consumer — every provider
result, merger and synthesizer output, the identity fan-out, and both cache paths. No provider needs
its own check. On the cache read the gate means *miss*, not `NotFound`: an empty `Success` written by
an older build otherwise outlives the fix by the type's TTL (90 days for `GENRE`), re-demoted on
every call and never refetched. Treating it as a miss lets the providers run and the write-back heal
the entry.

Extending it: one `EnrichmentData.Metadata` serves six types, so **which field answers which type is
per type** for five of them — `label` answers `LABEL` and nothing else — but `ALBUM_METADATA` and the
other thirty accept any field at all. `answers()`'s own `when` is exhaustive over payload classes, so
the compiler asks about a new one, and `answersMetadata`'s `when` **is** exhaustive over `EnrichmentType`
with no `else`, so a new type is a compile error until named, not a silent fall into the grab bag —
exhaustiveness buys naming, not rejection. That fails lenient, which is the right direction: the
gate's job is to catch payloads answering *nothing*, not to adjudicate partial ones.

## 14. An optional-id branch is invisible to anything reading `identifierRequirement`

```kotlin
// WRONG — capability declares NONE, so a result from this branch reads as a name search
val id = request.identifiers.get(IdentifierNamespace.DEEZER)?.toLongOrNull()
val artist = if (id != null) api.getArtist(id) else searchArtist(request.name)
return Success(type, data, providerId, confidence)  // provenance stays unset either way

// RIGHT — the branch taken is observed by the code that took it, nowhere else
return Success(type, data, providerId, confidence,
    provenance = if (id != null) LookupProvenance.PROVIDER_NATIVE_ID else null)
```

`IdentifierRequirement.NONE` means MusicBrainz canonical resolution is optional, not that the
provider never has an exact-id route of its own. A capability, a chain walk, and the engine's own
`stampProvenanceOne` fallback can all see only what running *required* — never what a specific call
*happened to use* when the requirement permitted either. Only the branch itself knows which one
ran, so only the branch itself can report it truthfully; leaving `provenance` unset here is not
neutral; it hands the engine's canonical-status fallback a case it cannot tell apart from a genuine
search. The same applies to a merged or synthesized result with several contributors and no single
winner: report the weakest contributing route, never infer one from canonical status alone.

## 28. A prose field from an upstream can arrive as markup, and a hand-written fixture never shows it

Last.fm welds `<a href="…">Read more on Last.fm</a>` onto every `bio.summary` and every album
`wiki.summary`. For an artist with no wiki that anchor *is* the whole value, so `isNotBlank()` passed
it, the mapper wrapped it, and `ARTIST_BIO` settled `Success` on a link — the chain never fell
through to the artist having no biography, and a demo escaping correctly rendered the tag as text.
Every well-known artist was affected too; the anchor just trailed real prose, where nobody read to
the end.

Nothing caught it because every Last.fm fixture in the suite was hand-written from what the field
*should* contain: `"Radiohead are an English rock band..."`, no anchor, for four years. A fixture
written from the shape you expect can only ever prove the code agrees with you. Copy the bytes from
a live response — including the ugly trailing part — and keep an emptiness-after-normalisation case
beside the happy path, because a payload that is non-blank and still carries no content is the one
the engine's blank check cannot demote.

## 30. A check that rebuilds a provider's URL asserts against a document the library never receives

The shape of an upstream answer depends on the parameters that asked for it, so a drift check that
writes its own URL is testing a different response than the mapper parses. The schema pin's
prototype hand-built the Wikidata request without `props=claims` and reported
`DRIFT wikidata: entities.Q44190.labels.en.value (absent)` against a perfectly healthy Wikidata:
the real request returns only `type`, `id` and `claims`, and the mapper reads property ids under
`claims`, never `labels`. The same trap has a second door — a required-field list written by hand
asserts fields nothing reads. Both produce a red saying DRIFT about a provider that has not moved,
which is the cry-wolf the watch exists to remove, one layer down.

So a pinned route takes its URL from the api client's own route function, and its field list lives
in the same file as the parse it mirrors, where a diff that moves a field shows the pin going stale
in the same hunk. `check_schema_pin_coverage.py` enforces that a list exists and is walked; it
cannot read either of these, so review does.

Fanart.tv is what the pin found on its first live run, and it is §28's lesson pointed at nesting
rather than content. `/v3/music/albums/{releaseGroupMbid}` resolves the release group to its
*artist* and answers with that artist's document, so the album sits under
`albums.<releaseGroupMbid>`. The mapper read `<releaseGroupMbid>` at the top level, found nothing,
and returned "this album has no art" — indistinguishable from the real thing, for as long as the
route has existed. Every fixture in the suite was marked *synthetic: no ground truth available* and
encoded the same wrong nesting, so the tests agreed with the code and both were wrong. A fixture
whose comment says it has no ground truth is not evidence about an upstream; it is a restatement of
the parse under it.

## Area — Transport and provider state

## 11. A retry ladder's coverage is not its trigger

`BudgetedTransientRetry.execute` wraps every request method of both shipped clients, so the ladder's
*coverage* is complete. What it retries is keyed on the **result type**, not the status code — and
for a long time only `HttpResult.RateLimited` qualified, which only a 429 produces. MusicBrainz does
not shed with 429; it sheds with 503, carrying the same `Retry-After` a 429 would. The mechanism for
throttling never ran against the throttling signal the highest-traffic provider actually sends.

Two things follow for anyone touching the status mapping:

- **The retryable 5xx set is a closed list — 502, 503, 504.** A `code in 500..599` test would
  silently absorb every future 5xx, including a 501 that will never succeed on a retry.
- **A retried-and-still-failing 5xx must stay `ErrorKind.NETWORK`.** `bodyOrThrowTransient` throws,
  `mapError` classifies, `ProviderChain` records a breaker failure. "Improving" a shed response into
  `ErrorKind.RATE_LIMIT` makes a shedding provider look healthy — §4 again, one layer down.

And the header a shed response carries cannot be added to `ServerError` — it is a public
`data class`, so a new constructor parameter breaks `copy()` for every consumer (§1). Anything a
retry needs beyond the result type travels alongside it, in `HttpAttempt` — which is public while its
constructor is not. `asAttempt` is the only way to build one, and it reads the `Retry-After` for a
`ServerError` and for nothing else.

The same lesson landed a second time, with no status code at all: a read timeout is a
`NetworkError`, which took the `else` arm and was handed back on the first attempt after spending
the whole `timeoutMs`. Two things a widened trigger has to get right, both of them the reason the
predicate is not simply "retry `NetworkError`":

- **`NetworkError` is two different failures.** A dropped connection is transient; a 200 whose body
  will not parse is a working transport returning a response the retry reproduces exactly — and it
  is the shape a provider changing its JSON arrives in, so retrying it triples the traffic of the
  one case worth noticing. The two are told apart by *how* they arrive: `execute`'s own
  `IOException` catch is the transport failure, and a returned `NetworkError` is the parse failure.
  A boolean on `HttpAttempt` would be a boolean an implementation forgets, which disables retry.
- **The budget test has to charge the attempt, not just the sleep.** A shed 503 fails in
  milliseconds, so fitting the sleep inside the `EnrichDeadline` is enough. A timeout has already
  spent `attemptTimeoutMs` and the retry may spend it again, so the wait *plus* that must fit.
  Reuse the sleep-only test and a hang gets two attempts inside a deadline that had room for one.
  A client that cannot name what an attempt costs — OkHttp's `callTimeout` is 0 unless set —
  passes 0 here and degrades to the sleep-only test with every test still green;
  `OkHttpClient.attemptCostMs()` is why the adapter charges connect plus read instead.

## 12. A provider's own memo is a cache no consumer can flush

`MusicBrainzEnricher` memoizes its artist, release, release-group-wiki, album-search,
album-suggestion, artist-search and track-resolution lookups because the engine asks for one type
at a time and `EnrichmentCache` is keyed by type — nothing above the provider can tell that GENRE
and ALBUM_TRACKS want the same release, and each repeat is a ~1.1s wait on the shared limiter.

The track memo holds *the resolution*, not the raw search: `resolveTrackQualifierFallback` is
called from inside it, not at each call site, or a per-type repeat of the fallback's own searches
would survive a memo scoped to the raw search alone. Same reasoning as the album-search memo
holding which release a title resolves to, one paragraph below.

That much is right, and what deleting them costs depends entirely on whether the album resolves.
Measured over the six album types MusicBrainz declares a capability for, on the shipped defaults —
in-process against `FakeHttpClient`, so no live number decays here. The recipe, if you need it
again: count the upstream requests one `enrich()` makes over all six types, then count them again
with each memo reduced to its bare API call.

| One album's fan-out | With the memos | Without |
|---|---|---|
| Album resolves | 3 requests | 6 — **2×** |
| Album is absent | 6 requests | 41 — **~7×** |

The resolvable path is cheap either way because `IDENTITY_TYPES` answers five of the six types from
the identity payload and drops them before the provider chain sees them, so the fan-out is really
one or two types wide. The miss is where the memos earn their keep: nothing is dropped, so every
type pays the whole ladder.

**Neither column is pinned by a test.** `ProviderMemoLifetimeTest` asserts per-kind counts over two
or three types — one release lookup, one artist search, one run of browse pages, two release
searches — so it catches a per-type *repeat*, which is the property worth guarding. The totals above
are six-type figures and come from the recipe, not from an assertion: re-run it rather than trusting
them.

What was wrong was its **lifetime**, not its layer. It sat on the provider object, which lives as
long as the engine, with no TTL and nothing able to clear it. So
`enrich(…, forceRefresh = true)` — documented as "fetches fresh data from providers" — invalidated
`EnrichmentCache`, called the provider, and was handed the payload the *first* call had fetched.
`invalidate()` had the same hole, and `engine.cache.clear()` cannot be intercepted at all: `cache` is
a public property, so a consumer clearing it never enters the engine. `ProviderCallScope` now owns
that state for one `enrich()` call, which makes every refresh path honest by construction.

Before adding provider-internal state of any kind:

- **State that outlives the call is a second cache with none of `EnrichmentCache`'s guarantees.**
  Put it in the call scope, or own a TTL and an invalidation path for it. `ProviderCallScope` is
  `internal`, so a provider outside this repo has only the second option.
- **Payload staleness is recoverable; identity staleness is not.** A memo keyed by MBID serves at
  worst an out-of-date payload for an entity already resolved. `MusicBrainzEnricher`'s album-search
  memo is keyed by *title/artist* instead, so it holds which entity a name resolves to — safe only
  because it dies with the call. Held any longer, no refresh could ever correct a mis-resolution.
- Per-call state rides the coroutine context, as `EnrichDeadline` and `TransientIdentifierMarker`
  already do. A new `EnrichmentProvider` method would be a documented break instead (§1).
- **One owner, one slot.** `ProviderCallScope.slot` keys on the owner's *identity* alone, not on
  what it is storing, so a provider that takes two slots takes the same slot twice: the second
  caller is handed the first's object through an unchecked cast, and the `ClassCastException` lands
  wherever that object is first used. A provider's own `catch (e: Exception)` then reports it as
  `Error(UNKNOWN)` on whichever path ran second — `ITunesProvider` held a UPC memo and an album
  scope this way, and a call mixing a barcode-bearing request with a barcode-free one lost the
  second to a cast error. Hold everything one provider reuses in one call-state object, as
  `FanartTvProvider`'s `FanartTvMemos` does.

## 25. A caller's identifier is an assertion, and nothing in a successful lookup checks it

A lookup by a supplied MusicBrainz id proves the id names an entity. It proves nothing about whether
that entity is the one the request described, because no name is compared on that path — and until
0.13.0 nothing anywhere made the comparison.

Measured at `06d664aa`: `forArtist("Radiohead", mbid = <Coldplay's live MBID>)` returned Coldplay's
genres, in one request, at `confidence = 1.0`, stamped `LookupProvenance.CANONICAL_ID` — the
strongest value the enum has — under `CanonicalStatus.NOT_ATTEMPTED_IDENTIFIER_TRUSTED`, which the
identity guide told consumers to treat as confident. `forAlbum("OK Computer", "Radiohead", mbid =
<Parachutes' MBID>)` and the track equivalent did the same. The caller's own correct name sat on the
request throughout.

Three things follow, and each is a separate trap.

**Resolving harder does not help.** Forcing identity resolution on every artist request — the fix the
ticket prescribed — returns the same wrong artist and upgrades the status to `RESOLVED`. A resolver
that validates an id resolves to *something* is not validating identity. Measured, not argued.

**Trust is not verification, and a status can say so.** `NOT_ATTEMPTED_IDENTIFIER_TRUSTED` is reachable
only when the request carried an id (`needsIdentityResolution`'s early return), so every occurrence
of it means "you brought an identifier and we did not check it". Read it as the caller's assertion
carried through, never as MusicBrainz agreeing.

**One guarded route is not a guarded surface.** The first fix checked the identifier on the route
`GENRE` takes and looked complete. Seven other types reach the same entity by their own route and
were all still unguarded: `BAND_MEMBERS`, `ARTIST_LINKS` and `ARTIST_POPULARITY` through a shared
artist helper, `ARTIST_DISCOGRAPHY` through a browse that looks nothing up, `ALBUM_TRACKS` through
its own release lookup, `CREDITS` through its own recording lookup, and `RELEASE_EDITIONS` through
the release-*group* id, whose response did not even carry an artist credit to check until
`inc=artist-credits` was added to it. Every one returned another entity's data at full confidence.

The shape of the mistake is worth more than the list: a guard placed in a *caller* protects that
caller, and the number of callers is not visible from the one being edited. The guards now sit on
the lookups themselves (`unlessDifferentArtist`), and `SuppliedIdentifierGuardMatrixTest` holds the
whole surface to the property rather than naming routes — it enrols a type by that type answering
the control request, so a new one joins by existing, not by being remembered.

**Contradiction and agreement need separate evidence.** `contradictsSuppliedName` reports only
*confident disagreement*; `nameMatchTier` reports only *confident agreement*; neither is the other's
negation, and the gap between them is unknown. Absence of contradiction is not corroboration, and a
future change that derives one from the other's failure reintroduces exactly the confusion above.

**Structured evidence beats the title, and only one piece of it survived contact with real data.**
A caller's own `year` and `trackCount` are the two things `EnrichmentRequest.ForAlbum` carries that
could catch a different album by the *same* artist, which the artist check provably cannot see. Both
rules were frozen before any data was captured, then scored on 181 studio release groups and 3139
releases (`corpora/album-year-contradiction/`). Track count fired on **29% of correct albums** —
deluxe editions, bonus discs, region variants — and was dropped. The year rule fired on **none**, and
ships as `unlessPredatingFirstRelease` on the release route and `markIfPredatingFirstRelease` on the
release-group one — two callers of one function, because the same mistake as above is available here:
a rule the release lookup applies is not a rule `RELEASE_EDITIONS` applies.

What made the year rule survivable is that it is one-sided by construction, not by tuning: an album
cannot predate its own first release, so an *earlier* caller year is positive evidence, while a
later one is any reissue and reports nothing. That costs about half the catch rate up front. The
population that decides such a rule is not a random wrong year — it is a caller whose identifier is
**right** and whose local tags came from a different pressing, which is the ordinary case.

**The evidence, ranked — and where it stops.** Each rank below is settled; reopening one needs new
data, not a new argument.

- **Cross-artist mismatch** — strong contradiction evidence. Reject the supplied identifier and
  recover by name.
- **Caller year predates the release group's first-release year** — conservative contradiction
  signal, in the measured legitimate-pressing population.
- **Track-count mismatch** — unsafe as contradiction evidence. Rejected after 29% false positives
  across legitimate pressings.
- **Title mismatch** — unvalidated and intentionally unused: edition, remaster and localisation
  variation make it a separate problem.

The comparison is deliberately on the **artist**, never the title — a remaster, an edition or a
localised title differs from what a caller typed while still being the album they meant. So a
different album *by the same artist* is not caught; that is a stated boundary with a test on it, not
an oversight.

Costs measured 2026-08-25 and recorded with their populations in
`.scratch/artist-mbid-provenance/spec.md`; the corpora ship as test fixtures under
`musicmeta-core/src/test/resources/corpora/artist-name-contradiction/`. Of 99 artist MBIDs Last.fm
hands out for its own chart, **0 were dead** — so a rule spent on detecting dead identifiers buys
nothing in that population, and wrong-but-live is the case worth paying for.

## 23. A memo that holds only successes multiplies a failing endpoint's retry cost by its readers

`CallMemo` writes on the success path, so a `fetch()` that throws leaves nothing behind and the next
type asking the same question runs the whole retry ladder again. That is the right default for a
memo whose readers are independent — but where *N* types read one upstream document, a sustained
failure costs *N* ladders instead of one, and none of them can succeed where the first could not.

`CoverArtArchiveProvider.getArtworkMetadata` is the worked case: four types read one release document
(ALBUM_ART's side-fetch, ALBUM_ART_BACK, ALBUM_BOOKLET, CD_ART), so a failing CAA release endpoint
was attempted 4 × the 3-attempt ladder = 12 times per call — and a *hung* one, which never reaches
the ladder at all, still cost one full timeout per type. Against a CAA sampled at ~10s per failed
attempt, an album enrich took 167-184s where sharing one budget takes 42-58s (offline replay over
captured samples, 2026-08-22 — treat the seconds as a scale, re-measure before building on them; the
attempt counts are arithmetic and `CoverArtArchiveMemoTest` pins the 4-to-1 collapse).

Wrap the outcome in a `Result` so the memo can hold a failure, and gate what may become one on
`ensureActive()` — §2's form, verbatim, for the same reason. **The blanket
`catch (e: CancellationException) { throw e }` is the trap inside the trap**: it was written here
first and it makes the whole fix a no-op against the failure shape that motivated it. A hung endpoint
does not usually arrive as an exhausted ladder — it arrives as a `CancellationException` from a
consumer `HttpClient`'s own `withTimeout`, raised while our job is perfectly healthy. Rethrown, that
memoizes nothing and every type re-attempts, which is the defect unchanged. `ensureActive()` makes
the right cut: our own cancellation escapes, a foreign one is the endpoint failing and is held.
`CoverArtArchiveMemoTest` pins both halves, and each half goes red under the other's implementation.

Before copying this, check what sharing costs. The memo is call-scoped, so nothing outlives one
`enrich()`. Under a sustained failure no result changes — every reader reports the `Error` it would
have earned running the ladder itself, and only the attempt count moves. But **a transient that
recovers between two readers is no longer visible to the second**: it inherits the first's error
where it would once have found its own `Success`. That trade is the fix, not an oversight in it —
it is what buys the collapse — but it is a real behaviour change and a memo whose readers can
genuinely disagree about a flapping endpoint should not take it.

## 27. A raw reserved character in a URL works on one client and cannot be sent on the other

`DefaultHttpClient` parses with `java.net.URI`, which refuses a raw `|`; `OkHttpEnrichmentClient`
hands the string to OkHttp's `HttpUrl`, which forwards it. So a provider URL carrying one is sendable
or unsendable depending on which transport the consumer injected, and the provider that built it
cannot tell from its own tests. It has shipped twice — Wikidata's multi-property query, and the
release-group browse, both live for months on the OkHttp path.

`HttpClient` puts the obligation on the caller: **the URL arrives already percent-encoded, and no
client decodes an escape or reinterprets a delimiter.** A `*Api` percent-encodes **every** value it
interpolates — a name, a title, a query, an identifier, the API key a consumer supplied — through
`encodeQueryValue` or `encodePathSegment`, whose difference is the one form encoding gets wrong for
a path: a space is `%20` there, not `+`. A numeric parameter needs no encoding, and its type is what
says so. A reserved character that is part of the *static* URL is written encoded at the call site —
`%7C` for a pipe separating multivalue parameters, not a literal `|`. Encoding at the client instead
cannot work: only the code that built the string knows whether a given `|` is a delimiter it meant or
data a user typed. What a client *may* do is canonicalize — OkHttp's `HttpUrl` removes dot segments
and encodes a raw space — so the contract is that the URL's meaning survives, not its bytes, and
nothing may depend on byte-identical transmission.

What catches a missed one is `FakeHttpClient.record()`, which fails the test that sent the URL.
`java.net.URI` refuses the characters neither client can carry; the shape check beside it refuses a
URL no template here produces — one with a fragment, or with a query pair that is not a single
`name=value` under a bare-word name. Between them a raw `#`, `?`, `=` or `&` arriving from an
unencoded value is a red test. The residue is a value that is itself a whole `&name=value` pair,
which no guard can tell from the template's own without reading the template; encoding at the call
site is what rules that one out. The guard also fires only on a URL some provider test exercises —
the release-group browse escaped because that call had no test at all, not because the guard was too
permissive.

## Area — Checks, comments, and config

## 9. A line-based check that skips lines can stop reading a file entirely

`check_test_shape.py` scans lines, so Kotlin fixture text inside a `"""` raw string is read as
source. The obvious repair — track the delimiter, skip what is inside — shipped twice and was wrong
both times in the same direction. Parity cannot tell an opener from a `"""` a comment mentions, nor
from a `//` inside an ordinary string literal; either wrong guess leaves the tracker stuck open, and
everything below is skipped, `@Test` lines included. Live on `DeezerProviderTest.kt`: **43 of its 49
tests were invisible while `check` printed clean across 86 test sources.**

`check_raw_string_content` tracks the same unreliable parity but *complains* about suspect lines
instead of skipping them, and only ever adds a finding. A wrong guess is now one visible error,
fixed by rewording the fixture. Precision unchanged; blast radius inverted.

**When a heuristic can't be made accurate, make it fail in the direction that shows.** A filter's
wrong guess hides work, a tripwire's is an annoyance. Any enter/exit flag also needs an end-of-file
invariant — getting stuck fails quiet all the way down.

## 10. Comment bulk is not a function of comment size

`CLAUDE.md`'s comment rule has no mechanism, and it cannot get one by counting. Neither detekt's
`comments` ruleset (10 rules, all presence/correctness, `active: false` by default) nor ktlint caps
comment size, so any gate here is hand-built — and every metric available to one is blind in the
same place.

Raw block *length* fares no better: the one cap that was tried picked its threshold (20 lines) to
clear a pre-designated exemplar, and the block-size histogram could not defend it — 25, and every
integer from 28 to 41, were equally unoccupied, so any "empirically derived" N there is a choice
wearing a measurement. Worse, a single blank line splits an over-long block into two passing ones,
and the failure message names the threshold, so it hands the reader the evasion.

The ratio evidence: measured once, 2026-08-09, over all 573 functions in the main sources — comment
lines against code lines per declaration, attributed over Kotlin PSI so a blank line cannot split a
block and a declaration's KDoc, leading `//` run and in-body comments all count against the code
they document. **To redo it:** walk `KtNamedFunction` nodes with `kotlin-compiler-embeddable`,
count lines touched by `PsiComment` against the rest, treat a line carrying both as code. Ratios
below are that measurement, not a live figure:

- `EnrichmentEngine.enrich` — 16 comment lines, 5 code, **ratio 3.20**. It is an interface member.
  A bodyless declaration has almost no code lines *by construction*, so the metric scores worst
  exactly where the caller contract matters most. Excluding bodyless members removes only 16 of the
  72 declarations at ratio ≥ 1.0.
- `MusicBrainzEnricher.pickBestRecording` — 42 comment lines, 17 code, the worst body-bearing case
  in the repo. Its ranking tiers record live-verified failure modes (§7). So does
  `DiscogsApi.searchArtist` at 25/11. Shortening either deletes evidence, not prose.

**No cutoff separates the incident from the best documentation in the repo, because on every
measurable axis they are the same object.** The distinction that matters — rationale a caller needs
versus rationale that is merely long — is not a length. This one stays review's job; the gap is
honest, and a gate here would be confidently wrong.

## 13. A config file that exists is not a config file that says anything

Both demos looked for `secrets.properties` in the app directory and then the repo root, took the
**first that existed**, and read only that one. `demo-web` ships a template of its own, so a copy
sitting next to the app with every line still commented out satisfied "exists" and shadowed a
filled-in file in the root. The demo ran keyless. The README says either location works; it did not.

What made it cost an afternoon rather than a minute is that the failure is *quiet and plausible*.
`ApiKeyConfig` treats a missing key as "skip that provider", which is a real supported mode — so
the run looked like a correct keyless run. The only signal was one startup line that scrolls past,
and the visible symptom was `no_provider` against exactly the keyed types, which reads as "I never
set those up" rather than "your keys are being ignored".

Two rules came out of it, and both generalise past this file:

- **Presence is not content.** A search path that stops at the first file that exists cannot
  distinguish a template from a configuration. Merge every file on the path — nearer last, so it
  wins per key — rather than electing one.
- **An empty value must not beat a fallback.** Every call site reads `secrets[k] ?: env(k)`, so a
  key present-but-blank returned `""`, which is non-null and therefore *won* against the environment
  variable it was supposed to defer to. Blank values are dropped per file, before the merge.

The same quiet keyless run has a second cause: **a git worktree has no `secrets.properties` at
all**, because the file is gitignored and worktrees materialise only tracked files, and the only
signal is the same startup line that scrolls past. Both demos now close this themselves: when the
repo root's `.git` is a worktree pointer *file*, `mainCheckoutSecrets` follows it and merges the
main checkout's `secrets.properties` as the outermost search-path layer, nearer files still winning
per key. Anything that reads keys by another route — an e2e run, a probe script — still starts
keyless in a worktree; a keyless measurement is not wrong, but it undercounts providers, so say
which mode a recorded number ran in.

The wider trap: any `?:` chain over a map is only as good as the map's willingness to say "absent".
A parser that faithfully records `k=` as `k -> ""` is not being helpful — it is converting an
absence into an answer, which is the same shape as §4 and as a `NotFound` standing in for a failure.

## 15. A detekt `excludes:` list replaces the defaults, it does not extend them

`config/detekt.yml` sets `buildUponDefaultConfig = true` (`build.gradle.kts`), so a rule the repo
never mentions keeps detekt's own defaults. A rule the repo *does* mention keeps none of them: the
`excludes:` list written here is the whole list, and every default path it does not restate is
silently back in scope.

`FunctionNaming` is where this bites. detekt's default config already exempts `**/test/**`, so
writing `excludes: ['**/testFixtures/**']` to cover the contract bases reads as adding one path. It
removes one. Drop `**/test/**` and run `:musicmeta-core:detektTest` and the build **fails with one
`FunctionNaming` issue per backtick-named `@Test` function in the module**; restore it and the same
run exits 0. **The count is not the evidence and is not quoted here** — it tracks the test population
and was already 60 out of date within the change that first measured it. The failure, not the figure,
is what the probe establishes.

**The failure direction is the reason this needs a note.** Both mistakes here are quiet. Restate a
default that is still a default and the config is merely redundant — nothing fails, so nothing tells
you. Drop one that is load-bearing and the rule fires once per test function, which is loud but
arrives as an unrelated wall of noise during an upgrade. Neither teaches you which of the two you
are looking at.

**On any detekt version bump, re-run the probe rather than reasoning about it:** drop `**/test/**`
from `FunctionNaming`, run `:musicmeta-core:detektTest`, and confirm it *fails*. If it fails, the
merge semantics are unchanged and the entry stays as it is. If it passes, detekt now appends rather
than replaces, and the restated default can go. Nothing mechanises this; a bump that skips it leaves
the repo carrying an exclusion broader than it needs, with `FunctionNaming` dead across every test
source and no signal saying so.


## 16. A probe that measures nothing reports a plausible number

A probe plants a deliberate break, runs a gate, and reads the result. Every step of that can succeed
while the break was never planted, and the run then measures the unbroken file and reports a figure
that looks like an answer. `open(p, 'w').write(open(p).read().replace(a, b))` is the shortest way to
get there: `open(p, 'w')` truncates before the read on the right-hand side executes, so the file is
emptied and the gate honestly reports what it found in an empty file. The number that comes back is
wrong by a factor, not by an obvious margin, which is exactly why it survives review.

**The recipe: assert the planted edit is present before trusting the run.** `diff` the file against
the copy taken beforehand, or `grep` for the string the edit was supposed to introduce, and fail the
probe if the edit is not there. Then read the gate's *unfiltered* output at least once — a filter
narrowed to the rule under test hides the finding that says the file is empty or unparseable. Edit
in place with `sed -i` or a heredoc rather than a read-then-write; restore by `cp` from the copy, not
`git checkout`, which takes uncommitted work with it. This applies to a probe against a lint config,
a check script, or a test: a probe is instrumentation, and instrumentation that cannot fail is the
same defect the thing being probed is meant to catch.


## 17. `runTest`'s clock makes a timeout over real I/O fire unconditionally

`runTest` runs on a virtual clock that jumps to the next scheduled time the moment every coroutine it
owns is idle. A test coroutine that suspends on **real** blocking I/O — a socket, a loopback server, a
file — is idle by that definition, so the clock leaps forward while the I/O is still in flight. Any
`withTimeout` wrapping that call therefore fires on every run, whatever the I/O did, and however
quickly it did it.

A test written to prove that a timeout propagates cancellation rather than being swallowed into a
classified result is then asserting nothing: its `catch (e: CancellationException)` runs
unconditionally, and the assertion is true by construction. It passes against correct code, passes
against a client that swallows cancellation, and passes against a server that answers instantly. It
cannot fail, which is the defect it was written to prevent.

**The recipe, and it attacks the premise rather than the implementation: remove the thing the test
says causes the outcome, and check the outcome goes away.** For a timeout test, set the server's
delay to zero so there is nothing to time out, assert the edit is present (§16), and run the suite.
The test must go **red**. If it stays green the timeout is virtual, the assertion is a tautology, and
no mutation of the code under test will ever reveal it — this failed to show up under a deliberately
broadened `catch` in the retry ladder, because cancellation is sticky and the framework absorbs the
mutation before it reaches the caller.

The fix is to put the timed section on a real clock — `withContext(Dispatchers.Default) {
withTimeout(...) { … } }` — not to switch the test to `runBlocking`, which reintroduces the real
`RateLimiter` delays `runTest` exists here to keep virtual. **"I could not make this go red" is a
finding, not a footnote**: a test verified only against unmutated code is proven to pass and unproven
to fail, and only the second claim is worth anything.


## 18. A test-results directory outlives the tree that produced it

`build/test-results/` is not cleared when the sources that produced it are reverted, and Gradle
serves it again untouched whenever `test` resolves to `UP-TO-DATE`. So a count read from that
directory describes **whichever tree last actually ran the tests**, which is not necessarily the tree
being certified. Apply a patch, run the suite, revert the patch, read the XML: the numbers still
describe the patched tree, and nothing in the output says so.

This is distinct from the two adjacent traps. It is not §16's — the edit really was planted, and the
run really did happen. It is not a suite that silently failed to re-run either, because the figure is
a genuine measurement; it is a genuine measurement **of the wrong thing**. The failure is legible
only if the number happens to look wrong: a suite reported as six tests larger than the tree can
account for is a lucky catch, and the same mistake in the failure count would read as a clean run.

**The recipe: `--rerun-tasks` for any figure that certifies a state, and take the figure after the
revert rather than around it.** `rm -rf` the module's `test-results` directory first if a previous
run's tree differed at all — a stale file that is never overwritten is served forever, because
`UP-TO-DATE` skips the writer, not just the tests.

One related hazard in the same procedure: **`git apply --3way` writes to the index**, so a
`git checkout -- <paths>` restore afterwards restores *from the staged patch* and reverts nothing.
Unstage first (`git reset -q HEAD -- .`, which leaves the worktree alone), then check out the tracked
paths, then require `git status --porcelain` to print nothing. **A restore that was not verified is a
restore that did not happen**, and the next run inherits the leftovers.

The same failure has a second route, and this repo is unusually exposed to it: **a relative path
resolves against whatever directory the shell is in**, and `.claude/worktrees/` can hold dozens of
checkouts of this repo at once. A command written with relative paths, run when the working directory
is a different worktree than intended, edits and measures that other tree — reporting a real,
internally consistent result about the wrong commit. It is not detectable from the numbers: a probe
aimed at a branch and run against `main` reports `main`'s test count, and looks exactly like a probe
that found nothing to report.

**Use absolute paths for anything that edits or measures a specific tree, and print the commit under
test before trusting the run** — `git -C <path> log --oneline -1` costs nothing and names the tree the
numbers came from. **Then assert the edit changed the number of lines you expected**, not merely that
it changed something: a mutation that deletes the intended block and a neighbouring one is still
"present" by any grep, and it will fail to compile rather than fail the test, which reads as a broken
build rather than a broken probe.


## 19. A suite can be fully covered by count and carry no test that discriminates

Coverage is usually judged by what exists: a case per branch, a test per behaviour, a file per
subject. That measure cannot see whether any of those tests would notice the behaviour going away.
A test whose scenario puts the subject in a state where it is **inert by specification** — every
candidate tied, the input null, the collection empty — passes identically whether the subject is
present, broken, or deleted. It is a characterisation of the inert case, and it reads on the page
exactly like a regression test for the live one.

Two such tests can sit beside each other covering "no matching candidate" and "the input was null",
and between them exercise every line of a ranking tier while proving nothing about it. Delete the
tier and both stay green. The suite still reports a healthy count, the diff still shows tests added
alongside the change, and review still sees a subject with tests next to it.

**This is invisible to reading, including careful reading.** A review that examined both a release
suite and a recording suite here found the asymmetry between them — five tests against two — and
described it as a coverage gap in *count*. The two suites' shared blind spot was that neither of the
two cases present in both could fail under the mutation its own subject implies, and no amount of
reading the tests surfaced that; demanding the mutation did, immediately.

**The recipe: pick the mutation the subject implies — remove the tier, drop the field, invert the
condition — and require a named test to go red.** State the expected red set *before* running it, and
report a deviation rather than reconciling it; a count that comes back different is the finding. When
a test genuinely cannot fail that way and is still worth keeping, **say so in its title or KDoc**:
inertness is a real property the callers may depend on, and an unlabelled test that cannot fail will
be refiled as a gap by the next reader, or trusted as a guard by the one after that.

Distinct from §16 (a probe whose planted edit was never present) and §18 (a real measurement of the
wrong tree): here the test runs, the measurement is honest, and the subject is genuinely exercised —
it simply cannot register the subject's absence.
