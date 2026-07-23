package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentLogger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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
 * A cancelled caller must still stop enrichment: absorbing that would leave `enrich()` working on
 * behalf of someone who has gone away. Every other failure is logged and degraded.
 *
 * The guard asks whether *our* job was cancelled, not whether the exception was a
 * `CancellationException` — `ensureActive()` throws only in the first case. A blanket
 * `catch (CancellationException) { throw e }` answers the second question and so also propagates a
 * cancellation raised *inside* the cache: [com.landofoz.musicmeta.EnrichmentCache] is
 * consumer-implementable and the shipped `RoomEnrichmentCache` does disk I/O, so a consumer wrapping
 * it in its own `withTimeout` is ordinary. Its expiry escaped `enrich()` and reached the caller as
 * the engine's deadline, when nothing of ours was cancelled and the cache was merely slow. (#61)
 *
 * None of the current call sites sit inside `enrich()`'s `withTimeout` block, so `enrichTimeoutMs`
 * is not what is being caught here — an external caller cancelling `enrich()` is.
 */
private suspend fun <T> runGuarded(
    logger: EnrichmentLogger,
    operation: String,
    fallback: T,
    block: suspend () -> T,
): T = try {
    block()
} catch (e: Exception) {
    currentCoroutineContext().ensureActive()
    logger.warn(TAG, "Cache $operation failed, continuing without cache: ${e.message}", e)
    fallback
}
