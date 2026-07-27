package com.landofoz.musicmeta.provider.discogs

import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Selection rules for [DiscogsApi.searchArtist].
 *
 * [BAD_COMPANY_LIVE] and [GIRLS_LIVE] are trimmed copies of live
 * `api.discogs.com/database/search?type=artist` responses (2026-07-27) — those two pin the real
 * field name (`title`, not `name`), the real `" (n)"` disambiguator and the real ordering. The rest
 * are constructed to pin one selection rule each.
 *
 * There is no popularity assertion here on purpose: the Discogs artist-search payload carries no
 * popularity signal, so name quality is the whole ranking and ties fall back to Discogs's order.
 */
class DiscogsApiSearchArtistTest {

    private val httpClient = FakeHttpClient()
    private val api = DiscogsApi("token", httpClient, RateLimiter(0))

    @Test
    fun `a disambiguator suffix does not lose to the bare-name homonym`() = runTest {
        // Given — the live response: "Bad Company (3)" is the Paul Rodgers rock band at rank 0,
        // while the bare "Bad Company" (id 2017) is a UK drum & bass group at rank 1. Discogs gives
        // the bare name to whoever was catalogued first, so it is not a quality signal.
        httpClient.givenJsonResponse("type=artist", BAD_COMPANY_LIVE)

        // When — searching for the artist
        val result = api.searchArtist("Bad Company")

        // Then — stripping " (n)" puts both at the same name rank, so Discogs's own order settles
        // it and the rock band is kept. Ranking the bare name higher would return 2017.
        assertEquals(261941L, result)
    }

    @Test
    fun `a disambiguated exact name beats a longer name ranked below it`() = runTest {
        // Given — the live response: "Girls (5)" (the SF indie band) then "Spice Girls"
        httpClient.givenJsonResponse("type=artist", GIRLS_LIVE)

        // When — searching for the artist
        val result = api.searchArtist("Girls")

        // Then — without stripping the suffix both would score as mere containing matches and the
        // ranking would be a coin toss; with it, "Girls (5)" is an outright name match
        assertEquals(1572552L, result)
    }

    @Test
    fun `picks the name match over a wrong-name artist listed first`() = runTest {
        // Given — Discogs ranks an unrelated artist ahead of the real one
        httpClient.givenJsonResponse("type=artist", WRONG_NAME_FIRST)

        // When — searching for the artist
        val result = api.searchArtist("Autechre")

        // Then — result order does not decide it, the name does
        assertEquals(7777L, result)
    }

    @Test
    fun `prefers an exact name over a looser containing match listed first`() = runTest {
        // Given — "DJ Radiohead" is ranked ahead of the exact-name entry
        httpClient.givenJsonResponse("type=artist", LOOSE_MATCH_FIRST)

        // When — searching for the artist
        val result = api.searchArtist("Radiohead")

        // Then — name quality is ranked, not just filtered
        assertEquals(399L, result)
    }

    @Test
    fun `returns null when no candidate name matches`() = runTest {
        // Given — every hit is a different artist
        httpClient.givenJsonResponse("type=artist", NO_NAME_MATCH)

        // When — searching for the artist
        val result = api.searchArtist("Autechre")

        // Then — no wrong artist is returned in place of a real miss
        assertNull(result)
    }

    @Test
    fun `a non-object element does not fail the whole search`() = runTest {
        // Given — a malformed first element ahead of the real artist
        httpClient.givenJsonResponse("type=artist", MALFORMED_ELEMENT_FIRST)

        // When — searching for the artist
        val result = api.searchArtist("Radiohead")

        // Then — it is skipped, not thrown: a JSONException here would surface as Error and
        // open the circuit breaker against a healthy Discogs (docs/pitfalls.md §4)
        assertEquals(399L, result)
    }

    @Test
    fun `requests more than one hit so a wrong name can be passed over`() = runTest {
        // Given — any response
        httpClient.givenJsonResponse("type=artist", BAD_COMPANY_LIVE)

        // When — searching for the artist
        api.searchArtist("Bad Company")

        // Then — the search asks for a candidate pool, not a single hit
        assertTrue(httpClient.requestedUrls.single().endsWith("per_page=10"))
    }

    private companion object {
        const val BAD_COMPANY_LIVE = """
            {"results":[
              {"id":261941,"type":"artist","title":"Bad Company (3)",
               "resource_url":"https://api.discogs.com/artists/261941"},
              {"id":2017,"type":"artist","title":"Bad Company",
               "resource_url":"https://api.discogs.com/artists/2017"},
              {"id":103501,"type":"artist","title":"Bad Company (2)"},
              {"id":6099367,"type":"artist","title":"Badd Company"}
            ]}
        """

        const val GIRLS_LIVE = """
            {"results":[
              {"id":1572552,"type":"artist","title":"Girls (5)",
               "resource_url":"https://api.discogs.com/artists/1572552"},
              {"id":47109,"type":"artist","title":"Spice Girls"},
              {"id":213484,"type":"artist","title":"Indigo Girls"},
              {"id":142944,"type":"artist","title":"Girls Aloud"}
            ]}
        """

        // The right-name candidate is deliberately NOT an exact match, so the assertion rests on
        // the name filter rejecting Taylor Swift — not on the same-name rank carrying it.
        const val WRONG_NAME_FIRST = """
            {"results":[
              {"id":1111,"type":"artist","title":"Taylor Swift"},
              {"id":7777,"type":"artist","title":"Autechre (Live)"}
            ]}
        """

        const val LOOSE_MATCH_FIRST = """
            {"results":[
              {"id":53477202,"type":"artist","title":"DJ Radiohead"},
              {"id":399,"type":"artist","title":"Radiohead"}
            ]}
        """

        const val NO_NAME_MATCH = """
            {"results":[
              {"id":1111,"type":"artist","title":"Taylor Swift"},
              {"id":2222,"type":"artist","title":"Boards of Canada"}
            ]}
        """

        const val MALFORMED_ELEMENT_FIRST = """
            {"results":[
              null,
              {"id":399,"type":"artist","title":"Radiohead"}
            ]}
        """
    }
}
