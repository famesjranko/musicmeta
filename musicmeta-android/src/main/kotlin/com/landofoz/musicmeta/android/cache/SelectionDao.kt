package com.landofoz.musicmeta.android.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
public interface SelectionDao {

    @Query(
        "SELECT EXISTS(SELECT 1 FROM selections WHERE entity_key = :entityKey AND enrichment_type = :type)",
    )
    public suspend fun isSelected(entityKey: String, type: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insert(entity: SelectionEntity)

    @Query("DELETE FROM selections WHERE entity_key = :entityKey AND enrichment_type = :type")
    public suspend fun delete(entityKey: String, type: String)

    @Query("DELETE FROM selections WHERE entity_key = :entityKey")
    public suspend fun deleteAll(entityKey: String)

    @Query("DELETE FROM selections")
    public suspend fun clearAll()
}
