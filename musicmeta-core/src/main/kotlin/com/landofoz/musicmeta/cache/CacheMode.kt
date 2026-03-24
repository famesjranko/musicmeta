package com.landofoz.musicmeta.cache

/**
 * Controls how the enrichment engine handles failures when a cached (possibly expired)
 * result is available.
 */
enum class CacheMode {
    /** Always fetch from network; cache is only used when fresh. */
    NETWORK_FIRST,
    /** Serve expired cache entries when provider returns Error or RateLimited. */
    STALE_IF_ERROR,
}
