package com.cascade.enrichment.android.cache

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "enrichment_cache",
    indices = [
        Index(value = ["entity_key", "enrichment_type"], unique = true),
        Index(value = ["expires_at"]),
    ],
)
data class EnrichmentCacheEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "entity_key") val entityKey: String,
    @ColumnInfo(name = "enrichment_type") val enrichmentType: String,
    @ColumnInfo(name = "provider") val provider: String,
    @ColumnInfo(name = "data_json") val dataJson: String,
    @ColumnInfo(name = "confidence") val confidence: Float,
    @ColumnInfo(name = "is_manual") val isManual: Boolean = false,
    @ColumnInfo(name = "cached_at") val cachedAt: Long,
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
)
