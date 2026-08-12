package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentLogger
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderInfo
import com.landofoz.musicmeta.http.CircuitBreaker

/**
 * Ids the engine itself puts in [com.landofoz.musicmeta.EnrichmentResult.provider], for results no
 * provider produced. A provider claiming one is indistinguishable from the engine speaking: it is
 * also a key `EnrichmentConfig.confidenceOverrides` is read with, so `confidenceOverrides["engine"]`
 * would demote the engine's own synthesised results.
 */
private val RESERVED_PROVIDER_IDS = setOf(
    "engine", "all_providers", "no_provider", "no_merger", "no_composite_handler",
)

/** Suffix every built-in [ResultMerger] labels its merged result with. Reserved for the same reason. */
private const val RESERVED_MERGER_SUFFIX = "_merger"

/**
 * Rejects an id that would collide with the engine's own, or with a provider already registered.
 *
 * Duplicate ids are the sharper case: a registry keys one circuit breaker per id, so two providers
 * sharing one share its breaker — a healthy twin records the successes that keep a failing one in
 * rotation forever, and no chain notices.
 *
 * @throws IllegalArgumentException if [id] is reserved or already taken by [existing].
 */
internal fun requireRegistrableProviderId(id: String, existing: Collection<String>) {
    require(id !in RESERVED_PROVIDER_IDS && !id.endsWith(RESERVED_MERGER_SUFFIX)) {
        "Provider id '$id' is reserved by the engine: ${RESERVED_PROVIDER_IDS.sorted()
            .joinToString()}, and any id ending '$RESERVED_MERGER_SUFFIX'. Rename the provider."
    }
    require(id !in existing) {
        "Provider id '$id' is already registered. Two providers sharing an id also share one " +
            "circuit breaker, so a healthy one keeps a failing one in rotation. Rename one of them."
    }
}

internal class ProviderRegistry(
    providers: List<EnrichmentProvider>,
    private val priorityOverrides: Map<String, Map<EnrichmentType, Int>> = emptyMap(),
    private val logger: EnrichmentLogger = EnrichmentLogger.NoOp,
) {

    private val allProviders: List<EnrichmentProvider> = providers.toList()
        .also { registered ->
            val seen = mutableSetOf<String>()
            registered.forEach { requireRegistrableProviderId(it.id, seen); seen.add(it.id) }
        }

    /** One circuit breaker per provider, shared across all chains. */
    private val circuitBreakers: Map<String, CircuitBreaker> =
        allProviders.associate { it.id to CircuitBreaker() }

    private val chains: Map<EnrichmentType, ProviderChain> = buildChains(allProviders)

    fun chainFor(type: EnrichmentType): ProviderChain? = chains[type]
    fun supportedTypes(): Set<EnrichmentType> = chains.keys

    fun identityProvider(): EnrichmentProvider? =
        allProviders.firstOrNull { it.isIdentityProvider }

    /** Providers that may have search capability, excluding the identity provider. */
    fun searchProviders(): List<EnrichmentProvider> {
        val identity = identityProvider()
        return allProviders.filter { it !== identity }
    }

    fun providerInfos(): List<ProviderInfo> = allProviders.map { provider ->
        ProviderInfo(
            provider.id,
            provider.displayName,
            provider.capabilities,
            provider.requiresApiKey,
            provider.isAvailable,
        )
    }

    private fun buildChains(providers: List<EnrichmentProvider>): Map<EnrichmentType, ProviderChain> {
        val byType = mutableMapOf<EnrichmentType, MutableList<Pair<EnrichmentProvider, Int>>>()
        for (provider in providers) {
            for (capability in provider.capabilities) {
                val priority = priorityOverrides[provider.id]?.get(capability.type)
                    ?: capability.priority
                byType.getOrPut(capability.type) { mutableListOf() }.add(provider to priority)
            }
        }
        return byType.mapValues { (type, providerPriorities) ->
            ProviderChain(
                type,
                providerPriorities.sortedByDescending { it.second }.map { it.first },
                circuitBreakers,
                logger,
            )
        }
    }
}
