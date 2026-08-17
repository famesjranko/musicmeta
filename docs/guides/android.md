# Android Integration

The `musicmeta-android` module adds Android-specific integrations on top of `musicmeta-core`. It supports **API 21+** (Android 5.0 Lollipop) — the module uses Room, WorkManager, and Hilt, none of which require a higher API level.

## Dependencies

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.github.famesjranko.musicmeta:musicmeta-core:v0.11.0")
    implementation("com.github.famesjranko.musicmeta:musicmeta-android:v0.11.0")
}
```

---

## RoomEnrichmentCache

Room-backed persistent cache that survives app restarts. Uses `kotlinx.serialization` for `EnrichmentData` serialization.

### Manual setup (without Hilt)

```kotlin
val db = EnrichmentCacheDatabase.create(context, "enrichment_cache.db")
val cache = RoomEnrichmentCache(db.enrichmentCacheDao(), db.negativeCacheDao())

val engine = EnrichmentEngine.Builder()
    .withDefaultProviders()
    .cache(cache)
    .config(EnrichmentConfig(userAgent = "MyApp/1.0 (contact@example.com)"))
    .build()
```

`EnrichmentCacheDatabase.create()` registers every migration for you, so it opens an on-device v1,
v2, or v3 file in place instead of crashing with "migration required but not found". If you need
your own `Room.databaseBuilder` — a different `SupportSQLiteOpenHelper.Factory`, callbacks, and so
on — register every migration yourself, as shown in the subsections below.

### MIGRATION_1_2

If you used a previous version (database version 1), this migration adds identity resolution columns:

```kotlin
Room.databaseBuilder(context, EnrichmentCacheDatabase::class.java, "enrichment_cache.db")
    .addMigrations(EnrichmentCacheDatabase.MIGRATION_1_2, EnrichmentCacheDatabase.MIGRATION_2_3)
    .build()
```

The migration adds `identity_match`, `identity_match_score`, and `resolved_ids_json` columns to the cache table.

### MIGRATION_2_3

If you used a previous version (database version 2), this migration adds the `negative_cache`
table `RoomEnrichmentCache` uses to cache a confident "providers had nothing" answer. Additive —
it touches nothing else, and existing `enrichment_cache` rows are untouched.

```kotlin
Room.databaseBuilder(context, EnrichmentCacheDatabase::class.java, "enrichment_cache.db")
    .addMigrations(EnrichmentCacheDatabase.MIGRATION_1_2, EnrichmentCacheDatabase.MIGRATION_2_3)
    .build()
```

### MIGRATION_3_4

If you used a previous version (database version 3), this migration **clears both cache tables**.
`identity_match`/`identity_match_score` named a call-level verdict before this release; the new
columns (`canonical_status`, `lookup_provenance`, `schema_version`) name a different fact entirely,
and a stored v3 row cannot be reinterpreted into them. Every pre-upgrade entry becomes a cache
miss, healed the same way any other miss is — the next live `enrich()` call refetches it.

```kotlin
Room.databaseBuilder(context, EnrichmentCacheDatabase::class.java, "enrichment_cache.db")
    .addMigrations(
        EnrichmentCacheDatabase.MIGRATION_1_2,
        EnrichmentCacheDatabase.MIGRATION_2_3,
        EnrichmentCacheDatabase.MIGRATION_3_4,
    )
    .build()
```

### MIGRATION_4_5

If you used a previous version (database version 4), this migration moves manual selections off
`enrichment_cache` and into their own `selections` table. Only rows carrying `is_manual = 1` are
copied — a selection is user intent, not cached data, so it is preserved rather than discarded.
`enrichment_cache` itself is then dropped and recreated without the `is_manual` column, per
`MIGRATION_3_4`'s precedent; every pre-upgrade cache entry becomes a miss the next `enrich()` call
heals. `negative_cache` is untouched — its shape did not change.

```kotlin
Room.databaseBuilder(context, EnrichmentCacheDatabase::class.java, "enrichment_cache.db")
    .addMigrations(
        EnrichmentCacheDatabase.MIGRATION_1_2,
        EnrichmentCacheDatabase.MIGRATION_2_3,
        EnrichmentCacheDatabase.MIGRATION_3_4,
        EnrichmentCacheDatabase.MIGRATION_4_5,
    )
    .build()
```

### Cleaning up expired entries

`RoomEnrichmentCache` provides a `deleteExpired()` method. Call it periodically to keep the database size manageable:

```kotlin
cache.deleteExpired() // removes all rows where expiresAt < now
```

A good pattern is a periodic WorkManager task — weekly is usually sufficient given the built-in TTLs. See [cache-management.md](cache-management.md) for TTL values by type.

---

## HiltEnrichmentModule

If your app uses Hilt, the library provides a ready-made module that wires up Room and the cache DAO automatically:

```kotlin
// The module is auto-installed via @InstallIn(SingletonComponent::class).
// It provides:
//   - EnrichmentCacheDatabase (singleton)
//   - EnrichmentCacheDao (singleton)
//   - NegativeCacheDao (singleton)
//   - SelectionDao (singleton)
//   - RoomEnrichmentCache (singleton)
//
// Database name: "enrichment_cache.db"
```

Build the engine in your own Hilt module, injecting the cache:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object MyEnrichmentModule {

    @Provides
    @Singleton
    fun provideEngine(
        roomCache: RoomEnrichmentCache,
    ): EnrichmentEngine {
        return EnrichmentEngine.Builder()
            .withDefaultProviders()
            .cache(roomCache)
            .config(EnrichmentConfig(
                userAgent = "MyApp/1.0 (contact@example.com)",
            ))
            .apiKeys(ApiKeyConfig(
                lastFmKey = BuildConfig.LASTFM_KEY,
            ))
            .build()
    }
}
```

---

## EnrichmentWorker (background batch enrichment)

`EnrichmentWorker` is an abstract `CoroutineWorker` base class for processing enrichment in batches via WorkManager. Subclass it to bridge your domain models.

**Breaking change in v0.7.0:** `onItemEnriched` now receives `EnrichmentResults` instead of `Map<EnrichmentType, EnrichmentResult>`. Update any existing subclasses.

```kotlin
class AlbumEnrichmentWorker(
    context: Context,
    params: WorkerParameters,
    override val engine: EnrichmentEngine,
    private val albumRepository: AlbumRepository,
) : EnrichmentWorker(context, params) {

    override fun batchTypes(): Set<EnrichmentType> = setOf(
        EnrichmentType.ALBUM_ART,
        EnrichmentType.GENRE,
        EnrichmentType.ALBUM_TRACKS,
    )

    override suspend fun buildRequests(inputData: Data): List<EnrichmentRequest> {
        val albumIds = inputData.getStringArray("album_ids") ?: return emptyList()
        return albumIds.mapNotNull { id ->
            val album = albumRepository.getById(id) ?: return@mapNotNull null
            EnrichmentRequest.forAlbum(album.title, album.artist)
        }
    }

    override suspend fun onItemEnriched(
        request: EnrichmentRequest,
        results: EnrichmentResults,   // EnrichmentResults, not Map
    ) {
        val albumRequest = request as EnrichmentRequest.ForAlbum
        albumRepository.updateEnrichment(
            title = albumRequest.title,
            artworkUrl = results.albumArt()?.url,
            genres = results.genres(),
            trackCount = results.get<EnrichmentData.Tracklist>(EnrichmentType.ALBUM_TRACKS)
                ?.tracks?.size,
        )
    }
}
```

The worker:
- Reports progress via `setProgress()` with keys `KEY_PROCESSED` and `KEY_TOTAL`
- Handles individual item failures gracefully — one failed enrichment does not stop the batch
- Returns `Result.retry()` if the system stops the worker mid-batch

Enqueue it:

```kotlin
val request = OneTimeWorkRequestBuilder<AlbumEnrichmentWorker>()
    .setInputData(workDataOf("album_ids" to albumIds.toTypedArray()))
    .setConstraints(Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build())
    .build()

WorkManager.getInstance(context).enqueue(request)
```

---

## ViewModel integration pattern

A typical pattern for using musicmeta in an Android ViewModel with the Hilt-provided engine:

```kotlin
@HiltViewModel
class ArtistDetailViewModel @Inject constructor(
    private val engine: EnrichmentEngine,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ArtistDetailState>(ArtistDetailState.Loading)
    val uiState: StateFlow<ArtistDetailState> = _uiState.asStateFlow()

    fun loadArtist(name: String) {
        viewModelScope.launch {
            _uiState.value = ArtistDetailState.Loading

            val profile = engine.artistProfile(name)

            when (profile.canonicalStatus) {
                CanonicalStatus.AMBIGUOUS -> {
                    _uiState.value = ArtistDetailState.Disambiguation(profile.suggestions)
                }
                else -> {
                    _uiState.value = ArtistDetailState.Loaded(
                        name = profile.name,
                        photoUrl = profile.photo?.url,
                        bio = profile.bio?.text,
                        genres = profile.genres.map { it.name },
                        similarArtists = profile.similarArtists?.artists?.map { it.name } ?: emptyList(),
                        topTracks = profile.topTracks?.tracks ?: emptyList(),
                    )
                }
            }
        }
    }

    fun pickCandidate(candidate: SearchCandidate) {
        viewModelScope.launch {
            _uiState.value = ArtistDetailState.Loading
            val profile = engine.artistProfile(candidate)
            _uiState.value = ArtistDetailState.Loaded(
                name = profile.name,
                photoUrl = profile.photo?.url,
                bio = profile.bio?.text,
                genres = profile.genres.map { it.name },
                similarArtists = profile.similarArtists?.artists?.map { it.name } ?: emptyList(),
                topTracks = profile.topTracks?.tracks ?: emptyList(),
            )
        }
    }
}

sealed class ArtistDetailState {
    data object Loading : ArtistDetailState()
    data class Loaded(
        val name: String,
        val photoUrl: String?,
        val bio: String?,
        val genres: List<String>,
        val similarArtists: List<String>,
        val topTracks: List<TopTrack>,
    ) : ArtistDetailState()
    data class Disambiguation(val candidates: List<SearchCandidate>) : ArtistDetailState()
}
```
