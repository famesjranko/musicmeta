package com.landofoz.musicmeta

import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import com.landofoz.musicmeta.engine.DefaultEnrichmentEngine
import com.landofoz.musicmeta.engine.ProviderRegistry
import com.landofoz.musicmeta.http.DefaultHttpClient
import com.landofoz.musicmeta.http.HttpClient

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

        fun addProvider(provider: EnrichmentProvider) = apply { providers.add(provider) }
        fun cache(cache: EnrichmentCache) = apply { this.cache = cache }
        fun httpClient(client: HttpClient) = apply { this.httpClient = client }
        fun config(config: EnrichmentConfig) = apply { this.config = config }
        fun logger(logger: EnrichmentLogger) = apply { this.logger = logger }

        fun build(): EnrichmentEngine {
            val registry = ProviderRegistry(providers, config.priorityOverrides)
            return DefaultEnrichmentEngine(
                registry = registry,
                cache = cache ?: InMemoryEnrichmentCache(),
                httpClient = httpClient ?: DefaultHttpClient(config.userAgent),
                config = config,
                logger = logger,
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
