package com.cascade.enrichment.engine

import com.cascade.enrichment.EnrichmentIdentifiers
import com.cascade.enrichment.EnrichmentProvider
import com.cascade.enrichment.EnrichmentRequest
import com.cascade.enrichment.EnrichmentResult
import com.cascade.enrichment.EnrichmentType
import com.cascade.enrichment.http.CircuitBreaker

class ProviderChain(
    val type: EnrichmentType,
    private val providers: List<EnrichmentProvider>,
    private val circuitBreakers: Map<String, CircuitBreaker> =
        providers.associate { it.id to CircuitBreaker() },
) {
    suspend fun resolve(request: EnrichmentRequest): EnrichmentResult {
        for (provider in providers) {
            if (!provider.isAvailable) continue
            if (!hasRequiredIdentifiers(provider, request.identifiers)) continue

            val breaker = circuitBreakers[provider.id]
            if (breaker != null && !breaker.allowRequest()) continue

            val result = try {
                provider.enrich(request, type)
            } catch (e: Exception) {
                EnrichmentResult.Error(type, provider.id, e.message ?: "Unknown error", e)
            }

            when (result) {
                is EnrichmentResult.Success -> { breaker?.recordSuccess(); return result }
                is EnrichmentResult.NotFound -> { breaker?.recordSuccess(); continue }
                is EnrichmentResult.RateLimited -> continue
                is EnrichmentResult.Error -> { breaker?.recordFailure(); continue }
            }
        }
        return EnrichmentResult.NotFound(type, "all_providers")
    }

    private fun hasRequiredIdentifiers(
        provider: EnrichmentProvider,
        identifiers: EnrichmentIdentifiers,
    ): Boolean {
        val capability = provider.capabilities.firstOrNull { it.type == type }
        if (capability == null || !capability.requiresIdentifier) return true
        return identifiers.musicBrainzId != null ||
            identifiers.musicBrainzReleaseGroupId != null ||
            identifiers.wikidataId != null ||
            identifiers.wikipediaTitle != null
    }

    fun providerCount(): Int = providers.size
}
