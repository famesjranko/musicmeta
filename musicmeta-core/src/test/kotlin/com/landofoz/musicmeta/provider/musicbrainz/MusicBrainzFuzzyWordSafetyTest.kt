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
import java.net.URLEncoder
import java.text.Normalizer

@RunWith(Parameterized::class)
class MusicBrainzFuzzyWordSafetyTest(private val entity: String) {
    private val httpClient = FakeHttpClient()
    private val api = MusicBrainzApi(httpClient, RateLimiter(0))

    @Test
    fun `numeric words stay exact after a strict miss`() = runTest {
        // Given - captured strict misses and fuzzy pools with exact or approximate year terms
        val recording = entity == "recording"
        val title = if (recording) "Strman - 2012 Remaster" else "Live at Wembly 1986"
        val name = if (recording) "strman-2012-remaster" else "live-at-wembly-1986"
        val strict = "$entity:\"${title.replace("-", "\\-")}\" AND artistname:\"\""
        val fuzzy = if (recording) {
            "recording:strman~ AND recording:2012 AND recording:remaster~"
        } else {
            "release:live~ AND release:at AND release:wembly~ AND release:1986"
        }
        givenCapture(strict, "$name-strict")
        givenCapture(fuzzy, "$name-exact-number")
        httpClient.givenJsonResponse("$entity?query", capture("$name-fuzzy-number"))
        val provider = MusicBrainzProvider(httpClient, RateLimiter(0))
        val request = if (recording) {
            EnrichmentRequest.forTrack(title, "")
        } else {
            EnrichmentRequest.forAlbum(title, "")
        }

        // When - searching for candidates after the strict title misses
        val candidates = provider.searchCandidates(request, 3)

        // Then - the year remains exact and only the captured matching editions survive
        assertEquals(listOf(strict, fuzzy), queries())
        val expected = if (recording) List(2) { "Starman (2012 remastered version)" } else emptyList()
        assertEquals(expected, candidates.map { it.title })
    }

    @Test
    fun `numeric-only titles have no fuzzy rescue`() = runTest {
        // Given - titles made only of long Unicode number words
        val titles = listOf("2012", "123456 7890", "２０１２", "ⅫⅩⅨⅧ")

        // When - requesting a fuzzy rescue for each numeric title
        val pools = titles.map { fuzzyTitles(it) }

        // Then - no numeric title can produce an approximate-number request
        assertEquals(titles.map { emptyList<String>() }, pools)
        assertEquals(emptyList<String>(), httpClient.requestedUrls)
    }

    @Test
    fun `decomposed short Hangul has no fuzzy rescue`() = runTest {
        // Given - a two-syllable title decomposed into separate Hangul code points
        val title = Normalizer.normalize("사랑", Normalizer.Form.NFD)

        // When - requesting a fuzzy rescue for the decomposed title
        val titles = fuzzyTitles(title)

        // Then - the short Hangul title returns no suggestions and causes no request
        assertEquals(emptyList<String>(), titles)
        assertEquals(emptyList<String>(), httpClient.requestedUrls)
    }

    @Test
    fun `lowercase expansion cannot promote a short word to fuzzy`() = runTest {
        // Given - a three-code-point word whose initial letter expands when lowercased
        val title = "İyi"

        // When - requesting a fuzzy rescue for the short title
        val titles = fuzzyTitles(title)

        // Then - lowercase expansion cannot introduce a fuzzy request
        assertEquals(emptyList<String>(), titles)
        assertEquals(emptyList<String>(), httpClient.requestedUrls)
    }

    @Test
    fun `two supplementary letters have no fuzzy rescue`() = runTest {
        // Given - two Deseret letters occupying four UTF-16 code units
        val title = "\uD801\uDC00\uD801\uDC01"

        // When - requesting a fuzzy rescue for the two-letter title
        val titles = fuzzyTitles(title)

        // Then - the two-letter title returns no suggestions and causes no request
        assertEquals(emptyList<String>(), titles)
        assertEquals(emptyList<String>(), httpClient.requestedUrls)
    }

    private suspend fun fuzzyTitles(title: String): List<String> = if (entity == "recording") {
        api.searchRecordingsFuzzy(title, "").map { it.title }
    } else {
        api.searchReleasesFuzzy(title, "").map { it.title }
    }

    private fun givenCapture(query: String, name: String) {
        httpClient.givenJsonResponse("query=${URLEncoder.encode(query, Charsets.UTF_8.name())}&", capture(name))
    }

    private fun capture(name: String): String = checkNotNull(
        javaClass.getResourceAsStream("/pools/musicbrainz-fuzzy-title-only/$entity-$name.json"),
    ).bufferedReader().use { it.readText() }

    private fun queries(): List<String> = httpClient.requestedUrls.map {
        URLDecoder.decode(it.substringAfter("query=").substringBefore("&"), Charsets.UTF_8.name())
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun entities(): List<String> = listOf("recording", "release")
    }
}
