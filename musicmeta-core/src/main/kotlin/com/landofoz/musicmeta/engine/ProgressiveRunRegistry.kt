package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentResults
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
     * [markClosed] fires is unaffected here — its abandonment goes through the fan-out's own
     * `finally` block, run as normal on [scope].
     *
     * [mutex] only ever guards the registration decision — reading [closed], checking [runs] for an
     * existing entry, and (for a fresh key) inserting into [runs] and launching [body]. [onClosed]
     * itself, and completing/emitting to the new run, both run *after* [withLock] returns: [onClosed]
     * reaches consumer code ([CachePersistence.applyStaleCacheToType] calls
     * [com.landofoz.musicmeta.cache.EnrichmentCache.getIncludingExpired] under `STALE_IF_ERROR`),
     * a genuine, unbounded suspension point that must never run while a thread-bound caller of
     * [markClosed] might be waiting on this same mutex to become free — see
     * [DefaultEnrichmentEngine.close]'s KDoc for why
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
