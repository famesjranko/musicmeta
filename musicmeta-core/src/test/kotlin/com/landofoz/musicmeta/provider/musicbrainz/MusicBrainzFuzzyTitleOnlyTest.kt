package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.net.URLDecoder

@RunWith(Parameterized::class)
class MusicBrainzFuzzyTitleOnlyTest(private val entity: String) {
    private val httpClient = FakeHttpClient()
    private val api = MusicBrainzApi(httpClient, RateLimiter(0))

    @Test
    fun `a title-only typo returns related candidates from the live capture`() = runTest {
        // Given - captured responses for the corrected query and both faulty query shapes
        givenCapture("$entity:Karma~ AND $entity:Polise~", "karma-polise-and-terms")
        givenCapture("$entity:Karma Polise~", "karma-polise-drop-artist")
        httpClient.givenJsonResponse("$entity?query", capture("karma-polise-original"))

        // When - searching for the misspelled title without an artist
        val titles = fuzzyTitles("Karma Polise", "")

        // Then - every candidate is the intended title and the search costs one request
        assertEquals(listOf("Karma Police", "Karma Police", "Karma Police"), titles)
        assertEquals("$entity:Karma~ AND $entity:Polise~", queries().single())
    }

    @Test
    fun `a one-word typo retains fuzzy title matching`() = runTest {
        // Given - a captured response to the one-word fuzzy query
        givenCapture("$entity:Paranojd~", "paranojd-and-terms")

        // When - searching for a one-word typo without an artist
        val titles = fuzzyTitles("Paranojd", "")

        // Then - the related titles are retained in upstream order in one request
        val expected = if (entity == "recording") {
            listOf("Paranoid, Paranoia", "Paranoid, Paranoia", "Paranoid (Prelude to Paranoia)")
        } else {
            listOf("Paranoid", "Paranoid", "Paranoid")
        }
        assertEquals(expected, titles)
        assertEquals("$entity:Paranojd~", queries().single())
    }

    @Test
    fun `a correctly spelled title still returns related candidates`() = runTest {
        // Given - a captured response to the correctly spelled title query
        givenCapture("$entity:Karma~ AND $entity:Police~", "karma-police-and-terms")

        // When - searching for the correct spelling without an artist
        val titles = fuzzyTitles("Karma Police", "")

        // Then - all three captured candidates survive and every title term is required
        assertEquals(listOf("Karma Police", "Karma Police", "Karma Police"), titles)
        assertEquals("$entity:Karma~ AND $entity:Police~", queries().single())
    }

    @Test
    fun `a supplied artist preserves the query and request count`() = runTest {
        // Given - the captured response to the existing artist-qualified typo query
        givenCapture("$entity:Karma Polise~ AND artistname:Radiohead~", "karma-polise-with-artist")

        // When - searching with an artist and an explicit limit
        val titles = fuzzyTitles("Karma Polise", "Radiohead", limit = 3)

        // Then - the same query returns the same three titles in one request
        assertEquals(listOf("Karma Police", "Karma Police", "Karma Police"), titles)
        assertEquals("$entity:Karma Polise~ AND artistname:Radiohead~", queries().single())
        assertEquals("fmt=json&limit=3", httpClient.requestedUrls.single().substringAfter("&"))
    }

    @Test
    fun `blank artists and whitespace cannot introduce unfielded title terms`() = runTest {
        // Given - a captured empty pool and title words containing Lucene syntax
        httpClient.givenJsonResponse("$entity?query", capture("karma-polise-quoted"))

        // When - searching with a whitespace artist and irregular title spacing
        fuzzyTitles("  Karma\tPolise?\n", " \t ", limit = 7)

        // Then - each escaped word stays in the title field and the requested limit survives
        assertEquals("$entity:Karma~ AND $entity:Polise\\?~", queries().single())
        assertEquals("fmt=json&limit=7", httpClient.requestedUrls.single().substringAfter("&"))
    }

    @Test
    fun `a blank title and artist return nothing without searching`() = runTest {
        // Given - a request with no title words or artist
        val title = " \t\n"

        // When - searching without any identifying text
        val titles = fuzzyTitles(title, " ")

        // Then - no broad query is sent to MusicBrainz
        assertEquals(emptyList<String>(), titles)
        assertEquals(emptyList<String>(), httpClient.requestedUrls)
    }

    @Test
    fun `candidate search uses the corrected fuzzy pool after a strict miss`() = runTest {
        // Given - a strict miss and the captured corrected fuzzy response
        givenCapture("$entity:Karma~ AND $entity:Polise~", "karma-polise-and-terms")
        httpClient.givenJsonResponse("%22", capture("karma-polise-quoted"))
        httpClient.givenJsonResponse("$entity?query", capture("karma-polise-original"))
        val provider = MusicBrainzProvider(httpClient, RateLimiter(0))
        val request = if (entity == "recording") {
            EnrichmentRequest.forTrack("Karma Polise", "")
        } else {
            EnrichmentRequest.forAlbum("Karma Polise", "")
        }

        // When - asking the provider for title-only candidates
        val candidates = provider.searchCandidates(request, 3)

        // Then - the strict request and one fuzzy retry return only the related titles
        assertEquals(listOf("Karma Police", "Karma Police", "Karma Police"), candidates.map { it.title })
        assertEquals(2, httpClient.requestedUrls.size)
        assertEquals("$entity:Karma~ AND $entity:Polise~", queries().last())
    }

    private suspend fun fuzzyTitles(title: String, artist: String, limit: Int = 3): List<String> =
        if (entity == "recording") {
            api.searchRecordingsFuzzy(title, artist, limit).map { it.title }
        } else {
            api.searchReleasesFuzzy(title, artist, limit).map { it.title }
        }

    private fun capture(name: String): String = checkNotNull(
        javaClass.getResourceAsStream("/pools/musicbrainz-fuzzy-title-only/$entity-$name.json"),
    ).bufferedReader().use { it.readText() }

    private fun givenCapture(query: String, name: String) {
        httpClient.givenJsonResponse(
            "query=${java.net.URLEncoder.encode(query, Charsets.UTF_8.name())}&",
            capture(name),
        )
    }

    private fun queries(): List<String> = httpClient.requestedUrls.map {
        URLDecoder.decode(it.substringAfter("query=").substringBefore("&"), Charsets.UTF_8.name())
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun entities(): List<String> = listOf("recording", "release")
    }
}
