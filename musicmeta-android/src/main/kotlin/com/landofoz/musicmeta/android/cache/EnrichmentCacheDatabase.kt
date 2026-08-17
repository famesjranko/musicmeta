package com.landofoz.musicmeta.android.cache

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [EnrichmentCacheEntity::class, NegativeCacheEntity::class, SelectionEntity::class],
    version = 5,
    exportSchema = true,
)
abstract class EnrichmentCacheDatabase : RoomDatabase() {
    abstract fun enrichmentCacheDao(): EnrichmentCacheDao
    abstract fun negativeCacheDao(): NegativeCacheDao
    abstract fun selectionDao(): SelectionDao

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
         * `identity_match`/`identity_match_score` name a fact no stored value can be reinterpreted
         * as [com.landofoz.musicmeta.LookupProvenance] or [com.landofoz.musicmeta.CanonicalStatus],
         * so this migration drops and recreates both tables rather than attempting a column-level
         * rewrite. Every pre-upgrade entry is a cache miss afterward, healed the same way any other
         * miss is: the next `enrich()` call refetches it live.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `enrichment_cache`")
                db.execSQL("DROP TABLE IF EXISTS `negative_cache`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `enrichment_cache` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`entity_key` TEXT NOT NULL, `enrichment_type` TEXT NOT NULL, `provider` TEXT NOT NULL, " +
                        "`data_json` TEXT NOT NULL, `confidence` REAL NOT NULL, `lookup_provenance` TEXT, " +
                        "`canonical_status` TEXT NOT NULL, `resolved_ids_json` TEXT, " +
                        "`is_stale` INTEGER NOT NULL DEFAULT 0, `is_manual` INTEGER NOT NULL DEFAULT 0, " +
                        "`cached_at` INTEGER NOT NULL, `expires_at` INTEGER NOT NULL, " +
                        "`schema_version` INTEGER NOT NULL DEFAULT $CACHE_SCHEMA_VERSION)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_enrichment_cache_entity_key_enrichment_type` " +
                        "ON `enrichment_cache` (`entity_key`, `enrichment_type`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_enrichment_cache_expires_at` ON `enrichment_cache` (`expires_at`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `negative_cache` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`entity_key` TEXT NOT NULL, `enrichment_type` TEXT NOT NULL, `provider` TEXT NOT NULL, " +
                        "`canonical_status` TEXT NOT NULL, `cached_at` INTEGER NOT NULL, " +
                        "`expires_at` INTEGER NOT NULL, `schema_version` INTEGER NOT NULL DEFAULT $CACHE_SCHEMA_VERSION)",
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
         * A manual selection is user intent, not cached data, so it is copied forward rather than
         * discarded the way [MIGRATION_3_4] discards a cache row: the next `enrich()` heals a lost
         * cache row, but nothing heals a lost selection. Only the `is_manual = 1` rows move, since
         * a selection is state about the key, not about freshness — an expired row's selection
         * still carries. `negative_cache` is untouched: its shape did not change, so dropping it
         * would discard healable data for no schema reason.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `selections` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`entity_key` TEXT NOT NULL, `enrichment_type` TEXT NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_selections_entity_key_enrichment_type` " +
                        "ON `selections` (`entity_key`, `enrichment_type`)",
                )
                db.execSQL(
                    "INSERT INTO selections (entity_key, enrichment_type) " +
                        "SELECT entity_key, enrichment_type FROM enrichment_cache WHERE is_manual = 1",
                )
                db.execSQL("DROP TABLE IF EXISTS `enrichment_cache`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `enrichment_cache` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`entity_key` TEXT NOT NULL, `enrichment_type` TEXT NOT NULL, `provider` TEXT NOT NULL, " +
                        "`data_json` TEXT NOT NULL, `confidence` REAL NOT NULL, `lookup_provenance` TEXT, " +
                        "`canonical_status` TEXT NOT NULL, `resolved_ids_json` TEXT, " +
                        "`is_stale` INTEGER NOT NULL DEFAULT 0, " +
                        "`cached_at` INTEGER NOT NULL, `expires_at` INTEGER NOT NULL, " +
                        "`schema_version` INTEGER NOT NULL DEFAULT $CACHE_SCHEMA_VERSION)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_enrichment_cache_entity_key_enrichment_type` " +
                        "ON `enrichment_cache` (`entity_key`, `enrichment_type`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_enrichment_cache_expires_at` ON `enrichment_cache` (`expires_at`)",
                )
            }
        }

        /**
         * Builds this database with every registered migration applied, so an on-device v1-v4 file
         * upgrades in place instead of Room refusing to open it. [name] is the database file
         * name — [com.landofoz.musicmeta.android.di.HiltEnrichmentModule] passes
         * `DEFAULT_DATABASE_NAME`. Building with your own `Room.databaseBuilder` instead means
         * registering [MIGRATION_1_2], [MIGRATION_2_3], [MIGRATION_3_4], and [MIGRATION_4_5] by hand.
         */
        fun create(context: Context, name: String): EnrichmentCacheDatabase =
            Room.databaseBuilder(context, EnrichmentCacheDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
    }
}
