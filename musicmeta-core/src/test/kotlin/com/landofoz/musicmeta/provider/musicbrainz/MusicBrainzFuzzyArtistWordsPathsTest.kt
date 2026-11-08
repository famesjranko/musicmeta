package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.engine.ProviderCallScope
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.net.URLDecoder
import java.net.URLEncoder

@RunWith(Parameterized::class)
class MusicBrainzFuzzyArtistWordsPathsTest(private val entity: String, private val case: String) {
    private val client = FakeHttpClient()
    private val provider = MusicBrainzProvider(client, RateLimiter(0))
    private val records = JSONArray(resource("captures.json"))
    private fun entry(arm: String) = (0 until records.length()).map { records.getJSONObject(it) }
        .single { it.getString("endpoint") == entity && it.getString("case") == case && it.getString("arm") == arm }
    private fun route(arm: String) {
        val row = entry(arm)
        client.givenJsonResponse("query=${URLEncoder.encode(row.getString("query"), "UTF-8")}&", resource(row.getString("file")))
    }
    private val title get() = entry("words").getString("title")
    private val artist get() = entry("words").getString("artist")
    private val request get() = if (entity == "recording") EnrichmentRequest.forTrack(title, artist) else EnrichmentRequest.forAlbum(title, artist)
    private fun queries() = client.requestedUrls.map { URLDecoder.decode(it.substringAfter("query=").substringBefore("&"), "UTF-8") }
    private fun resource(file: String) = checkNotNull(javaClass.getResourceAsStream("/pools/musicbrainz-fuzzy-artist-words/$file"))
        .bufferedReader().use { it.readText() }

    @Test
    fun `candidate search uses one artist word query after a strict miss`() = runTest {
        // Given - strict, current and words captures are routed by exact query
        route("strict"); route("words")

        // When - asking for candidates without hints
        val candidates = provider.searchCandidates(request, 3)

        // Then - strict search is followed by exactly one words query
        assertEquals(listOf(entry("strict").getString("query"), entry("words").getString("query")), queries())
        assertEquals(case == "micheal", candidates.isNotEmpty())
    }

    @Test
    fun `enrichment miss offers artist word suggestions without extra requests`() = runTest {
        // Given - canonical recording and strict captures; release artist lookup stays unrouted
        if (entity == "recording") route("canonical")
        route("strict"); route("words")
        val expected = buildList {
            if (entity == "recording") add(entry("canonical").getString("query"))
            add(entry("strict").getString("query"))
            if (entity == "release" && case != "featured") add("artist:\"$artist\" OR alias:\"$artist\"")
            add(entry("words").getString("query"))
        }

        // When - enrichment misses and returns suggestions
        val result = withContext(ProviderCallScope()) { provider.enrich(request, EnrichmentType.GENRE) }

        // Then - the existing ladder is unchanged and sends only one fuzzy request
        assertTrue(result is EnrichmentResult.NotFound)
        assertEquals(expected, queries())
        assertEquals(1, queries().count { it == entry("words").getString("query") })
        val suggestions = (result as EnrichmentResult.NotFound).suggestions.orEmpty()
        assertEquals(case == "micheal", suggestions.isNotEmpty())
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}/{1}")
        fun cases() = listOf("recording", "release").flatMap { endpoint ->
            listOf("micheal", "negative", "featured").map { arrayOf(endpoint, it) }
        }
    }
}
