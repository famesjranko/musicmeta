package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.Normalizer

class MusicBrainzFuzzyMixedWordsTest {
    private val httpClient = FakeHttpClient()
    private val api = MusicBrainzApi(httpClient, RateLimiter(0))

    @Test
    fun `a decade word stays exact after a strict release miss`() = runTest {
        // Given - captured strict and typo queries for a compilation with a decade in its title
        val title = "Gratest Hits of the 1980s"
        val strict = "release:\"$title\" AND artistname:\"\""
        val fuzzy = "release:gratest~ AND release:hits~ AND release:of AND release:the AND release:1980s"
        givenCapture(strict, "release-gratest-hits-1980s-strict")
        givenCapture(fuzzy, "release-gratest-hits-1980s-exact-number")
        givenCapture("$fuzzy~", "release-gratest-hits-1980s-fuzzy-number")
        val provider = MusicBrainzProvider(httpClient, RateLimiter(0))

        // When - asking for candidates after the strict album title misses
        val candidates = provider.searchCandidates(EnrichmentRequest.forAlbum(title, ""), 3)

        // Then - the decade stays exact and the captured 1980s compilation is the only suggestion
        assertEquals(listOf(strict, fuzzy), queries())
        assertEquals(listOf("80s HITS – 100 Greatest Songs of the 1980s"), candidates.map { it.title })
    }

    @Test
    fun `a numeric CJK strict miss does not trigger a fuzzy request`() = runTest {
        // Given - a strict miss and a broad fuzzy pool for a number-bearing CJK title
        val title = "東凉2020"
        val strict = "recording:\"$title\" AND artistname:\"\""
        givenCapture(strict, "recording-short-cjk-2020-strict")
        givenCapture("recording:$title~", "recording-short-cjk-2020-fuzzy-number")
        val provider = MusicBrainzProvider(httpClient, RateLimiter(0))

        // When - asking for candidates without an artist after the strict title misses
        val candidates = provider.searchCandidates(EnrichmentRequest.forTrack(title, ""), 3)

        // Then - the search stops after its strict miss without offering unrelated candidates
        assertEquals(listOf(strict), queries())
        assertEquals(emptyList<String>(), candidates.map { it.title })
    }

    @Test
    fun `a year with a CJK suffix has no fuzzy rescue`() = runTest {
        // Given - captured unrelated rows from a fuzzy year with a CJK suffix
        givenCapture("recording:2012年~", "recording-year-2012-cjk-fuzzy-number")

        // When - asking for typo suggestions for the year title without an artist
        val titles = fuzzyTitles("recording", "2012年")

        // Then - no request is made and none of the unrelated year titles are offered
        assertEquals(emptyList<String>(), titles)
        assertEquals(emptyList<String>(), httpClient.requestedUrls)
    }

    @Test
    fun `a mixed digit word alone has no fuzzy rescue`() = runTest {
        // Given - captured unrelated rows from fuzzy sum41 queries on both endpoints
        val entities = listOf("recording", "release")
        entities.forEach { givenCapture("$it:sum41~", "$it-sum41") }

        // When - asking both endpoints for typo suggestions for the mixed word
        val pools = entities.map { fuzzyTitles(it, "sum41") }

        // Then - neither endpoint makes a request or offers the captured unrelated rows
        assertEquals(listOf(emptyList<String>(), emptyList()), pools)
        assertEquals(emptyList<String>(), httpClient.requestedUrls)
    }

    @Test
    fun `musical symbols do not become required title words`() = runTest {
        // Given - captures for a title query with and without a stray musical combining mark
        val entities = listOf("recording", "release")
        entities.forEach { entity ->
            givenCapture("$entity:karma~ AND $entity:polise~", "$entity-karma-polise-words")
            givenCapture("$entity:𝅥 AND $entity:karma~ AND $entity:polise~", "$entity-karma-polise-musical-mark")
        }
        val title = "\uD834\uDD5F Karma Polise"
        val inputs = listOf(title, Normalizer.normalize(title, Normalizer.Form.NFD))

        // When - searching both spellings of the title with the musical note on both endpoints
        val pools = entities.flatMap { entity -> inputs.map { fuzzyTitles(entity, it) } }

        // Then - the note is absent from every query and all three intended titles remain available
        assertEquals(entities.flatMap { entity -> inputs.map { "$entity:karma~ AND $entity:polise~" } }, queries())
        assertEquals(List(4) { List(3) { "Karma Police" } }, pools)
    }

    private suspend fun fuzzyTitles(entity: String, title: String): List<String> = if (entity == "recording") {
        api.searchRecordingsFuzzy(title, "").map { it.title }
    } else {
        api.searchReleasesFuzzy(title, "").map { it.title }
    }

    private fun givenCapture(query: String, name: String) {
        val body = checkNotNull(javaClass.getResourceAsStream("/pools/musicbrainz-fuzzy-title-only/$name.json"))
            .bufferedReader().use { it.readText() }
        httpClient.givenJsonResponse("query=${URLEncoder.encode(query, Charsets.UTF_8.name())}&", body)
    }

    private fun queries(): List<String> = httpClient.requestedUrls.map {
        URLDecoder.decode(it.substringAfter("query=").substringBefore("&"), Charsets.UTF_8.name())
    }
}
