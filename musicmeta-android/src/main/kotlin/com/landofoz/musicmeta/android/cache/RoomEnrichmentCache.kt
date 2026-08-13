package com.landofoz.musicmeta.android.cache

import com.landofoz.musicmeta.CacheEnvelope
import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentCache
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentLogger
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.LookupProvenance
import kotlinx.serialization.json.Json

/**
 * Room-backed persistent enrichment cache.
 * Survives app restarts. Uses kotlinx-serialization for EnrichmentData.
 */
class RoomEnrichmentCache(
    private val dao: EnrichmentCacheDao,
    private val negativeDao: NegativeCacheDao,
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
    ): CacheEnvelope<EnrichmentResult.Success>? {
        val entity = dao.get(entityKey, type.name, clock()) ?: return null
        return deserializeEntity(entity, type)
    }

    override suspend fun getIncludingExpired(
        entityKey: String,
        type: EnrichmentType,
    ): CacheEnvelope<EnrichmentResult.Success>? {
        val entity = dao.getIncludingExpired(entityKey, type.name) ?: return null
        return deserializeEntity(entity, type)
    }

    /**
     * A [entity.schemaVersion][EnrichmentCacheEntity.schemaVersion] that does not match
     * [CACHE_SCHEMA_VERSION] is treated as a miss, never as a value to coerce or guess at — the
     * envelope's field meanings are what changed, not merely its JSON shape, so a partial decode
     * would silently misreport [LookupProvenance] or [CanonicalStatus] rather than fail loudly. An
     * unparseable [CanonicalStatus] name degrades to [CanonicalStatus.NOT_ATTEMPTED_CACHE_HIT]
     * instead — never a status stronger than what this row was actually written under.
     */
    private fun deserializeEntity(
        entity: EnrichmentCacheEntity,
        type: EnrichmentType,
    ): CacheEnvelope<EnrichmentResult.Success>? {
        if (entity.schemaVersion != CACHE_SCHEMA_VERSION) {
            logger.warn(TAG, "Cache schema mismatch for ${entity.entityKey}:$type; treating as a miss")
            return null
        }
        val data = try {
            json.decodeFromString<EnrichmentData>(entity.dataJson)
        } catch (e: Exception) {
            logger.warn(TAG, "Failed to deserialize cache entry ${entity.entityKey}:$type: ${e.message}", e)
            return null
        }
        val resolvedIds = entity.resolvedIdsJson?.let {
            try { json.decodeFromString<EnrichmentIdentifiers>(it) } catch (_: Exception) { null }
        }
        // Replays the original live lookup's provenance verbatim — never CACHE, which is reserved
        // for an implementation that cannot recover it at all.
        val provenance = entity.lookupProvenance?.let {
            try { LookupProvenance.valueOf(it) } catch (_: Exception) { null }
        } ?: LookupProvenance.CACHE
        val canonicalStatus = try {
            CanonicalStatus.valueOf(entity.canonicalStatus)
        } catch (_: Exception) {
            CanonicalStatus.NOT_ATTEMPTED_CACHE_HIT
        }
        val result = EnrichmentResult.Success(
            type, data, entity.provider, entity.confidence,
            resolvedIdentifiers = resolvedIds,
            provenance = provenance,
        )
        return CacheEnvelope(result, canonicalStatus)
    }

    override suspend fun put(
        entityKey: String,
        type: EnrichmentType,
        result: EnrichmentResult.Success,
        canonicalStatus: CanonicalStatus,
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
                lookupProvenance = result.provenance?.name,
                canonicalStatus = canonicalStatus.name,
                resolvedIdsJson = resolvedIdsJson,
                cachedAt = now,
                expiresAt = now + ttlMs,
            ),
        )
    }

    override suspend fun getNegative(
        entityKey: String,
        type: EnrichmentType,
    ): CacheEnvelope<EnrichmentResult.NotFound>? {
        val entity = negativeDao.get(entityKey, type.name, clock()) ?: return null
        if (entity.schemaVersion != CACHE_SCHEMA_VERSION) {
            logger.warn(TAG, "Cache schema mismatch for ${entity.entityKey}:$type; treating as a miss")
            return null
        }
        val canonicalStatus = try {
            CanonicalStatus.valueOf(entity.canonicalStatus)
        } catch (_: Exception) {
            CanonicalStatus.NOT_ATTEMPTED_CACHE_HIT
        }
        return CacheEnvelope(EnrichmentResult.NotFound(type = type, provider = entity.provider), canonicalStatus)
    }

    override suspend fun putNegative(
        entityKey: String,
        type: EnrichmentType,
        result: EnrichmentResult.NotFound,
        canonicalStatus: CanonicalStatus,
        ttlMs: Long,
    ) {
        val now = clock()
        negativeDao.insert(
            NegativeCacheEntity(
                entityKey = entityKey,
                enrichmentType = type.name,
                provider = result.provider,
                canonicalStatus = canonicalStatus.name,
                cachedAt = now,
                expiresAt = now + ttlMs,
            ),
        )
    }

    override suspend fun invalidate(entityKey: String, type: EnrichmentType?) {
        if (type != null) {
            dao.delete(entityKey, type.name)
            negativeDao.delete(entityKey, type.name)
        } else {
            dao.deleteAll(entityKey)
            negativeDao.deleteAll(entityKey)
        }
    }

    override suspend fun isManuallySelected(
        entityKey: String,
        type: EnrichmentType,
    ): Boolean = dao.isManual(entityKey, type.name) ?: false

    override suspend fun markManuallySelected(entityKey: String, type: EnrichmentType) {
        dao.markManual(entityKey, type.name)
    }

    override suspend fun clear() {
        dao.clearAll()
        negativeDao.clearAll()
    }

    /** Cleanup expired entries. Call periodically (e.g., from WorkManager). */
    suspend fun deleteExpired() {
        dao.deleteExpired(clock())
        negativeDao.deleteExpired(clock())
    }

    private companion object {
        const val TAG = "RoomEnrichmentCache"
    }
}
