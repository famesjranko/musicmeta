package com.landofoz.musicmeta.android.cache

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.landofoz.musicmeta.EnrichmentType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Walks every registered migration against framework SQLite on a device or emulator: each step on
 * its own, and then 1 to 5 in a single run of the chain an install that skipped four releases takes.
 * A step whose result a later step overwrites — the columns MIGRATION_1_2 adds, the tables
 * MIGRATION_2_3 creates — is invisible in the chain's end state, so it is pinned per step as well.
 * Connected tests never gate a merge; this is release evidence, run by hand before tagging.
 */
@RunWith(AndroidJUnit4::class)
class EnrichmentCacheDatabaseMigrationChainDeviceTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        EnrichmentCacheDatabase::class.java,
    )

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun deleteDatabases() {
        deleteAll()
    }

    @After
    fun cleanUp() {
        deleteAll()
    }

    @Test
    fun migration1To2AddsIdentityColumnsAndKeepsRowsOnDevice() {
        // Given - a v1 database holding one positive entry
        var db = helper.createDatabase(STEP_DB, 1)
        db.execSQL(
            "INSERT INTO enrichment_cache " +
                "(entity_key, enrichment_type, provider, data_json, confidence, is_manual, " +
                "cached_at, expires_at) " +
                "VALUES ('album:1', 'ALBUM_ART', 'p', '{}', 0.9, 1, 1000, 9999999999)",
        )
        db.close()

        // When - migrating to v2 with the real, additive migration
        db = helper.runMigrationsAndValidate(STEP_DB, 2, true, EnrichmentCacheDatabase.MIGRATION_1_2)

        // Then - the row survived with its manual flag, and the three added columns are null on it
        val row = db.query(
            "SELECT is_manual, identity_match, identity_match_score, resolved_ids_json " +
                "FROM enrichment_cache WHERE entity_key = 'album:1'",
        )
        assertTrue(row.moveToFirst())
        assertEquals(1, row.getInt(0))
        assertTrue(row.isNull(1))
        assertTrue(row.isNull(2))
        assertTrue(row.isNull(3))
        row.close()
    }

    @Test
    fun migration2To3CreatesNegativeCacheAndKeepsRowsOnDevice() {
        // Given - a v2 database holding one positive entry
        var db = helper.createDatabase(STEP_DB, 2)
        db.execSQL(
            "INSERT INTO enrichment_cache " +
                "(entity_key, enrichment_type, provider, data_json, confidence, is_manual, " +
                "cached_at, expires_at) " +
                "VALUES ('album:1', 'ALBUM_ART', 'p', '{}', 0.9, 0, 1000, 9999999999)",
        )
        db.close()

        // When - migrating to v3 with the real, additive migration
        db = helper.runMigrationsAndValidate(STEP_DB, 3, true, EnrichmentCacheDatabase.MIGRATION_2_3)

        // Then - the pre-existing positive row survived, and negative_cache exists and takes a row
        val existing = db.query("SELECT entity_key FROM enrichment_cache WHERE entity_key = 'album:1'")
        assertEquals(1, existing.count)
        existing.close()
        db.execSQL(
            "INSERT INTO negative_cache " +
                "(entity_key, enrichment_type, provider, identity_match, cached_at, expires_at) " +
                "VALUES ('album:2', 'GENRE', 'p', null, 1000, 9999999999)",
        )
        val inserted = db.query("SELECT entity_key FROM negative_cache WHERE entity_key = 'album:2'")
        assertEquals(1, inserted.count)
        inserted.close()
    }

    @Test
    fun migration3To4DiscardsBothCachesAndRebuildsThemOnDevice() {
        // Given - a v3 database holding a positive entry and a negative entry
        var db = helper.createDatabase(STEP_DB, 3)
        db.execSQL(
            "INSERT INTO enrichment_cache " +
                "(entity_key, enrichment_type, provider, data_json, confidence, is_manual, " +
                "cached_at, expires_at) " +
                "VALUES ('album:1', 'ALBUM_ART', 'p', '{}', 0.9, 1, 1000, 9999999999)",
        )
        db.execSQL(
            "INSERT INTO negative_cache " +
                "(entity_key, enrichment_type, provider, identity_match, cached_at, expires_at) " +
                "VALUES ('album:2', 'GENRE', 'p', null, 1000, 9999999999)",
        )
        db.close()

        // When - migrating to v4, which cannot reinterpret a stored value and so rebuilds instead
        db = helper.runMigrationsAndValidate(STEP_DB, 4, true, EnrichmentCacheDatabase.MIGRATION_3_4)

        // Then - both caches are empty and take a row in the v4 shape, the manual flag gone with it
        val cacheRows = db.query("SELECT entity_key FROM enrichment_cache")
        assertEquals(0, cacheRows.count)
        cacheRows.close()
        val negativeRows = db.query("SELECT entity_key FROM negative_cache")
        assertEquals(0, negativeRows.count)
        negativeRows.close()
        db.execSQL(
            "INSERT INTO enrichment_cache " +
                "(entity_key, enrichment_type, provider, data_json, confidence, canonical_status, " +
                "is_stale, is_manual, cached_at, expires_at, schema_version) " +
                "VALUES ('album:3', 'ALBUM_ART', 'p', '{}', 0.9, 'RESOLVED', 0, 1, 1000, 9999999999, 1)",
        )
        db.execSQL(
            "INSERT INTO negative_cache " +
                "(entity_key, enrichment_type, provider, canonical_status, cached_at, expires_at, " +
                "schema_version) " +
                "VALUES ('album:4', 'GENRE', 'p', 'NOT_FOUND', 1000, 9999999999, 1)",
        )
        val rebuilt = db.query("SELECT entity_key FROM enrichment_cache WHERE entity_key = 'album:3'")
        assertEquals(1, rebuilt.count)
        rebuilt.close()
    }

    @Test
    fun everyMigrationFromV1ReachesV5InOneWalkOnDevice() {
        // Given - a v1 database, the oldest shape an installed app can still be holding
        var db = helper.createDatabase(CHAIN_DB, 1)
        db.execSQL(
            "INSERT INTO enrichment_cache " +
                "(entity_key, enrichment_type, provider, data_json, confidence, is_manual, " +
                "cached_at, expires_at) " +
                "VALUES ('album:manual', 'ALBUM_ART', 'p', '{}', 0.9, 1, 1000, 9999999999)",
        )
        db.execSQL(
            "INSERT INTO enrichment_cache " +
                "(entity_key, enrichment_type, provider, data_json, confidence, is_manual, " +
                "cached_at, expires_at) " +
                "VALUES ('album:plain', 'GENRE', 'p', '{}', 0.9, 0, 1000, 9999999999)",
        )
        db.close()

        // When - walking all four migrations in one run, as an install skipping four releases does
        db = helper.runMigrationsAndValidate(
            CHAIN_DB,
            5,
            true,
            EnrichmentCacheDatabase.MIGRATION_1_2,
            EnrichmentCacheDatabase.MIGRATION_2_3,
            EnrichmentCacheDatabase.MIGRATION_3_4,
            EnrichmentCacheDatabase.MIGRATION_4_5,
        )

        // Then - the v5 shape validates, and nothing survives a pre-v4 file: the rows MIGRATION_1_2
        // and MIGRATION_2_3 carried are discarded by MIGRATION_3_4, so no selection reaches v5
        val cacheRows = db.query("SELECT entity_key FROM enrichment_cache")
        assertEquals(0, cacheRows.count)
        cacheRows.close()
        val negativeRows = db.query("SELECT entity_key FROM negative_cache")
        assertEquals(0, negativeRows.count)
        negativeRows.close()
        val selections = db.query("SELECT entity_key FROM selections")
        assertEquals(0, selections.count)
        selections.close()
    }

    @Test
    fun upgradedV1InstallOpensAndWritesThroughTheRealBuilder() {
        // Given - a v1 file on disk holding a manual selection, the state an old install left behind
        helper.createDatabase(UPGRADE_DB, 1).apply {
            execSQL(
                "INSERT INTO enrichment_cache " +
                    "(entity_key, enrichment_type, provider, data_json, confidence, is_manual, " +
                    "cached_at, expires_at) " +
                    "VALUES ('album:old', 'ALBUM_ART', 'p', '{}', 0.9, 1, 1000, 9999999999)",
            )
            close()
        }

        // When - opening the same file through the wiring a real app uses, which migrates in place
        val database = EnrichmentCacheDatabase.create(context, UPGRADE_DB)
        val cache = RoomEnrichmentCache(
            database.enrichmentCacheDao(),
            database.negativeCacheDao(),
            database.selectionDao(),
        )

        // Then - the pre-v4 selection is gone with its cache row, and the migrated file still writes
        runBlocking {
            assertFalse(cache.isManuallySelected("album:old", EnrichmentType.ALBUM_ART))
            cache.markManuallySelected("album:old", EnrichmentType.ALBUM_ART)
            assertTrue(cache.isManuallySelected("album:old", EnrichmentType.ALBUM_ART))
        }
        database.close()
    }

    private fun deleteAll() {
        context.deleteDatabase(STEP_DB)
        context.deleteDatabase(CHAIN_DB)
        context.deleteDatabase(UPGRADE_DB)
    }

    private companion object {
        const val STEP_DB = "device-step-migration-test"
        const val CHAIN_DB = "device-chain-migration-test"
        const val UPGRADE_DB = "device-chain-upgrade-test"
    }
}
