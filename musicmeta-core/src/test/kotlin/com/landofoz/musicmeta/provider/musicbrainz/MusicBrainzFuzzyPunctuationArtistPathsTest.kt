package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.net.URLDecoder
import java.net.URLEncoder

@RunWith(Parameterized::class)
class MusicBrainzFuzzyPunctuationArtistPathsTest(private val endpoint: String, private val case: String) {
    private val records = JSONArray(resource("captures.json"))

    private fun entry(arm: String) = (0 until records.length())
        .map { records.getJSONObject(it) }
        .single { it.getString("endpoint") == endpoint && it.getString("case") == case && it.getString("arm") == arm }

    private fun route(client: FakeHttpClient, arm: String) {
        val capture = entry(arm)
        client.givenJsonResponse(
            "query=${URLEncoder.encode(capture.getString("query"), "UTF-8")}&",
            resource(capture.getString("file")),
        )
    }

    private fun resource(file: String) = checkNotNull(
        javaClass.getResourceAsStream("/pools/musicbrainz-fuzzy-punctuation-artist/$file"),
    ).bufferedReader().use { it.readText() }

    private fun queries(client: FakeHttpClient) = client.requestedUrls.map {
        URLDecoder.decode(it.substringAfter("query=").substringBefore("&"), "UTF-8")
    }

    private fun expectedRows(): Set<Triple<String, String, String?>> {
        val rows = entry("phrase").getJSONArray("rows")
        return (0 until rows.length()).mapTo(mutableSetOf()) { index ->
            val row = rows.getJSONObject(index)
            val artist = if (row.isNull("parsedArtist")) null else row.getString("parsedArtist")
            Triple(row.getString("id"), row.getString("title"), artist)
        }
    }

    @Test
    fun `candidate typo search keeps the punctuation artist in one fuzzy request`() = runTest {
        // Given - strict, current, term and phrase captures are routed by their complete queries
        val client = FakeHttpClient()
        listOf("strict", "current", "term", "phrase").forEach { route(client, it) }
        val capture = entry("phrase")
        val request = if (endpoint == "recording") {
            EnrichmentRequest.forTrack(capture.getString("title"), capture.getString("artist"))
        } else {
            EnrichmentRequest.forAlbum(capture.getString("title"), capture.getString("artist"))
        }
        val provider = MusicBrainzProvider(client, RateLimiter(0))

        // When - searching for candidates after the strict typo misses
        val candidates = provider.searchCandidates(request, 3)

        // Then - strict search is followed by one exact phrase query and no other request
        assertEquals(listOf(entry("strict").getString("query"), entry("phrase").getString("query")), queries(client))
        assertEquals(2, client.requestedUrls.size)
        assertTrue(client.requestedUrls[0].endsWith("&fmt=json&limit=100"))
        assertTrue(client.requestedUrls[1].contains("/$endpoint?query="))
        assertTrue(client.requestedUrls[1].endsWith("&fmt=json&limit=3"))
        assertEquals(if (case == "bang") 3 else 2, candidates.size)
        assertEquals(
            expectedRows(),
            candidates.map { Triple(it.identifiers.musicBrainzId, it.title, it.artist) }.toSet(),
        )
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}/{1}")
        fun cases() = listOf(
            arrayOf("recording", "bang"),
            arrayOf("release", "bang"),
            arrayOf("recording", "plus-minus"),
            arrayOf("release", "plus-minus"),
        )
    }
}
