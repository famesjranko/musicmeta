package com.landofoz.musicmeta.android.cache

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnrichmentCacheDatabaseMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        EnrichmentCacheDatabase::class.java,
    )

    @Test
    fun `migration 2 to 3 creates negative_cache and keeps existing enrichment_cache rows`() {
        // Given - a v2 database holding one positive entry
        var db = helper.createDatabase(TEST_DB, 2)
        db.execSQL(
            "INSERT INTO enrichment_cache " +
                "(entity_key, enrichment_type, provider, data_json, confidence, is_manual, cached_at, expires_at) " +
                "VALUES ('album:1', 'ALBUM_ART', 'p', '{}', 0.9, 0, 1000, 2000)",
        )
        db.close()

        // When - migrating to v3 with the real, additive migration
        db = helper.runMigrationsAndValidate(TEST_DB, 3, true, EnrichmentCacheDatabase.MIGRATION_2_3)

        // Then - the pre-existing positive row survived, and negative_cache exists and accepts a row
        val existing = db.query("SELECT entity_key FROM enrichment_cache WHERE entity_key = 'album:1'")
        assertTrue(existing.moveToFirst())
        existing.close()
        db.execSQL(
            "INSERT INTO negative_cache (entity_key, enrichment_type, provider, cached_at, expires_at) " +
                "VALUES ('album:2', 'GENRE', 'p', 1000, 2000)",
        )
        val inserted = db.query("SELECT entity_key FROM negative_cache WHERE entity_key = 'album:2'")
        assertTrue(inserted.moveToFirst())
        inserted.close()
    }

    @Test
    fun `the module's builder wiring opens a real v2 file without crashing`() {
        // Given - a v2 file on disk, the shape a pre-upgrade install left behind, with a row in it
        helper.createDatabase(MODULE_DB, 2).apply {
            execSQL(
                "INSERT INTO enrichment_cache " +
                    "(entity_key, enrichment_type, provider, data_json, confidence, is_manual, cached_at, expires_at) " +
                    "VALUES ('album:1', 'ALBUM_ART', 'p', '{}', 0.9, 0, 1000, 2000)",
            )
            close()
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // When - opening it through the same builder wiring HiltEnrichmentModule installs
        val database = EnrichmentCacheDatabase.create(context, MODULE_DB)
        helper.closeWhenFinished(database)

        // Then - it opens without "migration required but not found". `identity_match` named a
        // different fact before v4, so MIGRATION_3_4 clears the table rather than reinterpreting
        // the old row — this is the documented clear-on-schema-mismatch behavior, not a bug — and
        // the new columns are usable going forward.
        runBlocking {
            assertNull(database.enrichmentCacheDao().getIncludingExpired("album:1", "ALBUM_ART"))
            database.negativeCacheDao().insert(
                NegativeCacheEntity(
                    entityKey = "album:2", enrichmentType = "GENRE", provider = "p",
                    canonicalStatus = "RESOLVED", cachedAt = 1000, expiresAt = 9_999_999_999,
                ),
            )
            assertNotNull(database.negativeCacheDao().get("album:2", "GENRE", 2000))
        }
    }

    @Test
    fun `migration 3 to 4 clears both tables rather than reinterpreting identity_match`() {
        // Given - a v3 database holding a positive and a negative entry under the old schema,
        // where identity_match named a call-level IdentityMatch verdict, not a per-row provenance
        var db = helper.createDatabase(TEST_DB_3, 3)
        db.execSQL(
            "INSERT INTO enrichment_cache " +
                "(entity_key, enrichment_type, provider, data_json, confidence, identity_match, " +
                "identity_match_score, is_manual, cached_at, expires_at) " +
                "VALUES ('album:1', 'ALBUM_ART', 'p', '{}', 0.9, 'RESOLVED', 95, 0, 1000, 9999999999)",
        )
        db.execSQL(
            "INSERT INTO negative_cache (entity_key, enrichment_type, provider, identity_match, cached_at, expires_at) " +
                "VALUES ('album:2', 'GENRE', 'p', 'RESOLVED', 1000, 9999999999)",
        )
        db.close()

        // When - migrating to v4 with the real, destructive migration
        db = helper.runMigrationsAndValidate(TEST_DB_3, 4, true, EnrichmentCacheDatabase.MIGRATION_3_4)

        // Then - both pre-existing rows are gone (a miss the next live call heals), and the new
        // columns accept a row shaped like what RoomEnrichmentCache now writes
        val positive = db.query("SELECT entity_key FROM enrichment_cache WHERE entity_key = 'album:1'")
        assertEquals(0, positive.count)
        positive.close()
        val negative = db.query("SELECT entity_key FROM negative_cache WHERE entity_key = 'album:2'")
        assertEquals(0, negative.count)
        negative.close()
        db.execSQL(
            "INSERT INTO enrichment_cache " +
                "(entity_key, enrichment_type, provider, data_json, confidence, lookup_provenance, " +
                "canonical_status, is_stale, is_manual, cached_at, expires_at, schema_version) " +
                "VALUES ('album:3', 'ALBUM_ART', 'p', '{}', 0.9, 'CANONICAL_ID', 'RESOLVED', 0, 0, 1000, 9999999999, 1)",
        )
        val fresh = db.query("SELECT entity_key FROM enrichment_cache WHERE entity_key = 'album:3'")
        assertEquals(1, fresh.count)
        fresh.close()
    }

    @Test
    fun `migration 4 to 5 carries only manual selections into the selections table`() {
        // Given - a v4 database with a selected live row, an unselected live row, a selected but
        // expired row, and a negative_cache row that this migration must leave untouched
        var db = helper.createDatabase(TEST_DB_4, 4)
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
        db = helper.runMigrationsAndValidate(TEST_DB_4, 5, true, EnrichmentCacheDatabase.MIGRATION_4_5)

        // Then - the selected live row's pair survived into selections
        val selLive = db.query(
            "SELECT 1 FROM selections WHERE entity_key = 'album:sel-live' AND enrichment_type = 'ALBUM_ART'",
        )
        assertTrue(selLive.moveToFirst())
        selLive.close()
        // And - the unselected row's pair did not resurrect as selected
        val unselLive = db.query(
            "SELECT 1 FROM selections WHERE entity_key = 'album:unsel-live' AND enrichment_type = 'GENRE'",
        )
        assertEquals(0, unselLive.count)
        unselLive.close()
        // And - the selected but expired row's pair also survived, and nothing else did: expiry
        // must not filter a selection, which is state about the key, not the row
        val selExpired = db.query(
            "SELECT 1 FROM selections WHERE entity_key = 'album:sel-expired' AND enrichment_type = 'ALBUM_ART'",
        )
        assertTrue(selExpired.moveToFirst())
        selExpired.close()
        val allSelections = db.query("SELECT entity_key FROM selections")
        assertEquals(2, allSelections.count)
        allSelections.close()
        // And - enrichment_cache itself was dropped and recreated empty, per MIGRATION_3_4's precedent
        val cacheRows = db.query("SELECT entity_key FROM enrichment_cache")
        assertEquals(0, cacheRows.count)
        cacheRows.close()
        // And - negative_cache is untouched: it did not change shape between v4 and v5, so dropping
        // it would discard healable data for no schema reason
        val negativeRow = db.query("SELECT canonical_status FROM negative_cache WHERE entity_key = 'album:neg'")
        assertTrue(negativeRow.moveToFirst())
        assertEquals("RESOLVED", negativeRow.getString(0))
        negativeRow.close()
    }

    private companion object {
        const val TEST_DB = "migration-test"
        const val TEST_DB_3 = "migration-test-3"
        const val TEST_DB_4 = "migration-test-4"
        const val MODULE_DB = "module-migration-test"
    }
}
