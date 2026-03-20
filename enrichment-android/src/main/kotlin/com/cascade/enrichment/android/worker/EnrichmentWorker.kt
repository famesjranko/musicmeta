package com.cascade.enrichment.android.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cascade.enrichment.EnrichmentEngine
import com.cascade.enrichment.EnrichmentRequest
import com.cascade.enrichment.EnrichmentResult
import com.cascade.enrichment.EnrichmentType

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
        results: Map<EnrichmentType, EnrichmentResult>,
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
