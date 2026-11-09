package com.landofoz.musicmeta.provider.musicbrainz

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
class MusicBrainzFuzzyPunctuationArtistTest(private val endpoint: String, private val case: String) {
    private val client = FakeHttpClient()
    private val api = MusicBrainzApi(client, RateLimiter(0))
    private val records = JSONArray(resource("captures.json"))

    private fun entry(arm: String) = (0 until records.length())
        .map { records.getJSONObject(it) }
        .single { it.getString("endpoint") == endpoint && it.getString("case") == case && it.getString("arm") == arm }

    private fun route(arm: String) {
        val capture = entry(arm)
        client.givenJsonResponse(
            "query=${URLEncoder.encode(capture.getString("query"), "UTF-8")}&",
            resource(capture.getString("file")),
        )
    }

    private fun resource(file: String) = checkNotNull(
        javaClass.getResourceAsStream("/pools/musicbrainz-fuzzy-punctuation-artist/$file"),
    ).bufferedReader().use { it.readText() }

    private fun queries() = client.requestedUrls.map {
        URLDecoder.decode(it.substringAfter("query=").substringBefore("&"), "UTF-8")
    }

    private fun expectedRows(): List<Triple<String, String, String?>> {
        val rows = entry("phrase").getJSONArray("rows")
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val artist = if (row.isNull("parsedArtist")) null else row.getString("parsedArtist")
            Triple(row.getString("id"), row.getString("title"), artist)
        }
    }

    private suspend fun search(title: String, artist: String): List<Triple<String, String, String?>> = if (endpoint == "recording") {
        api.searchRecordingsFuzzy(title, artist, 3).map { Triple(it.id, it.title, it.artistCredit) }
    } else {
        api.searchReleasesFuzzy(title, artist, 3).map { Triple(it.id, it.title, it.artistCredit) }
    }

    @Test
    fun `punctuation artist keeps its captured exact phrase pool`() = runTest {
        // Given - current, term and phrase bodies are routed by their complete captured queries
        listOf("current", "term", "phrase").forEach(::route)
        val capture = entry("phrase")

        // When - searching the captured typo title with its punctuation-only artist
        val actual = search(capture.getString("title"), capture.getString("artist"))

        // Then - the exact phrase query returns the captured rows in order from one URL
        assertEquals(listOf(capture.getString("query")), queries())
        assertEquals(1, client.requestedUrls.size)
        val url = client.requestedUrls.single()
        assertTrue(url.contains("/$endpoint?query="))
        assertTrue(url.endsWith("&fmt=json&limit=3"))
        val expected = expectedRows()
        assertEquals(if (case == "bang") 3 else 2, actual.size)
        assertEquals(expected, actual)
        assertEquals(capture.getString("artist"), actual.first().third)
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
