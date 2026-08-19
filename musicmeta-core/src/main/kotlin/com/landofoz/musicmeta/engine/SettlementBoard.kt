package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One type's finished, post-processed outcome — what [SettlementBoard.await] hands a dependent composite. */
internal data class Settled(
    val result: EnrichmentResult,
    val execution: ChainExecution?,
)

/**
 * Where every type of one `enrich`/`enrichProgressive` call lands as it finishes.
 *
 * [settle] runs [finalize] — consumer-supplied suspend I/O (`CatalogProvider.checkAvailability`, a
 * stale-cache read) — *before* taking the lock, so one type's slow finalize can never serialize
 * another's; the lock covers only the map write, the deferred completion, and the snapshot, which
 * do no I/O and cannot suspend indefinitely. Narrowing this any further would reopen the original
 * bug: a plain `mutableMapOf()` mutated from many `launch {}` children raced under a real thread
 * pool — `ConcurrentModificationException` in a sixth to a quarter of runs, and, worse, a short
 * terminal map with no exception at all in some of the rest — so the map write, deferred
 * completion, and snapshot must still be one atomic region.
 *
 * [await] lets a composite synthesizer suspend for its own dependencies without ever reading the
 * shared map directly. Each composite type is driven by exactly one coroutine that awaits its own
 * dependencies' [CompletableDeferred]s and calls [settle] for its own type exactly once, so two
 * dependents racing to "are my deps all in yet" — the double-synthesis failure mode — cannot arise;
 * nothing but that one coroutine can ever settle that type.
 */
internal class SettlementBoard(types: Set<EnrichmentType>) {
    private val mutex = Mutex()
    private val results = mutableMapOf<EnrichmentType, EnrichmentResult>()
    private val executions = mutableMapOf<EnrichmentType, ChainExecution>()
    private val deferreds: Map<EnrichmentType, CompletableDeferred<Settled>> =
        types.associateWith { CompletableDeferred() }

    /** Suspends until [type] settles. [type] must be one of the types this board was built for. */
    suspend fun await(type: EnrichmentType): Settled = deferreds.getValue(type).await()

    /**
     * Runs [finalize] on [raw] outside the lock, then records the result and [execution] for
     * [type] and returns a defensive-copy snapshot of every type settled so far — all inside one
     * lock, so a caller sending this snapshot as a stream emission never sends a torn or
     * later-mutated map, and so the deferred [type] dependents [await] always observes the
     * finalized value, never the raw one.
     */
    suspend fun settle(
        type: EnrichmentType,
        raw: EnrichmentResult,
        execution: ChainExecution?,
        finalize: suspend (EnrichmentResult) -> EnrichmentResult,
    ): Map<EnrichmentType, EnrichmentResult> {
        val processed = finalize(raw)
        return mutex.withLock {
            results[type] = processed
            if (execution != null) executions[type] = execution
            deferreds.getValue(type).complete(Settled(processed, execution))
            results.toMap()
        }
    }

    /** Every type settled so far, once no [settle] call for this board can still be in flight. */
    suspend fun snapshotResults(): Map<EnrichmentType, EnrichmentResult> = mutex.withLock { results.toMap() }

    /** @see snapshotResults */
    suspend fun snapshotExecutions(): Map<EnrichmentType, ChainExecution> = mutex.withLock { executions.toMap() }
}
