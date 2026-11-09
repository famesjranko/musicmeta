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
class MusicBrainzFeaturedCreditFuzzyTest(
    private val endpoint: String,
    private val case: String,
    private val marker: String,
) {
    private val client = FakeHttpClient()
    private val api = MusicBrainzApi(client, RateLimiter(0))
    private val records = JSONArray(resource("captures.json"))

    private data class ActualRow(val id: String, val title: String, val credit: String?, val artists: List<String>)

    private fun entry(case: String, arm: String) = (0 until records.length())
        .map { records.getJSONObject(it) }
        .single {
            it.getString("endpoint") == endpoint && it.getString("case") == case && when (arm) {
                "fuzzy-current", "fuzzy-primary", "fuzzy-components" ->
                    it.getString("mode") == "fuzzy" && it.getString("arm") == arm.removePrefix("fuzzy-")
                else -> it.getString("arm") == arm
            }
        }

    private fun route(capture: JSONObject) = client.givenJsonResponse(
        "query=" + URLEncoder.encode(capture.getString("query"), "UTF-8") + "&",
        resource(capture.getString("file")),
    )

    private fun resource(file: String) = checkNotNull(
        javaClass.getResourceAsStream("/pools/musicbrainz-featured-credit/" + file),
    ).bufferedReader().use { it.readText() }

    private fun queries() = client.requestedUrls.map {
        URLDecoder.decode(it.substringAfter("query=").substringBefore("&"), "UTF-8")
    }

    private fun actualRecordings(rows: List<MusicBrainzRecording>) = rows.map {
        ActualRow(it.id, it.title, it.artistCredit, it.artistCredits)
    }

    private fun actualReleases(rows: List<MusicBrainzRelease>) = rows.map {
        ActualRow(it.id, it.title, it.artistCredit, it.artistCredits)
    }

    private fun strings(array: JSONArray) = (0 until array.length()).map { array.getString(it) }

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
    fun `featured typo words retain captured suggestions`() = runTest {
        // Given - the current and component fuzzy captures are routed by complete decoded query
        route(entry(case, "fuzzy-current")); route(entry(case, "fuzzy-components"))

        // When - searching the typo title with the selected marker spelling
        val capture = entry(case, "fuzzy-current")
        val artist = if (case == "empire-typo") "JAY-Z " + marker + " Alicia Keys" else "Eminem " + marker + " Rihanna"
        val rows = if (endpoint == "recording") actualRecordings(api.searchRecordingsFuzzy(capture.getString("title"), artist, 3))
        else actualReleases(api.searchReleasesFuzzy(capture.getString("title"), artist, 3))

        // Then - the marker-free component query returns the captured ordered rows once
        assertEquals(listOf(entry(case, "fuzzy-components").getString("query")), queries())
        assertRows(entry(case, "fuzzy-components"), rows)
        assertEquals(1, client.requestedUrls.size)
    }

    @Test
    fun `featured typo negative remains empty`() = runTest {
        // Given - the negative current/component captures and a primary-only positive response
        route(entry("lvoe-negative", "fuzzy-current"))
        route(entry("lvoe-negative", "fuzzy-components"))
        route(entry("lvoe", "fuzzy-primary"))

        // When - searching the typo title with a guest that is not in the captured credit
        val artist = "Eminem " + marker + " Radiohead"
        val rows = if (endpoint == "recording") {
            api.searchRecordingsFuzzy("Lvoe the Way You Lie", artist, 3)
        } else {
            api.searchReleasesFuzzy("Lvoe the Way You Lie", artist, 3)
        }

        // Then - both featured components remain required and the negative stays empty
        assertEquals(listOf(entry("lvoe-negative", "fuzzy-components").getString("query")), queries())
        assertTrue(rows.isEmpty())
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}/{1}/{2}")
        fun cases() = listOf("recording", "release").flatMap { endpoint ->
            listOf("lvoe", "empire-typo").flatMap { case ->
                listOf("feat.", "Ft.", "fEaTuRiNg").map { marker -> arrayOf(endpoint, case, marker) }
            }
        }
    }
}
