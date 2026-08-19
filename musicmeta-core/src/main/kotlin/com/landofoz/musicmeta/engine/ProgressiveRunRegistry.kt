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
    private var closed = false

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
     * Marks this registry closed under [mutex] — the same lock [attachOrStart] takes to check the
     * flag before registering a new key, so a call for a key not yet seen either completes fully
     * before this returns (and starts normally on [scope]) or observes [closed] and never starts at
     * all; the two can never interleave partway. Idempotent.
     */
    suspend fun markClosed() {
        mutex.withLock { closed = true }
    }

    /** What [mutex] decided for a [dedupeKey], resolved into a [ProgressiveRun] outside the lock. */
    private sealed class Decision {
        class Attached(val run: ProgressiveRun) : Decision()
        class Closed(val run: ProgressiveRun) : Decision()
    }

    /**
     * Attaches to an in-flight run for [dedupeKey], or starts one: [body] runs as a child of
     * [scope] exactly once per key, regardless of how many concurrent callers ask for it — a call
     * that finds an existing entry never invokes [body] at all. Callers relying on [body] running
     * only for the call that actually starts the run (e.g. a `forceRefresh` invalidate pass) depend
     * on that guarantee.
     *
     * Once this registry is [markClosed], a call for a key it has not already seen never reaches
     * [scope] at all — `scope.launch` with an already-cancelled parent `Job` never runs its block,
     * which would otherwise leave a fresh run's [ProgressiveRun.terminal] incomplete forever. Instead
     * [onClosed] builds that run's terminal snapshot, completed here before this call returns,
     * exactly as if it had run and immediately been abandoned. A key already in flight when
     * [markClosed] fires is unaffected here — its abandonment goes through [runProgressiveFanOut]'s
     * own `finally` block, run as normal on [scope].
     *
     * [mutex] only ever guards the registration decision — reading [closed], checking [runs] for an
     * existing entry, and (for a fresh key) inserting into [runs] and launching [body]. [onClosed]
     * itself, and completing/emitting to the new run, both run *after* [withLock] returns: [onClosed]
     * reaches consumer code ([DefaultEnrichmentEngine.applyStaleCacheToType] calls
     * [com.landofoz.musicmeta.cache.EnrichmentCache.getIncludingExpired] under `STALE_IF_ERROR`),
     * a genuine, unbounded suspension point that must never run while a thread-bound caller of
     * [markClosed] might be waiting on this same mutex to become free — see [close]'s KDoc for why
     * that matters. Mirrors [complete], which evicts from [runs] under the lock and completes/emits
     * outside it for the same reason.
     */
    suspend fun attachOrStart(
        dedupeKey: String,
        onClosed: suspend (ProgressiveRun) -> EnrichmentResults,
        body: suspend (ProgressiveRun) -> Unit,
    ): ProgressiveRun {
        val decision = mutex.withLock {
            runs[dedupeKey]?.let { return@withLock Decision.Attached(it) }
            val run = ProgressiveRun(dedupeKey)
            if (closed) {
                Decision.Closed(run)
            } else {
                runs[dedupeKey] = run
                scope.launch { body(run) }
                Decision.Attached(run)
            }
        }
        return when (decision) {
            is Decision.Attached -> decision.run
            is Decision.Closed -> {
                val snapshot = onClosed(decision.run)
                decision.run.terminal.complete(snapshot)
                decision.run.shared.emit(snapshot)
                decision.run
            }
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
 * path: stamp every type still unsettled as an honest `ErrorKind.ENGINE_CLOSED` Error (mirroring
 * the timeout path's own straggler stamp, see [DefaultEnrichmentEngine.stampTimeoutStragglers]),
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
                stampAbandonedStragglers(types, session.board, settle)
                val results = session.board.snapshotResults()
                val identity =
                    session.identityResolution ?: IdentityResolution(request.identifiers, CanonicalStatus.FAILED)
                val abandoned = EnrichmentResults(results.filterKeys { it in types }, types, identity)
                progressiveRuns.complete(run, abandoned)
            }
        }
    }
}

/**
 * The per-type settle callback [runProgressiveFanOut] and an immediately-abandoned run (a call for
 * a never-seen key arriving after [DefaultEnrichmentEngine.close]) both use: finalizes [raw],
 * records it on [session]'s board, and emits an interim snapshot to [run]'s
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
        finalizeResult(request, type, it, execution, session.identityHolder)
    }
    val filtered = snapshot.filterKeys { it in types }
    if ((types - filtered.keys).isNotEmpty()) {
        run.shared.emit(EnrichmentResults(filtered, types, session.identityHolder.current))
    }
}

/**
 * Builds the terminal snapshot for a call whose dedupe key was never seen before the engine
 * [DefaultEnrichmentEngine.close]d — every requested type is unsettled, so this stamps all of them
 * via [stampAbandonedStragglers] rather than starting a fan-out on a scope that will never run it.
 */
internal suspend fun DefaultEnrichmentEngine.abandonedSnapshot(
    request: EnrichmentRequest,
    types: Set<EnrichmentType>,
    run: ProgressiveRunRegistry.ProgressiveRun,
): EnrichmentResults {
    val session = RunSession(
        board = SettlementBoard(types + compositeSubTypesOf(types)),
        identityHolder = IdentityHolder(IdentityResolution(request.identifiers, CanonicalStatus.FAILED)),
    )
    val settle = settleInto(request, types, session, run)
    stampAbandonedStragglers(types, session.board, settle)
    val results = session.board.snapshotResults()
    return EnrichmentResults(results.filterKeys { it in types }, types, session.identityHolder.current)
}
