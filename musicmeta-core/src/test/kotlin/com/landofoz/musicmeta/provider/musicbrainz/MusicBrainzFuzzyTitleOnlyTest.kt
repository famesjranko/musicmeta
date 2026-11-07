package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.net.URLDecoder

@RunWith(Parameterized::class)
class MusicBrainzFuzzyTitleOnlyTest(private val entity: String) {
    private val httpClient = FakeHttpClient()
    private val api = MusicBrainzApi(httpClient, RateLimiter(0))

    @Test
    fun `a title-only typo returns related candidates from the live capture`() = runTest {
        // Given - captured responses for the corrected query and both faulty query shapes
        givenCapture(
            "$entity:Karma~ AND $entity:Polise~ AND ($entity:Karma OR $entity:Polise)",
            "revision-karma-polise-anchored",
        )
        givenCapture("$entity:Karma~ AND $entity:Polise~", "karma-polise-and-terms")
        givenCapture("$entity:Karma Polise~", "karma-polise-drop-artist")
        httpClient.givenJsonResponse("$entity?query", capture("karma-polise-original"))

        // When - searching for the misspelled title without an artist
        val titles = fuzzyTitles("Karma Polise", "")

        // Then - every candidate is the intended title and the search costs one request
        assertEquals(listOf("Karma Police", "Karma Police", "Karma Police"), titles)
        assertEquals("$entity:Karma~ AND $entity:Polise~ AND ($entity:Karma OR $entity:Polise)", queries().single())
    }

    @Test
    fun `a one-word typo retains fuzzy title matching`() = runTest {
        // Given - a captured response to the one-word fuzzy query
        givenCapture("$entity:Paranojd~", "paranojd-and-terms")

        // When - searching for a one-word typo without an artist
        val titles = fuzzyTitles("Paranojd", "")

        // Then - the related titles are retained in upstream order in one request
        val expected = if (entity == "recording") {
            listOf("Paranoid, Paranoia", "Paranoid, Paranoia", "Paranoid (Prelude to Paranoia)")
        } else {
            listOf("Paranoid", "Paranoid", "Paranoid")
        }
        assertEquals(expected, titles)
        assertEquals("$entity:Paranojd~", queries().single())
    }

    @Test
    fun `a correctly spelled title still returns related candidates`() = runTest {
        // Given - a captured response to the correctly spelled title query
        givenCapture(
            "$entity:Karma~ AND $entity:Police~ AND ($entity:Karma OR $entity:Police)",
            "revision-karma-police-anchored",
        )
        givenCapture("$entity:Karma~ AND $entity:Police~", "karma-police-and-terms")

        // When - searching for the correct spelling without an artist
        val titles = fuzzyTitles("Karma Police", "")

        // Then - all three captured candidates survive and every title term is required
        assertEquals(listOf("Karma Police", "Karma Police", "Karma Police"), titles)
        assertEquals("$entity:Karma~ AND $entity:Police~ AND ($entity:Karma OR $entity:Police)", queries().single())
    }

    @Test
    fun `a supplied artist preserves the query and request count`() = runTest {
        // Given - the captured response to the existing artist-qualified typo query
        givenCapture("$entity:Karma Polise~ AND artistname:Radiohead~", "karma-polise-with-artist")

        // When - searching with an artist and an explicit limit
        val titles = fuzzyTitles("Karma Polise", "Radiohead", limit = 3)

        // Then - the same query returns the same three titles in one request
        assertEquals(listOf("Karma Police", "Karma Police", "Karma Police"), titles)
        assertEquals("$entity:Karma Polise~ AND artistname:Radiohead~", queries().single())
        assertEquals("fmt=json&limit=3", httpClient.requestedUrls.single().substringAfter("&"))
    }

    @Test
    fun `blank artists and whitespace cannot introduce unfielded title terms`() = runTest {
        // Given - a captured empty pool and punctuation attached to a title word
        httpClient.givenJsonResponse("$entity?query", capture("karma-polise-quoted"))

        // When - searching with a whitespace artist and irregular title spacing
        fuzzyTitles("  Karma\tPolise?\n", " \t ", limit = 7)

        // Then - punctuation is dropped and each word stays in the title field at the requested limit
        assertEquals(
            "$entity:Karma~ AND $entity:Polise~ AND ($entity:Karma OR $entity:Polise)",
            queries().single(),
        )
        assertEquals("fmt=json&limit=7", httpClient.requestedUrls.single().substringAfter("&"))
    }

    @Test
    fun `a blank title and artist return nothing without searching`() = runTest {
        // Given - a request with no title words or artist
        val title = " \t\n"

        // When - searching without any identifying text
        val titles = fuzzyTitles(title, " ")

        // Then - no broad query is sent to MusicBrainz
        assertEquals(emptyList<String>(), titles)
        assertEquals(emptyList<String>(), httpClient.requestedUrls)
    }

    @Test
    fun `candidate search uses the corrected fuzzy pool after a strict miss`() = runTest {
        // Given - a strict miss and the captured corrected fuzzy response
        givenCapture(
            "$entity:Karma~ AND $entity:Polise~ AND ($entity:Karma OR $entity:Polise)",
            "revision-karma-polise-anchored",
        )
        givenCapture("$entity:Karma~ AND $entity:Polise~", "karma-polise-and-terms")
        httpClient.givenJsonResponse("%22", capture("karma-polise-quoted"))
        httpClient.givenJsonResponse("$entity?query", capture("karma-polise-original"))
        val provider = MusicBrainzProvider(httpClient, RateLimiter(0))
        val request = if (entity == "recording") {
            EnrichmentRequest.forTrack("Karma Polise", "")
        } else {
            EnrichmentRequest.forAlbum("Karma Polise", "")
        }

        // When - asking the provider for title-only candidates
        val candidates = provider.searchCandidates(request, 3)

        // Then - the strict request and one fuzzy retry return only the related titles
        assertEquals(listOf("Karma Police", "Karma Police", "Karma Police"), candidates.map { it.title })
        assertEquals(2, httpClient.requestedUrls.size)
        assertEquals("$entity:Karma~ AND $entity:Polise~ AND ($entity:Karma OR $entity:Polise)", queries().last())
    }

    @Test
    fun `a short CJK typo returns no candidates without searching`() = runTest {
        // Given - the captured unrelated rows returned for a two-character fuzzy term
        httpClient.givenJsonResponse("$entity?query", capture("revision-short-cjk-old"))

        // When - searching for the short CJK typo without an artist
        val titles = fuzzyTitles("東凉", "")

        // Then - the unsafe fallback is refused before any request is sent
        assertEquals(emptyList<String>(), titles)
        assertEquals(emptyList<String>(), httpClient.requestedUrls)
    }

    @Test
    fun `a symbol title returns no candidates without searching`() = runTest {
        // Given - the captured unrelated rows returned for fuzzy letters with musical symbols
        httpClient.givenJsonResponse("$entity?query", capture("revision-symbols-old"))

        // When - searching for a title whose only words are single letters
        val titles = fuzzyTitles("F♯ B♯ ∞", "")

        // Then - no request can turn the symbols into unrelated suggestions
        assertEquals(emptyList<String>(), titles)
        assertEquals(emptyList<String>(), httpClient.requestedUrls)
    }

    @Test
    fun `titles made only of short words have no fuzzy rescue`() = runTest {
        // Given - titles whose letter and digit words are all shorter than four characters
        val titles = listOf("Hte", "The", "U2", "AC/DC", "千本桜")

        // When - requesting a fuzzy rescue for each title
        val pools = titles.map { fuzzyTitles(it, "") }

        // Then - the exact-only fallback is skipped for every title
        assertEquals(titles.map { emptyList<String>() }, pools)
        assertEquals(emptyList<String>(), httpClient.requestedUrls)
    }

    @Test
    fun `a short title strict miss does not trigger an unsafe candidate query`() = runTest {
        // Given - a captured strict miss and the unrelated rows an unsafe fuzzy retry would return
        httpClient.givenJsonResponse("%22", capture("revision-short-cjk-strict"))
        httpClient.givenJsonResponse("$entity?query", capture("revision-short-cjk-old"))
        val provider = MusicBrainzProvider(httpClient, RateLimiter(0))
        val request = if (entity == "recording") {
            EnrichmentRequest.forTrack("東凉", "")
        } else {
            EnrichmentRequest.forAlbum("東凉", "")
        }

        // When - asking for candidates after the strict title search misses
        val candidates = provider.searchCandidates(request, 3)

        // Then - only the strict request was made and no unrelated candidates are offered
        assertEquals(emptyList<String>(), candidates.map { it.title })
        assertEquals(1, httpClient.requestedUrls.size)
    }

    @Test
    fun `short words stay exact beside fuzzy words`() = runTest {
        // Given - a captured pool for a typo with two short context words
        val query = "$entity:Don't~ AND $entity:Stpo~ AND $entity:Me AND $entity:Now " +
            "AND ($entity:Don't OR $entity:Stpo)"
        givenCapture(query, "revision-short-words")

        // When - searching for the title without an artist
        val titles = fuzzyTitles("Don't Stpo Me Now", "")

        // Then - short terms have no fuzzy suffix and the intended titles survive
        assertEquals(query, queries().single())
        assertEquals(3, titles.size)
        assertTrue(titles.all { it.replace('’', '\'').equals("Don't Stop Me Now", ignoreCase = true) })
    }

    @Test
    fun `hyphens and slashes separate title words`() = runTest {
        // Given - a captured pool for the words on either side of a title separator
        val query = "$entity:Love~ AND $entity:Hate~ AND ($entity:Love OR $entity:Hate)"
        givenCapture(query, "revision-joined-words")

        // When - searching the correctly spelled title with a hyphen and with a slash
        val hyphenated = fuzzyTitles("Love-Hate", "")
        val slashed = fuzzyTitles("Love/Hate", "")

        // Then - both shapes send the same word query and retain the captured related titles
        assertEquals(listOf(query, query), queries())
        assertEquals(3, hyphenated.size)
        assertEquals(hyphenated, slashed)
        assertEquals("Love Hate Love Hate", hyphenated.first())
    }

    @Test
    fun `overlapping fuzzy words require an exact long word too`() = runTest {
        // Given - a captured pool with an exact-word requirement in addition to fuzzy terms
        val query = "$entity:Rokc~ AND $entity:Roll~ AND ($entity:Rokc OR $entity:Roll)"
        givenCapture(query, "revision-overlapping-words")

        // When - searching for a title whose fuzzy words can both match Rolc
        val titles = fuzzyTitles("Rokc Roll", "")

        // Then - the request requires a long word exactly and the displayed titles remain related
        assertEquals(query, queries().single())
        assertEquals(3, titles.size)
        assertEquals(if (entity == "recording") "Rock, Roll, Roll" else "Rock@Roll", titles.first())
    }

    private suspend fun fuzzyTitles(title: String, artist: String, limit: Int = 3): List<String> =
        if (entity == "recording") {
            api.searchRecordingsFuzzy(title, artist, limit).map { it.title }
        } else {
            api.searchReleasesFuzzy(title, artist, limit).map { it.title }
        }

    private fun capture(name: String): String = checkNotNull(
        javaClass.getResourceAsStream("/pools/musicbrainz-fuzzy-title-only/$entity-$name.json"),
    ).bufferedReader().use { it.readText() }

    private fun givenCapture(query: String, name: String) {
        httpClient.givenJsonResponse(
            "query=${java.net.URLEncoder.encode(query, Charsets.UTF_8.name())}&",
            capture(name),
        )
    }

    private fun queries(): List<String> = httpClient.requestedUrls.map {
        URLDecoder.decode(it.substringAfter("query=").substringBefore("&"), Charsets.UTF_8.name())
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun entities(): List<String> = listOf("recording", "release")
    }
}
