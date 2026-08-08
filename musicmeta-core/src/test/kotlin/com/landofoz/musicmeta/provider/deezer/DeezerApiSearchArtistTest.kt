package com.landofoz.musicmeta.provider.deezer

import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Selection rules for [DeezerApi.searchArtist].
 *
 * [RADIOHEAD_GHOST_FIRST] and [PORTISHEAD_CORRECT_FIRST] are trimmed copies of live
 * `api.deezer.com/search/artist` responses (2026-07-27) — those two pin the real field names
 * and the real ordering. The rest are constructed to pin one tie-break each.
 */
class DeezerApiSearchArtistTest {

    private val httpClient = FakeHttpClient()
    private val api = DeezerApi(httpClient, RateLimiter(0))

    @Test
    fun `picks the popular real artist over an exact-name ghost listed first`() = runTest {
        // Given - Deezer returns a ghost "Radiohead" (0 albums, 470 fans) ahead of id 399
        httpClient.givenJsonResponse("search/artist", RADIOHEAD_GHOST_FIRST)

        // When - searching for the artist
        val result = api.searchArtist("Radiohead")

        // Then - the real artist wins on fan count, not on result order
        assertEquals(399L, result?.id)
    }

    @Test
    fun `keeps hit 0 when it is already the best match`() = runTest {
        // Given - Deezer returns the real Portishead first, a fringe credit second
        httpClient.givenJsonResponse("search/artist", PORTISHEAD_CORRECT_FIRST)

        // When - searching for the artist
        val result = api.searchArtist("Portishead")

        // Then - hit 0 is kept
        assertEquals(1069L, result?.id)
    }

    @Test
    fun `a hugely popular wrong-name artist never beats a name match`() = runTest {
        // Given - a wrong-name artist with millions of fans outranks the modest real match
        httpClient.givenJsonResponse("search/artist", WRONG_NAME_MORE_POPULAR)

        // When - searching for the artist
        val result = api.searchArtist("Autechre")

        // Then - popularity is only a tiebreak among name matches
        assertEquals(7777L, result?.id)
    }

    @Test
    fun `returns null when no candidate name matches`() = runTest {
        // Given - every hit is a different artist
        httpClient.givenJsonResponse("search/artist", NO_NAME_MATCH)

        // When - searching for the artist
        val result = api.searchArtist("Autechre")

        // Then - no ghost is returned in place of a real miss
        assertNull(result)
    }

    @Test
    fun `prefers an exact name over a looser containing match with more fans`() = runTest {
        // Given - "DJ Radiohead" has more fans than the exact-name entry
        httpClient.givenJsonResponse("search/artist", LOOSE_MATCH_MORE_POPULAR)

        // When - searching for the artist
        val result = api.searchArtist("Radiohead")

        // Then - the exact name wins regardless of popularity
        assertEquals(399L, result?.id)
    }

    @Test
    fun `requests more than one hit so a ghost can be outvoted`() = runTest {
        // Given - any response
        httpClient.givenJsonResponse("search/artist", RADIOHEAD_GHOST_FIRST)

        // When - searching for the artist
        api.searchArtist("Radiohead")

        // Then - the search asks for a candidate pool, not a single hit
        assertTrue(httpClient.requestedUrls.single().endsWith("limit=10"))
    }

    @Test
    fun `a closer loose match beats a looser one with far more fans`() = runTest {
        // Given - no exact name in the pool: a containing match, and a popular half-token match
        httpClient.givenJsonResponse("search/artist", LOOSE_MATCH_BEATS_LOOSER_MATCH)

        // When - searching for the artist
        val result = api.searchArtist("Bad Company")

        // Then - name quality is ranked before popularity, so 9M fans does not carry Bad Bunny
        assertEquals(2002L, result?.id)
    }

    @Test
    fun `a non-object element does not fail the whole search`() = runTest {
        // Given - a malformed first element ahead of the real artist
        httpClient.givenJsonResponse("search/artist", MALFORMED_ELEMENT_FIRST)

        // When - searching for the artist
        val result = api.searchArtist("Radiohead")

        // Then - it is skipped, not thrown: a JSONException here would surface as Error and
        // open the circuit breaker against a healthy Deezer (docs/pitfalls.md §4)
        assertEquals(399L, result?.id)
    }

    @Test
    fun `the exact-name tier compares normalized names`() = runTest {
        // Given - the punctuated real name is outranked on fans by a looser match
        httpClient.givenJsonResponse("search/artist", PUNCTUATED_EXACT_NAME)

        // When - searching with the unpunctuated spelling
        val result = api.searchArtist("ACDC")

        // Then - "AC/DC" normalizes to an exact match, so it beats the tribute act
        assertEquals(115L, result?.id)
    }

    private companion object {
        const val RADIOHEAD_GHOST_FIRST = """
            {"data":[
              {"id":323887691,"name":"Radiohead","picture_medium":"https://cdn/ghost.jpg",
               "nb_album":0,"nb_fan":470},
              {"id":399,"name":"Radiohead","picture_medium":"https://cdn/real.jpg",
               "nb_album":45,"nb_fan":4065028},
              {"id":53477202,"name":"DJ Radiohead","nb_album":30,"nb_fan":63}
            ]}
        """

        const val PORTISHEAD_CORRECT_FIRST = """
            {"data":[
              {"id":1069,"name":"Portishead","picture_medium":"https://cdn/portishead.jpg",
               "nb_album":15,"nb_fan":578169},
              {"id":310697501,"name":"Portishead Adrian Utley, Ethiopiques","nb_album":0,"nb_fan":5}
            ]}
        """

        // The right-name candidate is deliberately NOT an exact match, so the assertion rests on
        // the name filter rejecting Taylor Swift — not on the same-name rank carrying it.
        const val WRONG_NAME_MORE_POPULAR = """
            {"data":[
              {"id":1111,"name":"Taylor Swift","nb_album":60,"nb_fan":9000000},
              {"id":7777,"name":"Autechre (Live)","nb_album":25,"nb_fan":120000}
            ]}
        """

        const val LOOSE_MATCH_BEATS_LOOSER_MATCH = """
            {"data":[
              {"id":2001,"name":"Bad Bunny","nb_album":20,"nb_fan":9000000},
              {"id":2002,"name":"Bad Company Live In Concert","nb_album":3,"nb_fan":5000}
            ]}
        """

        const val MALFORMED_ELEMENT_FIRST = """
            {"data":[
              null,
              {"id":399,"name":"Radiohead","nb_album":45,"nb_fan":4065028}
            ]}
        """

        const val NO_NAME_MATCH = """
            {"data":[
              {"id":1111,"name":"Taylor Swift","nb_album":60,"nb_fan":9000000},
              {"id":2222,"name":"Boards of Canada","nb_album":12,"nb_fan":400000}
            ]}
        """

        const val PUNCTUATED_EXACT_NAME = """
            {"data":[
              {"id":9999,"name":"ACDC Tribute Band","nb_album":4,"nb_fan":900000},
              {"id":115,"name":"AC/DC","nb_album":40,"nb_fan":3000}
            ]}
        """

        const val LOOSE_MATCH_MORE_POPULAR = """
            {"data":[
              {"id":53477202,"name":"DJ Radiohead","nb_album":30,"nb_fan":900000},
              {"id":399,"name":"Radiohead","nb_album":45,"nb_fan":4065}
            ]}
        """
    }
}
