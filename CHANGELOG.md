# Changelog

All notable changes to musicmeta will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.9.1] - 2026-03-26

### Fixed
- **Empty top track titles from ListenBrainz** — API parser read `track_name` but the API returns `recording_name`, causing all TopTrack titles to be empty strings
- **Missing album names from ListenBrainz top tracks** — parser looked for a nested `release` object but the API returns `release_name` as a top-level field

### Migration note
If you use `EnrichmentCache`, clear your cache after upgrading from 0.9.0 or earlier. Cached entries from previous versions may contain empty titles or missing fields that are now correctly populated.

## [0.9.0] - 2026-03-26

LB Radio & Track Preview — 34 enrichment types.

### Added
- **`TRACK_PREVIEW` enrichment type** — 30-second MP3 preview URL via Deezer track search. On-demand type (not in `DEFAULT_TRACK_TYPES`). Access via `EnrichmentResults.trackPreview()` or `TrackProfile.preview`. 24-hour TTL (CDN URLs may rotate). No API key required.
- **`ARTIST_RADIO_DISCOVERY` enrichment type** — community-driven radio playlist via ListenBrainz LB Radio. Returns `RadioPlaylist` with recording, artist, and release MBIDs. Access via `EnrichmentResults.radioDiscovery()` or `ArtistProfile.radioDiscovery`. 7-day TTL. Included in `DEFAULT_ARTIST_TYPES`. Requires `ApiKeyConfig.listenBrainzToken` (free ListenBrainz account).
- **`RadioDiscoveryMode` enum** on `EnrichmentConfig` — controls LB Radio discovery depth: `EASY` (familiar-adjacent, default), `MEDIUM`, `HARD` (adventurous, deeper cuts).
- **`ApiKeyConfig.listenBrainzToken`** — optional user token for LB Radio. When absent, `ARTIST_RADIO_DISCOVERY` is silently unavailable; all other ListenBrainz endpoints continue working without any token.
- **`DeezerTrackSearchResult`** now carries `previewUrl: String?`, `durationSec: Int?`, and `albumTitle: String?` — extracted from the already-available Deezer track search response, no extra API calls.
- **HttpClient header support** — `fetchJsonResult(url, headers)` overload added to `HttpClient` interface (default no-op impl for backward compatibility); used by LB Radio to send `Authorization: Token` headers.

## [0.8.2] - 2026-03-25

### Changed
- **Android `minSdk` lowered from 26 to 21** — no API-level-specific code exists in the android module; 21 (Android 5.0) is the practical floor
- **README** added Kotlin badge

## [0.8.1] - 2026-03-25

### Fixed
- **Search command** falls back to fuzzy matching when exact search returns empty — typos like "radohead" now find "Radiohead"
- **`TrackingCache`** delegates `getIncludingExpired()` — stale cache mode now works in demo

### Added
- **Demo**: `config http default|okhttp` — switch HTTP backend for live testing
- **Demo**: `config stale on|off` — toggle `STALE_IF_ERROR` cache mode
- **Demo**: `batch artist|album|track a; b; c` — bulk enrichment with streaming output
- **Demo**: `[stale]` indicator on results served from expired cache

### Changed
- **Maven group ID** changed from `com.landofoz` to `io.github.famesjranko` for Central Portal publishing
- **README** license set to Apache-2.0

## [0.8.0] - 2026-03-24

Production Readiness — 32 enrichment types.

### Added
- **`musicmeta-okhttp` module** — `OkHttpEnrichmentClient` implementing all 10 `HttpClient` methods via OkHttp 4.12.0 `Call` API. Transparent gzip decompression (no manual `Accept-Encoding` header). No built-in retry — delegates to OkHttp interceptors. Timeouts inherited from caller's `OkHttpClient` instance.
- **`CacheMode.STALE_IF_ERROR`** — when provider returns `Error` or `RateLimited` and an expired cache entry exists, serves the expired entry as `Success` with `isStale = true`. Does not serve stale for `NotFound` (provider found nothing). Stale results are not re-cached with fresh TTL.
- **`CacheMode` enum** on `EnrichmentConfig` — `NETWORK_FIRST` (default, existing behavior) and `STALE_IF_ERROR`
- **`isStale: Boolean`** on `EnrichmentResult.Success` — `false` by default, `true` when result is from expired cache via stale fallback
- **`getIncludingExpired()`** on `EnrichmentCache` — returns cached entry regardless of expiry. Default implementation returns `null` (backward compatible for custom caches). Implemented by `InMemoryEnrichmentCache` and `RoomEnrichmentCache`.
- **`enrichBatch()`** on `EnrichmentEngine` — returns `Flow<Pair<EnrichmentRequest, EnrichmentResults>>` for bulk enrichment. Sequential iteration with cooperative cancellation. Cache hits return immediately. Default method on interface with explicit override in `DefaultEnrichmentEngine`.
- **Maven Central publishing** via vanniktech `gradle-maven-publish-plugin` targeting `SonatypeHost.CENTRAL_PORTAL` — all 3 modules (`musicmeta-core`, `musicmeta-okhttp`, `musicmeta-android`) with POM metadata (Apache 2.0, developer, SCM), conditional GPG signing, sources + javadoc jars

### Changed
- **`InMemoryEnrichmentCache`** no longer eagerly deletes expired entries on `get()` — expired entries remain in the LRU map for stale serving via `getIncludingExpired()`
- **`EnrichmentEngine` interface** gains `enrichBatch()` default method — custom implementations inherit it automatically
- **Version bumped** from 0.1.0 to 0.8.0 across all modules
- **README** updated with Maven Central as primary installation method; JitPack preserved for existing consumers

## [0.7.0] - 2026-03-24

Developer Experience — profiles, named accessors, cache management, identity signals.

### Added
- **`EnrichmentResults` wrapper** — `enrich()` now returns `EnrichmentResults` (data class) instead of raw `Map`. Includes `raw` map access, `requestedTypes` set, and top-level `IdentityResolution`
- **`IdentityResolution` data class** — engine-level identity outcome (identifiers, match status, score, suggestions) accessible without scanning individual results
- **19 named accessors** on `EnrichmentResults` — `albumArt()`, `artistPhoto()`, `biography()`, `lyrics()`, `credits()`, `genres()`, `genreTags()`, `label()`, `releaseDate()`, `releaseType()`, `country()`, `similarArtists()`, `similarTracks()`, `topTracks()`, `radio()`, `discography()`, `similarAlbums()`, plus `artistPopularity()` and `trackPopularity()`
- **Generic typed accessor** `EnrichmentResults.get<T>(type)` — type-safe data extraction for any `EnrichmentData` subclass
- **`wasRequested(type)` and `result(type)`** on `EnrichmentResults` — distinguish "not requested" from "not found"; access raw result for error diagnostics
- **Metadata field fallback** — `genres()`, `label()`, `releaseDate()`, etc. try the dedicated type first, then fall back to `ALBUM_METADATA`
- **Default type sets** — `EnrichmentRequest.DEFAULT_ARTIST_TYPES`, `DEFAULT_ALBUM_TYPES`, `DEFAULT_TRACK_TYPES`; composable via set algebra
- **`defaultTypesFor(request)`** — returns the appropriate default set for any request kind
- **Profile extension functions** — `engine.artistProfile("Radiohead")`, `engine.albumProfile("OK Computer", "Radiohead")`, `engine.trackProfile("Creep", "Radiohead")` returning structured data classes with computed properties
- **`ArtistProfile`** — photo, bio, genres, members, discography, links, popularity, topTracks, similarArtists, radio, similarAlbums, timeline, genreDiscovery, identity, suggestions
- **`AlbumProfile`** — artwork (front/back/booklet/CD), genres, label, releaseDate, releaseType, country, tracks, editions, similarAlbums, genreDiscovery, identity
- **`TrackProfile`** — genres, lyrics, credits, artwork, popularity, similarTracks, genreDiscovery, identity
- **`SearchCandidate` profile overloads** — `engine.artistProfile(candidate)` for smooth "did you mean?" → re-enrich flow
- **Custom type sets on profiles** — `engine.artistProfile("Radiohead", types = setOf(GENRE, ARTIST_PHOTO))` to skip unnecessary API calls
- **`forceRefresh` parameter** on `enrich()` and all profile extensions — bypasses cache for the requested types, clears existing entries (including manual selections) before fetching
- **`engine.invalidate(request, type?)`** — invalidate cached data by request without knowing internal cache keys. Clears both MBID and name-alias keys. Pass `null` type to clear all types.
- **`engine.isManuallySelected(request, type)` / `engine.markManuallySelected(request, type)`** — manual selection support (e.g., user picks artwork) without cache key knowledge
- `SIMILAR_TRACKS` multi-provider merge — Deezer `/track/{id}/radio` added as second provider alongside Last.fm `track.getSimilar`
- `IdentityMatch` enum, `identityMatchScore`, `NotFound.suggestions`, short-circuit on suggestions, fuzzy fallback search
- **Demo CLI refactored** to showcase all three API tiers — enrichment commands use profile methods (Tier 1), profile summary card shows named accessors (Tier 2), per-type diagnostic output uses raw map (Tier 3). New `refresh` and `invalidate` commands demonstrate cache management. `pick` uses `SearchCandidate` overloads.
- **Developer guide split** into 7 focused pages under `docs/guides/` — quick-start, identity resolution, results & errors, cache management, configuration, extension points, Android integration

### Fixed
- **`ProviderChain` preserves failure reasons** — when all providers fail with `RateLimited` or `Error`, the chain now returns the actual failure instead of collapsing to `NotFound`. Consumers can distinguish "data doesn't exist" from "all providers failed" for retry logic
- **Room cache persists identity fields** — `identityMatch`, `identityMatchScore`, and `resolvedIdentifiers` now round-trip through `RoomEnrichmentCache` (previously silently dropped on cache read). DB migration v1→v2
- **Cache key convergence after disambiguation** — when identity resolution resolves an MBID, results are also cached under the name-based key, so future name-only lookups find the MBID-resolved data
- iTunes `itunesArtistId` stored in `resolvedIdentifiers` after artist search

### Changed
- **Breaking:** `EnrichmentEngine.enrich()` returns `EnrichmentResults` instead of `Map<EnrichmentType, EnrichmentResult>`. Access the raw map via `.raw`. Signature also gains `forceRefresh: Boolean = false` (source compatible)
- **Breaking:** `EnrichmentEngine` interface gains `invalidate()`, `isManuallySelected()`, `markManuallySelected()` — custom implementations must add these methods
- **Breaking:** `EnrichmentWorker.onItemEnriched()` parameter changed from `Map` to `EnrichmentResults`
- Room database version 1 → 2 (automatic migration included via `MIGRATION_1_2`)

## [0.6.0] - 2026-03-23

Recommendations Engine — 31 enrichment types.

### Added
- `SIMILAR_ARTISTS` multi-provider merge — Last.fm, ListenBrainz, and Deezer results deduplicated and combined via `SimilarArtistMerger`; each `SimilarArtist` has a `sources` field listing contributing providers
- `ARTIST_RADIO` enrichment type — Deezer `/artist/{id}/radio` endpoint returns ordered `RadioPlaylist` with up to 25 `RadioTrack` items (title, artist, album, durationMs, identifiers); 7-day TTL
- `SIMILAR_ALBUMS` enrichment type — `SimilarAlbumsProvider` fetches Deezer related artists and their top albums, scored by artist similarity and era proximity (±5yr = 1.2x, ±10yr = 1.0x, beyond = 0.8x)
- `GENRE_DISCOVERY` enrichment type — `GenreAffinityMatcher` uses a static taxonomy of ~70 genre relationships (parent, child, sibling) to produce `GenreAffinity` results with affinity scores and source genres
- `CatalogProvider` interface — consumers implement `checkAvailability(List<CatalogQuery>): List<CatalogMatch>` to filter recommendation results by local library or streaming service availability
- `CatalogFilterMode` enum — `AVAILABLE_ONLY`, `AVAILABLE_FIRST`, `UNFILTERED` — applied post-resolution to all recommendation types
- `ResultMerger` interface — extracted from `DefaultEnrichmentEngine`; `GenreMerger` and `SimilarArtistMerger` implement it; engine delegates all mergeable-type dispatch to the registry
- `CompositeSynthesizer` interface — extracted from `DefaultEnrichmentEngine`; `TimelineSynthesizer` and `GenreAffinityMatcher` implement it; engine delegates all composite-type dispatch to the registry
- `SimilarArtist.sources` field — `List<String>` listing provider IDs that contributed each artist (backfilled for Last.fm and ListenBrainz)
- `GenreAffinity` data class — `name`, `affinity: Float`, `relationship: String`, `sourceGenres: List<String>`
- `SimilarAlbum` data class — `title`, `artist`, `year: Int?`, `artistMatchScore: Float`, `thumbnailUrl: String?`, `identifiers: EnrichmentIdentifiers`
- `RadioPlaylist` and `RadioTrack` data classes — playlist container and track with `durationMs: Long?`
- `CatalogQuery` and `CatalogMatch` data classes — input/output types for `CatalogProvider.checkAvailability()`
- `GenreTaxonomy.kt` — static genre affinity data extracted to its own file (pure constant, no logic)
- `CatalogFilter.kt` — catalog filtering helpers extracted from `DefaultEnrichmentEngine`
- Enrichment showcase test updated with v0.6.0 feature spotlight (SIMILAR_ARTISTS merge, ARTIST_RADIO, SIMILAR_ALBUMS, GENRE_DISCOVERY)

### Changed
- `DefaultEnrichmentEngine` delegates mergeable-type dispatch to `ProviderRegistry.mergers` (was inline); delegates composite-type dispatch to `ProviderRegistry.synthesizers` (was inline)
- `EnrichmentConfig` gains optional `catalogProvider: CatalogProvider?` and `catalogFilterMode: CatalogFilterMode` (default `UNFILTERED` — no behavior change for existing consumers)
- `EnrichmentEngine.Builder` gains `.catalog(provider, mode)` convenience method
- `SIMILAR_ARTISTS` promoted to mergeable type — all configured providers contribute rather than first-success short-circuit

## [0.5.0] - 2026-03-22

New Capabilities & Tech Debt Cleanup.

### Added
- `CREDITS` enrichment type — `EnrichmentData.Credits` with `CreditEntry` (name, role, roleCategory, instruments); MusicBrainz provides via recording artist-rels, Discogs via extraartists
- `RELEASE_EDITIONS` enrichment type — `EnrichmentData.ReleaseEditions` with `ReleaseEdition` (title, format, country, year, catalogNumber, barcode, label); MusicBrainz provides via release-group releases, Discogs via master versions
- `ARTIST_TIMELINE` composite enrichment type — `EnrichmentData.ArtistTimeline` with `TimelineEvent` (date, type, description, relatedEntity); synthesized from `ARTIST_DISCOGRAPHY` + `BAND_MEMBERS` + artist life-span data
- `GENRE` genre tags — `EnrichmentData.Metadata.genreTags: List<GenreTag>?` with per-tag confidence and sources; backward-compatible alongside existing `genres` field
- `GenreTag` data class — `name`, `confidence: Float`, `sources: List<String>`
- `GenreMerger` — additive confidence scoring across MusicBrainz and Last.fm genre data; deduplicates by name
- `TimelineSynthesizer` — composite synthesizer combining discography, members, and life-span into ordered timeline events
- `ResultMerger` / `CompositeSynthesizer` interfaces — engine extension points allowing new mergers and synthesizers without modifying `DefaultEnrichmentEngine`
- `ArtworkSize` extended — Cover Art Archive, Deezer, iTunes, Fanart.tv now all populate `sizes` field with multiple resolutions
- Discogs: `RELEASE_EDITIONS` via master versions endpoint (formats, countries, years, catalog numbers)
- MusicBrainz: `CREDITS` via recording artist-rels (performance, production, songwriting roles)
- `ConfidenceCalculator` — standardized confidence methods (`idBasedLookup`, `authoritative`, `searchScore`, `fuzzyMatch`)
- `ErrorKind` enum on `EnrichmentResult.Error` — `NETWORK`, `AUTH`, `PARSE`, `RATE_LIMIT`, `UNKNOWN`
- `HttpResult` sealed class — typed HTTP responses replacing nullable returns in all 11 providers
- `InMemoryEnrichmentCache` added as default in-process LRU cache
- Enrichment showcase test with v0.5.0 feature spotlight section

### Changed
- All 11 providers migrated to `HttpResult`/`ErrorKind` uniform error handling
- `EnrichmentType.defaultTtlMs` — per-type TTL in enum; `EnrichmentConfig.ttlOverrides` for per-type override

## [0.4.0] - 2026-03-21

Provider Abstraction Overhaul — 25 enrichment types.

### Added
- 9 new enrichment types: `BAND_MEMBERS`, `ARTIST_DISCOGRAPHY`, `ALBUM_TRACKS`, `SIMILAR_TRACKS`, `ARTIST_BANNER`, `ARTIST_LINKS`, `ALBUM_ART_BACK`, `ALBUM_BOOKLET`, `ALBUM_METADATA`
- `ArtworkSize` data class and `Artwork.sizes` field — multi-size artwork support across Cover Art Archive, Deezer, iTunes, and Fanart.tv
- `IdentifierRequirement` enum replacing boolean `requiresIdentifier` — typed identifier checking per provider (MUSICBRAINZ_ID, WIKIDATA_ID, WIKIPEDIA_TITLE, etc.)
- `isIdentityProvider` flag and `resolveIdentity()` method on `EnrichmentProvider` interface — formalized identity resolution as a provider role
- 11 `*Mapper.kt` files — provider mapper pattern isolating DTO-to-EnrichmentData mapping from provider logic
- `ApiKeyConfig` data class and `EnrichmentEngine.Builder.apiKeys()` + `withDefaultProviders()` — one-line engine setup
- `EnrichmentIdentifiers.extra` map with `get()` and `withExtra()` — extensible identifier storage for provider-specific IDs (deezerId, discogsArtistId)
- `ErrorKind` enum on `EnrichmentResult.Error` — categorize errors as NETWORK, AUTH, PARSE, RATE_LIMIT, UNKNOWN
- `HttpResult` sealed class with `fetchJsonResult()` on `HttpClient` — typed HTTP responses (Ok, ClientError, ServerError, RateLimited, NetworkError)
- `ConfidenceCalculator` utility — standardized confidence scoring (idBasedLookup, authoritative, searchScore, fuzzyMatch) across all 11 providers
- `EnrichmentType.defaultTtlMs` — TTL moved into enum with `EnrichmentConfig.ttlOverrides` for per-type override
- MusicBrainz: band members via artist-rels, discography via release-group browse, tracklist from media array, artist links from all URL relation types
- Deezer: artist discography via `/artist/{id}/albums`, album tracks via `/album/{id}/tracks`, album metadata (trackCount, explicit, genres)
- Last.fm: `track.getSimilar` for SIMILAR_TRACKS, `track.getInfo` for track-level TRACK_POPULARITY (replacing artist-level data)
- Fanart.tv: ARTIST_BANNER capability via musicbanner images
- Cover Art Archive: JSON metadata endpoint for back cover and booklet art with image type filtering
- Discogs: band members via artist endpoint, album metadata (catalogNumber, communityRating)
- iTunes: album metadata (trackCount, primaryGenreName)
- ListenBrainz: batch POST endpoints for recording and artist popularity, top release groups for artist
- Wikidata: expanded properties — P569 (birth date), P570 (death date), P495 (country of origin), P106 (occupation) in a single API call
- Wikipedia: ARTIST_PHOTO via page media-list endpoint as supplemental source
- Enrichment showcase test updated to reflect v0.4.0 coverage (25 types)

### Changed
- `ProviderCapability.requiresIdentifier: Boolean` replaced by `identifierRequirement: IdentifierRequirement` enum
- `ProviderRegistry.identityProvider()` selects by `isIdentityProvider` flag instead of GENRE/LABEL heuristic
- `DefaultEnrichmentEngine.needsIdentityResolution()` is data-driven from provider capabilities, not hardcoded type list
- `DefaultEnrichmentEngine.ttlFor()` removed — TTL now on `EnrichmentType.defaultTtlMs` with config override
- `SimilarArtist.musicBrainzId: String?` replaced by `identifiers: EnrichmentIdentifiers`
- `PopularTrack.musicBrainzId: String?` replaced by `identifiers: EnrichmentIdentifiers`
- `EnrichmentData.IdentifierResolution` removed from public sealed class — identity resolution uses `resolvedIdentifiers` on `EnrichmentResult.Success`
- MusicBrainzProvider returns `Metadata` directly from identity resolution instead of `IdentifierResolution`
- All 11 providers delegate EnrichmentData construction to mapper objects (zero inline construction)
- All 11 providers use `ConfidenceCalculator` methods (zero hardcoded float confidence values)

### Fixed
- MusicBrainz: empty search results return `NotFound` instead of `RateLimited` (3 locations)
- Last.fm: API base URL uses HTTPS instead of HTTP
- Last.fm: `TRACK_POPULARITY` removed from capabilities (was returning artist-level data); properly restored with `track.getInfo`
- LRCLIB: duration parameter uses `Double` instead of `Int` — preserves fractional seconds (238500ms → 238.5s, not 238s)
- Wikidata: claim resolution filters for preferred-rank claims before falling back to first in array
- Wikidata: URL-encode pipe characters in multi-property query string (prevents `URISyntaxException`)

## [0.1.0] - 2026-03-21

### Added
- `EnrichmentEngine` with builder pattern, fan-out provider chains, confidence filtering, and configurable timeout
- Identity resolution pipeline — MusicBrainz resolves MBIDs, Wikidata IDs, and Wikipedia titles for downstream providers
- 11 providers: MusicBrainz, Cover Art Archive, Wikidata, Wikipedia, LRCLIB, Deezer, iTunes, Last.fm, ListenBrainz, Fanart.tv, Discogs
- `ProviderChain` with priority ordering and circuit breakers per provider
- `RateLimiter` for per-provider request throttling
- `InMemoryEnrichmentCache` with LRU eviction and TTL
- `EnrichmentConfig` with `minConfidence`, `confidenceOverrides`, `enableIdentityResolution`, `enrichTimeoutMs`
- `HttpClient` interface with `DefaultHttpClient` (java.net.HttpURLConnection)
- `ArtistMatcher` for music-aware fuzzy name matching across providers
- Search API (`engine.search()`) with candidate deduplication across providers
- Enrichment showcase test (`EnrichmentShowcaseTest`) — comprehensive E2E diagnostic
- API key forwarding in `build.gradle.kts` — Last.fm, Fanart.tv, and Discogs keys via system properties or env vars
- `musicmeta-android` module: `RoomEnrichmentCache`, `HiltEnrichmentModule`, `EnrichmentWorker`
- E2E test suite against real APIs (gated by `-Dinclude.e2e=true`)
- JitPack publishing support

### Fixed
- Artist identity resolution resolves wikidata/wikipedia URLs during all identity lookups
- E2E tests use `runBlocking` (not `runTest`) to avoid virtual-time timeout issues
- Silent exception swallowing in engine and cache now logged through `EnrichmentLogger`

### Changed
- Provider priorities configurable via `EnrichmentConfig.priorityOverrides`
- MusicBrainz minimum match score is a constructor param (`minMatchScore`, default 80)
- Artwork sizes are per-provider constructor params (not engine-level config)
