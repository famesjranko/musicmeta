package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentProvider
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Per-[DefaultEnrichmentEngine.enrich] home for state a provider reuses *within* one call —
 * installed once per call alongside [TransientIdentifierMarker].
 *
 * The fan-out calls `enrich(request, type)` once per type and `EnrichmentCache` is keyed by type,
 * so only a provider can tell that two of those types want the same upstream resource. **Nothing
 * put here survives the call**, which is what lets a consumer's `forceRefresh` reach upstream;
 * state a provider holds for longer is a cache with none of `EnrichmentCache`'s guarantees
 * (`docs/pitfalls.md` §12). A provider that finds no scope — one called outside an engine — keeps
 * its state for that one call, which is the same guarantee.
 */
internal class ProviderCallScope : AbstractCoroutineContextElement(Key) {

    private val slots = ConcurrentHashMap<String, Any>()

    /**
     * This call's state for [provider], from [create] on first use. Concurrent because the engine
     * resolves types as sibling `async` children, so two of them race for the same slot.
     *
     * Keyed by [EnrichmentProvider.id], as [ProviderRegistry] keys its circuit breakers: the
     * interface is public and states no `equals`/`hashCode` contract, so a consumer's provider
     * written as a `data class` would give two differently-configured instances one slot.
     */
    @Suppress("UNCHECKED_CAST") // one id, one slot, so the stored type is the one that slot stored
    fun <T : Any> slot(provider: EnrichmentProvider, create: () -> T): T =
        slots.computeIfAbsent(provider.id) { create() } as T

    internal companion object Key : CoroutineContext.Key<ProviderCallScope>
}
