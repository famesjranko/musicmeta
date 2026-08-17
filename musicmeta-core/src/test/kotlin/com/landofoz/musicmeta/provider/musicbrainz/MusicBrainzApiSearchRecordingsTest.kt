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
        // Given - a single matching recording
        httpClient.givenJsonResponse("recording?query", SINGLE_MATCH)

        // When - searching without an album hint
        api.searchRecordings("Enter Sandman", "Metallica")

        // Then - no release term in the Lucene query, default limit of 25
        val url = httpClient.requestedUrls.single()
        assertTrue(url.startsWith("https://musicbrainz.org/ws/2/recording?query="))
        assertTrue(url.contains("recording%3A%22Enter+Sandman%22"))
        assertTrue(url.contains("artistname%3A%22Metallica%22"))
        assertTrue(!url.contains("release%3A"))
        assertTrue(url.endsWith("&limit=25"))
    }

    @Test
    fun `still accepts an explicit limit override for tests that need a smaller pool`() = runTest {
        // Given - a single matching recording
        httpClient.givenJsonResponse("recording?query", SINGLE_MATCH)

        // When - searching with an explicit, non-default limit
        api.searchRecordings("Enter Sandman", "Metallica", limit = 3)

        // Then - the override reaches the URL, not the default
        assertTrue(httpClient.requestedUrls.single().endsWith("&limit=3"))
    }

    @Test
    fun `sends a release-hinted query when an album is given`() = runTest {
        // Given - a single matching recording
        httpClient.givenJsonResponse("recording?query", SINGLE_MATCH)

        // When - searching with an album hint
        api.searchRecordings("Enter Sandman", "Metallica", "Metallica")

        // Then - the Lucene query carries an escaped release:"..." term, ANDed on
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
        // Given - a single matching recording
        httpClient.givenJsonResponse("recording?query", SINGLE_MATCH)

        // When - the album hint itself carries a Lucene special character
        api.searchRecordings("Sabotage", "Black Sabbath", "Sabotage (Deluxe)")

        // Then - the parenthesis is backslash-escaped before URL-encoding, same as escapeLucene elsewhere
        val url = httpClient.requestedUrls.single()
        assertTrue(url.contains("release%3A%22Sabotage+%5C%28Deluxe%5C%29%22"))
    }

    @Test
    fun `falls back to the hint-less query when the hinted query returns zero recordings`() = runTest {
        // Given - the release-hinted query (identifiable by its "release:" term) comes back empty,
        // the hint-less query (identifiable by its lack of one) has the real candidate
        httpClient.givenJsonResponse("release%3A%22Some+Renamed+Edition%22", """{"recordings":[]}""")
        httpClient.givenJsonResponse("recording?query", SINGLE_MATCH)

        // When - searching with an album hint the hinted query cannot satisfy
        val result = api.searchRecordings("Enter Sandman", "Metallica", "Some Renamed Edition")

        // Then - both queries were sent, in that order, and the hint-less query's candidate wins
        assertEquals(2, httpClient.requestedUrls.size)
        assertTrue(httpClient.requestedUrls[0].contains("release%3A%22Some+Renamed+Edition%22"))
        assertTrue(!httpClient.requestedUrls[1].contains("release%3A"))
        assertEquals(1, result.size)
        assertEquals("rec-studio", result.single().id)
    }

    @Test
    fun `the resolution search never asks for more than MusicBrainz will serve`() = runTest {
        // Given - a single matching recording for the filtered resolution query
        httpClient.givenJsonResponse("recording?query", SINGLE_MATCH)

        // When - the hint-less resolution search runs, which is the only caller of the larger page
        api.searchCanonicalRecordings("Enter Sandman", "Metallica")

        // Then - the page it asks for is within MusicBrainz's own maximum, asserted on the constant
        // and on the URL alike so a hardcoded literal cannot slip past either. Above 100 the search
        // does not clamp and does not error — it silently serves the default 25 (measured
        // 2026-08-10: limit=101, 115 and 200 all served 25 against the same total), so raising this
        // makes the pool smaller than before the filter existed, with nothing else to say so.
        assertTrue(
            "CANONICAL_SEARCH_LIMIT is ${MusicBrainzApi.CANONICAL_SEARCH_LIMIT}; above 100 " +
                "MusicBrainz silently serves 25 instead, so the pool would shrink, not grow",
            MusicBrainzApi.CANONICAL_SEARCH_LIMIT <= MUSICBRAINZ_MAX_SEARCH_LIMIT,
        )
        val limit = httpClient.requestedUrls.single().substringAfter("&limit=").toInt()
        assertTrue("requested limit=$limit, past MusicBrainz's maximum", limit <= MUSICBRAINZ_MAX_SEARCH_LIMIT)
    }

    @Test
    fun `an album hint that finds recordings costs one request and no filter`() = runTest {
        // Given - a hinted query that finds something
        httpClient.givenJsonResponse("recording?query", SINGLE_MATCH)

        // When - the resolution search runs with an album hint MusicBrainz can satisfy
        api.searchCanonicalRecordings("Enter Sandman", "Metallica", "Metallica")

        // Then - that pool answers alone, unfiltered: the extra request below is the miss path's cost
        val url = httpClient.requestedUrls.single()
        assertTrue("expected the release term in $url", url.contains("release%3A%22Metallica%22"))
        assertTrue("expected no filter on a hinted request, got $url", !url.contains(CANONICAL_FILTER))
    }

    @Test
    fun `an album hint that finds nothing unions both hint-less pools`() = runTest {
        // Given - a hinted query with no hits, and two hint-less pools sharing one recording
        httpClient.givenJsonResponse("release%3A%22Some+Renamed+Edition%22", EMPTY_POOL)
        httpClient.givenJsonResponse(CANONICAL_FILTER, FILTERED_POOL)
        httpClient.givenJsonResponse("recording?query", UNFILTERED_POOL)

        // When - the resolution search runs with an album hint MusicBrainz has no release for
        val result = api.searchCanonicalRecordings("Enter Sandman", "Metallica", "Some Renamed Edition")

        // Then - three requests, the extra one being the deep filtered pool this function exists for
        val urls = httpClient.requestedUrls
        assertEquals(3, urls.size)
        assertTrue("expected the hint first, got ${urls[0]}", urls[0].contains("release%3A%22Some+Renamed+Edition%22"))
        assertTrue("expected the filter on the deep pool, got ${urls[1]}", urls[1].contains(CANONICAL_FILTER))
        assertTrue("expected the deep pool at limit=100, got ${urls[1]}", urls[1].endsWith("&limit=100"))
        assertTrue("expected no release term on the deep pool, got ${urls[1]}", !urls[1].contains("release%3A"))
        assertTrue("expected no filter on the shallow pool, got ${urls[2]}", !urls[2].contains(CANONICAL_FILTER))
        assertTrue("expected the shallow pool at limit=25, got ${urls[2]}", urls[2].endsWith("&limit=25"))

        // Then - both pools reach the caller, deduplicated by recording id, deep pool first
        assertEquals(listOf("rec-studio", "rec-shared", "rec-live"), result.map { it.id })
    }

    @Test
    fun `a title that names its own variant takes the unfiltered ladder`() = runTest {
        // Given - a single matching recording for whichever query is sent
        httpClient.givenJsonResponse("recording?query", SINGLE_MATCH)

        // When - the request's own title carries a trailing qualifier group
        api.searchCanonicalRecordings("Comfortably Numb (Live at Earls Court)", "Pink Floyd")

        // Then - one unfiltered request: the filter would delete the very recording the title names
        val url = httpClient.requestedUrls.single()
        assertTrue("expected no filter for a title naming a variant, got $url", !url.contains(CANONICAL_FILTER))
        assertTrue("expected the unfiltered page size, got $url", url.endsWith("&limit=25"))
    }

    private companion object {
        /** MusicBrainz's own maximum for a search `limit`. Past it there is no error, just 25. */
        const val MUSICBRAINZ_MAX_SEARCH_LIMIT = 100

        /** `-comment:*` as `URLEncoder` writes it — `:` escapes, `*` and `-` do not. */
        const val CANONICAL_FILTER = "-comment%3A*"

        const val SINGLE_MATCH = """
            {
              "recordings": [
                {
                  "id": "rec-studio", "score": 100, "title": "Enter Sandman",
                  "artist-credit": [{"artist": {"id": "art-metallica", "name": "Metallica"}}]
                }
              ]
            }
        """

        const val EMPTY_POOL = """{"count": 0, "recordings": []}"""

        const val FILTERED_POOL = """
            {
              "recordings": [
                {
                  "id": "rec-studio", "score": 100, "title": "Enter Sandman",
                  "artist-credit": [{"artist": {"id": "art-metallica", "name": "Metallica"}}]
                },
                {
                  "id": "rec-shared", "score": 100, "title": "Enter Sandman",
                  "artist-credit": [{"artist": {"id": "art-metallica", "name": "Metallica"}}]
                }
              ]
            }
        """

        const val UNFILTERED_POOL = """
            {
              "recordings": [
                {
                  "id": "rec-shared", "score": 100, "title": "Enter Sandman",
                  "artist-credit": [{"artist": {"id": "art-metallica", "name": "Metallica"}}]
                },
                {
                  "id": "rec-live", "score": 100, "title": "Enter Sandman",
                  "artist-credit": [{"artist": {"id": "art-metallica", "name": "Metallica"}}]
                }
              ]
            }
        """
    }
}
