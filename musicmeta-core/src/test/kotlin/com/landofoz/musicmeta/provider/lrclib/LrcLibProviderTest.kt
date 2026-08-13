package com.landofoz.musicmeta.provider.lrclib

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.engine.ProviderCallScope
import com.landofoz.musicmeta.engine.answers
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // Given - the API returns a track with both synced and plain lyrics
        httpClient.givenJsonResponse("/api/get", SYNCED_LYRICS_JSON)
        val request = EnrichmentRequest.forTrack(
            title = "Creep",
            artist = "Radiohead",
            album = "Pablo Honey",
            durationMs = 238_000L,
        )

        // When - enriching for synced lyrics
        val result = provider.enrich(request, EnrichmentType.LYRICS_SYNCED)

        // Then - a Success with the synced lyrics, plain lyrics, and confidence
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
        // Given - the API returns a track with only plain lyrics, no synced lyrics
        httpClient.givenJsonResponse("/api/get", PLAIN_ONLY_LYRICS_JSON)
        val request = EnrichmentRequest.forTrack(title = "Some Song", artist = "Some Artist")

        // When - enriching for plain lyrics
        val result = provider.enrich(request, EnrichmentType.LYRICS_PLAIN)

        // Then - a Success with null synced lyrics and the plain lyrics
        assertTrue(result is EnrichmentResult.Success)
        val lyrics = (result as EnrichmentResult.Success).data as EnrichmentData.Lyrics
        assertNull(lyrics.syncedLyrics)
        assertEquals("Just plain lyrics here", lyrics.plainLyrics)
    }

    @Test
    fun `enrich returns instrumental flag`() = runTest {
        // Given - the API returns a track marked instrumental
        httpClient.givenJsonResponse("/api/get", INSTRUMENTAL_JSON)
        val request = EnrichmentRequest.forTrack(
            title = "Treefingers",
            artist = "Radiohead",
            album = "Kid A",
        )

        // When - enriching for synced lyrics
        val result = provider.enrich(request, EnrichmentType.LYRICS_SYNCED)

        // Then - a Success whose lyrics are flagged instrumental
        assertTrue(result is EnrichmentResult.Success)
        val lyrics = (result as EnrichmentResult.Success).data as EnrichmentData.Lyrics
        assertTrue(lyrics.isInstrumental)
    }

    @Test
    fun `enrich falls back to search when exact match fails`() = runTest {
        // Given - no exact match response, but search returns results
        httpClient.givenJsonArrayResponse("/api/search", SEARCH_RESULTS_JSON)
        val request = EnrichmentRequest.forTrack(title = "Creep", artist = "Radiohead")

        // When - enriching for synced lyrics
        val result = provider.enrich(request, EnrichmentType.LYRICS_SYNCED)

        // Then - a Success sourced from the search fallback, at the accepted-artist confidence
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertEquals(0.8f, success.confidence)
        val lyrics = success.data as EnrichmentData.Lyrics
        assertEquals("[00:00.00] When you were here before", lyrics.syncedLyrics)
    }

    @Test
    fun `enrich returns NotFound for album requests`() = runTest {
        // Given - an album-level enrichment request, which LrcLib does not support
        val request = EnrichmentRequest.forAlbum(title = "Pablo Honey", artist = "Radiohead")

        // When - enriching for synced lyrics
        val result = provider.enrich(request, EnrichmentType.LYRICS_SYNCED)

        // Then - NotFound because LrcLib only handles track-level requests
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound when no results`() = runTest {
        // Given - no responses configured (both exact and search return null)
        httpClient.givenJsonArrayResponse("/api/search", "[]")
        val request = EnrichmentRequest.forTrack(title = "Nonexistent", artist = "Nobody")

        // When - enriching for synced lyrics
        val result = provider.enrich(request, EnrichmentType.LYRICS_SYNCED)

        // Then - NotFound because neither exact match nor search returned a result
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich uses album and duration for exact match when available`() = runTest {
        // Given - a track request with album and duration set
        httpClient.givenJsonResponse("/api/get", SYNCED_LYRICS_JSON)
        val request = EnrichmentRequest.forTrack(
            title = "Creep",
            artist = "Radiohead",
            album = "Pablo Honey",
            durationMs = 238_000L,
        )

        // When - enriching for synced lyrics
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

        // When - enriching for synced lyrics
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

        // When - enriching for synced lyrics
        provider.enrich(request, EnrichmentType.LYRICS_SYNCED)

        // Then - URL should contain duration=180.0 (float format)
        val url = httpClient.requestedUrls.first()
        assertTrue("URL should contain duration=180.0, was: $url", url.contains("duration=180.0"))
    }

    @Test
    fun `enrich returns NotFound when both syncedLyrics and plainLyrics are null and not instrumental`() = runTest {
        // Given - LrcLib returns a track with null lyrics and instrumental=false
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

        // When - enriching for synced lyrics
        val result = provider.enrich(request, EnrichmentType.LYRICS_SYNCED)

        // Then - NotFound because both lyrics are null and track is not instrumental
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns TrackMetadata with duration and album title for TRACK_METADATA`() = runTest {
        // Given - the API returns a track with duration and album populated
        httpClient.givenJsonResponse("/api/get", SYNCED_LYRICS_JSON)
        val request = EnrichmentRequest.forTrack(
            title = "Creep",
            artist = "Radiohead",
            album = "Pablo Honey",
            durationMs = 238_000L,
        )

        // When - enriching for track metadata
        val result = provider.enrich(request, EnrichmentType.TRACK_METADATA)

        // Then - a Success with the duration and album title populated
        assertTrue(result is EnrichmentResult.Success)
        val metadata = (result as EnrichmentResult.Success).data as EnrichmentData.TrackMetadata
        assertEquals(238000L, metadata.durationMs)
        assertEquals("Pablo Honey", metadata.albumTitle)
    }

    @Test
    fun `an empty TRACK_METADATA payload is a Success the engine's answers() gate demotes`() = runTest {
        // Given - LrcLib returns a result with no duration and no album
        httpClient.givenJsonResponse("/api/get", """{
            "id": 999,
            "trackName": "Mystery Track",
            "artistName": "Unknown",
            "albumName": null,
            "duration": null,
            "instrumental": false,
            "syncedLyrics": "some lyrics",
            "plainLyrics": null
        }""")
        val request = EnrichmentRequest.forTrack(title = "Mystery Track", artist = "Unknown")

        // When - enriching for track metadata
        val result = provider.enrich(request, EnrichmentType.TRACK_METADATA)

        // Then - the provider does not gate; PayloadAnswers owns empty-payload demotion
        val data = (result as EnrichmentResult.Success).data
        assertFalse(data.answers(EnrichmentType.TRACK_METADATA))
    }

    @Test
    fun `enrich returns Error with NETWORK ErrorKind when API throws IOException`() = runTest {
        // Given - fetchJsonResult throws IOException simulating a network failure
        httpClient.givenIoException("/api/get")
        val request = EnrichmentRequest.forTrack(
            title = "Creep",
            artist = "Radiohead",
        )

        // When - enriching for lyrics
        val result = provider.enrich(request, EnrichmentType.LYRICS_SYNCED)

        // Then - Error with NETWORK ErrorKind
        assertTrue("Expected Error but got $result", result is EnrichmentResult.Error)
        assertEquals(ErrorKind.NETWORK, (result as EnrichmentResult.Error).errorKind)
    }

    @Test
    fun `enrich returns NotFound when plainLyrics is empty string`() = runTest {
        // Given - LrcLib returns a track with empty string plainLyrics and null syncedLyrics
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

        // When - enriching for plain lyrics
        val result = provider.enrich(request, EnrichmentType.LYRICS_PLAIN)

        // Then - NotFound because plainLyrics is blank after takeIf check
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `search fallback rejects a wholly unrelated title for every capable type`() = runTest {
        // Given - no exact match, and the only search hit names a different song
        httpClient.givenJsonArrayResponse("/api/search", ALABAMA_SONG_JSON)
        val request = EnrichmentRequest.forTrack(title = "Song", artist = "David Bowie")

        // When - enriching for all three types LrcLib declares
        val synced = provider.enrich(request, EnrichmentType.LYRICS_SYNCED)
        val plain = provider.enrich(request, EnrichmentType.LYRICS_PLAIN)
        val metadata = provider.enrich(request, EnrichmentType.TRACK_METADATA)

        // Then - none of them accept the unrelated candidate
        assertTrue(synced is EnrichmentResult.NotFound)
        assertTrue(plain is EnrichmentResult.NotFound)
        assertTrue(metadata is EnrichmentResult.NotFound)
    }

    @Test
    fun `search fallback rejects an exact title from the wrong artist`() = runTest {
        // Given - the only search hit has the exact requested title but a different artist
        httpClient.givenJsonArrayResponse(
            "/api/search",
            """[{"id":1,"trackName":"Creep","artistName":"Someone Else","albumName":null,
                "duration":200.0,"instrumental":false,"syncedLyrics":"x","plainLyrics":"x"}]""",
        )
        val request = EnrichmentRequest.forTrack(title = "Creep", artist = "Radiohead")

        // When - enriching for synced lyrics
        val result = provider.enrich(request, EnrichmentType.LYRICS_SYNCED)

        // Then - NotFound because the artist was never accepted
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `search fallback keeps studio and live takes distinct`() = runTest {
        // Given - the only search hit is a live take the plain request never asked for
        httpClient.givenJsonArrayResponse(
            "/api/search",
            """[{"id":1,"trackName":"Starman (Live)","artistName":"David Bowie","albumName":null,
                "duration":200.0,"instrumental":false,"syncedLyrics":"x","plainLyrics":"x"}]""",
        )
        val request = EnrichmentRequest.forTrack(title = "Starman", artist = "David Bowie")

        // When - enriching for synced lyrics
        val result = provider.enrich(request, EnrichmentType.LYRICS_SYNCED)

        // Then - an unrequested live qualifier is not the requested studio recording
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `search fallback accepts equivalent qualifier delimiter syntax`() = runTest {
        // Given - the search hit spells the requested qualifier with parentheses
        httpClient.givenJsonArrayResponse(
            "/api/search",
            """[{"id":1,"trackName":"Starman (2012 Remaster)","artistName":"David Bowie","albumName":null,
                "duration":200.0,"instrumental":false,"syncedLyrics":"remastered lyrics","plainLyrics":"remastered lyrics"}]""",
        )
        val request = EnrichmentRequest.forTrack(title = "Starman - 2012 Remaster", artist = "David Bowie")

        // When - enriching for synced lyrics
        val result = provider.enrich(request, EnrichmentType.LYRICS_SYNCED)

        // Then - the equivalent delimiter syntax is accepted
        assertTrue(result is EnrichmentResult.Success)
        val lyrics = (result as EnrichmentResult.Success).data as EnrichmentData.Lyrics
        assertEquals("remastered lyrics", lyrics.plainLyrics)
    }

    @Test
    fun `search fallback ranks by album evidence without admitting a wrong title`() = runTest {
        // Given - one accepted candidate matches the hinted album; a second, better-ranked-looking
        // hit is a wrong title entirely and must never be selected regardless of its album
        httpClient.givenJsonArrayResponse(
            "/api/search",
            """[
                {"id":1,"trackName":"Starman","artistName":"David Bowie","albumName":"Ziggy Stardust",
                 "duration":200.0,"instrumental":false,"syncedLyrics":"a","plainLyrics":"a"},
                {"id":2,"trackName":"Starman","artistName":"David Bowie","albumName":"Best of Bowie",
                 "duration":200.0,"instrumental":false,"syncedLyrics":"b","plainLyrics":"b"},
                {"id":3,"trackName":"Life on Mars","artistName":"David Bowie","albumName":"Best of Bowie",
                 "duration":200.0,"instrumental":false,"syncedLyrics":"c","plainLyrics":"c"}
            ]""",
        )
        val request = EnrichmentRequest.forTrack(title = "Starman", artist = "David Bowie", album = "Best of Bowie")

        // When - enriching for synced lyrics
        val result = provider.enrich(request, EnrichmentType.LYRICS_SYNCED)

        // Then - the album-hinted, title-accepted candidate wins
        assertTrue(result is EnrichmentResult.Success)
        val lyrics = (result as EnrichmentResult.Success).data as EnrichmentData.Lyrics
        assertEquals("b", lyrics.plainLyrics)
    }

    @Test
    fun `enrich shares one search across LYRICS_SYNCED, LYRICS_PLAIN and TRACK_METADATA within one ProviderCallScope`() = runTest {
        // Given - no exact match, so all three types would otherwise fall back to search independently
        httpClient.givenJsonArrayResponse("/api/search", SEARCH_RESULTS_JSON)
        val request = EnrichmentRequest.forTrack(title = "Creep", artist = "Radiohead")

        // When - all three types resolve inside the same ProviderCallScope, as sibling types of one enrich() call
        val results = withContext(ProviderCallScope()) {
            listOf(
                provider.enrich(request, EnrichmentType.LYRICS_SYNCED),
                provider.enrich(request, EnrichmentType.LYRICS_PLAIN),
                provider.enrich(request, EnrichmentType.TRACK_METADATA),
            )
        }

        // Then - one search served all three types, and each reads the same selected entity
        assertEquals(1, httpClient.requestedUrls.count { it.contains("/api/search") })
        assertTrue(results.all { it is EnrichmentResult.Success })
        val metadata = results[2] as EnrichmentResult.Success
        assertEquals("Pablo Honey", (metadata.data as EnrichmentData.TrackMetadata).albumTitle)
    }

    companion object {
        private val ALABAMA_SONG_JSON = """
            [{"id":1,"trackName":"Alabama Song","artistName":"David Bowie","albumName":"Lodger",
              "duration":250.0,"instrumental":false,"syncedLyrics":"x","plainLyrics":"x"}]
        """.trimIndent()

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
