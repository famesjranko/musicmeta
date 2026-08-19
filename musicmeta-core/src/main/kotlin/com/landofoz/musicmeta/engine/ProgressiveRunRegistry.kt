package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentResults
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentityResolution
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Dedupe and lifecycle bookkeeping for [DefaultEnrichmentEngine.enrichProgressive]'s detached
 * runs, split out of [DefaultEnrichmentEngine] itself so the map/mutex mechanics have one small,
 * independently readable home. [scope] is [DefaultEnrichmentEngine]'s own `detachedScope`; this
 * class owns no scope of its own and has no lifecycle beyond it.
 */
internal class ProgressiveRunRegistry(private val scope: CoroutineScope) {
    private val mutex = Mutex()
    private val runs = mutableMapOf<String, ProgressiveRun>()

    /**
     * One detached [DefaultEnrichmentEngine.enrichProgressive] fan-out, shared by every collector
     * attached to it (the dedupe case). [shared] never completes on its own — a [MutableSharedFlow]
     * is a hot stream — so [terminal] is what tells a relayed collector the run is over: the exact
     * object reference sent as the run's last value, completed *before* that value is emitted so no
     * collector can observe the value without also seeing [terminal] already completed.
     */
    class ProgressiveRun(
        /** This registry's own key for the run — carried here so completion can evict itself. */
        val dedupeKey: String,
        val shared: MutableSharedFlow<EnrichmentResults> = MutableSharedFlow(replay = 1, extraBufferCapacity = 64),
        val terminal: CompletableDeferred<EnrichmentResults> = CompletableDeferred(),
    )

    /** The registry's current size, guarded by [mutex] — read by tests for eviction. */
    suspend fun inFlightCount(): Int = mutex.withLock { runs.size }

    /**
     * Attaches to an in-flight run for [dedupeKey], or starts one: [body] runs as a child of
     * [scope] exactly once per key, regardless of how many concurrent callers ask for it — a call
     * that finds an existing entry never invokes [body] at all. Callers relying on [body] running
     * only for the call that actually starts the run (e.g. a `forceRefresh` invalidate pass) depend
     * on that guarantee.
     */
    suspend fun attachOrStart(
        dedupeKey: String,
        body: suspend (ProgressiveRun) -> Unit,
    ): ProgressiveRun = mutex.withLock {
        runs.getOrPut(dedupeKey) {
            ProgressiveRun(dedupeKey).also { newRun -> scope.launch { body(newRun) } }
        }
    }

    /**
     * Removes [run]'s own key and completes it with [snapshot] as one region: a call attaching to
     * that key either sees the run before this executes (a normal attach) or after (and starts its
     * own new run) — never a run that is simultaneously terminal and still this registry's answer
     * for that key.
     */
    suspend fun complete(run: ProgressiveRun, snapshot: EnrichmentResults) {
        mutex.withLock { runs.remove(run.dedupeKey) }
        run.terminal.complete(snapshot)
        run.shared.emit(snapshot)
    }
}

/**
 * The one resolution path [DefaultEnrichmentEngine.enrich] and
 * [DefaultEnrichmentEngine.enrichProgressive] share, run as a child of the engine's own
 * `detachedScope` (via [ProgressiveRunRegistry.attachOrStart]) so a collector cancelling detaches
 * from it rather than cancelling it. Every settled type reaches [run]'s
 * [ProgressiveRunRegistry.ProgressiveRun.shared] as its own catalog-filtered, provenance-stamped,
 * stale-cache-resolved snapshot — except the type whose settlement first makes every requested
 * type present, which is suppressed: the real terminal snapshot, built after write-back below,
 * takes that emission's place.
 *
 * The `finally` block covers every way this can stop short of its own terminal snapshot — the
 * engine's `close()` cancelling its detached scope, or a genuinely uncaught failure — with one
 * path: complete [ProgressiveRunRegistry.ProgressiveRun.terminal] with whatever had settled so far
 * (write-back skipped, exactly like a timed-out run) so a relayed collector is released rather than
 * left waiting on a [shared][ProgressiveRunRegistry.ProgressiveRun.shared] that will never emit
 * again. [NonCancellable] because a cancelled job's own suspension points (the emit itself) would
 * otherwise throw before that release could reach the collector.
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
    val settle: suspend (EnrichmentType, EnrichmentResult, ChainExecution?) -> Unit = { type, raw, execution ->
        val snapshot = session.board.settle(type, raw, execution) {
            finalizeResult(request, type, it, execution, session.identityHolder)
        }
        val filtered = snapshot.filterKeys { it in types }
        if ((types - filtered.keys).isNotEmpty()) {
            run.shared.emit(EnrichmentResults(filtered, types, session.identityHolder.current))
        }
    }

    try {
        // Cache hits settle immediately: catalog filtering is the only finalize step that touches
        // them, and it does not depend on canonicalStatus, so there is nothing to wait on identity
        // resolution for. That is also what lets the first emission go out before identity resolves.
        for ((type, result) in cacheLayer.results) settle(type, result, null)

        // withTimeoutOrNull returns null only when *this* deadline expired. A nested withTimeout's
        // expiry — a consumer's CatalogProvider, say — propagates instead of being caught by type
        // and mislabelled as enrichTimeoutMs. (#55)
        val completed = withTimeoutOrNull(config.enrichTimeoutMs) {
            resolveUncachedTypes(request, forceRefresh, cacheLayer, session, settle)
            true
        } ?: false

        if (!completed) stampTimeoutStragglers(types, session.board, settle)

        // A timed-out run persists nothing. The deadline can fire part-way through finalizing a
        // type — catalog filtering does exactly that, per type — so what survives is a mix of
        // finished and unfinished work. Returning it is the contract; caching it would outlive the
        // run that truncated it, whether or not a collector was still attached. (#56)
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
            val context = WriteBackContext(resolution, cacheLayer.negativeCacheHits, session.chainExecutions)
            writeBack(request, resolvedRequest, results, context)
        }

        // A timeout that fired before identity resolution ran is the one gap identityResolution
        // never filled — FAILED is the honest status for a call that never learned otherwise.
        val identity = session.identityResolution ?: IdentityResolution(request.identifiers, CanonicalStatus.FAILED)
        val terminalSnapshot = EnrichmentResults(results.filterKeys { it in types }, types, identity)
        progressiveRuns.complete(run, terminalSnapshot)
    } finally {
        if (!run.terminal.isCompleted) {
            withContext(NonCancellable) {
                val results = session.board.snapshotResults()
                val identity =
                    session.identityResolution ?: IdentityResolution(request.identifiers, CanonicalStatus.FAILED)
                val abandoned = EnrichmentResults(results.filterKeys { it in types }, types, identity)
                progressiveRuns.complete(run, abandoned)
            }
        }
    }
}
