package com.landofoz.musicmeta.android.cache

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [EnrichmentCacheEntity::class, NegativeCacheEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class EnrichmentCacheDatabase : RoomDatabase() {
    abstract fun enrichmentCacheDao(): EnrichmentCacheDao
    abstract fun negativeCacheDao(): NegativeCacheDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE enrichment_cache ADD COLUMN identity_match TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE enrichment_cache ADD COLUMN identity_match_score INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE enrichment_cache ADD COLUMN resolved_ids_json TEXT DEFAULT NULL")
            }
        }

        // Additive: creates the negative_cache table only, leaving enrichment_cache untouched, so
        // on-device positive entries survive the upgrade.
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `negative_cache` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`entity_key` TEXT NOT NULL, `enrichment_type` TEXT NOT NULL, `provider` TEXT NOT NULL, " +
                        "`identity_match` TEXT, `cached_at` INTEGER NOT NULL, `expires_at` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_negative_cache_entity_key_enrichment_type` " +
                        "ON `negative_cache` (`entity_key`, `enrichment_type`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_negative_cache_expires_at` ON `negative_cache` (`expires_at`)",
                )
            }
        }

        /**
         * Builds this database with every registered migration applied, so an on-device v1 or v2
         * file upgrades in place instead of Room refusing to open it. [name] is the database file
         * name — [com.landofoz.musicmeta.android.di.HiltEnrichmentModule] passes
         * `DEFAULT_DATABASE_NAME`. Building with your own `Room.databaseBuilder` instead means
         * registering [MIGRATION_1_2] and [MIGRATION_2_3] by hand.
         */
        fun create(context: Context, name: String): EnrichmentCacheDatabase =
            Room.databaseBuilder(context, EnrichmentCacheDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
