package com.landofoz.musicmeta.provider.lrclib

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LrcLibProviderTest {

    private lateinit var httpClient: FakeHttpClient
    private lateinit var provider: LrcLibProvider

    @Before
    fun setUp() {
        httpClient = FakeHttpClient()
        val rateLimiter = RateLimiter(intervalMs = 0)
        provider = LrcLibProvider(httpClient, rateLimiter)
    }

    @Test
    fun `enrich returns synced lyrics when available`() = runTest {
        // Given
        httpClient.givenJsonResponse("/api/get", SYNCED_LYRICS_JSON)
        val request = EnrichmentRequest.forTrack(
            title = "Creep",
            artist = "Radiohead",
            album = "Pablo Honey",
            durationMs = 238_000L,
        )

        // When
        val result = provider.enrich(request, EnrichmentType.LYRICS_SYNCED)

        // Then
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        val lyrics = success.data as EnrichmentData.Lyrics
        assertEquals("[00:00.00] When you were here before\n[00:04.50] Couldn't look you in the eye", lyrics.syncedLyrics)
        assertEquals("When you were here before\nCouldn't look you in the eye", lyrics.plainLyrics)
        assertEquals(false, lyrics.isInstrumental)
        assertEquals(0.95f, success.confidence)
    }

    @Test
    fun `enrich returns plain lyrics when no synced available`() = runTest {
        // Given
        httpClient.givenJsonResponse("/api/get", PLAIN_ONLY_LYRICS_JSON)
        val request = EnrichmentRequest.forTrack(title = "Some Song", artist = "Some Artist")

        // When
        val result = provider.enrich(request, EnrichmentType.LYRICS_PLAIN)

        // Then
        assertTrue(result is EnrichmentResult.Success)
        val lyrics = (result as EnrichmentResult.Success).data as EnrichmentData.Lyrics
        assertNull(lyrics.syncedLyrics)
        assertEquals("Just plain lyrics here", lyrics.plainLyrics)
    }

    @Test
    fun `enrich returns instrumental flag`() = runTest {
        // Given
        httpClient.givenJsonResponse("/api/get", INSTRUMENTAL_JSON)
        val request = EnrichmentRequest.forTrack(
            title = "Treefingers",
            artist = "Radiohead",
            album = "Kid A",
        )

        // When
        val result = provider.enrich(request, EnrichmentType.LYRICS_SYNCED)

        // Then
        assertTrue(result is EnrichmentResult.Success)
        val lyrics = (result as EnrichmentResult.Success).data as EnrichmentData.Lyrics
        assertTrue(lyrics.isInstrumental)
    }

    @Test
    fun `enrich falls back to search when exact match fails`() = runTest {
        // Given - no exact match response, but search returns results
        httpClient.givenJsonArrayResponse("/api/search", SEARCH_RESULTS_JSON)
        val request = EnrichmentRequest.forTrack(title = "Creep", artist = "Radiohead")

        // When
        val result = provider.enrich(request, EnrichmentType.LYRICS_SYNCED)

        // Then
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertEquals(0.6f, success.confidence)
        val lyrics = success.data as EnrichmentData.Lyrics
        assertEquals("[00:00.00] When you were here before", lyrics.syncedLyrics)
    }

    @Test
    fun `enrich returns NotFound for album requests`() = runTest {
        // Given
        val request = EnrichmentRequest.forAlbum(title = "Pablo Honey", artist = "Radiohead")

        // When
        val result = provider.enrich(request, EnrichmentType.LYRICS_SYNCED)

        // Then
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound when no results`() = runTest {
        // Given - no responses configured (both exact and search return null)
        httpClient.givenJsonArrayResponse("/api/search", "[]")
        val request = EnrichmentRequest.forTrack(title = "Nonexistent", artist = "Nobody")

        // When
        val result = provider.enrich(request, EnrichmentType.LYRICS_SYNCED)

        // Then
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich uses album and duration for exact match when available`() = runTest {
        // Given
        httpClient.givenJsonResponse("/api/get", SYNCED_LYRICS_JSON)
        val request = EnrichmentRequest.forTrack(
            title = "Creep",
            artist = "Radiohead",
            album = "Pablo Honey",
            durationMs = 238_000L,
        )

        // When
        provider.enrich(request, EnrichmentType.LYRICS_SYNCED)

        // Then - verify the URL contains album and duration params
        val url = httpClient.requestedUrls.first()
        assertTrue("URL should contain album_name", url.contains("album_name="))
        assertTrue("URL should contain duration", url.contains("duration=238.0"))
    }

    @Test
    fun `duration is passed as float preserving fractional seconds`() = runTest {
        // Given - a track with durationMs that has fractional seconds (238.5s)
        httpClient.givenJsonResponse("/api/get", SYNCED_LYRICS_JSON)
        val request = EnrichmentRequest.forTrack(
            title = "Creep",
            artist = "Radiohead",
            album = "Pablo Honey",
            durationMs = 238_500L,
        )

        // When
        provider.enrich(request, EnrichmentType.LYRICS_SYNCED)

        // Then - URL should contain duration=238.5 (not duration=238)
        val url = httpClient.requestedUrls.first()
        assertTrue("URL should contain duration=238.5, was: $url", url.contains("duration=238.5"))
    }

    @Test
    fun `duration with exact milliseconds is passed as float`() = runTest {
        // Given - a track with durationMs that is exactly 180 seconds
        httpClient.givenJsonResponse("/api/get", SYNCED_LYRICS_JSON)
        val request = EnrichmentRequest.forTrack(
            title = "Creep",
            artist = "Radiohead",
            album = "Pablo Honey",
            durationMs = 180_000L,
        )

        // When
        provider.enrich(request, EnrichmentType.LYRICS_SYNCED)

        // Then - URL should contain duration=180.0 (float format)
        val url = httpClient.requestedUrls.first()
        assertTrue("URL should contain duration=180.0, was: $url", url.contains("duration=180.0"))
    }

    @Test
    fun `enrich returns NotFound when both syncedLyrics and plainLyrics are null and not instrumental`() = runTest {
        // Given — LrcLib returns a track with null lyrics and instrumental=false
        httpClient.givenJsonResponse("/api/get", """{
            "id": 999,
            "trackName": "Mystery Track",
            "artistName": "Unknown",
            "albumName": null,
            "duration": 180.0,
            "instrumental": false,
            "syncedLyrics": null,
            "plainLyrics": null
        }""")
        val request = EnrichmentRequest.forTrack(
            title = "Mystery Track",
            artist = "Unknown",
        )

        // When — enriching for synced lyrics
        val result = provider.enrich(request, EnrichmentType.LYRICS_SYNCED)

        // Then — NotFound because both lyrics are null and track is not instrumental
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns Error with NETWORK ErrorKind when API throws IOException`() = runTest {
        // Given — fetchJsonResult throws IOException simulating a network failure
        httpClient.givenIoException("/api/get")
        val request = EnrichmentRequest.forTrack(
            title = "Creep",
            artist = "Radiohead",
        )

        // When — enriching for lyrics
        val result = provider.enrich(request, EnrichmentType.LYRICS_SYNCED)

        // Then — Error with NETWORK ErrorKind
        assertTrue("Expected Error but got $result", result is EnrichmentResult.Error)
        assertEquals(ErrorKind.NETWORK, (result as EnrichmentResult.Error).errorKind)
    }

    @Test
    fun `enrich returns NotFound when plainLyrics is empty string`() = runTest {
        // Given — LrcLib returns a track with empty string plainLyrics and null syncedLyrics
        httpClient.givenJsonResponse("/api/get", """{
            "id": 888,
            "trackName": "Empty Song",
            "artistName": "Some Artist",
            "albumName": null,
            "duration": 120.0,
            "instrumental": false,
            "syncedLyrics": null,
            "plainLyrics": ""
        }""")
        val request = EnrichmentRequest.forTrack(
            title = "Empty Song",
            artist = "Some Artist",
        )

        // When — enriching for plain lyrics
        val result = provider.enrich(request, EnrichmentType.LYRICS_PLAIN)

        // Then — NotFound because plainLyrics is blank after takeIf check
        assertTrue(result is EnrichmentResult.NotFound)
    }

    companion object {
        private val SYNCED_LYRICS_JSON = """
            {
                "id": 123,
                "trackName": "Creep",
                "artistName": "Radiohead",
                "albumName": "Pablo Honey",
                "duration": 238.0,
                "instrumental": false,
                "syncedLyrics": "[00:00.00] When you were here before\n[00:04.50] Couldn't look you in the eye",
                "plainLyrics": "When you were here before\nCouldn't look you in the eye"
            }
        """.trimIndent()

        private val PLAIN_ONLY_LYRICS_JSON = """
            {
                "id": 789,
                "trackName": "Some Song",
                "artistName": "Some Artist",
                "albumName": null,
                "duration": 200.0,
                "instrumental": false,
                "syncedLyrics": null,
                "plainLyrics": "Just plain lyrics here"
            }
        """.trimIndent()

        private val INSTRUMENTAL_JSON = """
            {
                "id": 456,
                "trackName": "Treefingers",
                "artistName": "Radiohead",
                "albumName": "Kid A",
                "duration": 223.0,
                "instrumental": true,
                "syncedLyrics": null,
                "plainLyrics": ""
            }
        """.trimIndent()

        private val SEARCH_RESULTS_JSON = """
            [
                {
                    "id": 123,
                    "trackName": "Creep",
                    "artistName": "Radiohead",
                    "albumName": "Pablo Honey",
                    "duration": 238.0,
                    "instrumental": false,
                    "syncedLyrics": "[00:00.00] When you were here before",
                    "plainLyrics": "When you were here before"
                }
            ]
        """.trimIndent()
    }
}
