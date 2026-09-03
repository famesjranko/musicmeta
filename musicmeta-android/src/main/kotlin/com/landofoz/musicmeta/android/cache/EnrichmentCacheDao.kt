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

    @Query("DELETE FROM enrichment_cache WHERE entity_key = :entityKey AND enrichment_type = :type")
    public suspend fun delete(entityKey: String, type: String)

    @Query("DELETE FROM enrichment_cache WHERE entity_key = :entityKey")
    public suspend fun deleteAll(entityKey: String)

    @Query("DELETE FROM enrichment_cache")
    public suspend fun clearAll()

    @Query("DELETE FROM enrichment_cache WHERE expires_at < :now")
    public suspend fun deleteExpired(now: Long)
}
