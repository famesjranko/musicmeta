package com.landofoz.musicmeta.engine

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Records, for one enrich call, that a caller-supplied identifier named an entity confidently
 * different from the one the request described ([contradictsSuppliedName]).
 *
 * A coroutine-context element rather than a return value because the provider that discovers this is
 * not always the identity resolver — on a request whose types need no identifier, resolution never
 * runs and the contradiction surfaces during the ordinary fan-out, after the call's
 * [com.landofoz.musicmeta.IdentityResolution] has already been built.
 *
 * Latching, and never cleared: one provider recovering by name does not make the supplied identifier
 * good again. That is the whole point — a caller must be able to learn their identifier was wrong
 * even on a call that went on to answer everything.
 */
internal class SuppliedIdentifierContradiction : AbstractCoroutineContextElement(Key) {

    private val seen = AtomicBoolean(false)

    /** Records that a supplied identifier contradicted the request's own name. */
    fun mark() {
        seen.set(true)
    }

    /** Whether any provider found a supplied identifier naming a different entity this call. */
    fun seen(): Boolean = seen.get()

    internal companion object Key : CoroutineContext.Key<SuppliedIdentifierContradiction>
}
