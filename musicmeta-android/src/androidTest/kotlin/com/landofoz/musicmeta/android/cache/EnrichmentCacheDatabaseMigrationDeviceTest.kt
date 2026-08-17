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
 * Runs MIGRATION_4_5 against framework SQLite on a device or emulator. The Robolectric migration
 * suite proves the SQL; this proves it on the platform an installed app actually upgrades on, which
 * is the one place a failed migration cannot be reverted. Connected tests never gate a merge — this
 * is release evidence, run by hand before tagging.
 */
@RunWith(AndroidJUnit4::class)
class EnrichmentCacheDatabaseMigrationDeviceTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        EnrichmentCacheDatabase::class.java,
    )

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun deleteDatabases() {
        context.deleteDatabase(TEST_DB)
        context.deleteDatabase(UPGRADE_DB)
    }

    @After
    fun cleanUp() {
        context.deleteDatabase(TEST_DB)
        context.deleteDatabase(UPGRADE_DB)
    }

    @Test
    fun migration4To5CarriesOnlyManualSelectionsOnDevice() {
        // Given - a v4 database on framework SQLite with a selected live row, an unselected live
        // row, a selected but expired row, and a negative_cache row the migration must not touch
        var db = helper.createDatabase(TEST_DB, 4)
        db.execSQL(
            "INSERT INTO enrichment_cache " +
                "(entity_key, enrichment_type, provider, data_json, confidence, canonical_status, " +
                "is_stale, is_manual, cached_at, expires_at, schema_version) " +
                "VALUES ('album:sel-live', 'ALBUM_ART', 'p', '{}', 0.9, 'RESOLVED', 0, 1, 1000, 9999999999, 1)",
        )
        db.execSQL(
            "INSERT INTO enrichment_cache " +
                "(entity_key, enrichment_type, provider, data_json, confidence, canonical_status, " +
                "is_stale, is_manual, cached_at, expires_at, schema_version) " +
                "VALUES ('album:unsel-live', 'GENRE', 'p', '{}', 0.9, 'RESOLVED', 0, 0, 1000, 9999999999, 1)",
        )
        db.execSQL(
            "INSERT INTO enrichment_cache " +
                "(entity_key, enrichment_type, provider, data_json, confidence, canonical_status, " +
                "is_stale, is_manual, cached_at, expires_at, schema_version) " +
                "VALUES ('album:sel-expired', 'ALBUM_ART', 'p', '{}', 0.9, 'RESOLVED', 0, 1, 1000, 1500, 1)",
        )
        db.execSQL(
            "INSERT INTO negative_cache " +
                "(entity_key, enrichment_type, provider, canonical_status, cached_at, expires_at, schema_version) " +
                "VALUES ('album:neg', 'GENRE', 'p', 'RESOLVED', 1000, 9999999999, 1)",
        )
        db.close()

        // When - migrating to v5 with the real migration
        db = helper.runMigrationsAndValidate(TEST_DB, 5, true, EnrichmentCacheDatabase.MIGRATION_4_5)

        // Then - exactly the two selected pairs survived into selections, expiry not filtering,
        // the unselected pair absent, enrichment_cache rebuilt empty, and negative_cache untouched
        val selections = db.query(
            "SELECT entity_key FROM selections ORDER BY entity_key",
        )
        assertEquals(2, selections.count)
        selections.moveToFirst()
        assertEquals("album:sel-expired", selections.getString(0))
        selections.moveToNext()
        assertEquals("album:sel-live", selections.getString(0))
        val cacheRows = db.query("SELECT entity_key FROM enrichment_cache")
        assertEquals(0, cacheRows.count)
        val negativeRow = db.query(
            "SELECT canonical_status FROM negative_cache WHERE entity_key = 'album:neg'",
        )
        assertTrue(negativeRow.moveToFirst())
        assertEquals("RESOLVED", negativeRow.getString(0))
    }

    @Test
    fun upgradedInstallKeepsItsManualSelectionThroughTheRealBuilder() {
        // Given - a v4 file on disk holding a manual selection and an unselected row, the state a
        // pre-upgrade install left behind
        val old = helper.createDatabase(UPGRADE_DB, 4)
        old.execSQL(
            "INSERT INTO enrichment_cache " +
                "(entity_key, enrichment_type, provider, data_json, confidence, canonical_status, " +
                "is_stale, is_manual, cached_at, expires_at, schema_version) " +
                "VALUES ('album:kept', 'ALBUM_ART', 'p', '{}', 0.9, 'RESOLVED', 0, 1, 1000, 9999999999, 1)",
        )
        old.execSQL(
            "INSERT INTO enrichment_cache " +
                "(entity_key, enrichment_type, provider, data_json, confidence, canonical_status, " +
                "is_stale, is_manual, cached_at, expires_at, schema_version) " +
                "VALUES ('album:plain', 'GENRE', 'p', '{}', 0.9, 'RESOLVED', 0, 0, 1000, 9999999999, 1)",
        )
        old.close()

        // When - opening the same file through the wiring a real app uses, which migrates in place
        val database = EnrichmentCacheDatabase.create(context, UPGRADE_DB)
        val cache = RoomEnrichmentCache(
            database.enrichmentCacheDao(),
            database.negativeCacheDao(),
            database.selectionDao(),
        )

        // Then - the user's selection survived the upgrade and the unselected key did not gain one
        runBlocking {
            assertTrue(cache.isManuallySelected("album:kept", EnrichmentType.ALBUM_ART))
            assertFalse(cache.isManuallySelected("album:plain", EnrichmentType.GENRE))
        }
        database.close()
    }

    private companion object {
        const val TEST_DB = "device-migration-test"
        const val UPGRADE_DB = "device-upgrade-test"
    }
}
