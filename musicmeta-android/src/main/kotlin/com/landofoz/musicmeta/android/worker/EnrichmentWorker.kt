package com.landofoz.musicmeta.android.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResults
import com.landofoz.musicmeta.EnrichmentType
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * WorkManager worker for batch enrichment processing.
 * Processes items in batches, reporting progress, and enqueuing follow-up work.
 *
 * Input data:
 * - KEY_ENTITY_TYPE: "album" or "artist"
 * - KEY_ENTITY_IDS: JSON array of entity IDs to process
 * - KEY_ENTITY_NAMES: JSON array of display names (title or artist name)
 * - KEY_ENTITY_ARTISTS: JSON array of artist names (for albums)
 *
 * Apps provide their own subclass or factory to bridge their domain models
 * into EnrichmentRequest objects.
 */
abstract class EnrichmentWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    abstract val engine: EnrichmentEngine

    /** Subclass returns the enrichment types to use for batch processing. */
    abstract fun batchTypes(): Set<EnrichmentType>

    /** Subclass builds EnrichmentRequests from input data. */
    abstract suspend fun buildRequests(inputData: Data): List<EnrichmentRequest>

    /** Called after each item is enriched. Subclass persists results to its domain. */
    abstract suspend fun onItemEnriched(
        request: EnrichmentRequest,
        results: EnrichmentResults,
    )

    override suspend fun doWork(): Result {
        val requests = buildRequests(inputData)
        if (requests.isEmpty()) return Result.success()

        val types = batchTypes()
        var processed = 0

        for (request in requests) {
            if (isStopped) return Result.retry()

            try {
                val results = engine.enrich(request, types)
                onItemEnriched(request, results)
            } catch (_: Exception) {
                // A stopped worker is not an item failure: without this the loop swallows the
                // cancellation and walks the rest of the batch, exiting only because setProgress()
                // below happens to suspend — an accident, not a guard.
                //
                // ensureActive(), not `catch (CancellationException) { throw e }`. The latter also
                // propagates a CancellationException raised inside a consumer's onItemEnriched
                // (its own withTimeout, say), and WorkManager reads a generic cancellation escaping
                // doWork() as *failed* work — losing the batch output and failing dependent work,
                // where before it was one skipped item. (#53)
                currentCoroutineContext().ensureActive()
                // Individual item failure doesn't stop the batch
            }

            processed++
            setProgress(workDataOf(KEY_PROCESSED to processed, KEY_TOTAL to requests.size))
        }

        return Result.success(
            workDataOf(KEY_PROCESSED to processed, KEY_TOTAL to requests.size),
        )
    }

    companion object {
        const val KEY_PROCESSED = "processed"
        const val KEY_TOTAL = "total"
    }
}
