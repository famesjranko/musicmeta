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

    private companion object {
        const val TEST_DB = "migration-test"
        const val TEST_DB_3 = "migration-test-3"
        const val MODULE_DB = "module-migration-test"
    }
}
