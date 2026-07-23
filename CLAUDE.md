# CLAUDE.md

Onboarding for anyone — human or agent — changing this repo. `make check` is the gate: run it before
you push, CI runs the same script. [`ARCHITECTURE.md`](ARCHITECTURE.md) is the register of which
rules a mechanism enforces and which are merely intended; **rules with a mechanism are not repeated
here**, and where a document and a config disagree the config wins, because the config is the thing
that fails. Elsewhere: `README.md` (consumer-facing), `CHANGELOG.md` (one line per change, headline
plus `(#issue)`), `ROADMAP.md`, `docs/project/workflow.md`, `docs/project/release.md`.

There is no history document. `STORIES.md` was deleted (2026-07-23): git and the PR hold what
happened, and *why* a thing is the way it is belongs in a comment next to the mechanism. If an entry
can become wrong, it was never history.

## Architecture map

Paths below are relative to `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/` (package
`com.landofoz.musicmeta`). A call to `enrich(request, types, forceRefresh)` walks
`engine/DefaultEnrichmentEngine.kt` top to bottom; that one function is the map:

1. **Cache** — `forceRefresh` invalidates first, then each type is read via `guardedCacheRead`. Hits
   return without touching a provider; the rest become `uncachedTypes`. Keys come from
   `engine/EntityKey.kt` (`entityKeyFor`, plus the name-alias `entityKeyForName`). Every cache read
   and write inside `enrich()` goes through `engine/CacheGuard.kt` — a throwing cache degrades to a
   miss, never a failed `enrich()`.

2. **Identity resolution** — gated on `EnrichmentConfig.enableIdentityResolution` and
   `needsIdentityResolution()` (`engine/IdentityHelper.kt`, data-driven from capabilities).
   `ProviderRegistry.identityProvider()` returns the one provider with `isIdentityProvider = true` —
   today `MusicBrainzProvider`. Its `resolvedIdentifiers` merge into the request's
   `EnrichmentIdentifiers` (`request.withIdentifiers(...)`), so downstream providers do MBID lookups
   instead of fuzzy search. A `NotFound` carrying `suggestions` short-circuits the fan-out entirely.

3. **Chains** — `engine/ProviderRegistry.kt` builds one `ProviderChain` per `EnrichmentType` up
   front, sorted by `ProviderCapability.priority` (100 = primary, 50 = fallback) and
   `EnrichmentConfig.priorityOverrides`; one `http/CircuitBreaker.kt` per provider id, shared across
   every chain.

4. **Fan-out** — `resolveTypes()` splits the types three ways inside `coroutineScope { async { } }`:
   regular → `ProviderChain.resolve()` (first `Success` wins, `NotFound` falls through); mergeable
   (a `ResultMerger` is registered) → `ProviderChain.resolveAll()` collects every `Success`
   concurrently and the merger folds them (`engine/GenreMerger.kt`, `ArtworkMerger.kt`,
   `SimilarArtistMerger.kt`, `SimilarTrackMerger.kt`, `TopTrackMerger.kt`); composite → a
   `CompositeSynthesizer` (`engine/TimelineSynthesizer.kt`, `GenreAffinityMatcher.kt`) runs after its
   `dependencies`, which are resolved even when the caller did not ask for them. Both strategy kinds
   run inside `engine/StrategyGuard.kt`, which turns a throw into `Error` for that type.

5. **Eligibility and confidence** — `ProviderChain` skips providers that are unavailable, lack the
   capability's `IdentifierRequirement`, or have an open breaker. `filterByConfidence()` demotes any
   `Success` below `EnrichmentConfig.minConfidence` (default 0.5) to `NotFound`;
   `confidenceOverrides` is keyed by provider id and replaces the score outright.

6. **Post-processing and write-back** — `engine/CatalogFilter.kt`, identity-match stamping,
   `CacheMode.STALE_IF_ERROR` fallback to expired entries, then non-stale successes are cached under
   the primary key and, when identity added an MBID, the name-alias key too. Steps 2–6 sit inside
   `withTimeout(config.enrichTimeoutMs)`; on expiry unfinished types become
   `Error(..., ErrorKind.TIMEOUT)`.

Root-package types: `EnrichmentEngine.kt` (interface + the `Builder` wiring providers →
`ProviderRegistry` → `DefaultEnrichmentEngine`), `EnrichmentRequest.kt` (sealed:
`ForAlbum`/`ForArtist`/`ForTrack`), `EnrichmentResult.kt` (sealed:
`Success`/`NotFound`/`RateLimited`/`Error`), `EnrichmentResults.kt` (return value, raw map via
`.raw`), `EnrichmentData.kt` (`@Serializable` payloads), `EnrichmentProvider.kt` (interface,
`ProviderCapability`, `IdentifierRequirement`, `mapError`), `EnrichmentType.kt`,
`EnrichmentConfig.kt`, `EnrichmentCache.kt`.

## Module conventions

- **`musicmeta-core`** — pure Kotlin/JVM, no Android SDK. The engine, providers, `http/`.
- **`musicmeta-android`** — `RoomEnrichmentCache` (+ DAO/entity/database under `android/cache/`),
  `HiltEnrichmentModule`, `EnrichmentWorker`. Tests run under Robolectric.
- **`musicmeta-okhttp`** — one class, `OkHttpEnrichmentClient`, an `HttpClient` implementation.
- **`demo/`** — a *separate composite build*, never compiled by `./gradlew build`. It is the only
  in-tree consumer that compiles against the published surface the way an external one does. Exempt
  from house Kotlin style on purpose (`ARCHITECTURE.md`); `demo/run.sh` is still shellchecked.

Each provider lives in `provider/<name>/` as four files, and the split is load-bearing — the first
three are `internal`, so they can be renamed freely without an `apiDump`:

| File | Holds | Visibility |
|---|---|---|
| `*Api.kt` | HTTP calls, URL building, `org.json` parsing into models | `internal` |
| `*Models.kt` | plain data classes mirroring the API response, nothing else | `internal` |
| `*Mapper.kt` | model → `EnrichmentData` translation, pure functions | `internal` |
| `*Provider.kt` | implements `EnrichmentProvider`: capabilities, Api → Mapper → `EnrichmentResult` | **public** |

The public surface was narrowed to that boundary in v0.10.0 (#5): the `*Provider` classes,
`HttpClient`/`HttpResult`/`HttpResponse`/`DefaultHttpClient`/`RateLimiter`, the
`ResultMerger`/`CompositeSynthesizer` extension points, and the root-package types above.
`CircuitBreaker`, `MusicBrainzParser` and the built-in mergers/synthesizers are `internal` too, so a
refactor confined to them leaves `apiCheck` green. `RateLimiter` stays public only because it is a
parameter of nearly every provider constructor.

Tests mirror the main tree under `src/test/kotlin/...` (`engine/`, `http/`, `provider/<name>/`,
`cache/`, plus `e2e/` — live APIs, gated by `-Dinclude.e2e=true`, never merge-gating). Fakes over
mocks: `testutil/FakeProvider`, `FakeHttpClient`, `FakeEnrichmentCache`. Coroutine tests use
`kotlinx.coroutines.test.runTest`. Names are backticked sentences —
`` `provider returns NotFound when album has no art` `` — with Given-When-Then comments saying what
is given, what is done, and what is expected, not bare section markers.

## Commands

`make help` lists everything. The ones that matter:

```bash
make bootstrap      # once per machine: installs the pinned ruff/mypy/shellcheck ./check requires
make check          # the gate — exactly what CI runs
make check-fast     # lint + types only, for the edit loop; never evidence for a push
make test           # core unit tests
make test-all       # every module
make test-e2e       # live third-party APIs, needs keys
make format         # rewrite Kotlin and Python to house style
make lint           # all four lint layers, no tests — detekt compiles first, its rules need types
make api-check      # public ABI vs the committed api/*.api baselines
make api-dump       # regenerate them after an intentional change — the .api diff is the record
make demo           # compile demo/, the external-consumer canary
make versions       # module versions agree with each other and the CHANGELOG
```

A single class or method still needs Gradle directly:
`./gradlew :musicmeta-core:test --tests "…engine.ProviderChainTest"`.

## Pitfalls

Each of these cost this project a release, an issue, or a backfilled `### Breaking Changes` entry.

### 1. Inserting a parameter mid-list silently rebinds every argument after it

```kotlin
// WRONG — v0.9.2 did this to albumProfile/artistProfile/trackProfile, in a *patch* release
suspend fun EnrichmentEngine.albumProfile(
    title: String, artist: String, mbid: String? = null,
    identifiers: EnrichmentIdentifiers? = null,       // inserted here
    types: Set<EnrichmentType> = DEFAULT_ALBUM_TYPES,
)
// caller from v0.9.1: albumProfile("OK Computer", "Radiohead", null, myTypes)
//   → myTypes now binds to `identifiers`

// RIGHT — append, with a default
suspend fun EnrichmentEngine.albumProfile(
    title: String, artist: String, mbid: String? = null,
    types: Set<EnrichmentType> = DEFAULT_ALBUM_TYPES,
    identifiers: EnrichmentIdentifiers? = null,       // appended
)
```

Consequence: every positional caller re-binds silently. **The demo canary proves consumers still
compile; it does not prove argument order held** — v0.9.2 escaped it and was caught only by a type
mismatch; a `String?` inserted between `artist` and `mbid` would have compiled green and wrong.
`make api-check` plus reading the `.api` diff is the guard. Also recorded at v0.6.0
(`EnrichmentConfig.radioDiscoveryMode`) and v0.5.0 (`Metadata.genreTags`).

### 2. `catch (e: Exception)` in a suspend function eats cancellation

```kotlin
// WRONG — swallows our caller's cancellation
try { cache.get(key, type) } catch (e: Exception) { logger.warn(TAG, e.message); null }

// ALSO WRONG — rethrows a cancellation that was never ours (see the note below)
try { block() }
catch (e: CancellationException) { throw e }
catch (e: Exception) { logger.warn(TAG, "…"); fallback }

// RIGHT — engine/ProviderChain.kt, engine/DefaultEnrichmentEngine.kt
try { block() }
catch (e: Exception) {
    currentCoroutineContext().ensureActive()   // throws only if *this* job was cancelled
    logger.warn(TAG, "…"); fallback
}
```

Consequence: `CancellationException` is an `Exception`, so swallowing it defeats
`withTimeout(config.enrichTimeoutMs)` and leaves `enrich()` working for a caller that has gone away.
Both guards exist because a throwing cache (#22) and a throwing merger (#28) escaped `enrich()`;
every consumer-implementable interface needs one. `EnrichmentProvider`'s lives in two places:
`ProviderChain` rethrows around `provider.enrich(...)`, and `mapError()` rethrows rather than
classifying — which is why a provider's `catch (e: Exception)` should call `mapError(type, e)`
instead of building an `EnrichmentResult.Error` by hand.

The worst version is not the swallowed cancellation but what the result does next: an `Error`
makes `ProviderChain` record a circuit-breaker **failure**, so before #53 every `enrichTimeoutMs`
expiry counted against providers that never failed.

**Use `ensureActive()`, not `catch (CancellationException) { throw e }`.** The blanket rethrow is
wrong in the other direction: a `CancellationException` can also come from *inside* a provider —
its own `withTimeout` expiring — while our job is perfectly healthy. Rethrowing that escapes the
chain, cancels sibling providers, and is reported to the caller as the engine's deadline.
`ensureActive()` throws only when *this* job is cancelled, so the foreign case stays contained as
one provider's error. Both directions were shipped and caught during #53; the behaviour is pinned
by `ProviderChainCancellationTest`, not by a lint rule.

`engine/CacheGuard.kt` and `engine/StrategyGuard.kt` still carry the blanket rethrow. They predate
#53 and were not revisited, so they are the second form above, not the third — tracked in #61. Copy
`ProviderChain`, not those two.

**Do not reason that a swallowed cancellation is harmless because "it re-asserts at the next
suspension point".** It is not a guarantee — cancellation is cooperative, and a suspend function
may return without ever suspending again. That claim was written here during #53 and used to wave
through five real bugs. See [Kotlin's cancellation docs](https://kotlinlang.org/docs/cancellation-and-timeouts.html).

### 3. `org.json` returns a default for a missing key — it does not fail

```kotlin
// WRONG — shipped in 0.9.0; ListenBrainz sends recording_name, not track_name
title = item.optString("track_name", "")        // every TopTrack title was ""

// RIGHT — read the field the API actually sends, and treat blank as absent
val mbid = item.optString("recording_mbid").takeIf { it.isNotBlank() } ?: continue
albumName = item.optString("release_name").takeIf { it.isNotBlank() }
```

Consequence: no exception, no `NotFound` — empty strings enriched, cached, and persisted, needing a
0.9.1 fix plus a "clear your cache" migration note. Provider tests must assert against a fixture
copied from a real response, and `provider-drift.yml` watches for the fields moving again.

### 4. On a `@Serializable` payload the same insertion also breaks stored data

```kotlin
// WRONG — v0.5.0 inserted genreTags between genres and label
data class Metadata(val genres: List<String>? = null,
    val genreTags: List<GenreTag>? = null,   // every following element index shifted
    val label: String? = null, …)

// RIGHT — append: data class Metadata(val genres…, val label…, …, val genreTags…)
```

Consequence: name-based JSON survives, but element indices shift for index-based formats and
hand-written serializers, and positional `copy()` rebinds. v0.4.0 went further — replacing
`SimilarArtist.musicBrainzId` with `identifiers` broke every entry already persisted in the Room
cache, which is why a payload change asks whether it needs a cache-clear note in `CHANGELOG.md`.

### 5. `Error` and `NotFound` are not interchangeable in a provider

```kotlin
// WRONG — "the API has no cover art for this release" is not a failure
if (url == null) return EnrichmentResult.Error(type, id, "no artwork")

// RIGHT
if (url == null) return EnrichmentResult.NotFound(type, id)
```

Consequence: `ProviderChain` records a breaker *failure* on `Error` and a *success* on `NotFound`, so
mislabelling opens the circuit breaker against a healthy provider, and `CacheMode.STALE_IF_ERROR`
starts serving stale data. Reserve `Error`/`RateLimited` for transport and protocol problems —
`mapError()` on `EnrichmentProvider` classifies those into the right `ErrorKind` for you.

### 6. A capability's `identifierRequirement` defaults to `NONE`

```kotlin
// WRONG — this provider needs an MBID, but the chain will call it without one
ProviderCapability(type = EnrichmentType.ALBUM_ART, priority = 100)

// RIGHT
ProviderCapability(EnrichmentType.ALBUM_ART, priority = 100,
    identifierRequirement = IdentifierRequirement.MUSICBRAINZ_ID)
```

Consequence: `ProviderChain.hasRequiredIdentifiers()` is the only thing keeping an ID-only provider
from being called with nothing to look up. Undeclared, it burns a rate-limited request and returns
`NotFound` — which records breaker **success**, so a provider that never works looks healthy while a
lower-priority fallback wins the type. Declare it on every capability entry, not just the first.

### 7. Deleting an unused constructor parameter is still an ABI break

```kotlin
// WRONG as a "dead code cleanup" — this is a public constructor
class DefaultEnrichmentEngine(
    private val registry: ProviderRegistry,
    override val cache: EnrichmentCache,
-   private val httpClient: HttpClient,      // detekt: UnusedPrivateProperty
    private val config: EnrichmentConfig,
)
// RIGHT — a documented break in a 0.x *minor*, with an api-dump and a ### Breaking Changes
// entry — or it waits. Tracked in #48.
```

Consequence: `Builder.build()` allocates a second `DefaultHttpClient` purely to fill it, so it looks
like free cleanup — but it changes a published signature and cannot ride a patch. Same for anything
in `config/detekt-baseline-*.xml`: check whether the finding sits on a public signature first.

## Backwards compatibility

Published to Maven Central and JitPack — assume external consumers exist.

**The 0.x carve-out.** Pre-1.0, semver permits breaking changes on minor releases, and this repo
takes that carve-out explicitly:

- **Minor (`0.x.0`) MAY break the public API** — every break documented under a
  `### Breaking Changes` heading in `CHANGELOG.md` *and* visible in the reviewed `api/*.api` diff.
  A break that is in neither is a defect, not a release.
- **Patch (`0.x.y`) may NOT break the public API.** v0.9.2 did exactly this (pitfall 1); that is why
  the rule is written down.
- **At `1.0.0`** full semver applies: no break without a major bump.

Whatever the channel, evolve additively, and flag any break to the user before proceeding. Breaking
means removing or renaming public classes, functions or parameters; changing a return type;
reordering non-named parameters; changing enum or sealed-class variants a consumer may `when` over.
Prefer a new overload or a defaulted parameter appended at the end; deprecate with
`@Deprecated(ReplaceWith(...))` for at least one minor before removal. On the JVM, adding a
parameter *anywhere* to a function with defaults changes the method descriptor, so appending is the
source-compatible floor, not a full ABI guarantee — a 0.x minor is the place for either.

## Git rules

- **Never** add `Co-Authored-By`, "Generated with Claude", or any AI/Anthropic/tool attribution to
  commits, PR titles or bodies, issue comments, or anything else that leaves this machine.
- **Never** use `git revert` — it loses the change and records both the mistake and the undo. Use
  `git reset` or a manual edit.
- Always ask before running a destructive git command.

## Key conventions

- Group id `io.github.famesjranko`, Java 17, dependencies via `gradle/libs.versions.toml`.
- Provider responses are parsed with `org.json`; consumer-facing payloads use
  `kotlinx.serialization`.
- MusicBrainz needs a descriptive User-Agent and max 1 request/second — see the `RateLimiter(1100)`
  in `Builder.withDefaultProviders()` and `EnrichmentConfig.userAgent`.
- Comments explain non-obvious constraints, traps and guards — not what the code says — and stay to
  a line or two. Nothing enforces this (`ARCHITECTURE.md`, *Not enforced*); it is a judgement call.
