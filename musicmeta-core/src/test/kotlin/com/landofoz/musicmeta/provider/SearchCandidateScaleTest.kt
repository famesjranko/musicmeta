package com.landofoz.musicmeta.provider

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.provider.deezer.DeezerProvider
import com.landofoz.musicmeta.provider.itunes.ITunesProvider
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzProvider
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SearchCandidate.matchScore` is published as 0.0–1.0, and every provider that builds one has to
 * land inside that range whatever its upstream sends. MusicBrainz forwards a 0–100 search score it
 * does not promise to bound; iTunes and Deezer have no such number and substitute a constant.
 */
class SearchCandidateScaleTest {

    private val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

    @Test
    fun `no MusicBrainz candidate carries a matchScore outside the published range`() = runTest {
        // Given - a release search whose hit scores 101, past the top of MusicBrainz's own scale
        val httpClient = FakeHttpClient()
        httpClient.givenJsonResponse("release?query", RELEASE_SEARCH_OVER_100)

        // When - searching for candidates
        val candidates = MusicBrainzProvider(httpClient, RateLimiter(0)).searchCandidates(request, 10)

        // Then - the over-range score is capped rather than published as it arrived
        assertInRange(candidates)
    }

    @Test
    fun `no iTunes candidate carries a matchScore outside the published range`() = runTest {
        // Given - an iTunes album search response
        val httpClient = FakeHttpClient()
        httpClient.givenJsonResponse("itunes.apple.com", ITUNES_SEARCH)

        // When - searching for candidates
        val candidates = ITunesProvider(httpClient, RateLimiter(0)).searchCandidates(request, 10)

        // Then - the flat constant iTunes stands in with is already on the published scale
        assertInRange(candidates)
    }

    @Test
    fun `no Deezer candidate carries a matchScore outside the published range`() = runTest {
        // Given - a Deezer album search response
        val httpClient = FakeHttpClient()
        httpClient.givenJsonResponse("api.deezer.com", DEEZER_SEARCH)

        // When - searching for candidates
        val candidates = DeezerProvider(httpClient, RateLimiter(0)).searchCandidates(request, 10)

        // Then - the flat constant Deezer stands in with is already on the published scale
        assertInRange(candidates)
    }

    private fun assertInRange(candidates: List<SearchCandidate>) {
        assertTrue("the provider returned no candidate to check", candidates.isNotEmpty())
        candidates.forEach {
            assertTrue(
                "matchScore is published as 0.0-1.0, but ${it.provider} sent ${it.matchScore}",
                it.matchScore in 0f..1f,
            )
        }
    }

    private companion object {
        /**
         * Synthetic, not a capture: no recorded MusicBrainz response in this repo scores above 100,
         * and the clamp exists so that a Lucene score that does never reaches the published scale.
         */
        val RELEASE_SEARCH_OVER_100 = """
            {
              "releases": [
                {
                  "id": "over100",
                  "score": 101,
                  "title": "OK Computer",
                  "artist-credit": [{"artist": {"id": "def456", "name": "Radiohead"}}]
                }
              ]
            }
        """.trimIndent()

        val ITUNES_SEARCH = """
            {"resultCount":1,"results":[{
                "collectionName":"OK Computer",
                "artistName":"Radiohead",
                "artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/Music/100x100bb.jpg"
            }]}
        """.trimIndent()

        val DEEZER_SEARCH = """
            {"data":[{
                "title":"OK Computer",
                "artist":{"name":"Radiohead"},
                "cover_medium":"https://e-cdns-images.dzcdn.net/images/cover/medium.jpg"
            }]}
        """.trimIndent()
    }
}
