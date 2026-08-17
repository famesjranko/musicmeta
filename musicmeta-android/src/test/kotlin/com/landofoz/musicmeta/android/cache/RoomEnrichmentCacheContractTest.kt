package com.landofoz.musicmeta.android.cache

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.landofoz.musicmeta.EnrichmentCache
import com.landofoz.musicmeta.contract.EnrichmentCacheContract
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [RoomEnrichmentCache] under the shared [EnrichmentCacheContract], backed by a real in-memory Room
 * database built the same way [RoomEnrichmentCacheTest] builds one — never a faked DAO, so the
 * contract exercises the actual SQL and serialization round trip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomEnrichmentCacheContractTest : EnrichmentCacheContract() {

    private var database: EnrichmentCacheDatabase? = null

    override fun subject(): EnrichmentCache {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, EnrichmentCacheDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database = db
        return RoomEnrichmentCache(db.enrichmentCacheDao(), db.negativeCacheDao(), db.selectionDao())
    }

    override fun release(subject: EnrichmentCache) {
        database?.close()
        database = null
    }

    /**
     * The one override this contract sanctions, and it changes nothing about what is asserted — it
     * calls straight back to the inherited body. Deleting these six lines is how the mark comes off.
     */
    @Test
    override fun `manual selection survives an ordinary write`() {
        // Given - a key marked manually selected before anything has been stored against it
        // When - a first value is written for that same key and type
        // Then - the marker is still set, which this backing cannot yet do
        super.`manual selection survives an ordinary write`()
    }
}
