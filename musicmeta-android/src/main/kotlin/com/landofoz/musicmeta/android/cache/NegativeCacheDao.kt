package com.landofoz.musicmeta.android.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NegativeCacheDao {

    @Query(
        "SELECT * FROM negative_cache WHERE entity_key = :entityKey AND enrichment_type = :type AND expires_at > :now LIMIT 1",
    )
    suspend fun get(entityKey: String, type: String, now: Long): NegativeCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: NegativeCacheEntity)

    @Query("DELETE FROM negative_cache WHERE entity_key = :entityKey AND enrichment_type = :type")
    suspend fun delete(entityKey: String, type: String)

    @Query("DELETE FROM negative_cache WHERE entity_key = :entityKey")
    suspend fun deleteAll(entityKey: String)

    @Query("DELETE FROM negative_cache")
    suspend fun clearAll()

    @Query("DELETE FROM negative_cache WHERE expires_at < :now")
    suspend fun deleteExpired(now: Long)
}
