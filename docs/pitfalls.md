# Pitfalls

Each of these cost a release, an issue, or a backfilled `### Breaking Changes` entry. `CLAUDE.md`
carries the one-line rule; this file carries the worked example and the reason. Read the entry before
touching the thing it names.

## Traps in the pipeline

Read `enrich()` in `engine/DefaultEnrichmentEngine.kt` — it is the map, and this list is not
exhaustive. Paths are relative to `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/`.

- `CacheGuard.kt` degrades a throwing cache to a miss, but public `invalidate()`,
  `is`/`markManuallySelected()` and `getIncludingExpired()` are unguarded.
- An identity `NotFound` carrying `suggestions` short-circuits the whole provider fan-out.
- One `http/CircuitBreaker.kt` per provider id, shared across every chain.
- A `CompositeSynthesizer`'s `dependencies` are resolved even when the caller did not ask for them.
- `withTimeoutOrNull(enrichTimeoutMs)` returns null for *that* deadline only; a nested one
  propagates. An expiry does not discard results already fetched, but the write-back is skipped, so
  a timed-out run caches nothing. The stale fallback and write-back sit outside the timed block.
  §6 below holds the worked example.
- `filterByConfidence()` demotes a `Success` below `minConfidence` (0.5) to `NotFound`; its sibling
  `demoteUnanswered()` demotes one whose payload does not `answers()` its type, at any confidence,
  and runs on the cache read too — where an unanswered entry counts as a *miss*, so one written by
  an older build is refetched and healed rather than pinned for its TTL (§8).

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

**Both historical breaks are still in the tree, deliberately** — `identifiers` still sits mid-list in
`EnrichmentEngineExtensions.kt`, `genreTags` still at position 2 of `EnrichmentData.Metadata`.
Reordering either *now* is a second break. Do not "fix" them to match this rule; apply the rule to
new parameters and fields, which go last with a default.

- **Persisted data** — name-based JSON survives reordering, but *replacing or removing* a field
  breaks what consumers already stored: v0.4.0 swapped `SimilarArtist.musicBrainzId` for
  `identifiers` and broke every Room cache entry in the field. Any payload change asks whether
  `CHANGELOG.md` needs a cache-clear note. Round-trip tests cannot catch this — they encode and
  decode with the same code (`ARCHITECTURE.md`; goldens unwritten).
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

The surface was narrowed to the four-role boundary in v0.10.0 (#5). `CircuitBreaker`,
`MusicBrainzParser` and the built-in mergers/synthesizers are `internal`, so a refactor confined to
them leaves `apiCheck` green. `RateLimiter` is public only because it is a parameter of nearly every
provider constructor.

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
`CacheGuard`, `StrategyGuard`, `DefaultEnrichmentEngine`, `ITunesProvider` and `DeezerProvider`.
`guardedStrategy` is `suspend` purely to reach the job, since `ResultMerger.merge` and
`CompositeSynthesizer.synthesize` are not. `EnrichmentLogger` is the one consumer-implementable
interface guarded *without* `ensureActive()` — its two methods are not `suspend`, so cancellation
cannot be delivered into them and a `CancellationException` there can only be one the consumer's
logger built itself. `EnrichmentLogger.guarded()` holds the reasoning; the wrapper is applied at
`EnrichmentEngine.Builder.logger` so no call site repeats it (#71). Enforced by behaviour, not a rule — a textual rule was
written and deleted because the remediation it printed was itself the defect (`ARCHITECTURE.md`). Read `EnrichCacheFailureTest`, `EnrichStrategyFailureTest` and
`ProviderChainCancellationTest` before writing a cancellation test of your own. A provider's own
`catch` calls `mapError(type, e)` and deliberately does not special-case `CancellationException`.

**Never reason that a swallowed cancellation is harmless because "it re-asserts at the next
suspension point"** — cancellation is cooperative and a suspend function may never suspend again.

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

## 4. `Error` and `NotFound` are not interchangeable

```kotlin
if (url == null) return EnrichmentResult.Error(type, id, "no artwork")  // WRONG — not a failure
if (url == null) return EnrichmentResult.NotFound(type, id)            // RIGHT
```

`ProviderChain` records a breaker *failure* on `Error` and a *success* on `NotFound`, so mislabelling
opens the breaker against a healthy provider and `STALE_IF_ERROR` starts serving stale data. Reserve
`Error`/`RateLimited` for transport and protocol problems; `mapError()` classifies those into the
right `ErrorKind`. Nothing can check this — only you know what the response meant.

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
coroutine with its own. `CatalogProvider` is the live case only because it is the one
consumer-implementable call the engine makes unguarded; the cache, merge strategies and providers
reach it through `ensureActive()` guards instead (§2).

**A timed-out `results` map is a mix, not a prefix.** `applyCatalogFiltering()` rewrites entries one
type at a time inside the deadline, so an expiry mid-loop leaves some types filtered and some raw,
and the timeout backfill only fills types that are *missing* — a half-filtered `Success` carries no
marker at all. That mix is fine to return and was never fine to cache: the write-back ran outside
the deadline and persisted it under the primary *and* name-alias keys, so every later lookup was a
hit that skipped filtering (#56). Anything added inside that block inherits the same shape, which is
why the guard is on the write-back rather than on catalog filtering.

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
per type** — `label` answers `LABEL` and nothing else, and only `ALBUM_METADATA` accepts any field at
all. Every other payload answers its type iff it carries anything. The `when` is exhaustive over payload
*classes*, so the compiler asks about a new one — it is **not** exhaustive over types, so a new type
served by `Metadata` inherits grab-bag semantics. That fails lenient, which is the right direction:
the gate's job is to catch payloads answering *nothing*, not to adjudicate partial ones.

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
  in the repo. Its five ranking tiers each record a live-verified failure mode (§7). So does
  `DiscogsApi.searchArtist` at 25/11. Shortening either deletes evidence, not prose.

**No cutoff separates the incident from the best documentation in the repo, because on every
measurable axis they are the same object.** The distinction that matters — rationale a caller needs
versus rationale that is merely long — is not a length. This one stays review's job; the gap is
honest, and a gate here would be confidently wrong.
