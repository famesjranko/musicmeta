package com.landofoz.musicmeta.android.cache

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Bumped whenever a stored envelope's meaning changes; a row read under a different value is a schema mismatch. */
internal const val CACHE_SCHEMA_VERSION = 1

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
    /** [com.landofoz.musicmeta.LookupProvenance] name — how the live lookup selected this entity. */
    @ColumnInfo(name = "lookup_provenance") val lookupProvenance: String? = null,
    /** [com.landofoz.musicmeta.CanonicalStatus] name the live call carried when this row was written. */
    @ColumnInfo(name = "canonical_status") val canonicalStatus: String,
    @ColumnInfo(name = "resolved_ids_json") val resolvedIdsJson: String? = null,
    /** Always `false` on write — a stale result is never cached; kept so a decoded row states it explicitly. */
    @ColumnInfo(name = "is_stale") val isStale: Boolean = false,
    // No column for com.landofoz.musicmeta.EnrichmentResult.Success.isCatalogDegraded, deliberately:
    // RoomEnrichmentCache.get/getIncludingExpired hand the decoded Success back with the field at
    // its default false, and the engine's own finalizeResult step recomputes it against the live
    // CatalogProvider before a caller ever sees it — including a STALE_IF_ERROR substitution. See
    // EnrichmentResult.Success.isCatalogDegraded's KDoc for why this is call-scoped, not a stored
    // fact.
    @ColumnInfo(name = "cached_at") val cachedAt: Long,
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int = CACHE_SCHEMA_VERSION,
)
