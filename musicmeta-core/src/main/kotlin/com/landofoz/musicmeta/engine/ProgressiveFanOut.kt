package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentResults
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.IdentityResolution
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The honest "never learned otherwise" identity for a call whose fan-out never resolved one. */
private fun failedIdentityResolution(request: EnrichmentRequest) =
    IdentityResolution(request.identifiers, CanonicalStatus.FAILED)

/**
 * The one resolution path [DefaultEnrichmentEngine.enrich] and
 * [DefaultEnrichmentEngine.enrichProgressive] share, run as a child of the engine's own
 * `detachedScope` (via [ProgressiveRunRegistry.attachOrStart]) so a collector cancelling detaches
 * from it rather than cancelling it. Every settled type reaches [run]'s
 * [ProgressiveRunRegistry.ProgressiveRun.shared] as its own catalog-filtered, provenance-stamped,
 * stale-cache-resolved snapshot — except the type whose settlement first makes every requested
 * type present, which is suppressed: the real terminal snapshot, built after write-back below,
 * takes that emission's place instead.
 *
 * The `finally` block covers every way this can stop short of its own terminal snapshot — the
 * engine's `close()` cancelling its detached scope, or a genuinely uncaught failure — with one
 * path: stamp every type still unsettled as an honest `ErrorKind.ENGINE_CLOSED` Error (mirroring
 * the timeout path's own straggler stamp, see [DefaultEnrichmentEngine.stampStragglers]),
 * then complete [ProgressiveRunRegistry.ProgressiveRun.terminal] with the result (write-back
 * skipped, exactly like a timed-out run) so a relayed collector is released, every requested type
 * present, rather than left waiting on a [shared][ProgressiveRunRegistry.ProgressiveRun.shared]
 * that will never emit again. [NonCancellable] because a cancelled job's own suspension points (the
 * emit itself) would otherwise throw before that release could reach the collector.
 */
internal suspend fun DefaultEnrichmentEngine.runProgressiveFanOut(
    request: EnrichmentRequest,
    types: Set<EnrichmentType>,
    forceRefresh: Boolean,
    cacheLayer: CacheLayer,
    run: ProgressiveRunRegistry.ProgressiveRun,
) {
    val session = RunSession(
        board = SettlementBoard(types + compositeSubTypesOf(types)),
        identityHolder = IdentityHolder(IdentityResolution(request.identifiers, CanonicalStatus.RESOLVING)),
    )
    val settle = settleInto(request, types, session, run)

    try {
        // Cache hits settle immediately: catalog filtering is the only finalize step that touches
        // them, and it does not depend on canonicalStatus, so there is nothing to wait on identity
        // resolution for. That is also what lets the first emission go out before identity resolves.
        for ((type, result) in cacheLayer.results) settle(type, result, null)

        // withTimeoutOrNull returns null only when *this* deadline expired. A nested withTimeout's
        // expiry — a consumer's CatalogProvider, say — propagates instead of being caught by type
        // and mislabelled as enrichTimeoutMs.
        val completed = withTimeoutOrNull(config.enrichTimeoutMs) {
            resolveUncachedTypes(request, forceRefresh, cacheLayer, session, settle)
            true
        } ?: false

        if (!completed) {
            logEnrichTimeout()
            stampStragglers(types, session.board, settle) { type ->
                EnrichmentResult.Error(type, "engine", "Enrichment timed out", errorKind = ErrorKind.TIMEOUT)
            }
        }

        // A timed-out run persists nothing. The deadline can fire part-way through finalizing a
        // type — catalog filtering does exactly that, per type — so what survives is a mix of
        // finished and unfinished work. Returning it is the contract; caching it would outlive the
        // run that truncated it, whether or not a collector was still attached.
        val results = session.board.snapshotResults()
        if (completed) {
            // Both non-null whenever completed: resolveUncachedTypes assigns them before
            // streamResolveTypes runs, inside the same timed block, so a completed run always set
            // them first.
            val resolution = requireNotNull(session.identityResolution) {
                "identityResolution must be set once the timed block completes"
            }
            val resolvedRequest = requireNotNull(session.resolvedRequest) {
                "resolvedRequest must be set once the timed block completes"
            }
            val context = WriteBackContext(
                resolution,
                cacheLayer.negativeCacheHits,
                session.chainExecutions,
                session.staleSubstituteEmptied,
            )
            writeBack(request, resolvedRequest, results, context)
        }

        // A timeout that fired before identity resolution ran is the one gap identityResolution
        // never filled — FAILED is the honest status for a call that never learned otherwise.
        val identity = session.identityResolution ?: failedIdentityResolution(request)
        val terminalSnapshot = EnrichmentResults(results.filterKeys { it in types }, types, identity)
        progressiveRuns.complete(run, terminalSnapshot)
    } finally {
        if (!run.terminal.isCompleted) {
            withContext(NonCancellable) {
                stampStragglers(types, session.board, settle) { type ->
                    EnrichmentResult.Error(
                        type,
                        "engine",
                        "Engine closed before this type settled",
                        errorKind = ErrorKind.ENGINE_CLOSED,
                    )
                }
                val results = session.board.snapshotResults()
                val identity = session.identityResolution ?: failedIdentityResolution(request)
                val abandoned = EnrichmentResults(results.filterKeys { it in types }, types, identity)
                progressiveRuns.complete(run, abandoned)
            }
        }
    }
}

/**
 * The per-type settle callback [runProgressiveFanOut] and an immediately-abandoned run (a call for
 * a never-seen key arriving after [DefaultEnrichmentEngine.close]) both use: finalizes the raw
 * result it is handed, records it on [session]'s board, and emits an interim snapshot to [run]'s
 * [ProgressiveRunRegistry.ProgressiveRun.shared] unless this settlement is the one that completes
 * every requested type — the real terminal snapshot takes that emission's place instead.
 */
internal fun DefaultEnrichmentEngine.settleInto(
    request: EnrichmentRequest,
    types: Set<EnrichmentType>,
    session: RunSession,
    run: ProgressiveRunRegistry.ProgressiveRun,
): suspend (EnrichmentType, EnrichmentResult, ChainExecution?) -> Unit = { type, raw, execution ->
    val snapshot = session.board.settle(type, raw, execution) {
        finalizeResult(request, type, it, execution, session)
    }
    val filtered = snapshot.filterKeys { it in types }
    if ((types - filtered.keys).isNotEmpty()) {
        run.shared.emit(EnrichmentResults(filtered, types, session.identityHolder.current))
    }
}

/**
 * Builds the terminal snapshot for a call whose dedupe key was never seen before the engine
 * [DefaultEnrichmentEngine.close]d — every requested type is unsettled, so this stamps all of them
 * via [DefaultEnrichmentEngine.stampStragglers] rather than starting a fan-out on a scope that will
 * never run it.
 */
internal suspend fun DefaultEnrichmentEngine.abandonedSnapshot(
    request: EnrichmentRequest,
    types: Set<EnrichmentType>,
    run: ProgressiveRunRegistry.ProgressiveRun,
): EnrichmentResults {
    val session = RunSession(
        board = SettlementBoard(types + compositeSubTypesOf(types)),
        identityHolder = IdentityHolder(failedIdentityResolution(request)),
    )
    val settle = settleInto(request, types, session, run)
    stampStragglers(types, session.board, settle) { type ->
        EnrichmentResult.Error(
            type, "engine", "Engine closed before this type settled", errorKind = ErrorKind.ENGINE_CLOSED,
        )
    }
    val results = session.board.snapshotResults()
    return EnrichmentResults(results.filterKeys { it in types }, types, session.identityHolder.current)
}
