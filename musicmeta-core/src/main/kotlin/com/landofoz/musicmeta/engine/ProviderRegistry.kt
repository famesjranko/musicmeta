package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderInfo
import com.landofoz.musicmeta.http.CircuitBreaker

class ProviderRegistry(providers: List<EnrichmentProvider>) {

    private val allProviders: List<EnrichmentProvider> = providers.toList()

    /** One circuit breaker per provider, shared across all chains. */
    private val circuitBreakers: Map<String, CircuitBreaker> =
        allProviders.associate { it.id to CircuitBreaker() }

    private val chains: Map<EnrichmentType, ProviderChain> = buildChains(allProviders)

    fun chainFor(type: EnrichmentType): ProviderChain? = chains[type]
    fun supportedTypes(): Set<EnrichmentType> = chains.keys

    fun identityProvider(): EnrichmentProvider? =
        allProviders.firstOrNull { provider ->
            provider.capabilities.any { it.type == EnrichmentType.GENRE || it.type == EnrichmentType.LABEL }
        }

    /** Providers that may have search capability, excluding the identity provider. */
    fun searchProviders(): List<EnrichmentProvider> {
        val identity = identityProvider()
        return allProviders.filter { it !== identity }
    }

    fun providerInfos(): List<ProviderInfo> = allProviders.map { provider ->
        ProviderInfo(provider.id, provider.displayName, provider.capabilities, provider.requiresApiKey, provider.isAvailable)
    }

    private fun buildChains(providers: List<EnrichmentProvider>): Map<EnrichmentType, ProviderChain> {
        val byType = mutableMapOf<EnrichmentType, MutableList<Pair<EnrichmentProvider, Int>>>()
        for (provider in providers) {
            for (capability in provider.capabilities) {
                byType.getOrPut(capability.type) { mutableListOf() }.add(provider to capability.priority)
            }
        }
        return byType.mapValues { (type, providerPriorities) ->
            ProviderChain(
                type,
                providerPriorities.sortedByDescending { it.second }.map { it.first },
                circuitBreakers,
            )
        }
    }
}
