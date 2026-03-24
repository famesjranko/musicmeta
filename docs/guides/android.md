# Android Integration

The `musicmeta-android` module adds Android-specific integrations on top of `musicmeta-core`.

## Dependencies

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.github.famesjranko.musicmeta:musicmeta-core:v0.7.0")
    implementation("com.github.famesjranko.musicmeta:musicmeta-android:v0.7.0")
}
```

---

## RoomEnrichmentCache

Room-backed persistent cache that survives app restarts. Uses `kotlinx.serialization` for `EnrichmentData` serialization.

### Manual setup (without Hilt)

```kotlin
val db = Room.databaseBuilder(context, EnrichmentCacheDatabase::class.java, "enrichment_cache.db")
    .addMigrations(EnrichmentCacheDatabase.MIGRATION_1_2)
    .build()
val cache = RoomEnrichmentCache(db.enrichmentCacheDao())

val engine = EnrichmentEngine.Builder()
    .withDefaultProviders()
    .cache(cache)
    .config(EnrichmentConfig(userAgent = "MyApp/1.0 (contact@example.com)"))
    .build()
```

### MIGRATION_1_2

If you used a previous version (database version 1), apply the migration to add identity resolution columns:

```kotlin
Room.databaseBuilder(context, EnrichmentCacheDatabase::class.java, "enrichment_cache.db")
    .addMigrations(EnrichmentCacheDatabase.MIGRATION_1_2)
    .build()
```

The migration adds `identity_match`, `identity_match_score`, and `resolved_ids_json` columns to the cache table.

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

            when (profile.identityMatch) {
                IdentityMatch.SUGGESTIONS -> {
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
