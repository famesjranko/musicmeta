package com.landofoz.musicmeta.android.cache

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A manual selection: user intent that a key and type's data was chosen, not fetched. The only
 * home for that fact — a separate table from [EnrichmentCacheEntity] on purpose, the same way
 * [NegativeCacheEntity] is, because a selection must survive independently of whatever cached
 * row (if any) exists for the same key.
 */
@Entity(
    tableName = "selections",
    indices = [Index(value = ["entity_key", "enrichment_type"], unique = true)],
)
data class SelectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "entity_key") val entityKey: String,
    @ColumnInfo(name = "enrichment_type") val enrichmentType: String,
)
