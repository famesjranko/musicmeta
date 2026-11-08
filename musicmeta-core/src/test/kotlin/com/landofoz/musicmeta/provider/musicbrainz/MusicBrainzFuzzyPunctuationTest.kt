package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.net.URLEncoder

class MusicBrainzFuzzyPunctuationTest {
    private val httpClient = FakeHttpClient()
    private val api = MusicBrainzApi(httpClient, RateLimiter(0))

    @Test
    fun `parentheses are dropped and internal apostrophes survive a typo search`() = runTest {
        // Given - captured responses for the word query and the punctuated query that misses
        val query = "recording:I AND recording:Can't~ AND recording:Get AND recording:No " +
            "AND recording:Satisfacton~ AND (recording:Can't OR recording:Satisfacton)"
        givenCapture(query, "recording-satisfaction-title-case-exact-word")
        val expectedQuery = "recording:i AND recording:can't~ AND recording:get AND recording:no " +
            "AND recording:satisfacton~"
        givenCapture(expectedQuery, "recording-satisfaction")
        httpClient.givenJsonResponse("recording?query", capture("recording-satisfaction-punctuated"))

        // When - searching with straight and curly apostrophes inside the same title
        val straight = api.searchRecordingsFuzzy("(I Can't Get No) Satisfacton", "")
        val curly = api.searchRecordingsFuzzy("(I Can’t Get No) Satisfacton", "")

        // Then - both spellings use the same words and return the captured related title
        assertEquals(listOf(expectedQuery, expectedQuery), queries())
        assertEquals(3, straight.size)
        assertEquals("(I Can’t Get No Satisfaction) Satisfaction", straight.first().title)
        assertEquals(straight, curly)
    }

    @Test
    fun `an album punctuation miss can offer the findable title`() = runTest {
        // Given - the word query finds Rumours editions while the punctuation query misses
        val query = "release:Rumours~ AND release:Deluxe~ AND (release:Rumours OR release:Deluxe)"
        givenCapture(query, "release-rumours-title-case-exact-word")
        val expectedQuery = "release:rumours~ AND release:deluxe~"
        givenCapture(expectedQuery, "release-rumours")
        httpClient.givenJsonResponse("release?query", capture("release-rumours-punctuated"))

        // When - searching for the correctly spelled album title with a punctuation separator
        val releases = api.searchReleasesFuzzy("Rumours - Deluxe", "")

        // Then - the separator is absent from the query and the intended edition is offered
        assertEquals(expectedQuery, queries().single())
        assertEquals(3, releases.size)
        assertEquals("Rumours (deluxe)", releases.first().title)
    }

    @Test
    fun `album enrichment does not offer short word noise after a strict miss`() = runTest {
        // Given - captured strict misses and the unrelated fuzzy rows for both unsafe title shapes
        val subjects = listOf("東凉" to "short-cjk", "F♯ B♯ ∞" to "symbols").map { (title, name) ->
            val client = FakeHttpClient().apply {
                givenJsonResponse("%22", capture("release-$name-strict"))
                givenJsonResponse("release?query", capture("release-$name-fuzzy"))
            }
            Triple(title, client, MusicBrainzProvider(client, RateLimiter(0)))
        }

        // When - enriching each title without an artist
        val results = subjects.map { (title, _, provider) ->
            provider.enrich(EnrichmentRequest.forAlbum(title, ""), EnrichmentType.GENRE)
        }

        // Then - the misses offer no unrelated suggestions and cost only their strict request
        results.forEach { result ->
            assertTrue(result is EnrichmentResult.NotFound)
            assertTrue((result as EnrichmentResult.NotFound).suggestions.isNullOrEmpty())
        }
        subjects.forEach { (_, client, _) -> assertEquals(1, client.requestedUrls.size) }
    }

    private fun capture(name: String): String = checkNotNull(
        javaClass.getResourceAsStream("/pools/musicbrainz-fuzzy-title-only/$name.json"),
    ).bufferedReader().use { it.readText() }

    private fun givenCapture(query: String, name: String) {
        httpClient.givenJsonResponse("query=${URLEncoder.encode(query, Charsets.UTF_8.name())}&", capture(name))
    }

    private fun queries(): List<String> = httpClient.requestedUrls.map {
        URLDecoder.decode(it.substringAfter("query=").substringBefore("&"), Charsets.UTF_8.name())
    }
}
