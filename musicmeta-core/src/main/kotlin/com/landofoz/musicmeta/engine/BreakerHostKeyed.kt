package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentType

/**
 * A provider that rides more than one host and says so, so that a chain can key a circuit breaker
 * on the host rather than on the provider. The default is the provider's own id, which is the
 * control arm's key.
 */
internal interface BreakerHostKeyed {
    fun breakerKey(type: EnrichmentType): String
}
