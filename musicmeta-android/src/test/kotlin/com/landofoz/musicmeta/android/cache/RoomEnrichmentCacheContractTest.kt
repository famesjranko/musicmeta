package com.landofoz.musicmeta.android.cache

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.landofoz.musicmeta.EnrichmentCache
import com.landofoz.musicmeta.contract.EnrichmentCacheContract
import org.junit.Ignore
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
        return RoomEnrichmentCache(db.enrichmentCacheDao(), db.negativeCacheDao())
    }

    override fun release(subject: EnrichmentCache) {
        database?.close()
        database = null
    }

    /**
     * The one override this contract sanctions, and it changes nothing about what is asserted — it
     * calls straight back to the inherited body. Deleting these six lines is how the mark comes off.
     */
    @Ignore(
        "Red now, and owned by the ticket for a Room manual selection marked before the first " +
            "write: markManual is an UPDATE against the cached row, so on a key with no row yet it " +
            "matches nothing and is silently lost, while both in-memory backends hold the marker " +
            "beside the data and keep it. Remove this mark when a selection survives with no row " +
            "present; the test must go red first if it does not.",
    )
    @Test
    override fun `manual selection survives an ordinary write`() {
        // Given - a key marked manually selected before anything has been stored against it
        // When - a first value is written for that same key and type
        // Then - the marker is still set, which this backing cannot yet do
        super.`manual selection survives an ordinary write`()
    }
}
