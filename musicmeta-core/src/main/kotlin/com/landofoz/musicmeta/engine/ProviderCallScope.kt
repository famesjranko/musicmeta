package com.landofoz.musicmeta.engine

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

    private val slots = ConcurrentHashMap<OwnerIdentity, Any>()

    /**
     * This call's state for [owner], from [create] on first use. Concurrent because the engine
     * resolves types as sibling `async` children, so two of them race for the same slot.
     *
     * One slot per [owner] *instance*, keyed by reference ([OwnerIdentity]) rather than `equals`.
     * [owner] is usually the requesting [com.landofoz.musicmeta.EnrichmentProvider] itself, which
     * states no `equals`/`hashCode` contract — registration rejects a duplicate id, but two
     * differently-configured instances still reach here whenever a consumer's provider is a
     * `data class`, and sharing a slot between them would let whichever filled it first answer for
     * both. [owner] is typed `Any` rather than `EnrichmentProvider` so a provider-owned component
     * (an API client held one-per-provider, say) can pass itself and hold its own call-scoped state
     * without routing every call back through the provider; reference identity is what makes that
     * safe regardless of what `owner` is — the widened type carries no `equals`/`hashCode` promise
     * of its own to break. [owner] must be the provider or component itself, never a `String` or
     * boxed primitive — the JVM shares references for those, so [OwnerIdentity] rejects them at
     * first use.
     */
    @Suppress("UNCHECKED_CAST") // one instance, one slot, so the stored type is the one it stored
    fun <T : Any> slot(owner: Any, create: () -> T): T =
        slots.computeIfAbsent(OwnerIdentity(owner)) { create() } as T

    /** [owner] by reference, for use as a map key — see [slot]. */
    private class OwnerIdentity(private val owner: Any) {

        init {
            require(owner !is String && owner !is Number && owner !is Char && owner !is Boolean) {
                "slot owner must be the component holding the state, not the value '$owner' — " +
                    "the JVM shares references for interned strings and cached boxes, so two " +
                    "unrelated callers would share one slot"
            }
        }

        override fun equals(other: Any?): Boolean =
            other is OwnerIdentity && other.owner === owner

        override fun hashCode(): Int = System.identityHashCode(owner)
    }

    internal companion object Key : CoroutineContext.Key<ProviderCallScope>
}
