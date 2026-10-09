package com.landofoz.musicmeta.android.cache

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.landofoz.musicmeta.ArtworkSize
import com.landofoz.musicmeta.ArtworkSource
import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A Room row written before `attribution` existed still reads: the payload decodes, the new field is
 * null, and the row is a hit rather than a miss, so the cache needs no clearing and no schema bump.
 * The JSON bodies are literal strings, as the earlier build encoded them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomAttributionCompatTest {

    private lateinit var database: EnrichmentCacheDatabase
    private lateinit var cache: RoomEnrichmentCache

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, EnrichmentCacheDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        cache = RoomEnrichmentCache(database.enrichmentCacheDao(), database.negativeCacheDao(), database.selectionDao())
    }

    @After
    fun teardown() {
        database.close()
    }

    private suspend fun insertOldRow(key: String, type: EnrichmentType, provider: String, dataJson: String) {
        database.enrichmentCacheDao().insert(
            EnrichmentCacheEntity(
                entityKey = key,
                enrichmentType = type.name,
                provider = provider,
                dataJson = dataJson,
                confidence = 0.95f,
                canonicalStatus = CanonicalStatus.RESOLVED.name,
                cachedAt = 0L,
                expiresAt = Long.MAX_VALUE,
            ),
        )
    }

    @Test
    fun `a biography row cached before attribution existed reads back with its text and no attribution`() = runTest {
        // Given - a row whose payload is a Biography encoded by the build that had no attribution field
        insertOldRow(
            key = "artist:old-bio",
            type = EnrichmentType.ARTIST_BIO,
            provider = "wikipedia",
            dataJson = """{"type":"com.landofoz.musicmeta.EnrichmentData.Biography","text":"Radiohead are an English rock band formed in Abingdon.","source":"Wikipedia","language":"en","thumbnailUrl":"https://upload.wikimedia.org/wikipedia/commons/thumb/a/a1/Radiohead.jpg/330px-Radiohead.jpg"}""",
        )

        // When - the row is read through the cache's own decoder
        val retrieved = cache.get("artist:old-bio", EnrichmentType.ARTIST_BIO)

        // Then - it is a hit with the text and thumbnail intact and the attribution unknown
        assertNotNull(retrieved)
        val biography = retrieved!!.result.data as EnrichmentData.Biography
        assertEquals("Radiohead are an English rock band formed in Abingdon.", biography.text)
        assertEquals(
            "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a1/Radiohead.jpg/330px-Radiohead.jpg",
            biography.thumbnailUrl,
        )
        assertNull(biography.attribution)
    }

    @Test
    fun `an artwork row with alternatives cached before attribution existed reads back intact for the expired read too`() = runTest {
        // Given - a row whose payload is an Artwork with sizes and one alternative, encoded without attribution
        insertOldRow(
            key = "album:old-art",
            type = EnrichmentType.ALBUM_ART,
            provider = "coverartarchive",
            dataJson = """{"type":"com.landofoz.musicmeta.EnrichmentData.Artwork","url":"https://coverartarchive.org/release-group/1/front-1200.jpg","width":1200,"height":1200,"thumbnailUrl":"https://coverartarchive.org/release-group/1/front-250.jpg","sizes":[{"url":"https://coverartarchive.org/release-group/1/front-500.jpg","width":500,"height":500,"label":"500"}],"alternatives":[{"provider":"deezer","url":"https://cdn.example.test/cover.jpg","thumbnailUrl":"https://cdn.example.test/cover-small.jpg","sizes":[{"url":"https://cdn.example.test/cover-big.jpg","width":1000,"height":1000,"label":"big"}]}]}""",
        )
        val expected = EnrichmentData.Artwork(
            url = "https://coverartarchive.org/release-group/1/front-1200.jpg",
            width = 1200,
            height = 1200,
            thumbnailUrl = "https://coverartarchive.org/release-group/1/front-250.jpg",
            sizes = listOf(ArtworkSize("https://coverartarchive.org/release-group/1/front-500.jpg", 500, 500, "500")),
            alternatives = listOf(
                ArtworkSource(
                    provider = "deezer",
                    url = "https://cdn.example.test/cover.jpg",
                    thumbnailUrl = "https://cdn.example.test/cover-small.jpg",
                    sizes = listOf(ArtworkSize("https://cdn.example.test/cover-big.jpg", 1000, 1000, "big")),
                ),
            ),
        )

        // When - the row is read through the fresh read and the expired read
        val fresh = cache.get("album:old-art", EnrichmentType.ALBUM_ART)
        val expired = cache.getIncludingExpired("album:old-art", EnrichmentType.ALBUM_ART)

        // Then - both are hits equal to the artwork without attribution, on the image and on the alternative
        assertEquals(expected, fresh?.result?.data)
        assertEquals(expected, expired?.result?.data)
    }
}
