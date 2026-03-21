package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierRequirement
import com.landofoz.musicmeta.http.CircuitBreaker

class ProviderChain(
    val type: EnrichmentType,
    private val providers: List<EnrichmentProvider>,
    private val circuitBreakers: Map<String, CircuitBreaker> =
        providers.associate { it.id to CircuitBreaker() },
) {
    /**
     * Collects ALL Success results from every provider — does not short-circuit on first success.
     * Used for mergeable types (e.g. GENRE) where multiple providers contribute data.
     * Respects the same availability, identifier, and circuit breaker checks as resolve().
     */
    suspend fun resolveAll(request: EnrichmentRequest): List<EnrichmentResult.Success> {
        val successes = mutableListOf<EnrichmentResult.Success>()
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
                is EnrichmentResult.Success -> { breaker?.recordSuccess(); successes.add(result) }
                is EnrichmentResult.NotFound -> { breaker?.recordSuccess(); continue }
                is EnrichmentResult.RateLimited -> continue
                is EnrichmentResult.Error -> { breaker?.recordFailure(); continue }
            }
        }
        return successes
    }

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
        val capability = provider.capabilities.firstOrNull { it.type == type } ?: return true
        return when (capability.identifierRequirement) {
            IdentifierRequirement.NONE -> true
            IdentifierRequirement.MUSICBRAINZ_ID -> identifiers.musicBrainzId != null
            IdentifierRequirement.MUSICBRAINZ_RELEASE_GROUP_ID -> identifiers.musicBrainzReleaseGroupId != null
            IdentifierRequirement.WIKIDATA_ID -> identifiers.wikidataId != null
            IdentifierRequirement.WIKIPEDIA_TITLE -> identifiers.wikipediaTitle != null || identifiers.wikidataId != null
            IdentifierRequirement.ANY_IDENTIFIER -> identifiers.musicBrainzId != null ||
                identifiers.musicBrainzReleaseGroupId != null ||
                identifiers.wikidataId != null ||
                identifiers.wikipediaTitle != null
        }
    }

    fun providers(): List<EnrichmentProvider> = providers

    fun providerCount(): Int = providers.size
}
