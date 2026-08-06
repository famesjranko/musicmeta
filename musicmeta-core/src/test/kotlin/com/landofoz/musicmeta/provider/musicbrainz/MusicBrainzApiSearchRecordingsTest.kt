package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * URL-contract for [MusicBrainzApi.searchRecordings]'s album hint — the recording-search instance
 * of the same first-hit/dropped-hint disease [MusicBrainzSearchTest] covers for releases/artists.
 * Pool ranking itself lives in [MusicBrainzEnricher.pickBestRecording]; see
 * [MusicBrainzProviderTest] for those cases.
 */
class MusicBrainzApiSearchRecordingsTest {

    private val httpClient = FakeHttpClient()
    private val api = MusicBrainzApi(httpClient, RateLimiter(0))

    @Test
    fun `sends a hint-less query when no album is given`() = runTest {
        // Given — a single matching recording
        httpClient.givenJsonResponse("recording?query", SINGLE_MATCH)

        // When — searching without an album hint
        api.searchRecordings("Enter Sandman", "Metallica")

        // Then — no release term in the Lucene query, default limit of 25
        val url = httpClient.requestedUrls.single()
        assertTrue(url.startsWith("https://musicbrainz.org/ws/2/recording?query="))
        assertTrue(url.contains("recording%3A%22Enter+Sandman%22"))
        assertTrue(url.contains("artistname%3A%22Metallica%22"))
        assertTrue(!url.contains("release%3A"))
        assertTrue(url.endsWith("&limit=25"))
    }

    @Test
    fun `still accepts an explicit limit override for tests that need a smaller pool`() = runTest {
        // Given — a single matching recording
        httpClient.givenJsonResponse("recording?query", SINGLE_MATCH)

        // When — searching with an explicit, non-default limit
        api.searchRecordings("Enter Sandman", "Metallica", limit = 3)

        // Then — the override reaches the URL, not the default
        assertTrue(httpClient.requestedUrls.single().endsWith("&limit=3"))
    }

    @Test
    fun `sends a release-hinted query when an album is given`() = runTest {
        // Given — a single matching recording
        httpClient.givenJsonResponse("recording?query", SINGLE_MATCH)

        // When — searching with an album hint
        api.searchRecordings("Enter Sandman", "Metallica", "Metallica")

        // Then — the Lucene query carries an escaped release:"..." term, ANDed on
        val url = httpClient.requestedUrls.single()
        assertEquals(
            "https://musicbrainz.org/ws/2/recording?query=" +
                "recording%3A%22Enter+Sandman%22+AND+artistname%3A%22Metallica%22+AND+release%3A%22Metallica%22" +
                "&fmt=json&limit=25",
            url,
        )
    }

    @Test
    fun `escapes Lucene special characters in the release term the same way as the other fields`() = runTest {
        // Given — a single matching recording
        httpClient.givenJsonResponse("recording?query", SINGLE_MATCH)

        // When — the album hint itself carries a Lucene special character
        api.searchRecordings("Sabotage", "Black Sabbath", "Sabotage (Deluxe)")

        // Then — the parenthesis is backslash-escaped before URL-encoding, same as escapeLucene elsewhere
        val url = httpClient.requestedUrls.single()
        assertTrue(url.contains("release%3A%22Sabotage+%5C%28Deluxe%5C%29%22"))
    }

    @Test
    fun `falls back to the hint-less query when the hinted query returns zero recordings`() = runTest {
        // Given — the release-hinted query (identifiable by its "release:" term) comes back empty,
        // the hint-less query (identifiable by its lack of one) has the real candidate
        httpClient.givenJsonResponse("release%3A%22Some+Renamed+Edition%22", """{"recordings":[]}""")
        httpClient.givenJsonResponse("recording?query", SINGLE_MATCH)

        // When — searching with an album hint the hinted query cannot satisfy
        val result = api.searchRecordings("Enter Sandman", "Metallica", "Some Renamed Edition")

        // Then — both queries were sent, in that order, and the hint-less query's candidate wins
        assertEquals(2, httpClient.requestedUrls.size)
        assertTrue(httpClient.requestedUrls[0].contains("release%3A%22Some+Renamed+Edition%22"))
        assertTrue(!httpClient.requestedUrls[1].contains("release%3A"))
        assertEquals(1, result.size)
        assertEquals("rec-studio", result.single().id)
    }

    private companion object {
        const val SINGLE_MATCH = """
            {
              "recordings": [
                {"id": "rec-studio", "score": 100, "title": "Enter Sandman"}
              ]
            }
        """
    }
}
