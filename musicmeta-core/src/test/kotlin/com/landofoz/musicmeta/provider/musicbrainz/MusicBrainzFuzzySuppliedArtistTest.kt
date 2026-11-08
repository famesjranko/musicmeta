package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.engine.ProviderCallScope
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
class MusicBrainzFuzzySuppliedArtistTest(private val entity: String, private val name: String) {
    private val client = FakeHttpClient()
    private val provider = MusicBrainzProvider(client, RateLimiter(0))
    private val records = JSONArray(resource("captures.json"))
    private val forms = (0 until records.length()).map { records.getJSONObject(it) }
        .filter { it.getString("entity") == entity && it.getString("name") == name }
    private val words = forms.single { it.getString("form") == "words" }
    private val title = words.getString("title")
    private val artist = words.getString("artist")
    private val fuzzy = words.getString("query").substringBefore(" AND artistname") + " AND " + when (name) {
        "kiss" -> "artistname:kiss~"
        "halsey" -> "artistname:halsey~"
        "eminem" -> "artistname:eminem~"
        "metallica-leading", "metallica-trailing" -> "artistname:metallica~"
        "negative", "radiohead", "polise" -> "artistname:radiohead~"
        "eagles" -> "artistname:eagles~"
        else -> error("No artist word query for $name")
    }
    private val strict = forms.single { it.getString("form") == "strict" }.getString("query")
    private val request = if (entity == "recording") {
        EnrichmentRequest.forTrack(title, artist)
    } else {
        EnrichmentRequest.forAlbum(title, artist)
    }
    private val rows = JSONObject(resource(words.getString("file"))).getJSONArray("${entity}s")
    private val expectedIds = (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }

    @Test
    fun `supplied artist fuzzy search requires lowercase fielded title words`() = runTest {
        // Given - exact query routes to pre-change live responses for both fuzzy forms
        givenCaptures()
        val api = MusicBrainzApi(client, RateLimiter(0))

        // When - searching directly for a typo with the supplied artist
        val actual = if (entity == "recording") {
            api.searchRecordingsFuzzy(title, artist).map { it.id to it.title }
        } else {
            api.searchReleasesFuzzy(title, artist).map { it.id to it.title }
        }

        // Then - exactly one fielded query retains the captured rows and rejects the old pool
        assertEquals(listOf(fuzzy), queries())
        assertEquals(expectedIds, actual.map { it.first })
        assertEquals((0 until rows.length()).map { rows.getJSONObject(it).getString("title") }, actual.map { it.second })
        assertRelatedRows()
    }

    @Test
    fun `candidate search offers the supplied artist word pool after a strict miss`() = runTest {
        // Given - captured strict misses and exact routes for the old and fielded fuzzy searches
        givenCaptures()

        // When - discovering candidates for the supplied title and artist
        val candidates = provider.searchCandidates(request, 3)

        // Then - one strict search and one fuzzy search offer only captured related choices
        assertEquals(listOf(strict, fuzzy), queries())
        assertCandidates(candidates)
    }

    @Test
    fun `enrichment miss offers the supplied artist word pool as suggestions`() = runTest {
        // Given - captured strict misses and the fuzzy pools available to the suggestion cascade
        givenCaptures()

        val scope = ProviderCallScope()

        // When - name resolution misses for a track or album
        val result = withContext(scope) { provider.enrich(request, EnrichmentType.GENRE) }

        // Then - the existing ladder ends in suggestions after exactly one fuzzy request
        val expectedQueries = if (entity == "recording") listOf("$strict AND -comment:*", strict, fuzzy) else listOf(strict, fuzzy)
        assertEquals(expectedQueries, queries())
        assertTrue(result is EnrichmentResult.NotFound)
        val suggestions = (result as EnrichmentResult.NotFound).suggestions.orEmpty()
        assertEquals(expectedIds, suggestions.map { it.identifiers.musicBrainzId })
        assertCandidates(suggestions)
    }

    private fun assertCandidates(candidates: List<SearchCandidate>) {
        assertEquals(expectedIds.isEmpty(), candidates.isEmpty())
        assertTrue(candidates.all { it.identifiers.musicBrainzId in expectedIds })
        assertTrue(candidates.all { it.artist.equals(artist, ignoreCase = true) })
        assertRelatedRows()
    }

    private fun assertRelatedRows() {
        if (name == "negative") {
            assertEquals(0, rows.length())
        } else {
            assertEquals(3, rows.length())
            val expectedTitle = when (name) {
                "eminem" -> "Not Afraid"
                "metallica-leading", "metallica-trailing" -> "…And Justice for All"
                "radiohead", "polise" -> "Karma Police"
                "eagles" -> "Hotel California"
                else -> null
            }
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                val credit = row.getJSONArray("artist-credit").getJSONObject(0).getJSONObject("artist").getString("name")
                assertTrue(credit.equals(artist, ignoreCase = true))
                if (expectedTitle != null) {
                    assertTrue(expectedTitle.equals(row.getString("title").replace("...", "…"), ignoreCase = true))
                } else {
                    val prefix = if (name == "kiss") "Rock and Roll" else "Now or Never"
                    assertTrue(row.getString("title").startsWith(prefix))
                }
            }
        }
    }

    private fun givenCaptures() {
        forms.forEach { record ->
            client.givenJsonResponse(
                "query=${URLEncoder.encode(record.getString("query"), "UTF-8")}&",
                resource(record.getString("file")),
            )
        }
        // Reuse the pre-change capture rows as a parser and path compatibility fixture, not as live evidence.
        client.givenJsonResponse(
            "query=${URLEncoder.encode(fuzzy, "UTF-8")}&",
            resource(words.getString("file")),
        )
    }

    private fun queries() = client.requestedUrls.map {
        URLDecoder.decode(it.substringAfter("query=").substringBefore("&"), "UTF-8")
    }

    private fun resource(file: String) = checkNotNull(
        javaClass.getResourceAsStream("/pools/musicbrainz-fuzzy-supplied-artist/$file"),
    ).bufferedReader().use { it.readText() }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}/{1}")
        fun cases(): List<Array<String>> = listOf(
            arrayOf("recording", "kiss"), arrayOf("release", "kiss"),
            arrayOf("recording", "halsey"), arrayOf("release", "halsey"),
            arrayOf("recording", "eminem"), arrayOf("release", "metallica-leading"),
            arrayOf("release", "metallica-trailing"), arrayOf("recording", "negative"),
            arrayOf("recording", "radiohead"), arrayOf("release", "eagles"),
            arrayOf("recording", "polise"), arrayOf("release", "polise"),
        )
    }
}
