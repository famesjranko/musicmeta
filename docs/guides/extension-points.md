# Extension Points

## Custom providers

Implement `EnrichmentProvider` to add a new data source. The engine discovers capabilities from the `capabilities` list and automatically wires the provider into the correct chains.

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

        return try {
            val json = httpClient.fetchJson("https://api.spotify.com/v1/search?q=${artist.name}&type=artist")
                ?: return EnrichmentResult.NotFound(EnrichmentType.ARTIST_POPULARITY, id)

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

```kotlin
// build.gradle.kts
implementation("io.github.famesjranko:musicmeta-okhttp:0.8.2")
```

```kotlin
val engine = EnrichmentEngine.Builder()
    .httpClient(OkHttpEnrichmentClient(myOkHttpClient, "MyApp/1.0"))
    .withDefaultProviders()
    .build()
```

Call `.httpClient()` **before** `.withDefaultProviders()` so all default providers use OkHttp.

**Differences from `DefaultHttpClient`:**
- No built-in retry — add retries via OkHttp interceptors
- Gzip decompression handled transparently (do not set `Accept-Encoding` manually)
- Timeouts inherited from the `OkHttpClient` instance

### Custom HTTP clients

For other HTTP libraries (Ktor, Fuel, etc.), implement the `HttpClient` interface (10 methods):

```kotlin
class MyHttpClient : HttpClient {
    override suspend fun fetchJson(url: String): JSONObject? { /* ... */ }
    override suspend fun fetchJsonResult(url: String): HttpResult<JSONObject> { /* ... */ }
    override suspend fun fetchJsonArray(url: String): JSONArray? { /* ... */ }
    override suspend fun fetchJsonArrayResult(url: String): HttpResult<JSONArray> { /* ... */ }
    override suspend fun fetchBody(url: String): String? { /* ... */ }
    override suspend fun fetchRedirectUrl(url: String): String? { /* ... */ }
    override suspend fun postJson(url: String, body: String): JSONObject? { /* ... */ }
    override suspend fun postJsonArray(url: String, body: String): JSONArray? { /* ... */ }
    override suspend fun postJsonResult(url: String, body: String): HttpResult<JSONObject> { /* ... */ }
    override suspend fun postJsonArrayResult(url: String, body: String): HttpResult<JSONArray> { /* ... */ }
}

val engine = EnrichmentEngine.Builder()
    .httpClient(MyHttpClient())  // before withDefaultProviders()
    .withDefaultProviders()
    .build()
```

---

## Custom caches

Implement `EnrichmentCache` for any storage backend:

```kotlin
class RedisEnrichmentCache(private val redis: RedisClient) : EnrichmentCache {

    override suspend fun get(entityKey: String, type: EnrichmentType): EnrichmentResult.Success? {
        val key = "$entityKey:${type.name}"
        val json = redis.get(key) ?: return null
        // Deserialize and return
    }

    override suspend fun put(
        entityKey: String,
        type: EnrichmentType,
        result: EnrichmentResult.Success,
        ttlMs: Long,
    ) {
        val key = "$entityKey:${type.name}"
        redis.setex(key, ttlMs / 1000, serialize(result))
    }

    override suspend fun invalidate(entityKey: String, type: EnrichmentType?) {
        if (type != null) redis.del("$entityKey:${type.name}")
        else redis.keys("$entityKey:*").forEach { redis.del(it) }
    }

    override suspend fun isManuallySelected(entityKey: String, type: EnrichmentType): Boolean {
        return redis.sismember("manual_selections", "$entityKey:${type.name}")
    }

    override suspend fun markManuallySelected(entityKey: String, type: EnrichmentType) {
        redis.sadd("manual_selections", "$entityKey:${type.name}")
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
