package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentLogger
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

// Guards against a throwing ResultMerger or CompositeSynthesizer. Both are consumer-implementable
// and registered through EnrichmentEngine.Builder, like EnrichmentProvider (guarded in
// ProviderChain) and EnrichmentCache (guarded in CacheGuard) — this closes the third of three.

private const val TAG = "EnrichmentStrategy"

/**
 * Runs a merge or synthesis, converting a throw into [EnrichmentResult.Error] for [type].
 *
 * Reported rather than degraded, unlike the cache: a strategy *produces* the type's result, so
 * swallowing the failure would be indistinguishable from a genuine `NotFound`.
 *
 * Cancellation is settled the way `ProviderChain` and `CacheGuard` settle it: `ensureActive()` asks
 * whether *our* job was cancelled, which is the only question the guard can answer correctly. Both
 * call sites sit inside `enrich()`'s `withTimeout`, so that is how `enrichTimeoutMs` reaches here.
 *
 * The wrapper suspends solely to reach the job; [block] does not, because neither
 * [ResultMerger.merge] nor [CompositeSynthesizer.synthesize] does. A consumer wanting a deadline
 * inside one therefore reaches for `runBlocking { withTimeout { … } }`, and its expiry arrives here
 * as a `CancellationException` while our job is perfectly healthy. A blanket rethrow escaped with
 * it — cancelling the sibling types resolving alongside it and reaching the caller as the engine's
 * deadline. It is that strategy's failure, and stays contained as one. (#61)
 */
internal suspend fun guardedStrategy(
    logger: EnrichmentLogger,
    type: EnrichmentType,
    strategy: String,
    block: () -> EnrichmentResult,
): EnrichmentResult = try {
    block()
} catch (e: Exception) {
    currentCoroutineContext().ensureActive()
    logger.warn(TAG, "${type.name}: $strategy threw: ${e.message}", e)
    EnrichmentResult.Error(type, strategy, e.message ?: "Unknown error", e)
}
