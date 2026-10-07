package com.landofoz.musicmeta.android.cache

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.landofoz.musicmeta.ArtworkSource
import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.ContentAttribution
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttributionCacheRoundTripTest {
    private lateinit var database: EnrichmentCacheDatabase
    private lateinit var cache: RoomEnrichmentCache

    @Before fun setUp() {
        // Given - an empty Room database using the production cache implementation
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, EnrichmentCacheDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        cache = RoomEnrichmentCache(database.enrichmentCacheDao(), database.negativeCacheDao(), database.selectionDao())
    }

    @After fun tearDown() {
        database.close()
    }

    @Test fun `Room data JSON preserves independently attributed primary and alternative files`() = runTest {
        // Given - a cache value with different primary and alternative file credits
        val expected = artwork()

        // When - Room writes its dataJson and the cache reads the value back
        cache.put("album:attribution", EnrichmentType.ALBUM_ART, expected, CanonicalStatus.RESOLVED)
        val stored = requireNotNull(database.enrichmentCacheDao().getIncludingExpired("album:attribution", EnrichmentType.ALBUM_ART.name))
        val actual = requireNotNull(cache.get("album:attribution", EnrichmentType.ALBUM_ART)).result

        // Then - generic dataJson contains and restores each file's own attribution
        assertEquals(expected.data, actual.data)
        assertEquals(2, Regex("\"attribution\"").findAll(stored.dataJson).count())
    }

    @Test fun `old literal Room artwork JSON decodes with null attributions`() = runTest {
        // Given - a persisted pre-attribution artwork row with an alternative image
        database.enrichmentCacheDao().insert(
            EnrichmentCacheEntity(
                entityKey = "album:legacy",
                enrichmentType = EnrichmentType.ALBUM_ART.name,
                provider = "legacy",
                dataJson = """{"type":"com.landofoz.musicmeta.EnrichmentData.Artwork","url":"https://images.test/old.jpg","alternatives":[{"provider":"other","url":"https://images.test/old-alt.jpg"}]}""",
                confidence = 1f,
                canonicalStatus = CanonicalStatus.RESOLVED.name,
                cachedAt = 0,
                expiresAt = Long.MAX_VALUE,
            ),
        )

        // When - the production Room cache decodes the literal old row
        val actual = requireNotNull(cache.get("album:legacy", EnrichmentType.ALBUM_ART)).result.data as EnrichmentData.Artwork

        // Then - old payloads remain readable and make no attribution claim
        assertNull(actual.attribution)
        assertNull(actual.alternatives?.single()?.attribution)
    }

    private fun artwork(): EnrichmentResult.Success = EnrichmentResult.Success(
        EnrichmentType.ALBUM_ART,
        EnrichmentData.Artwork(
            "https://images.test/primary.jpg",
            attribution = ContentAttribution("File:primary.jpg", "https://files.test/primary"),
            alternatives = listOf(
                ArtworkSource(
                    "alternative",
                    "https://images.test/alternative.jpg",
                    attribution = ContentAttribution("File:alternative.jpg", "https://files.test/alternative"),
                ),
            ),
        ),
        "primary",
        1f,
    )
}
