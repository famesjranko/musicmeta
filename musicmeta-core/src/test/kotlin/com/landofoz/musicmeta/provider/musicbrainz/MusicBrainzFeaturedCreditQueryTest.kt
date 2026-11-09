package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.net.URLDecoder
import java.net.URLEncoder

@RunWith(Parameterized::class)
class MusicBrainzFeaturedCreditQueryTest(private val endpoint: String) {
    private val client = FakeHttpClient()
    private val api = MusicBrainzApi(client, RateLimiter(0))
    private val records = JSONArray(resource("captures.json"))

    private data class ActualRow(val id: String, val title: String, val credit: String?, val artists: List<String>)

    private fun entry(case: String, arm: String) = (0 until records.length())
        .map { records.getJSONObject(it) }
        .single {
            it.getString("endpoint") == endpoint && it.getString("case") == case && when (arm) {
                "strict" -> it.getString("mode") == "strict" && it.getString("arm") == "current"
                "components", "primary" -> it.getString("mode") == "strict" && it.getString("arm") == arm
                else -> it.getString("arm") == arm
            }
        }

    private fun route(capture: JSONObject) = client.givenJsonResponse(
        "query=${URLEncoder.encode(capture.getString("query"), "UTF-8")}&",
        resource(capture.getString("file")),
    )

    private fun resource(file: String) = checkNotNull(
        javaClass.getResourceAsStream("/pools/musicbrainz-featured-credit/$file"),
    ).bufferedReader().use { it.readText() }

    private fun queries() = client.requestedUrls.map {
        URLDecoder.decode(it.substringAfter("query=").substringBefore("&"), "UTF-8")
    }

    private fun strings(array: JSONArray) = (0 until array.length()).map { array.getString(it) }

    private fun actualRecordings(rows: List<MusicBrainzRecording>): List<ActualRow> = rows.map {
        ActualRow(it.id, it.title, it.artistCredit, it.artistCredits)
    }

    private fun actualReleases(rows: List<MusicBrainzRelease>): List<ActualRow> = rows.map {
        ActualRow(it.id, it.title, it.artistCredit, it.artistCredits)
    }

    private fun assertRows(capture: JSONObject, actual: List<ActualRow>) {
        val expected = capture.getJSONArray("rows")
        assertEquals(expected.length(), actual.size)
        for (index in 0 until expected.length()) {
            val row = expected.getJSONObject(index)
            assertEquals(row.getString("id"), actual[index].id)
            assertEquals(row.getString("title"), actual[index].title)
            assertEquals(row.getString("canonical_credit"), actual[index].credit)
            assertEquals(strings(row.getJSONArray("artists")), actual[index].artists)
            val parts = row.getJSONArray("artist_credit")
            val assembled = (0 until parts.length()).joinToString("") {
                val part = parts.getJSONObject(it)
                part.getJSONObject("artist").getString("name") + part.optString("joinphrase")
            }
            assertEquals(row.getString("canonical_credit"), assembled)
        }
    }

    @Test
    fun `strict featured credits retain both captured artists`() = runTest {
        // Given - current and component captures for every supported featured-credit spelling
        val cases = listOf("feat", "ft", "featuring", "empire")
        cases.forEach { case ->
            route(entry(case, "strict"))
            route(entry(if (case == "empire") "empire" else "feat", "components"))
        }

        // When - searching each featured credit on the selected endpoint
        val actual = cases.map { case ->
            val capture = entry(case, "strict")
            if (endpoint == "recording") {
                actualRecordings(api.searchRecordings(capture.getString("title"), capture.getString("artist"), limit = 3))
            } else {
                actualReleases(api.searchReleases(capture.getString("title"), capture.getString("artist"), limit = 3))
            }
        }

        // Then - each marker sends the component query and returns its captured structured rows
        val expectedQueries = cases.map { case -> entry(if (case == "empire") "empire" else "feat", "components").getString("query") }
        assertEquals(expectedQueries, queries())
        cases.forEachIndexed { index, case -> assertRows(entry(if (case == "empire") "empire" else "feat", "components"), actual[index]) }
        assertEquals(cases.size, client.requestedUrls.size)
    }

    @Test
    fun `strict featured negative retains the guest constraint`() = runTest {
        // Given - negative component captures and a primary-only response that would be non-empty
        route(entry("negative", "current")); route(entry("negative", "components"))
        route(entry("feat", "primary"))

        // When - searching for the featured credit with a different guest
        val capture = entry("negative", "current")
        val actual = if (endpoint == "recording") {
            api.searchRecordings(capture.getString("title"), capture.getString("artist"), limit = 3)
        } else {
            api.searchReleases(capture.getString("title"), capture.getString("artist"), limit = 3)
        }

        // Then - both component clauses remain in the exact empty query
        assertEquals(listOf(entry("negative", "components").getString("query")), queries())
        assertTrue(actual.isEmpty())
    }

    @Test
    fun `ordinary artist joins stay whole`() = runTest {
        // Given - current whole-artist captures for the join-word controls
        val cases = listOf("simon", "simon-and", "earth", "earth-and", "hall")
        cases.forEach { route(entry(it, "strict")) }

        // When - searching each ordinary artist input
        val actual = cases.map { case ->
            val capture = entry(case, "strict")
            if (endpoint == "recording") actualRecordings(api.searchRecordings(capture.getString("title"), capture.getString("artist"), limit = 3))
            else actualReleases(api.searchReleases(capture.getString("title"), capture.getString("artist"), 3))
        }

        // Then - every ordinary artist keeps its one captured phrase and rows
        assertEquals(cases.map { entry(it, "strict").getString("query") }, queries())
        cases.forEachIndexed { index, case -> assertRows(entry(case, "strict"), actual[index]) }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun endpoints() = listOf(arrayOf("recording"), arrayOf("release"))
    }
}
