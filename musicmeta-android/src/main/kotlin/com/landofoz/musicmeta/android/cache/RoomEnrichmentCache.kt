package com.landofoz.musicmeta.android.cache

import com.landofoz.musicmeta.EnrichmentCache
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import kotlinx.serialization.json.Json

/**
 * Room-backed persistent enrichment cache.
 * Survives app restarts. Uses kotlinx-serialization for EnrichmentData.
 */
class RoomEnrichmentCache(
    private val dao: EnrichmentCacheDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : EnrichmentCache {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun get(
        entityKey: String,
        type: EnrichmentType,
    ): EnrichmentResult.Success? {
        val entity = dao.get(entityKey, type.name, clock()) ?: return null
        val data = try {
            json.decodeFromString<EnrichmentData>(entity.dataJson)
        } catch (_: Exception) { return null }
        return EnrichmentResult.Success(type, data, entity.provider, entity.confidence)
    }

    override suspend fun put(
        entityKey: String,
        type: EnrichmentType,
        result: EnrichmentResult.Success,
        ttlMs: Long,
    ) {
        val now = clock()
        dao.insert(
            EnrichmentCacheEntity(
                entityKey = entityKey,
                enrichmentType = type.name,
                provider = result.provider,
                dataJson = json.encodeToString(EnrichmentData.serializer(), result.data),
                confidence = result.confidence,
                cachedAt = now,
                expiresAt = now + ttlMs,
            ),
        )
    }

    override suspend fun invalidate(entityKey: String, type: EnrichmentType?) {
        if (type != null) dao.delete(entityKey, type.name) else dao.deleteAll(entityKey)
    }

    override suspend fun isManuallySelected(
        entityKey: String,
        type: EnrichmentType,
    ): Boolean = dao.isManual(entityKey, type.name) ?: false

    override suspend fun markManuallySelected(entityKey: String, type: EnrichmentType) {
        dao.markManual(entityKey, type.name)
    }

    override suspend fun clear() = dao.clearAll()

    /** Cleanup expired entries. Call periodically (e.g., from WorkManager). */
    suspend fun deleteExpired() = dao.deleteExpired(clock())
}
