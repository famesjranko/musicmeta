package com.landofoz.musicmeta.android.cache

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A "providers had nothing" answer. A separate table from [EnrichmentCacheEntity] on purpose: a
 * negative must never be read back through the `Success`-typed positive table.
 */
@Entity(
    tableName = "negative_cache",
    indices = [
        Index(value = ["entity_key", "enrichment_type"], unique = true),
        Index(value = ["expires_at"]),
    ],
)
data class NegativeCacheEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "entity_key") val entityKey: String,
    @ColumnInfo(name = "enrichment_type") val enrichmentType: String,
    @ColumnInfo(name = "provider") val provider: String,
    @ColumnInfo(name = "identity_match") val identityMatch: String? = null,
    @ColumnInfo(name = "cached_at") val cachedAt: Long,
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
)
