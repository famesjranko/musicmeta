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
- A `CompositeSynthesizer`'s `dependencies` are resolved even when the caller did not ask for them.
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

**Both historical breaks are still in the tree, deliberately** — `identifiers` still sits mid-list in
`EnrichmentEngineExtensions.kt`, `genreTags` still at position 2 of `EnrichmentData.Metadata`.
Reordering either *now* is a second break. Do not "fix" them to match this rule; apply the rule to
new parameters and fields, which go last with a default.

- **Persisted data** — name-based JSON survives reordering, but *replacing or removing* a field
  breaks what consumers already stored: v0.4.0 swapped `SimilarArtist.musicBrainzId` for
  `identifiers` and broke every Room cache entry in the field. Any payload change asks whether
  `CHANGELOG.md` needs a cache-clear note. Round-trip tests cannot catch this — they encode and
  decode with the same code (`VERIFICATION.md`; goldens unwritten).
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

## Area — Provider data and matching

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
the code could not keep.

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

Combined-field search results carry a second trap: a provider that names both artist and album in
one display string (Discogs's `"Artist - Title"`) cannot be safely split at the first delimiter,
because either half may itself contain that delimiter. Stopping at the first boundary whose
artist-side merely passes the loose artist floor picks a false split when the real artist name
itself contains the delimiter. The safe parse tries every boundary and prefers the one where *both*
the artist and title sides match the request, falling back to an artist-only match only when no
boundary clears both sides.

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
