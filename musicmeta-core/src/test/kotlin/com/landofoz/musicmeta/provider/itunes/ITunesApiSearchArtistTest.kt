package com.landofoz.musicmeta.provider.itunes

import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Selection rules for [ITunesApi.searchArtist].
 *
 * [NIRVANA_LIVE] is a trimmed copy of a live
 * `itunes.apple.com/search?entity=musicArtist&term=Nirvana` response (2026-07-27) — it pins the
 * real field names (`artistName`, `artistId`) and the real ordering. The rest are constructed to
 * pin one selection rule each.
 *
 * There is no popularity assertion here on purpose: the iTunes payload carries no popularity
 * signal, so name quality is the whole ranking and ties fall back to iTunes' own order.
 */
class ITunesApiSearchArtistTest {

    private val httpClient = FakeHttpClient()
    private val api = ITunesApi(httpClient, RateLimiter(0))

    @Test
    fun `keeps hit 0 when it is already the best name match`() = runTest {
        // Given - the live response, where iTunes already ranks the grunge band first
        httpClient.givenJsonResponse("entity=musicArtist", NIRVANA_LIVE)

        // When - searching for the artist
        val result = api.searchArtist("Nirvana")?.value

        // Then - hit 0 is kept; the fix narrows the old behaviour, it does not re-rank it
        assertEquals(112018L, result)
    }

    @Test
    fun `picks the name match over a wrong-name artist listed first`() = runTest {
        // Given - iTunes ranks an unrelated artist ahead of the real one
        httpClient.givenJsonResponse("entity=musicArtist", WRONG_NAME_FIRST)

        // When - searching for the artist
        val result = api.searchArtist("Autechre")?.value

        // Then - result order does not decide it, the name does
        assertEquals(7777L, result)
    }

    @Test
    fun `prefers an exact name over a looser containing match listed first`() = runTest {
        // Given - "DJ Radiohead" is ranked ahead of the exact-name entry
        httpClient.givenJsonResponse("entity=musicArtist", LOOSE_MATCH_FIRST)

        // When - searching for the artist
        val result = api.searchArtist("Radiohead")?.value

        // Then - name quality is ranked, not just filtered
        assertEquals(399L, result)
    }

    @Test
    fun `returns null when no candidate name matches`() = runTest {
        // Given - every hit is a different artist
        httpClient.givenJsonResponse("entity=musicArtist", NO_NAME_MATCH)

        // When - searching for the artist
        val result = api.searchArtist("Autechre")?.value

        // Then - no wrong artist is returned in place of a real miss
        assertNull(result)
    }

    @Test
    fun `a non-object element does not fail the whole search`() = runTest {
        // Given - a malformed first element ahead of the real artist
        httpClient.givenJsonResponse("entity=musicArtist", MALFORMED_ELEMENT_FIRST)

        // When - searching for the artist
        val result = api.searchArtist("Radiohead")?.value

        // Then - it is skipped, not thrown: a JSONException here would surface as Error and
        // open the circuit breaker against a healthy iTunes (docs/pitfalls.md §4)
        assertEquals(399L, result)
    }

    @Test
    fun `requests more than one hit so a wrong name can be passed over`() = runTest {
        // Given - any response
        httpClient.givenJsonResponse("entity=musicArtist", NIRVANA_LIVE)

        // When - searching for the artist
        api.searchArtist("Nirvana")

        // Then - the search asks for a candidate pool, not a single hit
        assertTrue(httpClient.requestedUrls.single().endsWith("limit=10"))
    }

    private companion object {
        const val NIRVANA_LIVE = """
            {"resultCount":4,"results":[
              {"wrapperType":"artist","artistType":"Artist","artistName":"Nirvana",
               "artistId":112018,"amgArtistId":5034,"primaryGenreName":"Alternative"},
              {"wrapperType":"artist","artistType":"Artist","artistName":"Nirvana Nada",
               "artistId":1720728717,"primaryGenreName":"New Age"},
              {"wrapperType":"artist","artistType":"Artist","artistName":"Approaching Nirvana",
               "artistId":313583565,"primaryGenreName":"Dance"},
              {"wrapperType":"artist","artistType":"Artist","artistName":"Nirvana",
               "artistId":256176428,"amgArtistId":143230,"primaryGenreName":"Rock"}
            ]}
        """

        const val WRONG_NAME_FIRST = """
            {"resultCount":2,"results":[
              {"artistName":"Taylor Swift","artistId":1111},
              {"artistName":"Autechre (Live)","artistId":7777}
            ]}
        """

        const val LOOSE_MATCH_FIRST = """
            {"resultCount":2,"results":[
              {"artistName":"DJ Radiohead","artistId":53477202},
              {"artistName":"Radiohead","artistId":399}
            ]}
        """

        // This fixture is what makes the name filter load-bearing, and it is the only one that
        // can be: with the filter deleted every candidate still scores QUALITY_NONE, so the
        // ranking cannot separate them and maxWithOrNull hands back Taylor Swift instead of null.
        // A wrong-name-*first* fixture could never carry the filter — rank alone already sorts it.
        const val NO_NAME_MATCH = """
            {"resultCount":2,"results":[
              {"artistName":"Taylor Swift","artistId":1111},
              {"artistName":"Boards of Canada","artistId":2222}
            ]}
        """

        const val MALFORMED_ELEMENT_FIRST = """
            {"resultCount":2,"results":[
              null,
              {"artistName":"Radiohead","artistId":399}
            ]}
        """
    }
}
