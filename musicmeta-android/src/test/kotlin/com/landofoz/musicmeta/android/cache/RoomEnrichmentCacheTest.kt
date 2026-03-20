package com.landofoz.musicmeta.android.cache

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomEnrichmentCacheTest {

    private lateinit var database: EnrichmentCacheDatabase
    private lateinit var cache: RoomEnrichmentCache

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, EnrichmentCacheDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        cache = RoomEnrichmentCache(database.enrichmentCacheDao())
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun `stores and retrieves artwork result`() = runTest {
        // Given
        val data = EnrichmentData.Artwork(
            url = "https://example.com/art.jpg",
            thumbnailUrl = "https://example.com/thumb.jpg",
            width = 600,
            height = 600,
        )
        val result = EnrichmentResult.Success(
            type = EnrichmentType.ALBUM_ART,
            data = data,
            provider = "coverartarchive",
            confidence = 0.95f,
        )

        // When
        cache.put("album:123", EnrichmentType.ALBUM_ART, result)
        val retrieved = cache.get("album:123", EnrichmentType.ALBUM_ART)

        // Then
        assertNotNull(retrieved)
        assertEquals("coverartarchive", retrieved!!.provider)
        assertEquals(0.95f, retrieved.confidence)
        val artworkData = retrieved.data as EnrichmentData.Artwork
        assertEquals("https://example.com/art.jpg", artworkData.url)
        assertEquals("https://example.com/thumb.jpg", artworkData.thumbnailUrl)
        assertEquals(600, artworkData.width)
        assertEquals(600, artworkData.height)
    }

    @Test
    fun `stores and retrieves metadata result`() = runTest {
        // Given
        val data = EnrichmentData.Metadata(
            genres = listOf("Rock", "Alternative"),
            label = "Island Records",
            releaseDate = "1991-09-24",
            releaseType = "Album",
            country = "US",
        )
        val result = EnrichmentResult.Success(
            type = EnrichmentType.GENRE,
            data = data,
            provider = "musicbrainz",
            confidence = 0.9f,
        )

        // When
        cache.put("album:456", EnrichmentType.GENRE, result)
        val retrieved = cache.get("album:456", EnrichmentType.GENRE)

        // Then
        assertNotNull(retrieved)
        assertEquals("musicbrainz", retrieved!!.provider)
        val metaData = retrieved.data as EnrichmentData.Metadata
        assertEquals(listOf("Rock", "Alternative"), metaData.genres)
        assertEquals("Island Records", metaData.label)
        assertEquals("1991-09-24", metaData.releaseDate)
        assertEquals("Album", metaData.releaseType)
        assertEquals("US", metaData.country)
    }

    @Test
    fun `stores and retrieves lyrics result`() = runTest {
        // Given
        val data = EnrichmentData.Lyrics(
            syncedLyrics = "[00:01.00]Hello world",
            plainLyrics = "Hello world",
            isInstrumental = false,
        )
        val result = EnrichmentResult.Success(
            type = EnrichmentType.LYRICS_SYNCED,
            data = data,
            provider = "lrclib",
            confidence = 0.85f,
        )

        // When
        cache.put("track:789", EnrichmentType.LYRICS_SYNCED, result)
        val retrieved = cache.get("track:789", EnrichmentType.LYRICS_SYNCED)

        // Then
        assertNotNull(retrieved)
        assertEquals("lrclib", retrieved!!.provider)
        val lyricsData = retrieved.data as EnrichmentData.Lyrics
        assertEquals("[00:01.00]Hello world", lyricsData.syncedLyrics)
        assertEquals("Hello world", lyricsData.plainLyrics)
        assertFalse(lyricsData.isInstrumental)
    }

    @Test
    fun `stores and retrieves biography result`() = runTest {
        // Given
        val data = EnrichmentData.Biography(
            text = "A legendary band formed in 1976.",
            source = "wikipedia",
            language = "en",
            thumbnailUrl = "https://example.com/bio-thumb.jpg",
        )
        val result = EnrichmentResult.Success(
            type = EnrichmentType.ARTIST_BIO,
            data = data,
            provider = "wikipedia",
            confidence = 0.8f,
        )

        // When
        cache.put("artist:abc", EnrichmentType.ARTIST_BIO, result)
        val retrieved = cache.get("artist:abc", EnrichmentType.ARTIST_BIO)

        // Then
        assertNotNull(retrieved)
        assertEquals("wikipedia", retrieved!!.provider)
        val bioData = retrieved.data as EnrichmentData.Biography
        assertEquals("A legendary band formed in 1976.", bioData.text)
        assertEquals("wikipedia", bioData.source)
        assertEquals("en", bioData.language)
        assertEquals("https://example.com/bio-thumb.jpg", bioData.thumbnailUrl)
    }

    @Test
    fun `returns null for expired entry`() = runTest {
        // Given — cache with a controllable clock
        var now = 1_000_000L
        val clockCache = RoomEnrichmentCache(database.enrichmentCacheDao()) { now }

        val result = EnrichmentResult.Success(
            type = EnrichmentType.ALBUM_ART,
            data = EnrichmentData.Artwork(url = "https://example.com/art.jpg"),
            provider = "coverartarchive",
            confidence = 0.9f,
        )

        // Put at time=1_000_000 with TTL=5000ms (expires at 1_005_000)
        clockCache.put("album:exp", EnrichmentType.ALBUM_ART, result, ttlMs = 5_000L)

        // When — still valid
        val beforeExpiry = clockCache.get("album:exp", EnrichmentType.ALBUM_ART)

        // Advance clock past expiry
        now = 1_006_000L
        val afterExpiry = clockCache.get("album:exp", EnrichmentType.ALBUM_ART)

        // Then
        assertNotNull(beforeExpiry)
        assertNull(afterExpiry)
    }

    @Test
    fun `returns null for non-existent key`() = runTest {
        // When
        val result = cache.get("nonexistent:key", EnrichmentType.ALBUM_ART)

        // Then
        assertNull(result)
    }

    @Test
    fun `invalidate removes specific type`() = runTest {
        // Given — two different types for the same entity
        val artResult = EnrichmentResult.Success(
            type = EnrichmentType.ALBUM_ART,
            data = EnrichmentData.Artwork(url = "https://example.com/art.jpg"),
            provider = "coverartarchive",
            confidence = 0.9f,
        )
        val metaResult = EnrichmentResult.Success(
            type = EnrichmentType.GENRE,
            data = EnrichmentData.Metadata(genres = listOf("Rock")),
            provider = "musicbrainz",
            confidence = 0.85f,
        )
        cache.put("album:inv", EnrichmentType.ALBUM_ART, artResult)
        cache.put("album:inv", EnrichmentType.GENRE, metaResult)

        // When — invalidate only artwork
        cache.invalidate("album:inv", EnrichmentType.ALBUM_ART)

        // Then — artwork gone, metadata still present
        assertNull(cache.get("album:inv", EnrichmentType.ALBUM_ART))
        assertNotNull(cache.get("album:inv", EnrichmentType.GENRE))
    }

    @Test
    fun `invalidate with null type removes all types for entity`() = runTest {
        // Given
        val artResult = EnrichmentResult.Success(
            type = EnrichmentType.ALBUM_ART,
            data = EnrichmentData.Artwork(url = "https://example.com/art.jpg"),
            provider = "coverartarchive",
            confidence = 0.9f,
        )
        val metaResult = EnrichmentResult.Success(
            type = EnrichmentType.GENRE,
            data = EnrichmentData.Metadata(genres = listOf("Rock")),
            provider = "musicbrainz",
            confidence = 0.85f,
        )
        cache.put("album:all", EnrichmentType.ALBUM_ART, artResult)
        cache.put("album:all", EnrichmentType.GENRE, metaResult)

        // When — invalidate all types
        cache.invalidate("album:all", type = null)

        // Then — both gone
        assertNull(cache.get("album:all", EnrichmentType.ALBUM_ART))
        assertNull(cache.get("album:all", EnrichmentType.GENRE))
    }

    @Test
    fun `handles manual selection flag`() = runTest {
        // Given
        val result = EnrichmentResult.Success(
            type = EnrichmentType.ALBUM_ART,
            data = EnrichmentData.Artwork(url = "https://example.com/art.jpg"),
            provider = "coverartarchive",
            confidence = 0.9f,
        )
        cache.put("album:manual", EnrichmentType.ALBUM_ART, result)

        // When — initially not manual
        val beforeMark = cache.isManuallySelected("album:manual", EnrichmentType.ALBUM_ART)

        // Then
        assertFalse(beforeMark)

        // When — mark as manual
        cache.markManuallySelected("album:manual", EnrichmentType.ALBUM_ART)

        // Then
        assertTrue(cache.isManuallySelected("album:manual", EnrichmentType.ALBUM_ART))
    }

    @Test
    fun `clear removes all entries`() = runTest {
        // Given — entries for different entities
        val result1 = EnrichmentResult.Success(
            type = EnrichmentType.ALBUM_ART,
            data = EnrichmentData.Artwork(url = "https://example.com/a.jpg"),
            provider = "p1",
            confidence = 0.9f,
        )
        val result2 = EnrichmentResult.Success(
            type = EnrichmentType.ARTIST_BIO,
            data = EnrichmentData.Biography(text = "Bio", source = "wiki", language = "en"),
            provider = "p2",
            confidence = 0.8f,
        )
        cache.put("album:1", EnrichmentType.ALBUM_ART, result1)
        cache.put("artist:1", EnrichmentType.ARTIST_BIO, result2)

        // When
        cache.clear()

        // Then
        assertNull(cache.get("album:1", EnrichmentType.ALBUM_ART))
        assertNull(cache.get("artist:1", EnrichmentType.ARTIST_BIO))
    }

    @Test
    fun `concurrent writes are safe`() = runTest {
        // Given — launch many concurrent writes
        val jobs = (1..50).map { i ->
            async {
                val result = EnrichmentResult.Success(
                    type = EnrichmentType.ALBUM_ART,
                    data = EnrichmentData.Artwork(url = "https://example.com/$i.jpg"),
                    provider = "provider$i",
                    confidence = 0.9f,
                )
                cache.put("album:concurrent:$i", EnrichmentType.ALBUM_ART, result)
            }
        }

        // When — await all
        jobs.awaitAll()

        // Then — all entries are retrievable
        for (i in 1..50) {
            val retrieved = cache.get("album:concurrent:$i", EnrichmentType.ALBUM_ART)
            assertNotNull("Entry $i should exist", retrieved)
            val artworkData = retrieved!!.data as EnrichmentData.Artwork
            assertEquals("https://example.com/$i.jpg", artworkData.url)
        }
    }
}
