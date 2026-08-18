# Extension Points

## Custom providers

Implement `EnrichmentProvider` to add a new data source. The engine discovers capabilities from the `capabilities` list and automatically wires the provider into the correct chains.

<!-- no-compile: elides other `enrich` methods and an undefined `mapError` helper; illustrative, not a complete provider -->
```kotlin
class SpotifyProvider(
    private val httpClient: HttpClient,
    private val rateLimiter: RateLimiter,
    private val accessToken: String,
) : EnrichmentProvider {

    override val id = "spotify"
    override val displayName = "Spotify"
    override val requiresApiKey = true
    override val isAvailable = accessToken.isNotBlank()

    override val capabilities = listOf(
        ProviderCapability(EnrichmentType.ARTIST_POPULARITY, priority = 80),
        ProviderCapability(EnrichmentType.TRACK_POPULARITY, priority = 80),
        ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, priority = 70),
    )

    override suspend fun enrich(
        request: EnrichmentRequest,
        type: EnrichmentType,
    ): EnrichmentResult {
        rateLimiter.acquire()

        return when (type) {
            EnrichmentType.ARTIST_POPULARITY -> enrichArtistPopularity(request)
            EnrichmentType.TRACK_POPULARITY -> enrichTrackPopularity(request)
            EnrichmentType.SIMILAR_ARTISTS -> enrichSimilarArtists(request)
            else -> EnrichmentResult.NotFound(type, id)
        }
    }

    private suspend fun enrichArtistPopularity(request: EnrichmentRequest): EnrichmentResult {
        val artist = request as? EnrichmentRequest.ForArtist
            ?: return EnrichmentResult.NotFound(EnrichmentType.ARTIST_POPULARITY, id)

        val url = "https://api.spotify.com/v1/search?q=${artist.name}&type=artist"

        return try {
            // Act on which failure it is: treating a 429 or a 5xx the same as a genuine 404 makes
            // it a NotFound, and a NotFound counts as a circuit-breaker
            // *success* — the provider looks healthy while a throttle makes it answer nothing.
            // Throwing on a transient sends it through the catch below into Error(NETWORK), which
            // the breaker records as a failure. See docs/pitfalls.md §4.
            val json = when (val result = httpClient.fetchJsonResult(url)) {
                is HttpResult.Ok -> result.body
                is HttpResult.ClientError ->
                    return EnrichmentResult.NotFound(EnrichmentType.ARTIST_POPULARITY, id)
                is HttpResult.RateLimited -> throw IOException("HTTP 429: rate limited")
                is HttpResult.ServerError -> throw IOException("HTTP ${result.statusCode}: server error")
                is HttpResult.NetworkError -> throw IOException(result.message, result.cause)
            }

            val popularity = json.getJSONObject("artists")
                .getJSONArray("items").getJSONObject(0)
                .getInt("popularity")

            EnrichmentResult.Success(
                type = EnrichmentType.ARTIST_POPULARITY,
                data = EnrichmentData.Popularity(rank = popularity),
                provider = id,
                confidence = 0.85f,
            )
        } catch (e: Exception) {
            mapError(EnrichmentType.ARTIST_POPULARITY, e)
        }
    }

    // ... other enrich methods
}
```

Register the provider with the builder:

<!-- no-compile: depends on `SpotifyProvider` from the example above, which is itself opted out -->
```kotlin
val engine = EnrichmentEngine.Builder()
    .withDefaultProviders()
    .addProvider(SpotifyProvider(httpClient, RateLimiter(100), "my-token"))
    .build()
```

### Provider capability priorities

- 100 = primary source (tried first)
- 50 = fallback
- Higher values = higher priority in the chain

### Identifier requirements

If your provider needs a resolved identifier (not just title/artist text), declare it on the capability:

```kotlin
ProviderCapability(
    type = EnrichmentType.ARTIST_BIO,
    priority = 80,
    identifierRequirement = IdentifierRequirement.WIKIPEDIA_TITLE,
)
```

Available requirements:

| Value | Required field |
|-------|----------------|
| `NONE` | No requirement — title/artist text is sufficient |
| `MUSICBRAINZ_ID` | `identifiers.musicBrainzId` must be present |
| `MUSICBRAINZ_RELEASE_GROUP_ID` | `identifiers.musicBrainzReleaseGroupId` must be present |
| `WIKIDATA_ID` | `identifiers.wikidataId` must be present |
| `WIKIPEDIA_TITLE` | `identifiers.wikipediaTitle` must be present |
| `ANY_IDENTIFIER` | Any of the above must be present |

Providers with unsatisfied requirements are automatically skipped.

---

## HTTP clients

### OkHttp adapter (recommended for Android)

The `musicmeta-okhttp` module ships a ready-to-use `OkHttpEnrichmentClient`. Add the dependency and pass your existing `OkHttpClient`:

<!-- no-compile: build.gradle.kts fragment, not application Kotlin -->
```kotlin
// build.gradle.kts
implementation("io.github.famesjranko:musicmeta-okhttp:0.12.0")
```

```kotlin
val engine = EnrichmentEngine.Builder()
    .httpClient(OkHttpEnrichmentClient(myOkHttpClient, "MyApp/1.0"))
    .withDefaultProviders()
    .build()
```

Call `.httpClient()` **before** `.withDefaultProviders()` so all default providers use OkHttp.

**Differences from `DefaultHttpClient`:**
- Gzip decompression handled transparently (do not set `Accept-Encoding` manually)
- Timeouts inherited from the `OkHttpClient` instance, which is also where the retry budget gets what it charges an attempt

Both clients retry through the same ladder, `BudgetedTransientRetry` — see below. Do **not** add a retrying interceptor to your `OkHttpClient`: an interceptor cannot see the enrich deadline, so its retries are unbudgeted and stack on top of the budgeted ones.

### Transient retry

A 429, a shed 502/503/504 and a transport failure are retried — three attempts by default, honouring `Retry-After` — and every sleep is charged against the enclosing `enrich()` deadline, so a wait that would run past it is refused and the failure surfaces instead. Running past that deadline cancels the whole fan-out and loses every other provider's in-flight work, which is why the budget is not optional and why the deadline itself stays internal.

`DefaultHttpClient` and `OkHttpEnrichmentClient` are already wired to it, with nothing to configure. A client of your own opts in by wrapping each attempt:

<!-- no-compile: illustrative pattern: `MyLibrary`, `mapToResult` and `asAttempt` are undefined placeholders -->
```kotlin
class MyHttpClient(private val http: MyLibrary) : HttpClient {
    // connect + read: what one attempt against a hanging upstream may spend. Never your library's
    // equivalent of OkHttp's callTimeout, which is 0 unless set — a 0 charge lets a hang retry into
    // a deadline that had room for one attempt.
    private val retry = BudgetedTransientRetry(attemptTimeoutMs = 20_000)

    override suspend fun fetchJsonResult(url: String, headers: Map<String, String>) = retry.execute {
        val response = http.get(url, headers)          // an IOException out of here is a transport
        mapToResult(response)                          // failure: let it fly, do not catch it
            .asAttempt(response.header("Retry-After")) // read for a 5xx, ignored otherwise
    }
}
```

Two things the ladder needs that `HttpResult` cannot carry:

- **A transport failure is the `IOException` you let out of the block.** `execute` catches it. A `NetworkError` you *return* is treated as a response that arrived and will not parse — not retried, because a second identical request reproduces it, and it is the shape a provider changing its JSON arrives in.
- **`asAttempt(retryAfterHeader)` reads the header for a `ServerError` and nothing else.** A 429's wait travels in `RateLimited.retryAfterMs`, which the ladder reads from the result itself. Pass the header unconditionally and let `asAttempt` decide; omitting it for a 5xx only falls back to exponential backoff.

`OkHttpEnrichmentClient(client, userAgent, maxAttempts = 1)` opts out of retrying; there is no knob for what an attempt is charged, because that comes from your `OkHttpClient`'s own timeouts.

To test the refusal branch from outside this library, run the call inside `withRetryBudgetForTest`. It is marked `@MusicmetaTestApi`, so the compiler refuses a call that has not opted in — a test says so once, and a production call site has to say something it would not want to write:

<!-- no-compile: JUnit/coroutines-test fragment with undeclared fixtures (`client`, `server`); illustrative, not compiled here -->
```kotlin
@OptIn(MusicmetaTestApi::class)
class MyHttpClientTest {
    @Test fun `a wait past the enrich deadline is refused`() = runTest {
        val result = withRetryBudgetForTest(budgetMs = 50) { client.fetchJsonResult(url) }
        assertEquals(1, server.requestCount)
    }
}
```

Pair it with the same fixture under a generous budget and assert the request counts differ — a client with no retry at all also passes the refusal test on its own.

### Custom HTTP clients

For other HTTP libraries (Ktor, Fuel, etc.), implement the `HttpClient` interface — six methods, none defaulted, every one returning an `HttpResult`:

<!-- no-compile: elided method bodies (`/* ... */`); shows the methods to implement, not a compiling implementation -->
```kotlin
class MyHttpClient : HttpClient {
    override suspend fun fetchJsonResult(url: String): HttpResult<JSONObject> = fetchJsonResult(url, emptyMap())
    override suspend fun fetchJsonResult(url: String, headers: Map<String, String>): HttpResult<JSONObject> { /* ... */ }
    override suspend fun fetchJsonArrayResult(url: String): HttpResult<JSONArray> { /* ... */ }
    override suspend fun fetchRedirectUrlResult(url: String): HttpResult<String> { /* ... */ }
    override suspend fun postJsonResult(url: String, body: String): HttpResult<JSONObject> { /* ... */ }
    override suspend fun postJsonArrayResult(url: String, body: String): HttpResult<JSONArray> { /* ... */ }
}

val engine = EnrichmentEngine.Builder()
    .httpClient(MyHttpClient())  // before withDefaultProviders()
    .withDefaultProviders()
    .build()
```

Return the real status in every case: a `RateLimited`, `ServerError` or `NetworkError` is what makes a
provider report `Error` — retryable, breaker-visible, `STALE_IF_ERROR`-eligible — instead of `NotFound`.
Collapsing failures into a 404 (or dropping the `headers` map, which is where `Authorization` arrives)
is silent, and shows up only as missing enrichment.


---

## Custom caches

Implement `EnrichmentCache` for any storage backend:

<!-- no-compile: illustrative custom-cache example; `RedisClient` is an undefined placeholder type, and `serialize`/`deserializeEnvelope` are undefined helpers -->
```kotlin
class RedisEnrichmentCache(private val redis: RedisClient) : EnrichmentCache {

    private fun resultKey(entityKey: String, type: EnrichmentType) = "result:$entityKey:${type.name}"
    private fun negativeKey(entityKey: String, type: EnrichmentType) = "negative:$entityKey:${type.name}"
    private fun selectionKey(entityKey: String, type: EnrichmentType) = "$entityKey:${type.name}"

    override suspend fun get(
        entityKey: String,
        type: EnrichmentType,
    ): CacheEnvelope<EnrichmentResult.Success>? {
        val json = redis.get(resultKey(entityKey, type)) ?: return null
        return deserializeEnvelope(json)
    }

    override suspend fun getIncludingExpired(
        entityKey: String,
        type: EnrichmentType,
    ): CacheEnvelope<EnrichmentResult.Success>? {
        // This client evicts on TTL, so there is nothing expired left to serve stale.
        return null
    }

    override suspend fun put(
        entityKey: String,
        type: EnrichmentType,
        result: EnrichmentResult.Success,
        canonicalStatus: CanonicalStatus,
        ttlMs: Long,
    ) {
        redis.setex(resultKey(entityKey, type), ttlMs / 1000, serialize(CacheEnvelope(result, canonicalStatus)))
    }

    override suspend fun getNegative(
        entityKey: String,
        type: EnrichmentType,
    ): CacheEnvelope<EnrichmentResult.NotFound>? {
        val json = redis.get(negativeKey(entityKey, type)) ?: return null
        return deserializeEnvelope(json)
    }

    override suspend fun putNegative(
        entityKey: String,
        type: EnrichmentType,
        result: EnrichmentResult.NotFound,
        canonicalStatus: CanonicalStatus,
        ttlMs: Long,
    ) {
        redis.setex(negativeKey(entityKey, type), ttlMs / 1000, serialize(CacheEnvelope(result, canonicalStatus)))
    }

    override suspend fun invalidate(entityKey: String, type: EnrichmentType?) {
        val types = type?.let(::listOf) ?: EnrichmentType.entries
        types.forEach {
            redis.del(resultKey(entityKey, it))
            redis.del(negativeKey(entityKey, it))
            redis.srem("manual_selections", selectionKey(entityKey, it))
        }
    }

    override suspend fun isManuallySelected(entityKey: String, type: EnrichmentType): Boolean {
        return redis.sismember("manual_selections", selectionKey(entityKey, type))
    }

    override suspend fun markManuallySelected(entityKey: String, type: EnrichmentType) {
        redis.sadd("manual_selections", selectionKey(entityKey, type))
    }

    override suspend fun clear() {
        redis.flushDb()
    }
}
```

See [cache-management.md](cache-management.md) for cache key structure and TTL details.

---

## Custom mergers

Implement `ResultMerger` for types where multiple providers should contribute to a single result instead of short-circuiting on the first success:

```kotlin
import com.landofoz.musicmeta.engine.ResultMerger

object MyCustomMerger : ResultMerger {
    override val type = EnrichmentType.SIMILAR_ARTISTS

    override fun merge(results: List<EnrichmentResult.Success>): EnrichmentResult {
        if (results.isEmpty()) return EnrichmentResult.NotFound(type, "merger")

        val allArtists = results.flatMap {
            (it.data as? EnrichmentData.SimilarArtists)?.artists ?: emptyList()
        }

        val merged = allArtists
            .groupBy { it.name.lowercase() }
            .map { (_, group) ->
                group.first().copy(
                    matchScore = group.map { it.matchScore }.average().toFloat(),
                    sources = group.flatMap { it.sources }.distinct(),
                )
            }
            .sortedByDescending { it.matchScore }

        return EnrichmentResult.Success(
            type = type,
            data = EnrichmentData.SimilarArtists(merged),
            provider = "merger",
            confidence = results.maxOf { it.confidence },
        )
    }
}

val engine = EnrichmentEngine.Builder()
    .withDefaultProviders()
    .addMerger(MyCustomMerger)
    .build()
```

---

## Custom synthesizers

Implement `CompositeSynthesizer` for types that are computed from other resolved types. The engine resolves all `dependencies` first, then calls `synthesize()` with the resolved results map.

<!-- no-compile: assumes `buildTimeline`, an undefined helper this example does not define -->
```kotlin
import com.landofoz.musicmeta.engine.CompositeSynthesizer

object MyArtistSummarySynthesizer : CompositeSynthesizer {
    override val type = EnrichmentType.ARTIST_TIMELINE // or a custom type

    override val dependencies = setOf(
        EnrichmentType.ARTIST_BIO,
        EnrichmentType.GENRE,
        EnrichmentType.ARTIST_DISCOGRAPHY,
    )

    override fun synthesize(
        resolved: Map<EnrichmentType, EnrichmentResult>,
        identityResult: EnrichmentResult?,
        request: EnrichmentRequest,
    ): EnrichmentResult {
        val bio = (resolved[EnrichmentType.ARTIST_BIO] as? EnrichmentResult.Success)
            ?.data as? EnrichmentData.Biography
        val discography = (resolved[EnrichmentType.ARTIST_DISCOGRAPHY] as? EnrichmentResult.Success)
            ?.data as? EnrichmentData.Discography

        if (bio == null && discography == null) {
            return EnrichmentResult.NotFound(type, "synthesizer")
        }

        val events = buildTimeline(bio, discography)

        return EnrichmentResult.Success(
            type = type,
            data = EnrichmentData.ArtistTimeline(events),
            provider = "synthesizer",
            confidence = 0.9f,
        )
    }
}

val engine = EnrichmentEngine.Builder()
    .withDefaultProviders()
    .addSynthesizer(MyArtistSummarySynthesizer)
    .build()
```
