# Migration guide

Every break musicmeta has shipped since 0.10.0, newest first, with the edit each one asks of you.
Find the version you are on, then work down this page from the top to it.

Two kinds of break appear here and they cost you different things:

- **Source breaks** stop your code compiling. Each has its own section, with a before/after pair.
- **Binary-only breaks** compile unchanged but need a rebuild against the new artifact; skip the
  rebuild and the JVM throws `NoSuchMethodError` or `AbstractMethodError` at the first call. They
  are collected in one table per version, because there is no source edit to show.

The full per-release list, additions and fixes included, is [CHANGELOG.md](../../CHANGELOG.md).

## Unreleased

### `RadioDiscoveryMode.apiValue` is internal

It carried ListenBrainz's wire strings, which are that provider's business rather than a contract
worth freezing.

<!-- no-compile: reads the withdrawn `apiValue` property -->
```kotlin
val wireValue = RadioDiscoveryMode.EASY.apiValue
```

Branch on the constant and supply your own label:

```kotlin
val radioMode = RadioDiscoveryMode.EASY
val radioLabel = when (radioMode) {
    RadioDiscoveryMode.EASY -> "Easy"
    RadioDiscoveryMode.MEDIUM -> "Medium"
    RadioDiscoveryMode.HARD -> "Hard"
}
```

### `ProviderPolicies.AS_READ_ON` is removed

One date for every provider was a fiction: each entry's terms were read on its own day.

<!-- no-compile: reads the withdrawn `AS_READ_ON` constant -->
```kotlin
val readOn = ProviderPolicies.AS_READ_ON
```

Read the date off the entry you are displaying:

```kotlin
val lastfmReadOn = ProviderPolicies.all["lastfm"]?.asReadOn
```

### `EnrichmentData.TrackPreview.durationMs` is nullable, with no 30-second default

The 30-second default was Deezer's clip length dressed as a fact about every source. Deezer still
reports 30000; a source that does not state a length now reports nothing.

<!-- no-compile: `durationMs` was a non-null `Long` defaulting to 30000 -->
```kotlin
val clipSeconds: Long = preview.durationMs / 1000
```

Handle the unknown length:

```kotlin
val preview = EnrichmentData.TrackPreview(url = "https://example.test/clip.mp3", source = "deezer")
val clipSeconds = preview.durationMs?.div(1000)
```

### `MusicBrainzProvider` drops `thumbnailSize` and `DEFAULT_THUMBNAIL_SIZE`

A MusicBrainz release search carries no cover-art flag, so the thumbnail that parameter sized was
never built.

<!-- no-compile: passes the withdrawn `thumbnailSize` argument -->
```kotlin
val musicBrainzSized = MusicBrainzProvider(httpClient, rateLimiter, thumbnailSize = 500)
```

Drop the argument:

```kotlin
val musicBrainz = MusicBrainzProvider(DefaultHttpClient("MyApp/1.0 ( you@example.test )"), RateLimiter(1))
```

### `ApiKeyConfig` is keyed by the `ApiKey` enum

Four named fields meant a fifth key was a breaking change; an enum key makes it an addition.

<!-- no-compile: uses the withdrawn per-provider constructor fields -->
```kotlin
val apiKeys = ApiKeyConfig(lastfmApiKey = "your-key", discogsToken = "your-token")
```

Build it from `ApiKey` pairs, and read it the same way:

```kotlin
val apiKeys = ApiKeyConfig.of(
    ApiKey.LASTFM_API_KEY to "your-key",
    ApiKey.DISCOGS_PERSONAL_TOKEN to "your-token",
)
val lastfmKey = apiKeys.get(ApiKey.LASTFM_API_KEY)
```

`KeyRequirement.Required` and `KeyRequirement.Optional` name an `ApiKey` instead of a field name,
for the same reason.

### `SearchCandidate.score: Int` is now `matchScore: Float`

One scale across the library: every match score is 0.0-1.0, the scale `Success.confidence` always
used. The constructor is reordered required-first at the same time
(`title, provider, identifiers, matchScore`), so positional construction moves — use named arguments.

<!-- no-compile: reads the withdrawn 0-100 `score` property -->
```kotlin
val percent = candidate.score
```

Read `matchScore`, and scale it yourself for display:

```kotlin
val candidatePercent = (candidate.matchScore * 100).toInt()
```

### `IdentityResolution.matchScore` and `*Profile.identityMatchScore` are `Float?` on 0.0-1.0

Same reason: a `100` on one type and a `1.0f` on another described the same thing in two scales.

<!-- no-compile: compares against the withdrawn 0-100 `Int` scale -->
```kotlin
val confident = results.identity.matchScore >= 90
```

A `100` becomes a `1.0f`, and the value is null when nothing scored it:

```kotlin
val identityScore = results.identity.matchScore
val confidentIdentity = identityScore != null && identityScore >= 0.9f
```

### `discoverMbidEntityType` is an `EnrichmentEngine` member, not a top-level extension

An engine you wrap could not carry the extension; a member travels with the engine it asks.

<!-- no-compile: calls the withdrawn top-level extension -->
```kotlin
import com.landofoz.musicmeta.discoverMbidEntityType

val entityType = engine.discoverMbidEntityType("65f4f0c5-ef9e-490c-aee3-909e7ae6b2ab")
```

Drop the import; the call site is otherwise unchanged.

```kotlin
val entityType = engine.discoverMbidEntityType("65f4f0c5-ef9e-490c-aee3-909e7ae6b2ab")
```

### `CanonicalStatus` gains `RESOLVING` and `CONTRADICTED`

`RESOLVING` marks a pre-terminal `enrichProgressive` emission — it can never reach you from
`enrich()`'s return or from a stream's terminal emission. `CONTRADICTED` reports that an MBID you
supplied names a different entity than the request. An exhaustive `when` needs a branch for each.

```kotlin
val identityLabel = when (results.identity.status) {
    CanonicalStatus.RESOLVED -> "matched"
    CanonicalStatus.RESOLVING -> "still resolving"
    CanonicalStatus.CONTRADICTED -> "the identifier you supplied names something else"
    CanonicalStatus.AMBIGUOUS -> "several candidates"
    CanonicalStatus.UNRESOLVED -> "no match"
    CanonicalStatus.FAILED -> "resolution failed"
    CanonicalStatus.NOT_ATTEMPTED_CACHE_HIT,
    CanonicalStatus.NOT_ATTEMPTED_DISABLED,
    CanonicalStatus.NOT_ATTEMPTED_NO_PROVIDER,
    CanonicalStatus.NOT_ATTEMPTED_IDENTIFIER_TRUSTED,
    -> "not attempted"
}
```

### `CanonicalStatus.NOT_ATTEMPTED_NOT_REQUIRED` is renamed `NOT_ATTEMPTED_IDENTIFIER_TRUSTED`

The old name said resolution was unnecessary; it only ever meant an identifier nobody checked.

<!-- no-compile: names the renamed constant -->
```kotlin
val trusted = results.identity.status == CanonicalStatus.NOT_ATTEMPTED_NOT_REQUIRED
```

Rename at every call site:

```kotlin
val identifierTrusted = results.identity.status == CanonicalStatus.NOT_ATTEMPTED_IDENTIFIER_TRUSTED
```

### `ErrorKind` gains `ENGINE_CLOSED`

It is reachable only when the engine was `close()`d before a requested type settled. An exhaustive
`when` over `ErrorKind` needs a branch.

```kotlin
val closedTypes = results.raw.values
    .filterIsInstance<EnrichmentResult.Error>()
    .filter { it.errorKind == ErrorKind.ENGINE_CLOSED }
    .map { it.type }
```

### A throwing `CatalogProvider.checkAvailability` no longer escapes `enrich()`

It used to fail the whole call. The affected recommendation type now degrades to unfiltered results
and the run still caches, so a timeout you wanted to be fatal is absorbed instead.

Catch your own timeout inside the provider, and decide there what an unanswered catalogue means:

```kotlin
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

class BoundedCatalogProvider(private val delegate: CatalogProvider) : CatalogProvider {
    override suspend fun checkAvailability(items: List<CatalogQuery>): List<CatalogMatch> =
        try {
            withTimeout(2_000) { delegate.checkAvailability(items) }
        } catch (timeout: TimeoutCancellationException) {
            items.map { CatalogMatch(available = false, source = "bounded", confidence = 0.0f) }
        }
}
```

`EnrichmentResult.Success.isCatalogDegraded` is `true` on any result that reached you unranked this
way:

```kotlin
val anyDegraded = results.raw.values
    .filterIsInstance<EnrichmentResult.Success>()
    .any { it.isCatalogDegraded }
```

### `CatalogProvider.checkAvailability` is called concurrently

It used to be called once per `enrich()` call, in sequence. It is now called from several coroutines
at once, so a stateful implementation must be thread-safe.

```kotlin
import java.util.concurrent.ConcurrentHashMap

class MemoizingCatalogProvider : CatalogProvider {
    private val known = ConcurrentHashMap<String, Boolean>()

    override suspend fun checkAvailability(items: List<CatalogQuery>): List<CatalogMatch> =
        items.map { query ->
            val available = known.computeIfAbsent(query.title + "|" + query.artist) { true }
            CatalogMatch(available = available, source = "memoizing")
        }
}
```

### `CompositeSynthesizer.synthesize` receives finalized dependencies

Under `CacheMode.STALE_IF_ERROR` a failed-but-stale dependency now arrives as `Success`, not
`Error`, so a synthesizer can no longer tell a genuine failure from a served-stale one by type
alone.

Where that distinction matters, read it off the result:

```kotlin
import com.landofoz.musicmeta.engine.CompositeSynthesizer

class StaleAwareSynthesizer : CompositeSynthesizer {
    override val type: EnrichmentType = EnrichmentType.ARTIST_TIMELINE
    override val dependencies: Set<EnrichmentType> = setOf(EnrichmentType.ARTIST_DISCOGRAPHY)

    override fun synthesize(
        resolved: Map<EnrichmentType, EnrichmentResult>,
        identityResult: EnrichmentResult?,
        request: EnrichmentRequest,
    ): EnrichmentResult {
        val discography = resolved[EnrichmentType.ARTIST_DISCOGRAPHY]
        val servedStale = (discography as? EnrichmentResult.Success)?.isStale == true
        return if (servedStale) {
            // A stale dependency: settle NotFound rather than publish a timeline built on it.
            EnrichmentResult.NotFound(type, "stale_aware_synthesizer")
        } else {
            EnrichmentResult.Success(type, EnrichmentData.ArtistTimeline(emptyList()), "stale_aware_synthesizer", 1.0f)
        }
    }
}
```

### Cancelling `enrich()`'s caller is complete-and-cache, not abort-and-forfeit

Cancelling the calling coroutine used to abandon the fan-out and throw away everything in flight. It
now detaches: the run continues to completion and still writes back, so the next caller gets a cache
hit instead of paying for the same work twice.

Nothing needs editing, but the work no longer stops when you stop waiting. `close()` is what
abandons a detached run:

```kotlin
engine.close()
```

### `EnrichmentEngine.Builder.build()` rejects two registrations it used to accept

Both were silently dead configurations: a dependency cycle among `CompositeSynthesizer`s could never
resolve, and a type carrying both a `CompositeSynthesizer` and a `ResultMerger` could never run the
merger. `build()` now throws `IllegalArgumentException`, naming every type on the loop in the first
case.

Fix the registration. If you build engines from user configuration, handle the throw:

```kotlin
val configuredEngine = try {
    EnrichmentEngine.Builder().build()
} catch (invalid: IllegalArgumentException) {
    null
}
```

### `PopularTrack` and `Popularity.topTracks` are removed

`ARTIST_TOP_TRACKS` already carries the same tracks as `TopTrack`, so `Popularity` held a second,
weaker copy. Cached `Popularity` entries still decode — the old key is ignored.

<!-- no-compile: reads the withdrawn `topTracks` property off `Popularity` -->
```kotlin
val popularTracks = popularity.topTracks
```

Ask for the type that owns them:

```kotlin
val artistTopTracks = results.topTracks()?.tracks.orEmpty()
```

### `EnrichmentIdentifiers.get(String)` and `withExtra(String, String)` are removed

A raw string key let any typo through, and told a reader nothing about which namespace it named.

<!-- no-compile: uses the withdrawn string-keyed accessors -->
```kotlin
val discogsRelease = results.identity.identifiers.get("discogs_release")
```

Use the typed door. `DISCOGS_RELEASE`, `DISCOGS_MASTER` and `ITUNES_COLLECTION` are new constants
carrying the same wire keys the strings did:

```kotlin
val discogsRelease = results.identity.identifiers.get(IdentifierNamespace.DISCOGS_RELEASE)
val withDiscogs = results.identity.identifiers.with(IdentifierNamespace.DISCOGS_RELEASE, "249504")
```

### `DiscographyAlbum.year` is `Int?`, not `String?`

It is a year, and every sibling type — `SimilarAlbum`, `ReleaseEdition` — already said so. A
Room-cached discography written by 0.12.0 still reads; a row whose year was not a number refetches
once.

<!-- no-compile: parses the withdrawn `String?` shape -->
```kotlin
val debutYear = results.discography()?.albums.orEmpty().minOfOrNull { it.year?.toIntOrNull() ?: 0 }
```

Drop the parse; the year arrives as a number:

```kotlin
val debutYear = results.discography()?.albums.orEmpty().mapNotNull { it.year }.minOrNull()
```

### `SearchCandidate.year` is `Int?`, not `String?`

It was written from a MusicBrainz release date or artist begin date whole, so a field named `year`
could hand you `1997-06-16`; it now carries the year those dates start with, and null when an
upstream states no usable one.

<!-- no-compile: parses the withdrawn `String?` shape -->
```kotlin
val candidateYear = candidate.year?.take(4)?.toIntOrNull()
```

Drop the truncation and the parse:

```kotlin
val candidateYear = candidate.year
val candidateIsNineties = candidateYear != null && candidateYear in 1990..1999
```

### Binary-only breaks

| What moved | What to do |
|---|---|
| `EnrichmentEngine` gains `enrichProgressive`, `enrichBatchProgressive`, `close()` and `discoverMbidEntityType`, all defaulted | Nothing, unless you implement `EnrichmentEngine` yourself: an implementation built against an older `.jar` throws `AbstractMethodError` on the first call until recompiled |
| `EnrichmentResult.Success` gains `isCatalogDegraded`, appended last and defaulted | Recompile: the constructor and `copy` descriptors changed |
| `EnrichmentRequest.forAlbum`'s pre-`trackCount`/`year` overload is removed | Recompile: source is unaffected because both parameters default, but a `.jar` compiled against 0.12.0 throws `NoSuchMethodError` |

## 0.12.0

### `ProviderInfo.isEnabled` is removed

`getProviders()` only ever reported `true`, so filtering on it was a no-op.

<!-- no-compile: reads the withdrawn `isEnabled` property -->
```kotlin
val usable = engine.getProviders().filter { it.isEnabled }
```

Filter on `isAvailable`, which reports whether the provider can actually run:

```kotlin
val usableProviders = engine.getProviders().filter { it.isAvailable }
```

### `ARTIST_POPULARITY` and `TRACK_POPULARITY` report `provider = "popularity_merger"`

Both types are merged from three sources, so no single provider id was ever the truthful answer.

A per-provider `confidenceOverrides` entry no longer affects either type:

```kotlin
val perProvider = EnrichmentConfig(confidenceOverrides = mapOf("lastfm" to 0.5f))
```

Key the override on the merger to weight the merged result:

```kotlin
val popularityWeighted = EnrichmentConfig(confidenceOverrides = mapOf("popularity_merger" to 0.5f))
```

### `Builder.addProvider` throws on a duplicate or reserved provider id

Duplicate ids shared one circuit breaker, so a healthy provider kept a failing twin in rotation. The
configuration is now refused at registration instead of silently degrading.

Rename your provider if its id collides with another of yours, or with one the engine reserves:
`engine`, `all_providers`, `no_provider`, `no_merger`, `no_composite_handler`, and any id ending
`_merger`.

```kotlin
val duplicateRegistration = runCatching {
    EnrichmentEngine.Builder()
        .addProvider(MusicBrainzProvider(DefaultHttpClient("MyApp/1.0"), RateLimiter(1)))
        .addProvider(MusicBrainzProvider(DefaultHttpClient("MyApp/1.0"), RateLimiter(1)))
        .build()
}
```

### An identity provider that throws or returns `Error` resolves to `CanonicalStatus.FAILED`

It used to produce `null`, or a confident value nothing had stamped, which read as "resolution
agreed" when nothing had. A `when` over `CanonicalStatus` needs a `FAILED` branch — see the `when`
above.

A `FAILED` result is also excluded from the cache write-back, so a retry after a transient identity
failure re-resolves rather than serving the failed guess for the whole TTL. Nothing to edit, but
that retry now costs an upstream call it previously did not.

`CompositeSynthesizer.synthesize` receives that `Error` as `identityResult`, where it previously
received `null`, the "not attempted" value:

```kotlin
class IdentityAwareSynthesizer : CompositeSynthesizer {
    override val type: EnrichmentType = EnrichmentType.GENRE_DISCOVERY
    override val dependencies: Set<EnrichmentType> = setOf(EnrichmentType.GENRE)

    override fun synthesize(
        resolved: Map<EnrichmentType, EnrichmentResult>,
        identityResult: EnrichmentResult?,
        request: EnrichmentRequest,
    ): EnrichmentResult = when (identityResult) {
        // Resolution never ran, so the genres carry their usual weight.
        null -> synthesizeFrom(resolved, confidence = 1.0f)
        // Resolution ran and failed: the entity behind these genres is unverified.
        is EnrichmentResult.Error -> synthesizeFrom(resolved, confidence = 0.5f)
        else -> synthesizeFrom(resolved, confidence = 1.0f)
    }

    private fun synthesizeFrom(resolved: Map<EnrichmentType, EnrichmentResult>, confidence: Float): EnrichmentResult {
        val genres = (resolved[EnrichmentType.GENRE] as? EnrichmentResult.Success)?.data
            ?: return EnrichmentResult.NotFound(type, "identity_aware_synthesizer")
        return EnrichmentResult.Success(type, genres, "identity_aware_synthesizer", confidence)
    }
}
```

### `DefaultHttpClient` no longer publishes `MAX_RETRY_AFTER_SEC`

The 120-second standalone retry ceiling moved into the shared ladder, where every client gets it.

<!-- no-compile: reads the withdrawn constant -->
```kotlin
val ceiling = DefaultHttpClient.MAX_RETRY_AFTER_SEC
```

There is no replacement to read: the ceiling is the transport's, not yours.

### `EnrichmentCache`'s `getIncludingExpired`, `getNegative` and `putNegative` are abstract

They were defaulted, which let a cache implement six of nine methods and lose negative caching and
stale-while-revalidate without saying so. A six-method cache now fails to compile, and one compiled
against the old interface throws `NoSuchMethodError`.

Implement all nine. The same release changed two of these signatures — see the two sections below —
so write them against the current shapes:

<!-- no-compile: signature fragments, not a whole `EnrichmentCache` -->
```kotlin
override suspend fun getIncludingExpired(entityKey: String, type: EnrichmentType): CacheEnvelope<EnrichmentResult.Success>?
override suspend fun getNegative(entityKey: String, type: EnrichmentType): CacheEnvelope<EnrichmentResult.NotFound>?
override suspend fun putNegative(
    entityKey: String,
    type: EnrichmentType,
    result: EnrichmentResult.NotFound,
    canonicalStatus: CanonicalStatus,
    ttlMs: Long,
)
```

### `EnrichmentCache.get`, `getIncludingExpired` and `getNegative` return a `CacheEnvelope`

The engine needs to know how old an entry is and whether it was served stale; a bare result could
not say. This is a suspend-function descriptor change that erasure hides, so it never appeared in
the `.api` diff — treat it as breaking regardless.

<!-- no-compile: reads the withdrawn bare return value -->
```kotlin
val cachedBio: EnrichmentResult.Success? = engine.cache.get(opaqueEntityKey, EnrichmentType.ARTIST_BIO)
```

Read `.result` wherever you read the return value directly:

```kotlin
val cachedBio = engine.cache.get(opaqueEntityKey, EnrichmentType.ARTIST_BIO)?.result
```

### `EnrichmentCache.put` and `putNegative` take a `canonicalStatus`

An entry that does not record which identity verdict produced it cannot be invalidated when that
verdict turns out to be wrong. The parameter has no default: pass the call's status through.

<!-- no-compile: signature fragments, not a whole `EnrichmentCache` -->
```kotlin
override suspend fun put(
    entityKey: String,
    type: EnrichmentType,
    result: EnrichmentResult.Success,
    canonicalStatus: CanonicalStatus,
    ttlMs: Long,
)

override suspend fun putNegative(
    entityKey: String,
    type: EnrichmentType,
    result: EnrichmentResult.NotFound,
    canonicalStatus: CanonicalStatus,
    ttlMs: Long,
)
```

### `IdentityMatch` is removed; `IdentityResolution.match` is now `status`

A hard swap with no shim: one enum, `CanonicalStatus`, now says both what was matched and why it was
not. `Success` and `NotFound` drop `identityMatch` and `identityMatchScore`; `Success` gains
`provenance`, which says how the provider selected the entity.

<!-- no-compile: reads the withdrawn `match` property and `IdentityMatch` type -->
```kotlin
val matched = results.identity.match == IdentityMatch.EXACT
```

```kotlin
val matchedCanonically = results.identity.status == CanonicalStatus.RESOLVED
val howEachWasFound = results.raw.values
    .filterIsInstance<EnrichmentResult.Success>()
    .mapNotNull { it.provenance }
```

The full old-to-new mapping, including every `identity == null` case, is the table in
[docs/how-it-works.md](../how-it-works.md) under "Step 7: Identity Model".

### `EnrichmentResults.identity` is non-null

It always carries a `CanonicalStatus` now, including every reason resolution did not run, so
`identity == null` no longer compiles — and the reason it did not run is no longer lost.

<!-- no-compile: null-checks a property that can no longer be null -->
```kotlin
val status = results.identity?.status
```

```kotlin
val identityStatus = results.identity.status
```

### `LookupProvenance` gains `EXTERNAL_CATALOG_ID`

It distinguishes a direct catalogue lookup — an iTunes UPC, say — from a provider-native id. An
exhaustive `when` needs a branch.

```kotlin
val firstSuccess = results.raw.values.filterIsInstance<EnrichmentResult.Success>().firstOrNull()
val provenanceLabel = when (firstSuccess?.provenance) {
    LookupProvenance.CANONICAL_ID -> "by MBID"
    LookupProvenance.EXTERNAL_CATALOG_ID -> "by catalogue id"
    LookupProvenance.PROVIDER_NATIVE_ID -> "by the provider's own id"
    LookupProvenance.EXACT_NAME -> "by exact name"
    LookupProvenance.FUZZY_NAME -> "by fuzzy name"
    LookupProvenance.QUALIFIER_FALLBACK_NAME -> "by a qualifier fallback"
    LookupProvenance.CACHE -> "from cache"
    null -> "not stated"
}
```

### `RoomEnrichmentCache` takes `negativeDao`, then `selectionDao`

Negative caching (schema v3) and manual selection (schema v5) each moved into their own table, and
each added a required constructor parameter. `EnrichmentCacheDao` lost `isManual`, `markManual` and
`insertPreservingManual` with the second of those; `SelectionDao.isSelected` replaces `isManual` and
is always a non-null `Boolean`. Neither DAO change appears in the `.api` diff — erasure hides both.

<!-- no-compile: the withdrawn single-DAO constructor, on Android types this page does not compile against -->
```kotlin
val cache = RoomEnrichmentCache(database.enrichmentCacheDao())
```

Build the database through its factory and hand `RoomEnrichmentCache` all three DAOs off it, so a
later schema bump changes one line rather than your wiring — [android.md](android.md) has the full
setup:

<!-- no-compile: Android types this page does not compile against; android.md's fences do -->
```kotlin
val db = EnrichmentCacheDatabase.create(context, "enrichment_cache.db")
val cache = RoomEnrichmentCache(db.enrichmentCacheDao(), db.negativeCacheDao(), db.selectionDao())
```

### Android cache schema v4 clears the cache, and every manual selection with it

`identity_match` and `identity_score` named a different fact under the new identity model and could
not be reinterpreted, so `MIGRATION_3_4` clears both tables and the next call refetches.

That clear also drops every manual selection made before schema v4, and nothing heals a lost
selection: re-select after upgrading an install that predates v4. Nothing to edit.

### Two normalization fixes change which artists match

Neither is a source change; both change results you may have been relying on.

- Two distinct all-non-Latin artist names — two different CJK names, say — no longer match each
  other. Both used to normalize to an empty string and compare equal.
- A non-Latin artist request such as 東京事変 against a romanizing provider (Deezer, iTunes, Discogs)
  now returns no match, instead of that provider's unverified top hit.

### Binary-only breaks

| What moved | What to do |
|---|---|
| `EnrichmentData.Popularity` gains `signals`, `IdentityResolution` gains `title`/`artist`, `GenreTag` gains `curated` — each appended last and defaulted | Recompile: the constructor and `copy` descriptors changed, so an older `.jar` calling either throws `NoSuchMethodError` |
| `EnrichmentCacheEntity` and `NegativeCacheEntity` gain `canonicalStatus`/`isStale`, and `EnrichmentCacheEntity` drops `isManual` | Recompile if you construct or `copy()` either directly. `component11` and up renumber, so a destructuring declaration over these silently rebinds — check every one |
| `OkHttpEnrichmentClient`'s constructor gains a defaulted `maxAttempts` | Recompile. It now retries a 429, a shed 502/503/504 and a transport failure on core's budgeted ladder |

## 0.11.0

### `HttpClient` is six abstract `HttpResult` methods

The nullable-returning half of the interface could not say why a call failed, so a 429 on the
artwork path read as "no artwork". The nullable `fetchJson`, `fetchJsonArray`, `fetchBody`,
`fetchRedirectUrl`, `postJson` and `postJsonArray` are deleted, and `fetchJsonResult` and
`fetchRedirectUrlResult` are no longer defaulted — implement both, or `Authorization` headers go
unsent.

<!-- no-compile: implements the withdrawn nullable half of `HttpClient` -->
```kotlin
class MyHttpClient : HttpClient {
    override suspend fun fetchJson(url: String, headers: Map<String, String>): JSONObject? = null
}
```

Implement the `*Result` methods instead — [extension-points.md](extension-points.md) carries a
complete custom client:

<!-- no-compile: signature fragments, not a whole `HttpClient` -->
```kotlin
override suspend fun fetchJsonResult(url: String, headers: Map<String, String>): HttpResult<JSONObject>
override suspend fun fetchRedirectUrlResult(url: String): HttpResult<String>
```

### A `CatalogProvider`'s own `withTimeout` expiry propagates out of `enrich()`

It used to be reported as our timeout, which sent you looking for a fault in the engine. If you are
upgrading past the current release, this is superseded: a throwing `checkAvailability` no longer
escapes `enrich()` at all, and that section above is the one to act on.

### `engine/` internals are `internal`

`DefaultEnrichmentEngine`, `ProviderRegistry`, `ProviderChain`, `ArtistMatcher` and
`ConfidenceCalculator` were public by omission, never by design, and pinned the engine's internals
as a contract.

<!-- no-compile: constructs a type that is now `internal` -->
```kotlin
val myEngine = DefaultEnrichmentEngine(providers, config)
```

Build engines through the builder:

```kotlin
val builtEngine = EnrichmentEngine.Builder()
    .addProvider(MusicBrainzProvider(DefaultHttpClient("MyApp/1.0"), RateLimiter(1)))
    .build()
```

`ResultMerger` and `CompositeSynthesizer` stay public: they are the documented extension points, and
`addMerger`/`addSynthesizer` still take yours.

## 0.10.0

0.10.0 baselined the public API and removed about 80 types that were public by omission. Every break
below is a withdrawal of something never meant to be part of the surface, and each has a supported
replacement that has not moved since.

### Provider, `http/` and `engine/` internals are `internal`

The `*Api`, `*Mapper` and `*Models` types behind each provider, `MusicBrainzParser`,
`CircuitBreaker`, and the concrete mergers and synthesizers (`ArtworkMerger`, `GenreMerger`,
`SimilarArtistMerger`, `SimilarTrackMerger`, `TopTrackMerger`, `TimelineSynthesizer`,
`GenreAffinityMatcher`) are all hidden.

<!-- no-compile: constructs types that are now `internal` -->
```kotlin
val api = DeezerApi(httpClient, rateLimiter)
val merger = GenreMerger()
```

Construct and register the public `*Provider` classes, which have not changed:

```kotlin
val deezer = DeezerProvider(DefaultHttpClient("MyApp/1.0"), RateLimiter(100))
```

`HttpClient`, `HttpResult`, `HttpResponse`, `DefaultHttpClient` and `RateLimiter` stay public, as do
the `ResultMerger` and `CompositeSynthesizer` interfaces.

### `SimilarAlbumsProvider(DeezerApi)` is replaced

Its constructor took a provider internal, which is why it could not survive that internal being
hidden. It now matches every other provider.

<!-- no-compile: passes a type that is now `internal` -->
```kotlin
val similarAlbumsOld = SimilarAlbumsProvider(DeezerApi(httpClient, rateLimiter))
```

Pass the HTTP infrastructure instead, as every other provider takes it:

```kotlin
val similarAlbums = SimilarAlbumsProvider(DefaultHttpClient("MyApp/1.0"), RateLimiter(100))
```

### `ProviderChain`'s constructor is `internal`

0.10.0 withdrew direct construction while leaving the class public, reachable through
`ProviderRegistry.chainFor(type)`. If you are upgrading past 0.11.0 this is superseded: that release
made `ProviderChain` and `ProviderRegistry` `internal` outright, and the `engine/` section above is
the one to act on.
