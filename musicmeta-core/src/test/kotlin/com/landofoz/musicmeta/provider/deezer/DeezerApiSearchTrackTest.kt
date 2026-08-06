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
 * first-hit-wins disease [DeezerApiSearchArtistTest] pins for artists
 * (`.scratch/provider-code-findings/issues/19-deezer-track-search-drops-album-hint-first-hit-wins.md`).
 *
 * [BLACKENED_RANK_POOL] and [HARVESTER_ADVANCED_RESULT] mirror the live shapes from that ticket's
 * 2026-08-06 evidence: a remix ranked ahead of the studio original on a plain query, and the
 * studio original recoverable only via the advanced field query once an album hint is known.
 */
class DeezerApiSearchTrackTest {

    private val httpClient = FakeHttpClient()
    private val api = DeezerApi(httpClient, RateLimiter(0))

    @Test
    fun `sends a plain query when no album hint is given`() = runTest {
        // Given — a single matching candidate
        httpClient.givenJsonResponse("search/track", SINGLE_MATCH_RESPONSE)

        // When — searching without an album hint
        api.searchTrack("Blackened", "Metallica")

        // Then — the plain "artist title" query, not the advanced field syntax
        assertEquals(
            "https://api.deezer.com/search/track?q=Metallica+Blackened&limit=5",
            httpClient.requestedUrls.single(),
        )
    }

    @Test
    fun `sends the advanced field query when an album hint is given`() = runTest {
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
    fun `falls back to the plain query when the advanced query returns zero results`() = runTest {
        // Given — the advanced query (identifiable by its "track:" field) comes back empty, the
        // plain query (identifiable by its plain "q=Artist Title" shape) has the real candidate
        httpClient.givenJsonResponse("track%3A%22Harvester", """{"data":[],"total":0}""")
        httpClient.givenJsonResponse("q=Metallica+Harvester", SINGLE_MATCH_RESPONSE)

        // When — searching with an album hint that the advanced query cannot satisfy
        val result = api.searchTrack("Harvester of Sorrow", "Metallica", "Some Renamed Edition")

        // Then — both queries were sent, in that order, and the plain query's candidate wins
        assertEquals(2, httpClient.requestedUrls.size)
        assertTrue(httpClient.requestedUrls[0].contains("track%3A%22Harvester"))
        assertTrue(httpClient.requestedUrls[1].contains("q=Metallica+Harvester"))
        assertEquals(555L, result?.id)
    }

    @Test
    fun `resolves the album-hinted advanced query to the studio edition`() = runTest {
        // Given — Deezer's advanced query returns the studio track (verified live, 2026-08-06:
        // total=3, the studio "Harvester of Sorrow | ...And Justice for All (Remastered)" first)
        httpClient.givenJsonResponse("search/track", HARVESTER_ADVANCED_RESULT)

        // When — searching with the album hint
        val result = api.searchTrack("Harvester of Sorrow", "Metallica", "And Justice for All")

        // Then — the studio edition, not a live take
        assertEquals(20L, result?.id)
        assertEquals("...And Justice for All (Remastered)", result?.albumTitle)
    }

    @Test
    fun `ranks the exact-title studio track over an earlier-listed remix`() = runTest {
        // Given — a 5-candidate pool with the 2020 remix first and the studio original third,
        // mirroring live api.deezer.com "q=metallica blackened" (2026-08-06)
        httpClient.givenJsonResponse("search/track", BLACKENED_RANK_POOL)

        // When — searching without an album hint (the plain query's own pool must be re-ranked)
        val result = api.searchTrack("Blackened", "Metallica")

        // Then — the exact-title studio track wins, not the remix ranked first by Deezer
        assertEquals(3L, result?.id)
        assertEquals("Blackened", result?.title)
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
    fun `returns null when the pool has no candidate with a matching artist name`() = runTest {
        // Given — every hit is a different artist
        httpClient.givenJsonResponse(
            "search/track",
            """{"data":[{"id":1,"title":"Blackened","artist":{"name":"Some Cover Band"}}]}""",
        )

        // When — searching for the real artist
        val result = api.searchTrack("Blackened", "Metallica")

        // Then — no wrong-artist candidate is returned in place of a real miss
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

        const val BLACKENED_RANK_POOL = """
            {"data":[
              {"id":1,"title":"Blackened 2020","artist":{"id":399,"name":"Metallica"},
               "album":{"title":"Metallica (2020 Remixes)"}},
              {"id":2,"title":"Blackened (Live)","artist":{"id":399,"name":"Metallica"},
               "album":{"title":"S&M2"}},
              {"id":3,"title":"Blackened","artist":{"id":399,"name":"Metallica"},
               "album":{"title":"...And Justice for All"}},
              {"id":4,"title":"Blackened (Remastered)","artist":{"id":399,"name":"Metallica"},
               "album":{"title":"...And Justice for All (Remastered)"}},
              {"id":5,"title":"Blackened - Napalm Version","artist":{"id":399,"name":"Metallica"},
               "album":{"title":"Some Comp"}}
            ]}
        """
    }
}
