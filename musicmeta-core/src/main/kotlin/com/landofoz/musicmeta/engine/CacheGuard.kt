package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentLogger
import kotlinx.coroutines.CancellationException

// Guards against a throwing EnrichmentCache. The cache is an optimisation, so a broken one should
// cost a round-trip to the providers rather than take enrichment down. This is not a defensive
// hypothetical: EnrichmentCache is a consumer-implementable interface, and the shipped
// RoomEnrichmentCache does disk I/O.

private const val TAG = "EnrichmentCache"

/** Runs a cache read, degrading to `null` — a cache miss — when the implementation throws. */
internal suspend fun <T : Any> guardedCacheRead(
    logger: EnrichmentLogger,
    operation: String,
    block: suspend () -> T?,
): T? = runGuarded(logger, operation, fallback = null, block = block)

/** Runs a cache write, degrading to a no-op when the implementation throws. */
internal suspend fun guardedCacheWrite(
    logger: EnrichmentLogger,
    operation: String,
    block: suspend () -> Unit,
) {
    runGuarded(logger, operation, fallback = Unit, block = block)
}

/**
 * [CancellationException] is rethrown rather than swallowed. A cancelled caller surfaces as a
 * [CancellationException] from any suspending cache call, and absorbing it would leave `enrich()`
 * working on behalf of a caller that has gone away. None of the current call sites sit inside
 * `enrich()`'s `withTimeout` block, but rethrowing also means moving one inside it later cannot
 * silently defeat the deadline. Every other failure is logged and degraded.
 */
private suspend fun <T> runGuarded(
    logger: EnrichmentLogger,
    operation: String,
    fallback: T,
    block: suspend () -> T,
): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    logger.warn(TAG, "Cache $operation failed, continuing without cache: ${e.message}", e)
    fallback
}
