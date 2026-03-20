package com.cascade.enrichment.android.cache

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [EnrichmentCacheEntity::class], version = 1, exportSchema = true)
abstract class EnrichmentCacheDatabase : RoomDatabase() {
    abstract fun enrichmentCacheDao(): EnrichmentCacheDao
}
