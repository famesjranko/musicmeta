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
import java.util.Locale

@RunWith(Parameterized::class)
class MusicBrainzFuzzyArtistWordsTest(private val entity: String) {
    private val client = FakeHttpClient()
    private val api = MusicBrainzApi(client, RateLimiter(0))
    private val records = JSONArray(resource("captures.json"))

    private fun entry(case: String, arm: String) = (0 until records.length())
        .map { records.getJSONObject(it) }
        .single { it.getString("endpoint") == entity && it.getString("case") == case && it.getString("arm") == arm }

    private fun route(case: String, arm: String) {
        val capture = entry(case, arm)
        client.givenJsonResponse(
            "query=${URLEncoder.encode(capture.getString("query"), "UTF-8")}&",
            resource(capture.getString("file")),
        )
    }

    private suspend fun search(title: String, artist: String) = if (entity == "recording") {
        api.searchRecordingsFuzzy(title, artist)
    } else api.searchReleasesFuzzy(title, artist)

    private suspend fun displayedRows(title: String, artist: String): List<Triple<String, String, String?>> =
        if (entity == "recording") api.searchRecordingsFuzzy(title, artist).map { Triple(it.id, it.title, it.artistCredit) }
        else api.searchReleasesFuzzy(title, artist).map { Triple(it.id, it.title, it.artistCredit) }

    private fun queries() = client.requestedUrls.map {
        URLDecoder.decode(it.substringAfter("query=").substringBefore("&"), "UTF-8")
    }

    private fun resource(file: String) = checkNotNull(
        javaClass.getResourceAsStream("/pools/musicbrainz-fuzzy-artist-words/$file"),
    ).bufferedReader().use { it.readText() }

    private fun expectedRows(case: String): List<Triple<String, String, String?>> {
        val capture = entry(case, "words")
        val rows = JSONObject(resource(capture.getString("file"))).getJSONArray("${entity}s")
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val credit = row.getJSONArray("artist-credit").getJSONObject(0).getJSONObject("artist").getString("name")
            Triple(row.getString("id"), row.getString("title"), credit)
        }
    }

    @Test
    fun `first artist word typo returns captured intended rows`() = runTest {
        // Given - the captured words response is routed by its complete query
        route("micheal", "words")

        // When - searching for the misspelled first artist word
        val actual = displayedRows("Bilie Jean", "Micheal Jackson")

        // Then - the word query returns its three captured rows in order
        assertEquals(listOf(entry("micheal", "words").getString("query")), queries())
        assertEquals(expectedRows("micheal"), actual)
    }

    @Test
    fun `every artist word constrains the captured negative control`() = runTest {
        // Given - current and words captures for the constructed negative control
        route("negative", "current"); route("negative", "words")

        // When - searching for Michael Radiohead
        val actual = search("Bilie Jean", "Michael Radiohead")

        // Then - all supplied words constrain the one request and admit no rows
        assertEquals(listOf(entry("negative", "words").getString("query")), queries())
        assertTrue(actual.isEmpty())
    }

    @Test
    fun `artist words retain captured intended top rows`() = runTest {
        // Given - exact routes for the ten captured words queries and zepplin phrase controls
        val cases = listOf("zepplin", "alone", "billie", "stairway", "simon", "earth", "the-beatles", "beatles", "radiohead", "eagles")
        cases.forEach { route(it, "words") }
        route("zepplin", "current"); route("zepplin", "phrase")

        // When - searching each captured title and artist pair
        val results = cases.map { case ->
            val capture = entry(case, "words")
            displayedRows(capture.getString("title"), capture.getString("artist"))
        }

        // Then - each request uses its exact query and retains the captured ordered top rows
        assertEquals(cases.map { entry(it, "words").getString("query") }, queries())
        cases.forEachIndexed { index, case ->
            val capture = entry(case, "words")
            val rowFile = JSONObject(resource(capture.getString("file"))).getJSONArray("${entity}s")
            assertEquals(3, rowFile.length())
            assertEquals(expectedRows(case), results[index])
        }
    }

    @Test
    fun `featured credit miss sends no rescue query`() = runTest {
        // Given - the captured empty response for the complete featured artist words query
        route("featured", "words")

        // When - searching for the featured artist credit
        val result = search("Love the Way You Liee", "Eminem feat. Rihanna")

        // Then - feat remains a required fuzzy artist word in one request
        assertTrue(result.isEmpty())
        assertEquals(listOf(entry("featured", "words").getString("query")), queries())
    }

    @Test
    fun `boolean artist words stay lowercase literal terms`() = runTest {
        // Given - empty bodies for syntax-only OR and NOT queries
        client.givenJsonResponse("$entity?query", resource(entry("negative", "words").getString("file")))

        // When - querying each uppercase Boolean word
        search("Bilie Jean", "OR")
        search("Bilie Jean", "NOT")

        // Then - both values are lowercase literal exact artist terms in one request each
        assertEquals(
            listOf("$entity:bilie~ AND $entity:jean~ AND artistname:or", "$entity:bilie~ AND $entity:jean~ AND artistname:not"),
            queries(),
        )
    }

    @Test
    fun `short and numeric artist words remain exact without an artist guard`() = runTest {
        // Given - an empty response for syntax-only short and numeric words
        client.givenJsonResponse("$entity?query", resource(entry("negative", "words").getString("file")))

        // When - searching with short, punctuated and numeric artist words
        search("Bilie Jean", "U2 AC/DC sum41 2012 ２０１２ ⅫⅩⅨⅧ 2012年")

        // Then - every extracted word is exact and the eligible title still sends one request
        assertEquals(
            "$entity:bilie~ AND $entity:jean~ AND artistname:u2 AND artistname:ac AND artistname:dc AND artistname:sum41 AND artistname:2012 AND artistname:２０１２ AND artistname:ⅻⅹⅸⅷ AND artistname:2012年",
            queries().single(),
        )
    }

    @Test
    fun `artist terms reuse NFC apostrophes and code point rules`() = runTest {
        // Given - syntax-only empty responses for the Unicode artist cases
        client.givenJsonResponse("$entity?query", resource(entry("negative", "words").getString("file")))
        val decomposed = java.text.Normalizer.normalize("Björk", java.text.Normalizer.Form.NFD)

        // When - searching with decomposed accents, curly apostrophes and supplementary letters
        search("Bilie Jean", "$decomposed D’Angelo")
        search("Bilie Jean", "사랑 İyi 𐐀𐐁")
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            search("Bilie Jean", "FIRE")
        } finally {
            Locale.setDefault(originalLocale)
        }

        // Then - NFC extraction and code point classification match the shared title rules
        assertEquals(
            listOf(
                "$entity:bilie~ AND $entity:jean~ AND artistname:björk~ AND artistname:d'angelo~",
                "$entity:bilie~ AND $entity:jean~ AND artistname:사랑 AND artistname:i̇yi AND artistname:𐐨𐐩",
                "$entity:bilie~ AND $entity:jean~ AND artistname:fire~",
            ),
            queries(),
        )
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun endpoints() = listOf(arrayOf("recording"), arrayOf("release"))
    }
}
