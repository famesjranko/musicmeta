package com.landofoz.musicmeta.provider.deezer

import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Selection rules for [DeezerApi.searchTrack] — the track-search instance of the same
 * first-hit-wins disease [DeezerApiSearchArtistTest] pins for artists.
 *
 * [BLACKENED_PLAIN_POOL] and [BLACKENED_ADVANCED_POOL] are trimmed from the live api.deezer.com
 * payloads for `q=Metallica Blackened` and `q=artist:"Metallica" track:"Blackened"` (2026-08-06):
 * the plain query's top 5 omits the studio original entirely — its best Metallica candidates are
 * the "Blackened 2020" remix and two live takes — while the field query returns the studio
 * original first. That asymmetry is why the field query is the primary query even without an
 * album hint: no ranking of the plain pool can produce a candidate the pool doesn't contain.
 */
class DeezerApiSearchTrackTest {

    private val httpClient = FakeHttpClient()
    private val api = DeezerApi(httpClient, RateLimiter(0))

    @Test
    fun `sends the advanced field query even without an album hint`() = runTest {
        // Given — a single matching candidate
        httpClient.givenJsonResponse("search/track", SINGLE_MATCH_RESPONSE)

        // When — searching without an album hint
        api.searchTrack("Blackened", "Metallica")

        // Then — the artist:/track: field query, not the plain "artist title" keyword query
        assertEquals(
            "https://api.deezer.com/search/track?q=artist%3A%22Metallica%22+track%3A%22Blackened%22&limit=5",
            httpClient.requestedUrls.single(),
        )
    }

    @Test
    fun `sends the album field in the advanced query when an album hint is given`() = runTest {
        // Given — a single matching candidate
        httpClient.givenJsonResponse("search/track", SINGLE_MATCH_RESPONSE)

        // When — searching with an album hint
        api.searchTrack("Harvester of Sorrow", "Metallica", "And Justice for All")

        // Then — the advanced artist:/track:/album: field query, quoted and URL-encoded
        assertEquals(
            "https://api.deezer.com/search/track?" +
                "q=artist%3A%22Metallica%22+track%3A%22Harvester+of+Sorrow%22+album%3A%22And+Justice+for+All%22" +
                "&limit=5",
            httpClient.requestedUrls.single(),
        )
    }

    @Test
    fun `falls back album field query, field query, plain query, narrowest first`() = runTest {
        // Given — both field queries come back empty; only the plain query has the candidate.
        // Registration order matters: FakeHttpClient matches first-registered substring, and the
        // album%3A key is the only one that distinguishes the album-hinted URL.
        httpClient.givenJsonResponse("album%3A", """{"data":[],"total":0}""")
        httpClient.givenJsonResponse("track%3A%22Harvester", """{"data":[],"total":0}""")
        httpClient.givenJsonResponse("q=Metallica+Harvester", SINGLE_MATCH_RESPONSE)

        // When — searching with an album hint neither field query can satisfy
        val result = api.searchTrack("Harvester of Sorrow", "Metallica", "Some Renamed Edition")

        // Then — all three queries were sent, narrowest first, and the plain candidate wins
        assertEquals(3, httpClient.requestedUrls.size)
        assertTrue(httpClient.requestedUrls[0].contains("album%3A"))
        assertTrue(httpClient.requestedUrls[1].contains("track%3A%22Harvester"))
        assertTrue(!httpClient.requestedUrls[1].contains("album%3A"))
        assertTrue(httpClient.requestedUrls[2].contains("q=Metallica+Harvester"))
        assertEquals(555L, result?.id)
    }

    @Test
    fun `falls back when a query's pool filters down to zero artist matches`() = runTest {
        // Given — the field query's pool is non-empty but every hit is a different artist;
        // the plain query has the real candidate. A raw-but-all-wrong-artist pool must fall
        // through like an empty one, not swallow the search as a miss.
        httpClient.givenJsonResponse(
            "track%3A%22Harvester",
            """{"data":[{"id":1,"title":"Harvester of Sorrow","artist":{"name":"Some Cover Band"}}]}""",
        )
        httpClient.givenJsonResponse("q=Metallica+Harvester", SINGLE_MATCH_RESPONSE)

        // When — searching without an album hint
        val result = api.searchTrack("Harvester of Sorrow", "Metallica")

        // Then — the plain query was still tried and its candidate returned
        assertEquals(2, httpClient.requestedUrls.size)
        assertEquals(555L, result?.id)
    }

    @Test
    fun `resolves the album-hinted advanced query to the studio edition`() = runTest {
        // Given — Deezer's advanced query returns the studio track (verified live, 2026-08-06:
        // the studio "Harvester of Sorrow | ...And Justice for All (Remastered)" first)
        httpClient.givenJsonResponse("search/track", HARVESTER_ADVANCED_RESULT)

        // When — searching with the album hint
        val result = api.searchTrack("Harvester of Sorrow", "Metallica", "And Justice for All")

        // Then — the studio edition, not a live take
        assertEquals(20L, result?.id)
        assertEquals("...And Justice for All (Remastered)", result?.albumTitle)
    }

    @Test
    fun `recovers the studio original the plain query's pool omits entirely`() = runTest {
        // Given — the live 2026-08-06 shape: the field query's pool has the studio "Blackened"
        // first, the remix and live takes behind it
        httpClient.givenJsonResponse("track%3A%22Blackened", BLACKENED_ADVANCED_POOL)
        httpClient.givenJsonResponse("q=Metallica+Blackened", BLACKENED_PLAIN_POOL)

        // When — searching without an album hint
        val result = api.searchTrack("Blackened", "Metallica")

        // Then — the exact-title studio track, from the field query alone; the plain query
        // (whose live pool contains no studio take to rank) was never needed
        assertEquals(575867532L, result?.id)
        assertEquals("Blackened", result?.title)
        assertEquals(1, httpClient.requestedUrls.size)
    }

    @Test
    fun `ranks the exact-title studio track over an earlier-listed remix`() = runTest {
        // Given — the field query's live pool order: "Blackened 2020" and live takes are
        // candidates alongside the studio original
        httpClient.givenJsonResponse(
            "search/track",
            """
            {"data":[
              {"id":958985372,"title":"Blackened 2020","artist":{"id":119,"name":"Metallica"},
               "album":{"title":"Blackened 2020"}},
              {"id":575867532,"title":"Blackened","artist":{"id":119,"name":"Metallica"},
               "album":{"title":"…And Justice for All (Remastered)"}}
            ]}
            """.trimIndent(),
        )

        // When — searching without an album hint
        val result = api.searchTrack("Blackened", "Metallica")

        // Then — the exact-title studio track wins, not the remix listed first
        assertEquals(575867532L, result?.id)
    }

    @Test
    fun `treats a bare trailing year as an unrequested edition marker`() = runTest {
        // Given — nothing has the exact requested title; Deezer marks its remix with a bare
        // year suffix ("Blackened 2020", live 2026-08-06), not a parenthetical
        httpClient.givenJsonResponse(
            "search/track",
            """
            {"data":[
              {"id":40,"title":"Blackened 2020","artist":{"name":"Metallica"}},
              {"id":41,"title":"Blackened Again","artist":{"name":"Metallica"}}
            ]}
            """.trimIndent(),
        )

        // When — searching without an album hint
        val result = api.searchTrack("Blackened", "Metallica")

        // Then — the year-suffixed remix loses to the suffix without a marker
        assertEquals(41L, result?.id)
    }

    @Test
    fun `does not penalise a year the request itself contains`() = runTest {
        // Given — the request is for the year-suffixed edition by exact title
        httpClient.givenJsonResponse(
            "search/track",
            """
            {"data":[
              {"id":50,"title":"Blackened 2020","artist":{"name":"Metallica"}},
              {"id":51,"title":"Blackened","artist":{"name":"Metallica"}}
            ]}
            """.trimIndent(),
        )

        // When — the request explicitly names the 2020 edition
        val result = api.searchTrack("Blackened 2020", "Metallica")

        // Then — the exact-title tier wins; its own year is not held against it
        assertEquals(50L, result?.id)
    }

    @Test
    fun `prefers a marker-free approximate title over a live-tagged one when nothing is exact`() = runTest {
        // Given — neither candidate has the exact requested title; only one carries an
        // unrequested "(Live ...)" marker
        httpClient.givenJsonResponse(
            "search/track",
            """
            {"data":[
              {"id":10,"title":"Harvester Of Sorrow (Live In Mexico City)","artist":{"name":"Metallica"}},
              {"id":11,"title":"Harvester Of Sorrow - Rehearsal","artist":{"name":"Metallica"}}
            ]}
            """.trimIndent(),
        )

        // When — searching without an album hint
        val result = api.searchTrack("Harvester of Sorrow", "Metallica")

        // Then — the marker-free candidate is preferred
        assertEquals(11L, result?.id)
    }

    @Test
    fun `penalises a dash-suffixed live marker with no parentheses`() = runTest {
        // Given — the live take is marked by a bare " - Live at …" suffix, no parentheses
        httpClient.givenJsonResponse(
            "search/track",
            """
            {"data":[
              {"id":60,"title":"Harvester of Sorrow - Live at Wembley","artist":{"name":"Metallica"}},
              {"id":61,"title":"Harvester of Sorrow - Rehearsal","artist":{"name":"Metallica"}}
            ]}
            """.trimIndent(),
        )

        // When — searching without an album hint
        val result = api.searchTrack("Harvester of Sorrow", "Metallica")

        // Then — the dash-suffixed live take loses like a parenthetical one would
        assertEquals(61L, result?.id)
    }

    @Test
    fun `does not penalise a parenthetical marker the request itself asked for`() = runTest {
        // Given — the requested title itself names the live take
        httpClient.givenJsonResponse(
            "search/track",
            """
            {"data":[
              {"id":30,"title":"Harvester Of Sorrow (Live In Mexico City)","artist":{"name":"Metallica"}},
              {"id":31,"title":"Harvester Of Sorrow - Rehearsal","artist":{"name":"Metallica"}}
            ]}
            """.trimIndent(),
        )

        // When — the request explicitly asks for the live version by exact title
        val result = api.searchTrack("Harvester Of Sorrow (Live In Mexico City)", "Metallica")

        // Then — the exact-title tier wins outright; the marker is not held against it
        assertEquals(30L, result?.id)
    }

    @Test
    fun `returns null when no query yields a candidate with a matching artist name`() = runTest {
        // Given — every hit, on every query, is a different artist
        httpClient.givenJsonResponse(
            "search/track",
            """{"data":[{"id":1,"title":"Blackened","artist":{"name":"Some Cover Band"}}]}""",
        )

        // When — searching for the real artist
        val result = api.searchTrack("Blackened", "Metallica")

        // Then — both queries were exhausted; no wrong-artist candidate replaces a real miss
        assertEquals(2, httpClient.requestedUrls.size)
        assertNull(result)
    }

    private companion object {
        const val SINGLE_MATCH_RESPONSE = """
            {"data":[
              {"id":555,"title":"Harvester of Sorrow","artist":{"id":399,"name":"Metallica"},
               "album":{"title":"...And Justice for All"}}
            ]}
        """

        const val HARVESTER_ADVANCED_RESULT = """
            {"data":[
              {"id":20,"title":"Harvester of Sorrow","artist":{"id":399,"name":"Metallica"},
               "album":{"title":"...And Justice for All (Remastered)"}}
            ],"total":1}
        """

        /** Live `q=artist:"Metallica" track:"Blackened"` top hits, trimmed (2026-08-06). */
        const val BLACKENED_ADVANCED_POOL = """
            {"data":[
              {"id":575867532,"title":"Blackened","artist":{"id":119,"name":"Metallica"},
               "album":{"title":"…And Justice for All (Remastered)"}},
              {"id":958985372,"title":"Blackened 2020","artist":{"id":119,"name":"Metallica"},
               "album":{"title":"Blackened 2020"}},
              {"id":575877082,"title":"Blackened (Live / Seattle '89)","artist":{"id":119,"name":"Metallica"},
               "album":{"title":"…And Justice for All (Remastered Deluxe Box Set)"}},
              {"id":140687657,"title":"Blackened (Remastered) (Live)","artist":{"id":119,"name":"Metallica"},
               "album":{"title":"Live At The Reunion Arena, Dallas, Tx 5 Feb '89 (Remastered)"}}
            ]}
        """

        /** Live `q=Metallica Blackened` top 5, trimmed (2026-08-06) — no studio original at all. */
        const val BLACKENED_PLAIN_POOL = """
            {"data":[
              {"id":958985372,"title":"Blackened 2020","artist":{"id":119,"name":"Metallica"},
               "album":{"title":"Blackened 2020"}},
              {"id":575877082,"title":"Blackened (Live / Seattle '89)","artist":{"id":119,"name":"Metallica"},
               "album":{"title":"…And Justice for All (Remastered Deluxe Box Set)"}},
              {"id":140687657,"title":"Blackened (Remastered) (Live)","artist":{"id":119,"name":"Metallica"},
               "album":{"title":"Live At The Reunion Arena, Dallas, Tx 5 Feb '89 (Remastered)"}},
              {"id":3788342752,"title":"Blackened","artist":{"id":463,"name":"Apocalyptica"},
               "album":{"title":"Plays Metallica (Vol. 2)"}},
              {"id":3205774601,"title":"Enter Sandman (Symphony Orchestra Version)",
               "artist":{"id":9999,"name":"Blackened Symphony Orchestra"},
               "album":{"title":"Blackened, Sinfonía Metallica 2024"}}
            ]}
        """
    }
}
