package com.landofoz.musicmeta.android.cache

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [EnrichmentCacheEntity::class], version = 2, exportSchema = true)
abstract class EnrichmentCacheDatabase : RoomDatabase() {
    abstract fun enrichmentCacheDao(): EnrichmentCacheDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE enrichment_cache ADD COLUMN identity_match TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE enrichment_cache ADD COLUMN identity_match_score INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE enrichment_cache ADD COLUMN resolved_ids_json TEXT DEFAULT NULL")
            }
        }
    }
}
