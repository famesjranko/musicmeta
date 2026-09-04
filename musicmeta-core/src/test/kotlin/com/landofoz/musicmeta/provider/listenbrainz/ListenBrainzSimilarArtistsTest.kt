package com.landofoz.musicmeta.provider.listenbrainz

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.IdentifierRequirement
import com.landofoz.musicmeta.http.HttpResult
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Labs `similar-artists` route: what it answers with, and the three answers that are not data.
 *
 * The bodies below are the live route's own, captured 2026-09-05 with the pinned algorithm; the
 * hundred-entry ones are cut to their first three entries, each entry verbatim.
 *
 * The pinned algorithm string is written out here rather than read from [ListenBrainzApi]: a test
 * that reads the constant agrees with whatever the constant says, and the point of pinning is that
 * moving it is a decision somebody makes on purpose.
 */
class ListenBrainzSimilarArtistsTest {

    private lateinit var httpClient: FakeHttpClient
    private lateinit var provider: ListenBrainzProvider

    @Before
    fun setUp() {
        httpClient = FakeHttpClient()
        provider = ListenBrainzProvider(httpClient, RateLimiter(0L))
    }

    @Test
    fun `enrich maps a Labs answer to named artists carrying their MBID and a relative score`() = runTest {
        // Given - the shape the live route returns, captured 2026-09-05: scored rows, top score first
        httpClient.givenJsonArrayResponse("similar-artists/json", RADIOHEAD_ANSWER)

        // When - enriching an artist Labs has ample session data for
        val result = provider.enrich(radioheadRequest(), EnrichmentType.SIMILAR_ARTISTS)

        // Then - each row keeps its name and MBID, and the scores are scaled to the top row and halved,
        // so this provider's own favourite cannot reach the top of a merged list alone
        assertTrue("a mapped answer is a Success, got $result", result is EnrichmentResult.Success)
        val similar = (result as EnrichmentResult.Success).data as EnrichmentData.SimilarArtists
        assertEquals(3, similar.artists.size)
        assertEquals("Nirvana", similar.artists[0].name)
        assertEquals("5b11f4ce-a62d-471e-81fc-a69a8278c7da", similar.artists[0].identifiers.musicBrainzId)
        assertEquals(listOf("listenbrainz"), similar.artists[0].sources)
        assertEquals(0.5f, similar.artists[0].matchScore, 0.0001f)
        assertEquals("Red Hot Chili Peppers", similar.artists[1].name)
        assertEquals(10587f / 11156f * 0.5f, similar.artists[1].matchScore, 0.0001f)
        assertEquals("Muse", similar.artists[2].name)
        assertEquals(10286f / 11156f * 0.5f, similar.artists[2].matchScore, 0.0001f)
    }

    @Test
    fun `enrich answers a long-tail artist with the thin list Labs has for it`() = runTest {
        // Given - the live route's whole answer for an artist with one neighbour, captured 2026-09-05
        httpClient.givenJsonArrayResponse("similar-artists/json", THIN_ANSWER)

        // When - enriching that artist
        val result = provider.enrich(thinRequest(), EnrichmentType.SIMILAR_ARTISTS)

        // Then - one artist, not a NotFound: a short list is thin data, not missing data
        assertTrue("thin data is still data, got $result", result is EnrichmentResult.Success)
        val similar = (result as EnrichmentResult.Success).data as EnrichmentData.SimilarArtists
        assertEquals("Gregg Plummer", similar.artists.single().name)
        assertEquals(0.5f, similar.artists.single().matchScore, 0.0001f)
    }

    @Test
    fun `enrich returns NotFound when Labs answers an empty list`() = runTest {
        // Given - the live route's answer for an artist it holds no similarity data for, captured 2026-09-05
        httpClient.givenJsonArrayResponse("similar-artists/json", "[]")

        // When - enriching that artist
        val result = provider.enrich(emptyRequest(), EnrichmentType.SIMILAR_ARTISTS)

        // Then - the route was asked, and its empty answer is the NotFound Last.fm's route gives for one
        assertEquals(1, httpClient.requestedUrls.size)
        assertTrue("an empty answer is NotFound, got $result", result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns Error when Labs rejects the pinned algorithm`() = runTest {
        // Given - the 400 the live route answers an algorithm it does not know, captured 2026-09-05
        httpClient.givenHttpResultArray(
            "similar-artists/json",
            HttpResult.ClientError(400, ALGORITHM_REJECTED_BODY),
        )

        // When - enriching with the pinned algorithm that upstream no longer accepts
        val result = provider.enrich(radioheadRequest(), EnrichmentType.SIMILAR_ARTISTS)

        // Then - an Error naming the algorithm, never the empty answer a retirement would otherwise look like
        assertTrue("a rejected algorithm is an Error, got $result", result is EnrichmentResult.Error)
        val error = result as EnrichmentResult.Error
        assertEquals(ErrorKind.UNKNOWN, error.errorKind)
        assertTrue(
            "the message must name the algorithm that was refused, got ${error.message}",
            PINNED_ALGORITHM in error.message,
        )
    }

    @Test
    fun `enrich returns a NETWORK Error when the Labs host sheds the request`() = runTest {
        // Given - a 503 from the Labs host rather than a rejection of the request
        httpClient.givenHttpResultArray("similar-artists/json", HttpResult.ServerError(503))

        // When - enriching an artist
        val result = provider.enrich(radioheadRequest(), EnrichmentType.SIMILAR_ARTISTS)

        // Then - a transient Error, which a shed-tolerant caller can tell from a retired algorithm
        assertTrue("a shed is an Error, got $result", result is EnrichmentResult.Error)
        assertEquals(ErrorKind.NETWORK, (result as EnrichmentResult.Error).errorKind)
    }

    @Test
    fun `the request asks the Labs host for the pinned algorithm under the plural mbid parameter`() = runTest {
        // Given - a route that answers whatever it is asked
        httpClient.givenJsonArrayResponse("similar-artists/json", RADIOHEAD_ANSWER)

        // When - enriching an artist
        provider.enrich(radioheadRequest(), EnrichmentType.SIMILAR_ARTISTS)

        // Then - the URL is the Labs host, the plural parameter, and the one algorithm this route is pinned to
        val url = httpClient.requestedUrls.single()
        assertEquals(
            "https://labs.api.listenbrainz.org/similar-artists/json" +
                "?artist_mbids=$RADIOHEAD_MBID&algorithm=$PINNED_ALGORITHM",
            url,
        )
    }

    @Test
    fun `SIMILAR_ARTISTS is declared at priority 50 on a resolved MBID`() = runTest {
        // Given - a provider built without a token, since the Labs route needs none
        val provider = ListenBrainzProvider(FakeHttpClient(), RateLimiter(0L))

        // When - reading the SIMILAR_ARTISTS capability it declares
        val capability = provider.capabilities.single { it.type == EnrichmentType.SIMILAR_ARTISTS }

        // Then - 50, the fallback priority this provider uses where another is primary, and it needs the MBID
        assertEquals(50, capability.priority)
        assertEquals(IdentifierRequirement.MUSICBRAINZ_ID, capability.identifierRequirement)
    }

    @Test
    fun `a refusal that is not a 400 does not blame the pinned algorithm`() = runTest {
        // Given - a 404 from the Labs host, which says nothing about the algorithm
        httpClient.givenHttpResultArray("similar-artists/json", HttpResult.ClientError(404))

        // When - enriching an artist
        val result = provider.enrich(radioheadRequest(), EnrichmentType.SIMILAR_ARTISTS)

        // Then - still an Error, but one that names the status rather than accusing the algorithm
        assertTrue("a refusal is an Error, got $result", result is EnrichmentResult.Error)
        val message = (result as EnrichmentResult.Error).message
        assertTrue("the message must name the status, got $message", "404" in message)
        assertTrue("only a 400 says the algorithm was retired, got $message", PINNED_ALGORITHM !in message)
    }

    @Test
    fun `enrich returns NotFound for SIMILAR_ARTISTS when the request carries no MBID`() = runTest {
        // Given - a request that identity resolution never settled an MBID for
        val request = EnrichmentRequest.ForArtist(identifiers = EnrichmentIdentifiers(), name = "Radiohead")

        // When - enriching for similar artists
        val result = provider.enrich(request, EnrichmentType.SIMILAR_ARTISTS)

        // Then - NotFound without a request, since the Labs route is keyed on the MBID and has no name form
        assertTrue("no MBID means no route to call, got $result", result is EnrichmentResult.NotFound)
        assertTrue("nothing should have been requested", httpClient.requestedUrls.isEmpty())
    }

    private fun radioheadRequest() = EnrichmentRequest.ForArtist(
        identifiers = EnrichmentIdentifiers(musicBrainzId = RADIOHEAD_MBID),
        name = "Radiohead",
    )

    private fun thinRequest() = EnrichmentRequest.ForArtist(
        identifiers = EnrichmentIdentifiers(musicBrainzId = "440fd8aa-2a36-460c-8615-8af2cc06d62e"),
        name = "Scott Lawlor",
    )

    private fun emptyRequest() = EnrichmentRequest.ForArtist(
        identifiers = EnrichmentIdentifiers(musicBrainzId = "688a0168-2cc4-4d95-a4cb-e0883cafd8e9"),
        name = "Darius Ciuta",
    )

    private companion object {
        const val RADIOHEAD_MBID = "a74b1b7f-71a5-4011-9441-d0b5e4122711"

        const val PINNED_ALGORITHM =
            "session_based_days_7500_session_300_contribution_5_threshold_10_limit_100_filter_True_skip_30"

        val RADIOHEAD_ANSWER = """
            [
              {"artist_mbid": "5b11f4ce-a62d-471e-81fc-a69a8278c7da", "name": "Nirvana",
               "comment": "1980s-1990s US grunge band", "type": "Group", "gender": null,
               "score": 11156, "reference_mbid": "$RADIOHEAD_MBID"},
              {"artist_mbid": "8bfac288-ccc5-448d-9573-c33ea2aa5c30", "name": "Red Hot Chili Peppers",
               "comment": "", "type": "Group", "gender": null,
               "score": 10587, "reference_mbid": "$RADIOHEAD_MBID"},
              {"artist_mbid": "9c9f1380-2516-4fc9-a3e6-f9f61941d090", "name": "Muse",
               "comment": "UK rock band", "type": "Group", "gender": null,
               "score": 10286, "reference_mbid": "$RADIOHEAD_MBID"}
            ]
        """.trimIndent()

        val THIN_ANSWER = """
            [
              {"artist_mbid": "dc692467-9458-402e-884a-7fd16aede0c1", "name": "Gregg Plummer",
               "comment": "", "type": "Person", "gender": null,
               "score": 12, "reference_mbid": "440fd8aa-2a36-460c-8615-8af2cc06d62e"}
            ]
        """.trimIndent()

        /** The live 400 body, cut to the sentence that enumerates the members the route still accepts. */
        val ALGORITHM_REJECTED_BODY = """
            <!doctype html>
            <html lang=en>
            <title>400 Bad Request</title>
            <h1>Bad Request</h1>
            <p>1 validation error for SimilarArtistsViewerInput<br>algorithm<br>  value is not a valid
            enumeration member; permitted: 'session_based_days_1825_session_300_contribution_3_threshold_10_limit_100_filter_True_skip_30',
            'session_based_days_7500_session_300_contribution_3_threshold_10_limit_100_filter_True_skip_30',
            'session_based_days_9000_session_300_contribution_5_threshold_15_limit_50_skip_30',
            'session_based_days_75_session_300_contribution_5_threshold_10_limit_100_filter_True_skip_30',
            'session_based_days_7500_session_300_contribution_5_threshold_10_limit_100_filter_True_skip_30',
            'session_based_days_1800_session_300_contribution_3_threshold_10_limit_100_filter_True_skip_30'
            (type=type_error.enum)</p>
        """.trimIndent()
    }
}
