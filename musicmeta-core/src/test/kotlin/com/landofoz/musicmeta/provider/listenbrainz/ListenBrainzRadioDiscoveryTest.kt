package com.landofoz.musicmeta.provider.listenbrainz

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.RadioDiscoveryMode
import com.landofoz.musicmeta.engine.RECOMMENDATION_TYPES
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ListenBrainzRadioDiscoveryTest {

    private lateinit var httpClient: FakeHttpClient

    @Before
    fun setUp() {
        httpClient = FakeHttpClient()
    }

    // --- JSPF fixture data ---

    private val JSPF_RESPONSE = """{
        "payload": {
            "jspf": {
                "playlist": {
                    "track": [
                        {
                            "title": "Everything In Its Right Place",
                            "creator": "Radiohead",
                            "identifier": ["https://musicbrainz.org/recording/e5c7b21c-32ad-4747-8c8e-2b4fba1d39c2"],
                            "duration": 251000,
                            "album": "Kid A",
                            "extension": {
                                "https://musicbrainz.org/doc/jspf#playlist": {
                                    "artist_identifiers": ["https://musicbrainz.org/artist/a74b1b7f-71a5-4011-9441-d0b5e4122711"],
                                    "release_identifier": "https://musicbrainz.org/release/b84ee12a-09ef-421b-82de-0441a926375b"
                                }
                            }
                        },
                        {
                            "title": "Idioteque",
                            "creator": "Radiohead",
                            "identifier": ["https://musicbrainz.org/recording/f1e7b21c-22ad-4747-8c8e-2b4fba1d39d3"],
                            "duration": 309000,
                            "album": "Kid A",
                            "extension": {
                                "https://musicbrainz.org/doc/jspf#playlist": {
                                    "artist_identifiers": ["https://musicbrainz.org/artist/a74b1b7f-71a5-4011-9441-d0b5e4122711"],
                                    "release_identifier": "https://musicbrainz.org/release/b84ee12a-09ef-421b-82de-0441a926375b"
                                }
                            }
                        }
                    ]
                }
            }
        }
    }"""

    private val JSPF_MINIMAL_RESPONSE = """{
        "payload": {
            "jspf": {
                "playlist": {
                    "track": [
                        {
                            "title": "Minimal Track",
                            "creator": "Minimal Artist"
                        }
                    ]
                }
            }
        }
    }"""

    private val JSPF_EMPTY_RESPONSE = """{
        "payload": {
            "jspf": {
                "playlist": {
                    "track": []
                }
            }
        }
    }"""

    // --- Tests ---

    @Test
    fun `provider returns RadioPlaylist with JSPF fields mapped correctly`() = runTest {
        // Given: provider with auth token and valid JSPF response with 2 full tracks
        val provider = ListenBrainzProvider(httpClient, RateLimiter(0L), authToken = "test-token")
        httpClient.givenJsonResponse("lb-radio", JSPF_RESPONSE)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "a74b1b7f-71a5-4011-9441-d0b5e4122711"),
            name = "Radiohead",
        )

        // When: enriching for ARTIST_RADIO_DISCOVERY
        val result = provider.enrich(request, EnrichmentType.ARTIST_RADIO_DISCOVERY)

        // Then: Success with RadioPlaylist containing 2 fully-mapped tracks
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertEquals("listenbrainz", success.provider)
        assertEquals(0.95f, success.confidence, 0.01f)

        val playlist = success.data as EnrichmentData.RadioPlaylist
        assertEquals(2, playlist.tracks.size)

        val first = playlist.tracks[0]
        assertEquals("Everything In Its Right Place", first.title)
        assertEquals("Radiohead", first.artist)
        assertEquals("Kid A", first.album)
        assertEquals(251000L, first.durationMs)
        assertEquals("e5c7b21c-32ad-4747-8c8e-2b4fba1d39c2", first.identifiers.musicBrainzId)
        assertEquals("a74b1b7f-71a5-4011-9441-d0b5e4122711", first.identifiers.extra["artistMbid"])
        assertEquals("b84ee12a-09ef-421b-82de-0441a926375b", first.identifiers.extra["releaseMbid"])
    }

    @Test
    fun `provider returns NotFound when no auth token provided`() = runTest {
        // Given: provider WITHOUT auth token (capability not registered)
        val provider = ListenBrainzProvider(httpClient, RateLimiter(0L), authToken = null)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "some-mbid"),
            name = "Radiohead",
        )

        // When: enriching for ARTIST_RADIO_DISCOVERY
        val result = provider.enrich(request, EnrichmentType.ARTIST_RADIO_DISCOVERY)

        // Then: NotFound because getRadio returns empty when no auth token
        assertTrue(result is EnrichmentResult.NotFound)
        assertEquals("listenbrainz", (result as EnrichmentResult.NotFound).provider)
    }

    @Test
    fun `provider returns NotFound when API returns empty track list`() = runTest {
        // Given: provider with auth token but empty JSPF response
        val provider = ListenBrainzProvider(httpClient, RateLimiter(0L), authToken = "test-token")
        httpClient.givenJsonResponse("lb-radio", JSPF_EMPTY_RESPONSE)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "some-mbid"),
            name = "Radiohead",
        )

        // When: enriching for ARTIST_RADIO_DISCOVERY
        val result = provider.enrich(request, EnrichmentType.ARTIST_RADIO_DISCOVERY)

        // Then: NotFound because track list is empty
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `provider uses MBID as prompt when available`() = runTest {
        // Given: provider with auth token, artist request with MBID
        val provider = ListenBrainzProvider(httpClient, RateLimiter(0L), authToken = "test-token")
        httpClient.givenJsonResponse("lb-radio", JSPF_RESPONSE)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "test-mbid"),
            name = "Radiohead",
        )

        // When: enriching for ARTIST_RADIO_DISCOVERY
        provider.enrich(request, EnrichmentType.ARTIST_RADIO_DISCOVERY)

        // Then: URL contains URL-encoded artist:(test-mbid) prompt
        assertTrue(
            "Expected URL with artist:(test-mbid) but got: ${httpClient.requestedUrls}",
            httpClient.requestedUrls.any { it.contains("artist%3A%28test-mbid%29") },
        )
    }

    @Test
    fun `provider uses artist name as prompt when no MBID available`() = runTest {
        // Given: provider with auth token, artist request without MBID
        val provider = ListenBrainzProvider(httpClient, RateLimiter(0L), authToken = "test-token")
        httpClient.givenJsonResponse("lb-radio", JSPF_RESPONSE)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(),
            name = "Radiohead",
        )

        // When: enriching for ARTIST_RADIO_DISCOVERY
        provider.enrich(request, EnrichmentType.ARTIST_RADIO_DISCOVERY)

        // Then: URL contains URL-encoded artist:(Radiohead) prompt
        assertTrue(
            "Expected URL with artist:(Radiohead) but got: ${httpClient.requestedUrls}",
            httpClient.requestedUrls.any { it.contains("artist%3A%28Radiohead%29") },
        )
    }

    @Test
    fun `provider passes radio discovery mode from config`() = runTest {
        // Given: provider with HARD mode config
        val provider = ListenBrainzProvider(
            httpClient,
            RateLimiter(0L),
            authToken = "test-token",
            config = EnrichmentConfig(radioDiscoveryMode = RadioDiscoveryMode.HARD),
        )
        httpClient.givenJsonResponse("lb-radio", JSPF_RESPONSE)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "some-mbid"),
            name = "Radiohead",
        )

        // When: enriching for ARTIST_RADIO_DISCOVERY
        provider.enrich(request, EnrichmentType.ARTIST_RADIO_DISCOVERY)

        // Then: URL contains mode=hard
        assertTrue(
            "Expected URL with mode=hard but got: ${httpClient.requestedUrls}",
            httpClient.requestedUrls.any { it.contains("mode=hard") },
        )
    }

    @Test
    fun `capability present with auth token`() {
        // Given: provider with auth token
        val provider = ListenBrainzProvider(httpClient, RateLimiter(0L), authToken = "test-token")

        // Then: ARTIST_RADIO_DISCOVERY capability is registered with priority 100
        val cap = provider.capabilities.find { it.type == EnrichmentType.ARTIST_RADIO_DISCOVERY }
        assertNotNull("Expected ARTIST_RADIO_DISCOVERY capability to be present", cap)
        assertEquals(100, cap!!.priority)
    }

    @Test
    fun `capability absent without auth token`() {
        // Given: provider without auth token
        val provider = ListenBrainzProvider(httpClient, RateLimiter(0L), authToken = null)

        // Then: ARTIST_RADIO_DISCOVERY capability is NOT registered
        assertTrue(
            "Expected ARTIST_RADIO_DISCOVERY capability to be absent",
            provider.capabilities.none { it.type == EnrichmentType.ARTIST_RADIO_DISCOVERY },
        )
    }

    @Test
    fun `mapper handles tracks with null optional fields`() {
        // Given: a minimal ListenBrainzRadioTrack with only title and artist
        val track = ListenBrainzRadioTrack(
            title = "Minimal Track",
            artist = "Minimal Artist",
            album = null,
            durationMs = null,
            recordingMbid = null,
            artistMbid = null,
            releaseMbid = null,
        )

        // When: mapping to RadioPlaylist
        val playlist = ListenBrainzMapper.toRadioPlaylist(listOf(track))

        // Then: RadioTrack has title and artist; all optional fields are null; extra map is empty
        assertEquals(1, playlist.tracks.size)
        val radioTrack = playlist.tracks[0]
        assertEquals("Minimal Track", radioTrack.title)
        assertEquals("Minimal Artist", radioTrack.artist)
        assertNull(radioTrack.album)
        assertNull(radioTrack.durationMs)
        assertNull(radioTrack.identifiers.musicBrainzId)
        assertTrue(
            "Expected empty extra map but got: ${radioTrack.identifiers.extra}",
            radioTrack.identifiers.extra.isEmpty(),
        )
    }

    @Test
    fun `ARTIST_RADIO_DISCOVERY is in DEFAULT_ARTIST_TYPES`() {
        // Then: ARTIST_RADIO_DISCOVERY is included in the default artist enrichment type set
        assertTrue(
            EnrichmentRequest.DEFAULT_ARTIST_TYPES.contains(EnrichmentType.ARTIST_RADIO_DISCOVERY),
        )
    }

    @Test
    fun `ARTIST_RADIO_DISCOVERY is in RECOMMENDATION_TYPES`() {
        // Then: ARTIST_RADIO_DISCOVERY is treated as a recommendation type for catalog filtering
        assertTrue(
            RECOMMENDATION_TYPES.contains(EnrichmentType.ARTIST_RADIO_DISCOVERY),
        )
    }

    @Test
    fun `confidence is authoritative for radio discovery results`() = runTest {
        // Given: provider with auth token and valid JSPF response
        val provider = ListenBrainzProvider(httpClient, RateLimiter(0L), authToken = "test-token")
        httpClient.givenJsonResponse("lb-radio", JSPF_RESPONSE)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "some-mbid"),
            name = "Radiohead",
        )

        // When: enriching for ARTIST_RADIO_DISCOVERY
        val result = provider.enrich(request, EnrichmentType.ARTIST_RADIO_DISCOVERY)

        // Then: confidence is 0.95f (authoritative)
        assertTrue(result is EnrichmentResult.Success)
        assertEquals(0.95f, (result as EnrichmentResult.Success).confidence, 0.01f)
    }

    @Test
    fun `provider returns NotFound for non-artist request`() = runTest {
        // Given: provider with auth token, but a ForTrack request (not ForArtist)
        val provider = ListenBrainzProvider(httpClient, RateLimiter(0L), authToken = "test-token")
        httpClient.givenJsonResponse("lb-radio", JSPF_RESPONSE)
        val request = EnrichmentRequest.ForTrack(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "some-mbid"),
            title = "Some Track",
            artist = "Radiohead",
        )

        // When: enriching for ARTIST_RADIO_DISCOVERY with a track request
        val result = provider.enrich(request, EnrichmentType.ARTIST_RADIO_DISCOVERY)

        // Then: NotFound because enrichRadioDiscovery requires ForArtist
        assertTrue(result is EnrichmentResult.NotFound)
        assertEquals("listenbrainz", (result as EnrichmentResult.NotFound).provider)
    }

    @Test
    fun `provider sends Authorization header with token`() = runTest {
        // Given: provider with auth token and valid JSPF response
        val provider = ListenBrainzProvider(httpClient, RateLimiter(0L), authToken = "my-secret-token")
        httpClient.givenJsonResponse("lb-radio", JSPF_RESPONSE)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "some-mbid"),
            name = "Radiohead",
        )

        // When: enriching for ARTIST_RADIO_DISCOVERY
        provider.enrich(request, EnrichmentType.ARTIST_RADIO_DISCOVERY)

        // Then: the HTTP request included the Authorization header with the token
        val radioHeaders = httpClient.requestedHeaders.lastOrNull { headers ->
            headers.containsKey("Authorization")
        }
        assertNotNull("Expected Authorization header to be sent", radioHeaders)
        assertEquals("Token my-secret-token", radioHeaders!!["Authorization"])
    }

    @Test
    fun `mapper correctly maps all MBID fields including extra identifiers`() {
        // Given: a track with all MBID fields populated
        val track = ListenBrainzRadioTrack(
            title = "Everything In Its Right Place",
            artist = "Radiohead",
            album = "Kid A",
            durationMs = 251000L,
            recordingMbid = "e5c7b21c-32ad-4747-8c8e-2b4fba1d39c2",
            artistMbid = "a74b1b7f-71a5-4011-9441-d0b5e4122711",
            releaseMbid = "b84ee12a-09ef-421b-82de-0441a926375b",
        )

        // When: mapping to RadioPlaylist
        val playlist = ListenBrainzMapper.toRadioPlaylist(listOf(track))

        // Then: all three MBID fields are correctly mapped
        val radioTrack = playlist.tracks[0]
        assertEquals("e5c7b21c-32ad-4747-8c8e-2b4fba1d39c2", radioTrack.identifiers.musicBrainzId)
        assertEquals("a74b1b7f-71a5-4011-9441-d0b5e4122711", radioTrack.identifiers.extra["artistMbid"])
        assertEquals("b84ee12a-09ef-421b-82de-0441a926375b", radioTrack.identifiers.extra["releaseMbid"])
        assertEquals(2, radioTrack.identifiers.extra.size)
    }
}
