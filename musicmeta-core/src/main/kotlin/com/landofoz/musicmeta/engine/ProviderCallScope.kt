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

    private val slots = ConcurrentHashMap<ProviderIdentity, Any>()

    /**
     * This call's state for [provider], from [create] on first use. Concurrent because the engine
     * resolves types as sibling `async` children, so two of them race for the same slot.
     *
     * One slot per provider *instance*, not per [EnrichmentProvider.id]. Registration rejects a
     * duplicate id, but two differently-configured instances still reach here whenever a consumer's
     * provider is a `data class` — [EnrichmentProvider] states no `equals`/`hashCode` contract — and
     * sharing a slot between them would let whichever filled it first answer for both.
     */
    @Suppress("UNCHECKED_CAST") // one instance, one slot, so the stored type is the one it stored
    fun <T : Any> slot(provider: EnrichmentProvider, create: () -> T): T =
        slots.computeIfAbsent(ProviderIdentity(provider)) { create() } as T

    /**
     * A [provider] by reference, for use as a map key. [EnrichmentProvider] is public and states no
     * `equals`/`hashCode` contract, so a consumer's provider written as a `data class` would
     * otherwise hand two distinct instances one slot.
     */
    private class ProviderIdentity(private val provider: EnrichmentProvider) {
        override fun equals(other: Any?): Boolean =
            other is ProviderIdentity && other.provider === provider

        override fun hashCode(): Int = System.identityHashCode(provider)
    }

    internal companion object Key : CoroutineContext.Key<ProviderCallScope>
}
