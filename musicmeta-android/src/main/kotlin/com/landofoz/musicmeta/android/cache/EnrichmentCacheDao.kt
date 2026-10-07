package com.landofoz.musicmeta.android.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
public interface EnrichmentCacheDao {

    @Query(
        "SELECT * FROM enrichment_cache WHERE entity_key = :entityKey AND enrichment_type = :type AND expires_at > :now LIMIT 1",
    )
    public suspend fun get(entityKey: String, type: String, now: Long): EnrichmentCacheEntity?

    @Query(
        "SELECT * FROM enrichment_cache WHERE entity_key = :entityKey AND enrichment_type = :type LIMIT 1",
    )
    public suspend fun getIncludingExpired(entityKey: String, type: String): EnrichmentCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insert(entity: EnrichmentCacheEntity)

    /**
     * Inserts a positive result unless a selection already protects a positive row for this tuple.
     * One SQL statement makes selection inspection and replacement indivisible; a marker-only
     * selection still permits its first positive fill.
     */
    @Query(
        """
        INSERT OR REPLACE INTO enrichment_cache
        (entity_key, enrichment_type, provider, data_json, confidence, lookup_provenance,
         canonical_status, resolved_ids_json, is_stale, cached_at, expires_at, schema_version)
        SELECT :entityKey, :type, :provider, :dataJson, :confidence, :lookupProvenance,
               :canonicalStatus, :resolvedIdsJson, 0, :cachedAt, :expiresAt, :schemaVersion
        WHERE NOT EXISTS (
            SELECT 1 FROM selections WHERE entity_key = :entityKey AND enrichment_type = :type
        ) OR NOT EXISTS (
            SELECT 1 FROM enrichment_cache WHERE entity_key = :entityKey AND enrichment_type = :type
        )
        """,
    )
    @Suppress("LongParameterList")
    public suspend fun insertUnlessPinned(
        entityKey: String,
        type: String,
        provider: String,
        dataJson: String,
        confidence: Float,
        lookupProvenance: String?,
        canonicalStatus: String,
        resolvedIdsJson: String?,
        cachedAt: Long,
        expiresAt: Long,
        schemaVersion: Int,
    )

    /** Inserts a negative answer only when the tuple has no manual selection marker. */
    @Query(
        """
        INSERT OR REPLACE INTO negative_cache
        (entity_key, enrichment_type, provider, canonical_status, cached_at, expires_at, schema_version)
        SELECT :entityKey, :type, :provider, :canonicalStatus, :cachedAt, :expiresAt, :schemaVersion
        WHERE NOT EXISTS (
            SELECT 1 FROM selections WHERE entity_key = :entityKey AND enrichment_type = :type
        )
        """,
    )
    @Suppress("LongParameterList")
    public suspend fun insertNegativeUnlessPinned(
        entityKey: String,
        type: String,
        provider: String,
        canonicalStatus: String,
        cachedAt: Long,
        expiresAt: Long,
        schemaVersion: Int,
    )

    @Query("DELETE FROM enrichment_cache WHERE entity_key = :entityKey AND enrichment_type = :type")
    public suspend fun delete(entityKey: String, type: String)

    @Query("DELETE FROM enrichment_cache WHERE entity_key = :entityKey")
    public suspend fun deleteAll(entityKey: String)

    @Query("DELETE FROM enrichment_cache")
    public suspend fun clearAll()

    @Query(
        """
        DELETE FROM enrichment_cache WHERE expires_at < :now AND NOT EXISTS (
            SELECT 1 FROM selections
            WHERE selections.entity_key = enrichment_cache.entity_key
              AND selections.enrichment_type = enrichment_cache.enrichment_type
        )
        """,
    )
    public suspend fun deleteExpired(now: Long)
}
