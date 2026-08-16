package com.landofoz.musicmeta.contract

import com.landofoz.musicmeta.EnrichmentCache
import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache

/** The shipped in-memory cache under the shared [EnrichmentCacheContract]. */
class InMemoryEnrichmentCacheContractTest : EnrichmentCacheContract() {
    override fun subject(): EnrichmentCache = InMemoryEnrichmentCache()
}
