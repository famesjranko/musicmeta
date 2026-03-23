package com.landofoz.musicmeta

import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import com.landofoz.musicmeta.engine.ArtworkMerger
import com.landofoz.musicmeta.engine.DefaultEnrichmentEngine
import com.landofoz.musicmeta.engine.GenreAffinityMatcher
import com.landofoz.musicmeta.engine.GenreMerger
import com.landofoz.musicmeta.engine.ProviderRegistry
import com.landofoz.musicmeta.engine.SimilarArtistMerger
import com.landofoz.musicmeta.engine.SimilarTrackMerger
import com.landofoz.musicmeta.engine.TimelineSynthesizer
import com.landofoz.musicmeta.http.DefaultHttpClient
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.provider.coverartarchive.CoverArtArchiveProvider
import com.landofoz.musicmeta.provider.deezer.DeezerApi
import com.landofoz.musicmeta.provider.deezer.DeezerProvider
import com.landofoz.musicmeta.provider.deezer.SimilarAlbumsProvider
import com.landofoz.musicmeta.provider.discogs.DiscogsProvider
import com.landofoz.musicmeta.provider.fanarttv.FanartTvProvider
import com.landofoz.musicmeta.provider.itunes.ITunesProvider
import com.landofoz.musicmeta.provider.lastfm.LastFmProvider
import com.landofoz.musicmeta.provider.listenbrainz.ListenBrainzProvider
import com.landofoz.musicmeta.provider.lrclib.LrcLibProvider
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzProvider
import com.landofoz.musicmeta.provider.wikidata.WikidataProvider
import com.landofoz.musicmeta.provider.wikipedia.WikipediaProvider

interface EnrichmentEngine {

    suspend fun enrich(
        request: EnrichmentRequest,
        types: Set<EnrichmentType>,
    ): Map<EnrichmentType, EnrichmentResult>

    suspend fun search(
        request: EnrichmentRequest,
        limit: Int = 10,
    ): List<SearchCandidate>

    fun getProviders(): List<ProviderInfo>

    val cache: EnrichmentCache

    class Builder {
        private val providers = mutableListOf<EnrichmentProvider>()
        private var cache: EnrichmentCache? = null
        private var httpClient: HttpClient? = null
        private var config: EnrichmentConfig = EnrichmentConfig()
        private var logger: EnrichmentLogger = EnrichmentLogger.NoOp
        private var apiKeyConfig: ApiKeyConfig? = null
        private val mergers = mutableListOf<com.landofoz.musicmeta.engine.ResultMerger>(
            GenreMerger, SimilarArtistMerger, SimilarTrackMerger,
            ArtworkMerger(EnrichmentType.ARTIST_PHOTO),
            ArtworkMerger(EnrichmentType.ALBUM_ART),
        )
        private val synthesizers = mutableListOf<com.landofoz.musicmeta.engine.CompositeSynthesizer>(TimelineSynthesizer, GenreAffinityMatcher)

        fun addProvider(provider: EnrichmentProvider) = apply { providers.add(provider) }
        fun cache(cache: EnrichmentCache) = apply { this.cache = cache }
        fun httpClient(client: HttpClient) = apply { this.httpClient = client }
        fun config(config: EnrichmentConfig) = apply { this.config = config }
        fun logger(logger: EnrichmentLogger) = apply { this.logger = logger }
        fun apiKeys(config: ApiKeyConfig) = apply { this.apiKeyConfig = config }
        fun catalog(provider: CatalogProvider, mode: CatalogFilterMode = CatalogFilterMode.UNFILTERED) = apply {
            this.config = this.config.copy(catalogProvider = provider, catalogFilterMode = mode)
        }
        fun addMerger(merger: com.landofoz.musicmeta.engine.ResultMerger) = apply { mergers.add(merger) }
        fun addSynthesizer(synthesizer: com.landofoz.musicmeta.engine.CompositeSynthesizer) = apply { synthesizers.add(synthesizer) }

        fun withDefaultProviders() = apply {
            val client = httpClient ?: DefaultHttpClient(config.userAgent)
            val mbRateLimiter = RateLimiter(1100) // MusicBrainz: max 1 req/sec
            val defaultRateLimiter = RateLimiter(100)

            // Always-available providers (no API key needed)
            addProvider(MusicBrainzProvider(client, mbRateLimiter))
            addProvider(CoverArtArchiveProvider(client, defaultRateLimiter))
            addProvider(WikidataProvider(client, defaultRateLimiter))
            addProvider(WikipediaProvider(client, defaultRateLimiter))
            addProvider(DeezerProvider(client))
            val deezerApi = DeezerApi(client, defaultRateLimiter)
            addProvider(SimilarAlbumsProvider(deezerApi))
            addProvider(ITunesProvider(client))
            addProvider(ListenBrainzProvider(client, defaultRateLimiter))
            addProvider(LrcLibProvider(client, defaultRateLimiter))

            // Key-requiring providers (only added if key is provided)
            val keys = apiKeyConfig
            if (keys != null) {
                keys.lastFmKey?.let {
                    addProvider(LastFmProvider(it, client, defaultRateLimiter))
                }
                keys.fanartTvProjectKey?.let {
                    addProvider(FanartTvProvider(it, client, defaultRateLimiter))
                }
                keys.discogsPersonalToken?.let {
                    addProvider(DiscogsProvider(it, client, defaultRateLimiter))
                }
            }
        }

        fun build(): EnrichmentEngine {
            val registry = ProviderRegistry(providers, config.priorityOverrides, logger)
            return DefaultEnrichmentEngine(
                registry = registry,
                cache = cache ?: InMemoryEnrichmentCache(),
                httpClient = httpClient ?: DefaultHttpClient(config.userAgent),
                config = config,
                logger = logger,
                mergers = mergers.toList(),
                synthesizers = synthesizers.toList(),
            )
        }
    }
}

data class SearchCandidate(
    val title: String,
    val artist: String?,
    val year: String?,
    val country: String?,
    val releaseType: String?,
    val score: Int,
    val thumbnailUrl: String?,
    val identifiers: EnrichmentIdentifiers,
    val provider: String,
)

data class ProviderInfo(
    val id: String,
    val displayName: String,
    val capabilities: List<ProviderCapability>,
    val requiresApiKey: Boolean,
    val isAvailable: Boolean,
    val isEnabled: Boolean = true,
)
