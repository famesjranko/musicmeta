package com.landofoz.musicmeta.android.cache

import com.landofoz.musicmeta.EnrichmentCache
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentLogger
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentityMatch
import kotlinx.serialization.json.Json

/**
 * Room-backed persistent enrichment cache.
 * Survives app restarts. Uses kotlinx-serialization for EnrichmentData.
 */
class RoomEnrichmentCache(
    private val dao: EnrichmentCacheDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: EnrichmentLogger = EnrichmentLogger.NoOp,
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
        } catch (e: Exception) {
            logger.warn(TAG, "Failed to deserialize cache entry $entityKey:$type: ${e.message}", e)
            return null
        }
        val resolvedIds = entity.resolvedIdsJson?.let {
            try { json.decodeFromString<EnrichmentIdentifiers>(it) } catch (_: Exception) { null }
        }
        val identityMatch = entity.identityMatch?.let {
            try { IdentityMatch.valueOf(it) } catch (_: Exception) { null }
        }
        return EnrichmentResult.Success(
            type, data, entity.provider, entity.confidence,
            resolvedIdentifiers = resolvedIds,
            identityMatchScore = entity.identityMatchScore,
            identityMatch = identityMatch,
        )
    }

    override suspend fun put(
        entityKey: String,
        type: EnrichmentType,
        result: EnrichmentResult.Success,
        ttlMs: Long,
    ) {
        val now = clock()
        val resolvedIdsJson = result.resolvedIdentifiers?.let {
            json.encodeToString(EnrichmentIdentifiers.serializer(), it)
        }
        dao.insert(
            EnrichmentCacheEntity(
                entityKey = entityKey,
                enrichmentType = type.name,
                provider = result.provider,
                dataJson = json.encodeToString(EnrichmentData.serializer(), result.data),
                confidence = result.confidence,
                identityMatch = result.identityMatch?.name,
                identityMatchScore = result.identityMatchScore,
                resolvedIdsJson = resolvedIdsJson,
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

    private companion object {
        const val TAG = "RoomEnrichmentCache"
    }
}
