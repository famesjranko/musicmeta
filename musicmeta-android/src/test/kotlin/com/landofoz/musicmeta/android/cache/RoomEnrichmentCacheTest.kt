package com.landofoz.musicmeta.android.cache

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.LookupProvenance
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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

    /** Matches [RoomEnrichmentCache]'s own config, so a row built here decodes the way one it wrote would. */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

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

    @Test
    fun `stores and retrieves artwork result`() = runTest {
        // Given - an artwork success result
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

        // When - putting then getting it from the cache
        cache.put("album:123", EnrichmentType.ALBUM_ART, result, CanonicalStatus.RESOLVED)
        val retrieved = cache.get("album:123", EnrichmentType.ALBUM_ART)

        // Then - the retrieved result matches what was stored
        assertNotNull(retrieved)
        assertEquals("coverartarchive", retrieved!!.result.provider)
        assertEquals(0.95f, retrieved.result.confidence)
        val artworkData = retrieved.result.data as EnrichmentData.Artwork
        assertEquals("https://example.com/art.jpg", artworkData.url)
        assertEquals("https://example.com/thumb.jpg", artworkData.thumbnailUrl)
        assertEquals(600, artworkData.width)
        assertEquals(600, artworkData.height)
    }

    @Test
    fun `stores and retrieves metadata result`() = runTest {
        // Given - a metadata success result
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

        // When - putting then getting it from the cache
        cache.put("album:456", EnrichmentType.GENRE, result, CanonicalStatus.RESOLVED)
        val retrieved = cache.get("album:456", EnrichmentType.GENRE)

        // Then - the retrieved result matches what was stored
        assertNotNull(retrieved)
        assertEquals("musicbrainz", retrieved!!.result.provider)
        val metaData = retrieved.result.data as EnrichmentData.Metadata
        assertEquals(listOf("Rock", "Alternative"), metaData.genres)
        assertEquals("Island Records", metaData.label)
        assertEquals("1991-09-24", metaData.releaseDate)
        assertEquals("Album", metaData.releaseType)
        assertEquals("US", metaData.country)
    }

    @Test
    fun `stores and retrieves lyrics result`() = runTest {
        // Given - a lyrics success result
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

        // When - putting then getting it from the cache
        cache.put("track:789", EnrichmentType.LYRICS_SYNCED, result, CanonicalStatus.RESOLVED)
        val retrieved = cache.get("track:789", EnrichmentType.LYRICS_SYNCED)

        // Then - the retrieved result matches what was stored
        assertNotNull(retrieved)
        assertEquals("lrclib", retrieved!!.result.provider)
        val lyricsData = retrieved.result.data as EnrichmentData.Lyrics
        assertEquals("[00:01.00]Hello world", lyricsData.syncedLyrics)
        assertEquals("Hello world", lyricsData.plainLyrics)
        assertFalse(lyricsData.isInstrumental)
    }

    @Test
    fun `stores and retrieves biography result`() = runTest {
        // Given - a biography success result
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

        // When - putting then getting it from the cache
        cache.put("artist:abc", EnrichmentType.ARTIST_BIO, result, CanonicalStatus.RESOLVED)
        val retrieved = cache.get("artist:abc", EnrichmentType.ARTIST_BIO)

        // Then - the retrieved result matches what was stored
        assertNotNull(retrieved)
        assertEquals("wikipedia", retrieved!!.result.provider)
        val bioData = retrieved.result.data as EnrichmentData.Biography
        assertEquals("A legendary band formed in 1976.", bioData.text)
        assertEquals("wikipedia", bioData.source)
        assertEquals("en", bioData.language)
        assertEquals("https://example.com/bio-thumb.jpg", bioData.thumbnailUrl)
    }

    @Test
    fun `returns null for expired entry`() = runTest {
        // Given - cache with a controllable clock
        var now = 1_000_000L
        val clockCache = RoomEnrichmentCache(
            database.enrichmentCacheDao(), database.negativeCacheDao(), database.selectionDao(), clock = { now },
        )

        val result = EnrichmentResult.Success(
            type = EnrichmentType.ALBUM_ART,
            data = EnrichmentData.Artwork(url = "https://example.com/art.jpg"),
            provider = "coverartarchive",
            confidence = 0.9f,
        )

        // Put at time=1_000_000 with TTL=5000ms (expires at 1_005_000)
        clockCache.put("album:exp", EnrichmentType.ALBUM_ART, result, CanonicalStatus.RESOLVED, ttlMs = 5_000L)

        // When - still valid
        val beforeExpiry = clockCache.get("album:exp", EnrichmentType.ALBUM_ART)

        // Advance clock past expiry
        now = 1_006_000L
        val afterExpiry = clockCache.get("album:exp", EnrichmentType.ALBUM_ART)

        // Then - valid before expiry, null after
        assertNotNull(beforeExpiry)
        assertNull(afterExpiry)
    }

    @Test
    fun `returns null for non-existent key`() = runTest {
        // Given - an empty cache
        // When - getting a key that was never put
        val result = cache.get("nonexistent:key", EnrichmentType.ALBUM_ART)

        // Then - null is returned
        assertNull(result)
    }

    @Test
    fun `invalidate removes specific type`() = runTest {
        // Given - two different types for the same entity
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
        cache.put("album:inv", EnrichmentType.ALBUM_ART, artResult, CanonicalStatus.RESOLVED)
        cache.put("album:inv", EnrichmentType.GENRE, metaResult, CanonicalStatus.RESOLVED)

        // When - invalidate only artwork
        cache.invalidate("album:inv", EnrichmentType.ALBUM_ART)

        // Then - artwork gone, metadata still present
        assertNull(cache.get("album:inv", EnrichmentType.ALBUM_ART))
        assertNotNull(cache.get("album:inv", EnrichmentType.GENRE))
    }

    @Test
    fun `invalidate with null type removes all types for entity`() = runTest {
        // Given - two different types for the same entity
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
        cache.put("album:all", EnrichmentType.ALBUM_ART, artResult, CanonicalStatus.RESOLVED)
        cache.put("album:all", EnrichmentType.GENRE, metaResult, CanonicalStatus.RESOLVED)

        // When - invalidate all types
        cache.invalidate("album:all", type = null)

        // Then - both gone
        assertNull(cache.get("album:all", EnrichmentType.ALBUM_ART))
        assertNull(cache.get("album:all", EnrichmentType.GENRE))
    }

    @Test
    fun `negative entries round-trip without re-asking`() = runTest {
        // Given - a NotFound for a type nothing answered
        val notFound = EnrichmentResult.NotFound(
            type = EnrichmentType.ALBUM_ART,
            provider = "all_providers",
        )

        // When - putting then getting the negative from the cache
        cache.putNegative("album:neg", EnrichmentType.ALBUM_ART, notFound, CanonicalStatus.RESOLVED, ttlMs = 5_000L)
        val retrieved = cache.getNegative("album:neg", EnrichmentType.ALBUM_ART)

        // Then - the retrieved negative matches what was stored
        assertNotNull(retrieved)
        assertEquals("all_providers", retrieved!!.result.provider)
    }

    @Test
    fun `returns null for an expired negative entry`() = runTest {
        // Given - a cache with a controllable clock, and a negative put with a short TTL
        var now = 1_000_000L
        val clockCache = RoomEnrichmentCache(
            database.enrichmentCacheDao(), database.negativeCacheDao(), database.selectionDao(), clock = { now },
        )
        val notFound = EnrichmentResult.NotFound(type = EnrichmentType.ALBUM_ART, provider = "p")
        clockCache.putNegative("album:neg-exp", EnrichmentType.ALBUM_ART, notFound, CanonicalStatus.RESOLVED, ttlMs = 5_000L)

        // When - still valid, then after the clock advances past expiry
        val beforeExpiry = clockCache.getNegative("album:neg-exp", EnrichmentType.ALBUM_ART)
        now = 1_006_000L
        val afterExpiry = clockCache.getNegative("album:neg-exp", EnrichmentType.ALBUM_ART)

        // Then - valid before expiry, null after — there is no expired-negative read path
        assertNotNull(beforeExpiry)
        assertNull(afterExpiry)
    }

    @Test
    fun `invalidate clears a negative entry`() = runTest {
        // Given - a negative and a positive cached for different types of the same entity
        val notFound = EnrichmentResult.NotFound(type = EnrichmentType.ALBUM_ART, provider = "p")
        val genreResult = EnrichmentResult.Success(
            type = EnrichmentType.GENRE,
            data = EnrichmentData.Metadata(genres = listOf("Rock")),
            provider = "musicbrainz",
            confidence = 0.85f,
        )
        cache.putNegative("album:neg-inv", EnrichmentType.ALBUM_ART, notFound, CanonicalStatus.RESOLVED, ttlMs = 5_000L)
        cache.put("album:neg-inv", EnrichmentType.GENRE, genreResult, CanonicalStatus.RESOLVED)

        // When - invalidating only the negative's type
        cache.invalidate("album:neg-inv", EnrichmentType.ALBUM_ART)

        // Then - the negative is gone, the unrelated positive entry is untouched
        assertNull(cache.getNegative("album:neg-inv", EnrichmentType.ALBUM_ART))
        assertNotNull(cache.get("album:neg-inv", EnrichmentType.GENRE))
    }

    @Test
    fun `clear removes negative entries too`() = runTest {
        // Given - a negative cached for one entity
        val notFound = EnrichmentResult.NotFound(type = EnrichmentType.ALBUM_ART, provider = "p")
        cache.putNegative("album:neg-clear", EnrichmentType.ALBUM_ART, notFound, CanonicalStatus.RESOLVED, ttlMs = 5_000L)

        // When - clearing the cache
        cache.clear()

        // Then - the negative entry is gone
        assertNull(cache.getNegative("album:neg-clear", EnrichmentType.ALBUM_ART))
    }

    @Test
    fun `handles manual selection flag`() = runTest {
        // Given - a cached artwork result
        val result = EnrichmentResult.Success(
            type = EnrichmentType.ALBUM_ART,
            data = EnrichmentData.Artwork(url = "https://example.com/art.jpg"),
            provider = "coverartarchive",
            confidence = 0.9f,
        )
        cache.put("album:manual", EnrichmentType.ALBUM_ART, result, CanonicalStatus.RESOLVED)

        // When - initially not manual
        val beforeMark = cache.isManuallySelected("album:manual", EnrichmentType.ALBUM_ART)

        // Then - manual selection defaults to false
        assertFalse(beforeMark)

        // When - mark as manual
        cache.markManuallySelected("album:manual", EnrichmentType.ALBUM_ART)

        // Then - manual selection is now true
        assertTrue(cache.isManuallySelected("album:manual", EnrichmentType.ALBUM_ART))

        // When - refreshing that entry normally
        cache.put("album:manual", EnrichmentType.ALBUM_ART, result, CanonicalStatus.RESOLVED)

        // Then - an ordinary write preserves the user's selection
        assertTrue(cache.isManuallySelected("album:manual", EnrichmentType.ALBUM_ART))

        // When - explicitly invalidating that entry
        cache.invalidate("album:manual", EnrichmentType.ALBUM_ART)

        // Then - its manual-selection state is removed with it
        assertFalse(cache.isManuallySelected("album:manual", EnrichmentType.ALBUM_ART))
    }

    @Test
    fun `clear removes all entries`() = runTest {
        // Given - entries for different entities
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
        cache.put("album:1", EnrichmentType.ALBUM_ART, result1, CanonicalStatus.RESOLVED)
        cache.put("artist:1", EnrichmentType.ARTIST_BIO, result2, CanonicalStatus.RESOLVED)

        // When - clearing the cache
        cache.clear()

        // Then - no entries remain
        assertNull(cache.get("album:1", EnrichmentType.ALBUM_ART))
        assertNull(cache.get("artist:1", EnrichmentType.ARTIST_BIO))
    }

    @Test
    fun `stores and retrieves identity fields`() = runTest {
        // Given - result with identity resolution data
        val ids = EnrichmentIdentifiers(
            musicBrainzId = "a74b1b7f-71a5-4011-9441-d0b5e4122711",
            wikidataId = "Q188451",
            wikipediaTitle = "Radiohead",
        )
        val result = EnrichmentResult.Success(
            type = EnrichmentType.GENRE,
            data = EnrichmentData.Metadata(genres = listOf("Rock")),
            provider = "musicbrainz",
            confidence = 0.9f,
            resolvedIdentifiers = ids,
            provenance = LookupProvenance.CANONICAL_ID,
        )

        // When - putting then getting it from the cache
        cache.put("artist:radiohead", EnrichmentType.GENRE, result, CanonicalStatus.RESOLVED)
        val retrieved = cache.get("artist:radiohead", EnrichmentType.GENRE)

        // Then - identity fields and the written canonicalStatus round-tripped
        assertNotNull(retrieved)
        assertEquals(LookupProvenance.CANONICAL_ID, retrieved!!.result.provenance)
        assertEquals(CanonicalStatus.RESOLVED, retrieved.canonicalStatus)
        assertNotNull(retrieved.result.resolvedIdentifiers)
        assertEquals("a74b1b7f-71a5-4011-9441-d0b5e4122711", retrieved.result.resolvedIdentifiers!!.musicBrainzId)
        assertEquals("Q188451", retrieved.result.resolvedIdentifiers!!.wikidataId)
        assertEquals("Radiohead", retrieved.result.resolvedIdentifiers!!.wikipediaTitle)
    }

    @Test
    fun `getNegative reads back the canonicalStatus putNegative wrote, closing the write-only gap`() = runTest {
        // Given - a negative entry stored under a specific canonical status
        val notFound = EnrichmentResult.NotFound(type = EnrichmentType.ALBUM_ART, provider = "p")
        cache.putNegative("album:neg-status", EnrichmentType.ALBUM_ART, notFound, CanonicalStatus.NOT_ATTEMPTED_IDENTIFIER_TRUSTED, ttlMs = 60_000L)

        // When - reading the same key back
        val retrieved = cache.getNegative("album:neg-status", EnrichmentType.ALBUM_ART)

        // Then - the envelope carries the exact status it was written under
        assertNotNull(retrieved)
        assertEquals(CanonicalStatus.NOT_ATTEMPTED_IDENTIFIER_TRUSTED, retrieved!!.canonicalStatus)
    }

    @Test
    fun `stores and retrieves result without identity fields`() = runTest {
        // Given - result with no identity data (e.g., MBID pre-provided)
        val result = EnrichmentResult.Success(
            type = EnrichmentType.ALBUM_ART,
            data = EnrichmentData.Artwork(url = "https://example.com/art.jpg"),
            provider = "coverartarchive",
            confidence = 0.95f,
        )

        // When - putting then getting it from the cache
        cache.put("album:no-id", EnrichmentType.ALBUM_ART, result, CanonicalStatus.RESOLVED)
        val retrieved = cache.get("album:no-id", EnrichmentType.ALBUM_ART)

        // Then - no provenance was recorded at write time, so the reader reports CACHE rather than
        // guessing at what the original live lookup used
        assertNotNull(retrieved)
        assertEquals(LookupProvenance.CACHE, retrieved!!.result.provenance)
        assertNull(retrieved.result.resolvedIdentifiers)
    }

    @Test
    fun `a stored canonical status this version cannot name degrades to NOT_ATTEMPTED_CACHE_HIT`() = runTest {
        // Given - a row written by a version whose CanonicalStatus name this one does not have
        database.enrichmentCacheDao().insert(
            EnrichmentCacheEntity(
                entityKey = "album:unknown-status",
                enrichmentType = EnrichmentType.ALBUM_ART.name,
                provider = "coverartarchive",
                dataJson = json.encodeToString(
                    EnrichmentData.serializer(),
                    EnrichmentData.Artwork(url = "https://example.com/art.jpg"),
                ),
                confidence = 0.95f,
                canonicalStatus = "RESOLVED_BY_A_LATER_VERSION",
                cachedAt = 0L,
                expiresAt = Long.MAX_VALUE,
            ),
        )

        // When - reading it back
        val retrieved = cache.get("album:unknown-status", EnrichmentType.ALBUM_ART)

        // Then - the row still reads, under the weakest status a written row can carry
        assertNotNull(retrieved)
        assertEquals(CanonicalStatus.NOT_ATTEMPTED_CACHE_HIT, retrieved!!.canonicalStatus)
    }

    /**
     * The wire form 0.12.x wrote for `ARTIST_POPULARITY`, when `Popularity` still had a `topTracks`
     * list. Held as a literal because a payload a consumer already persisted is what this pins.
     */
    private val popularityJsonWithTopTracks = """
        {"type":"com.landofoz.musicmeta.EnrichmentData.Popularity","listenCount":900,"listenerCount":42,"rank":3,"topTracks":[{"title":"Creep","identifiers":{"musicBrainzId":"abc"},"listenCount":50000,"rank":1}],"signals":[]}
    """.trimIndent()

    @Test
    fun `a popularity row cached with topTracks still reads, the removed key being unknown`() = runTest {
        // Given - a row written by a version whose Popularity still carried topTracks
        database.enrichmentCacheDao().insert(
            EnrichmentCacheEntity(
                entityKey = "artist:top-tracks-key",
                enrichmentType = EnrichmentType.ARTIST_POPULARITY.name,
                provider = "listenbrainz",
                dataJson = popularityJsonWithTopTracks,
                confidence = 0.95f,
                canonicalStatus = CanonicalStatus.RESOLVED.name,
                cachedAt = 0L,
                expiresAt = Long.MAX_VALUE,
            ),
        )

        // When - reading it back through the cache's own decoder
        val retrieved = cache.get("artist:top-tracks-key", EnrichmentType.ARTIST_POPULARITY)

        // Then - a hit, not a miss: the flat fields survive and the removed key is ignored
        assertNotNull(retrieved)
        val popularity = retrieved!!.result.data as EnrichmentData.Popularity
        assertEquals(900L, popularity.listenCount)
        assertEquals(42L, popularity.listenerCount)
        assertEquals(3, popularity.rank)
    }

    /**
     * The wire form 0.12.x wrote when only ListenBrainz's top-recordings route answered
     * `ARTIST_POPULARITY`: every flat field null, the tracks in `topTracks`.
     */
    private val popularityJsonTopTracksOnly = """
        {"type":"com.landofoz.musicmeta.EnrichmentData.Popularity","listenCount":null,"listenerCount":null,"rank":null,"topTracks":[{"title":"Creep","identifiers":{"musicBrainzId":"abc"},"listenCount":50000,"rank":1}],"signals":[]}
    """.trimIndent()

    @Test
    fun `a popularity row holding only topTracks decodes to a payload carrying nothing`() = runTest {
        // Given - a row whose whole content was the removed key
        database.enrichmentCacheDao().insert(
            EnrichmentCacheEntity(
                entityKey = "artist:top-tracks-only",
                enrichmentType = EnrichmentType.ARTIST_POPULARITY.name,
                provider = "listenbrainz",
                dataJson = popularityJsonTopTracksOnly,
                confidence = 0.95f,
                canonicalStatus = CanonicalStatus.RESOLVED.name,
                cachedAt = 0L,
                expiresAt = Long.MAX_VALUE,
            ),
        )

        // When - reading it back through the cache's own decoder
        val retrieved = cache.get("artist:top-tracks-only", EnrichmentType.ARTIST_POPULARITY)

        // Then - it decodes rather than throwing, into an empty Popularity, which the engine's
        // cache read discards as answering nothing
        assertNotNull(retrieved)
        val popularity = retrieved!!.result.data as EnrichmentData.Popularity
        assertNull(popularity.listenCount)
        assertNull(popularity.listenerCount)
        assertNull(popularity.rank)
        assertTrue(popularity.signals.isEmpty())
    }

    // synthetic - a JSON literal shaped like the wire form a persisted track preview carries under
    // `encodeDefaults = true`, not a live capture.
    private val storedPreviewJson =
        """{"type":"com.landofoz.musicmeta.EnrichmentData.TrackPreview",""" +
            """"url":"https://example.com/p.mp3","durationMs":30000,"source":"deezer"}"""

    // synthetic - the same wire form from a source that states no clip length.
    private val storedPreviewWithoutDurationJson =
        """{"type":"com.landofoz.musicmeta.EnrichmentData.TrackPreview",""" +
            """"url":"https://example.com/p.mp3","source":"other"}"""

    private fun previewRow(entityKey: String, dataJson: String) = EnrichmentCacheEntity(
        entityKey = entityKey,
        enrichmentType = EnrichmentType.TRACK_PREVIEW.name,
        provider = "deezer",
        dataJson = dataJson,
        confidence = 0.9f,
        canonicalStatus = CanonicalStatus.RESOLVED.name,
        cachedAt = 0L,
        expiresAt = Long.MAX_VALUE,
    )

    @Test
    fun `a stored track preview payload keeps the clip length it was written with`() = runTest {
        // Given - a row carrying a track preview payload an earlier build already persisted
        database.enrichmentCacheDao().insert(previewRow("track:stored-duration", storedPreviewJson))

        // When - reading it back through the cache
        val retrieved = cache.get("track:stored-duration", EnrichmentType.TRACK_PREVIEW)

        // Then - the payload decodes and reports the clip length the row stores
        assertNotNull(retrieved)
        val preview = retrieved!!.result.data as EnrichmentData.TrackPreview
        assertEquals("https://example.com/p.mp3", preview.url)
        assertEquals(30_000L, preview.durationMs)
        assertEquals("deezer", preview.source)
    }

    @Test
    fun `a stored track preview payload with no clip length reads back as null`() = runTest {
        // Given - a row whose payload states no clip length at all
        database.enrichmentCacheDao().insert(
            previewRow("track:no-duration", storedPreviewWithoutDurationJson),
        )

        // When - reading it back through the cache
        val retrieved = cache.get("track:no-duration", EnrichmentType.TRACK_PREVIEW)

        // Then - the absence survives instead of being filled in with a length nobody reported
        assertNotNull(retrieved)
        val preview = retrieved!!.result.data as EnrichmentData.TrackPreview
        assertNull(preview.durationMs)
    }

    @Test
    fun `a negative row's unnameable canonical status degrades the same way`() = runTest {
        // Given - a negative row carrying a CanonicalStatus name this version does not have
        database.negativeCacheDao().insert(
            NegativeCacheEntity(
                entityKey = "album:unknown-negative-status",
                enrichmentType = EnrichmentType.ALBUM_ART.name,
                provider = "coverartarchive",
                canonicalStatus = "RESOLVED_BY_A_LATER_VERSION",
                cachedAt = 0L,
                expiresAt = Long.MAX_VALUE,
            ),
        )

        // When - reading it back
        val retrieved = cache.getNegative("album:unknown-negative-status", EnrichmentType.ALBUM_ART)

        // Then - the negative still reads, under the same degraded status as a positive row
        assertNotNull(retrieved)
        assertEquals(CanonicalStatus.NOT_ATTEMPTED_CACHE_HIT, retrieved!!.canonicalStatus)
    }

    @Test
    fun `concurrent writes are safe`() = runTest {
        // Given - launch many concurrent writes
        val jobs = (1..50).map { i ->
            async {
                val result = EnrichmentResult.Success(
                    type = EnrichmentType.ALBUM_ART,
                    data = EnrichmentData.Artwork(url = "https://example.com/$i.jpg"),
                    provider = "provider$i",
                    confidence = 0.9f,
                )
                cache.put("album:concurrent:$i", EnrichmentType.ALBUM_ART, result, CanonicalStatus.RESOLVED)
            }
        }

        // When - await all
        jobs.awaitAll()

        // Then - all entries are retrievable
        for (i in 1..50) {
            val retrieved = cache.get("album:concurrent:$i", EnrichmentType.ALBUM_ART)
            assertNotNull("Entry $i should exist", retrieved)
            val artworkData = retrieved!!.result.data as EnrichmentData.Artwork
            assertEquals("https://example.com/$i.jpg", artworkData.url)
        }
    }
}
