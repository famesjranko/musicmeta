# Cache Management

## EnrichmentCache interface

```kotlin
interface EnrichmentCache {
    suspend fun get(entityKey: String, type: EnrichmentType): EnrichmentResult.Success?
    suspend fun put(entityKey: String, type: EnrichmentType, result: EnrichmentResult.Success, ttlMs: Long)
    suspend fun invalidate(entityKey: String, type: EnrichmentType? = null)
    suspend fun isManuallySelected(entityKey: String, type: EnrichmentType): Boolean
    suspend fun markManuallySelected(entityKey: String, type: EnrichmentType)
    suspend fun clear()
}
```

The engine handles all `get`/`put` calls transparently. You interact with the cache through higher-level engine methods described below.

---

## InMemoryEnrichmentCache (default)

LRU cache that lives in process memory. Used automatically when no cache is provided to the builder.

```kotlin
// Default: 500 entries
val engine = EnrichmentEngine.Builder()
    .withDefaultProviders()
    .build() // uses InMemoryEnrichmentCache(maxEntries = 500)

// Custom capacity
val engine = EnrichmentEngine.Builder()
    .withDefaultProviders()
    .cache(InMemoryEnrichmentCache(maxEntries = 2000))
    .build()
```

For a persistent cache that survives process restarts, use `RoomEnrichmentCache` on Android — see [android.md](android.md).

---

## Cache key structure

The engine generates cache keys from the normalized request:

- Artist: `"artist:radiohead"`
- Album: `"album:radiohead:ok computer"`
- Track: `"track:radiohead:creep"`

Each key is combined with the `EnrichmentType` to form the full entry key: `"artist:radiohead:GENRE"`.

### Key convergence after disambiguation

When the user picks a disambiguation candidate, the re-enrichment request carries the resolved MBID. Subsequent lookups for the same entity converge to the same cache key regardless of the original query text. This means "bush" and "Bush (band)" that both resolve to the same MBID will share cached results. See [identity-resolution.md](identity-resolution.md) for the disambiguation flow.

---

## TTLs per type

Each `EnrichmentType` has a built-in TTL:

| Category | TTL | Types |
|----------|-----|-------|
| Preview | 24 hours | `TRACK_PREVIEW` |
| Statistics | 7 days | `TRACK_POPULARITY`, `ARTIST_POPULARITY`, `ARTIST_RADIO`, `ARTIST_RADIO_DISCOVERY`, `ARTIST_TOP_TRACKS` |
| Dynamic data | 30 days | `ARTIST_PHOTO`, `ARTIST_BACKGROUND`, `ARTIST_BIO`, `SIMILAR_ARTISTS`, `SIMILAR_TRACKS`, `BAND_MEMBERS`, `CREDITS`, `ARTIST_DISCOGRAPHY`, `ARTIST_TIMELINE`, `SIMILAR_ALBUMS`, `GENRE_DISCOVERY` |
| Artwork | 90 days | `ALBUM_ART`, `ARTIST_LOGO`, `CD_ART`, `ARTIST_BANNER`, `ALBUM_ART_BACK`, `ALBUM_BOOKLET`, `GENRE`, `ALBUM_METADATA`, `LYRICS_SYNCED`, `LYRICS_PLAIN`, `ARTIST_LINKS` |
| Stable metadata | 365 days | `LABEL`, `RELEASE_DATE`, `RELEASE_TYPE`, `COUNTRY`, `ALBUM_TRACKS`, `RELEASE_EDITIONS` |

Override per-type TTLs via `EnrichmentConfig.ttlOverrides`:

```kotlin
EnrichmentConfig(
    ttlOverrides = mapOf(
        EnrichmentType.GENRE to 180L * 24 * 60 * 60 * 1000,            // 6 months
        EnrichmentType.ARTIST_POPULARITY to 1L * 24 * 60 * 60 * 1000,  // 1 day
    ),
)
```

---

## Invalidating cache entries

Use `engine.invalidate()` to clear cached data by request — the engine resolves the cache key for you:

```kotlin
val request = EnrichmentRequest.forArtist("Radiohead")

// Invalidate all cached types for this entity
engine.invalidate(request)

// Invalidate a specific type only
engine.invalidate(request, EnrichmentType.GENRE)
```

This is preferred over calling `engine.cache.invalidate(entityKey, ...)` directly because it handles key generation consistently.

---

## forceRefresh

Bypass the cache entirely and fetch fresh data from providers:

```kotlin
// Via profile methods
val profile = engine.artistProfile("Radiohead", forceRefresh = true)
val profile = engine.albumProfile("OK Computer", "Radiohead", forceRefresh = true)
val profile = engine.trackProfile("Creep", "Radiohead", forceRefresh = true)

// Via enrich() directly
val results = engine.enrich(
    EnrichmentRequest.forArtist("Radiohead"),
    EnrichmentRequest.DEFAULT_ARTIST_TYPES,
    forceRefresh = true,
)
```

`forceRefresh = true` clears existing cache entries for the requested types (including any manual selection flags) before fetching. The fresh results are written back to the cache normally.

---

## Manual selection

The cache supports marking entries as "manually selected" — useful when a user explicitly picks artwork or corrects a result. Manually selected entries survive normal cache invalidation (they are not cleared unless explicitly overwritten or `forceRefresh` is used).

```kotlin
val request = EnrichmentRequest.forArtist("Radiohead")

// Mark an entry as manually selected (e.g., user picked this photo)
engine.markManuallySelected(request, EnrichmentType.ARTIST_PHOTO)

// Check if an entry was manually selected before overwriting
val isManual = engine.isManuallySelected(request, EnrichmentType.ARTIST_PHOTO)
if (!isManual) {
    // Safe to refresh automatically
}
```

Use manual selection for features like user artwork overrides, where the user's explicit choice should not be silently replaced by a background refresh.

### Manual selection via the cache directly

If you need to operate on raw cache keys (e.g., in a bulk migration):

```kotlin
engine.cache.markManuallySelected("artist:radiohead", EnrichmentType.ARTIST_PHOTO)
engine.cache.isManuallySelected("artist:radiohead", EnrichmentType.ARTIST_PHOTO)
engine.cache.invalidate("artist:radiohead")             // clears all types for this entity
engine.cache.invalidate("artist:radiohead", EnrichmentType.GENRE)  // specific type only
engine.cache.clear()                                    // wipes the entire cache
```

---

## Stale-while-revalidate (offline fallback)

By default, expired cache entries are not served — the engine returns provider errors as-is. Enable `STALE_IF_ERROR` to serve expired data when the network fails:

```kotlin
val engine = EnrichmentEngine.Builder()
    .withDefaultProviders()
    .config(EnrichmentConfig(cacheMode = CacheMode.STALE_IF_ERROR))
    .build()
```

### How it works

When a provider returns `Error` or `RateLimited` and an expired cache entry exists for that type, the engine serves the expired entry as `Success` with `isStale = true`. The consumer can show a staleness indicator:

```kotlin
val result = results.result(EnrichmentType.GENRE) as? EnrichmentResult.Success
if (result?.isStale == true) {
    showBanner("Showing cached data — check your connection")
}
```

### Rules

| Provider result | Expired entry exists? | Behavior |
|-----------------|----------------------|----------|
| `Error` | Yes | Serve stale (`isStale = true`) |
| `RateLimited` | Yes | Serve stale (`isStale = true`) |
| `Error` | No | Return `Error` as-is |
| `NotFound` | Yes | Return `NotFound` — provider found nothing, stale would be misleading |
| `Success` | — | Return fresh `Success` normally |

### Cache write guard

Stale results are **not** re-written to cache. The `!isStale` guard prevents expired data from receiving a fresh TTL. The original expired entry remains in the cache for future stale serving until LRU eviction removes it.

### CacheMode values

| Mode | Behavior |
|------|----------|
| `NETWORK_FIRST` | Default. Expired entries are never served. Errors returned as-is. |
| `STALE_IF_ERROR` | Serve expired entries on `Error`/`RateLimited`. Fresh results cached normally. |

### Custom cache implementations

If you implement `EnrichmentCache`, add `getIncludingExpired()` to support stale serving:

```kotlin
override suspend fun getIncludingExpired(
    entityKey: String,
    type: EnrichmentType,
): EnrichmentResult.Success? {
    // Return the entry regardless of expiry
    // Default implementation returns null (stale serving disabled)
}
```

`InMemoryEnrichmentCache` and `RoomEnrichmentCache` both implement this method.

---

## Clearing expired entries (Android)

`RoomEnrichmentCache` provides a `deleteExpired()` method for housekeeping. Call it periodically — a good pattern is a periodic WorkManager task:

```kotlin
cache.deleteExpired() // removes all rows where expiresAt < now
```

See [android.md](android.md) for the full WorkManager pattern.
