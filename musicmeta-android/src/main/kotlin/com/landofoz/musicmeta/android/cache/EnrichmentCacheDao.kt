package com.landofoz.musicmeta.android.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface EnrichmentCacheDao {

    @Query(
        "SELECT * FROM enrichment_cache WHERE entity_key = :entityKey AND enrichment_type = :type AND expires_at > :now LIMIT 1",
    )
    suspend fun get(entityKey: String, type: String, now: Long): EnrichmentCacheEntity?

    @Query(
        "SELECT * FROM enrichment_cache WHERE entity_key = :entityKey AND enrichment_type = :type LIMIT 1",
    )
    suspend fun getIncludingExpired(entityKey: String, type: String): EnrichmentCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: EnrichmentCacheEntity)

    @Query("DELETE FROM enrichment_cache WHERE entity_key = :entityKey AND enrichment_type = :type")
    suspend fun delete(entityKey: String, type: String)

    @Query("DELETE FROM enrichment_cache WHERE entity_key = :entityKey")
    suspend fun deleteAll(entityKey: String)

    @Query(
        "SELECT is_manual FROM enrichment_cache WHERE entity_key = :entityKey AND enrichment_type = :type LIMIT 1",
    )
    suspend fun isManual(entityKey: String, type: String): Boolean?

    @Query(
        "UPDATE enrichment_cache SET is_manual = 1 WHERE entity_key = :entityKey AND enrichment_type = :type",
    )
    suspend fun markManual(entityKey: String, type: String)

    @Query("DELETE FROM enrichment_cache")
    suspend fun clearAll()

    @Query("DELETE FROM enrichment_cache WHERE expires_at < :now")
    suspend fun deleteExpired(now: Long)
}
