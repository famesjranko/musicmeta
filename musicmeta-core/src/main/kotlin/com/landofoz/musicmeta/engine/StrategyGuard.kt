package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentLogger
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import kotlinx.coroutines.CancellationException

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
 * [CancellationException] is rethrown. Both call sites sit inside `enrich()`'s `withTimeout`, so
 * absorbing it would defeat the enrichment deadline.
 */
internal fun guardedStrategy(
    logger: EnrichmentLogger,
    type: EnrichmentType,
    strategy: String,
    block: () -> EnrichmentResult,
): EnrichmentResult = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    logger.warn(TAG, "${type.name}: $strategy threw: ${e.message}", e)
    EnrichmentResult.Error(type, strategy, e.message ?: "Unknown error", e)
}
