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
import java.util.Locale

@RunWith(Parameterized::class)
class MusicBrainzFuzzyTitleOnlyTest(private val entity: String) {
    private val httpClient = FakeHttpClient()
    private val api = MusicBrainzApi(httpClient, RateLimiter(0))

    @Test
    fun `a title-only typo returns related candidates from the live capture`() = runTest {
        // Given - captured responses for title-word, unfielded-word and empty-artist queries
        givenCapture(
            "$entity:Karma~ AND $entity:Polise~ AND ($entity:Karma OR $entity:Polise)",
            "karma-polise-title-case-exact-word",
        )
        givenCapture("$entity:Karma~ AND $entity:Polise~", "karma-polise-title-case")
        givenCapture("$entity:karma~ AND $entity:polise~", "karma-polise")
        givenCapture("$entity:Karma Polise~", "karma-polise-unfielded-word")
        httpClient.givenJsonResponse("$entity?query", capture("karma-polise-empty-artist"))

        // When - searching for the misspelled title without an artist
        val titles = fuzzyTitles("Karma Polise", "")

        // Then - every candidate is the intended title and the search costs one request
        assertEquals(listOf("Karma Police", "Karma Police", "Karma Police"), titles)
        assertEquals("$entity:karma~ AND $entity:polise~", queries().single())
    }

    @Test
    fun `a one-word typo retains fuzzy title matching`() = runTest {
        // Given - a captured response to the one-word fuzzy query
        givenCapture("$entity:Paranojd~", "paranojd-title-case")
        givenCapture("$entity:paranojd~", "paranojd")

        // When - searching for a one-word typo without an artist
        val titles = fuzzyTitles("Paranojd", "")

        // Then - the related titles are retained in upstream order in one request
        val expected = if (entity == "recording") {
            listOf("Paranoid, Paranoia", "Paranoid, Paranoia", "Paranoid (Prelude to Paranoia)")
        } else {
            listOf("Paranoid", "Paranoid", "Paranoid")
        }
        assertEquals(expected, titles)
        assertEquals("$entity:paranojd~", queries().single())
    }

    @Test
    fun `a correctly spelled title still returns related candidates`() = runTest {
        // Given - a captured response to the correctly spelled title query
        givenCapture(
            "$entity:Karma~ AND $entity:Police~ AND ($entity:Karma OR $entity:Police)",
            "karma-police-title-case-exact-word",
        )
        givenCapture("$entity:Karma~ AND $entity:Police~", "karma-police-title-case")
        givenCapture("$entity:karma~ AND $entity:police~", "karma-police")

        // When - searching for the correct spelling without an artist
        val titles = fuzzyTitles("Karma Police", "")

        // Then - all three captured candidates survive and every title term is required
        assertEquals(listOf("Karma Police", "Karma Police", "Karma Police"), titles)
        assertEquals("$entity:karma~ AND $entity:police~", queries().single())
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
            "$entity:karma~ AND $entity:polise~",
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
    fun `candidate search uses the title-word fuzzy pool after a strict miss`() = runTest {
        // Given - a strict miss and the captured title-word fuzzy response
        givenCapture(
            "$entity:Karma~ AND $entity:Polise~ AND ($entity:Karma OR $entity:Polise)",
            "karma-polise-title-case-exact-word",
        )
        givenCapture("$entity:Karma~ AND $entity:Polise~", "karma-polise-title-case")
        givenCapture("$entity:karma~ AND $entity:polise~", "karma-polise")
        givenCapture("$entity:\"Karma Polise\" AND artistname:\"\"", "karma-polise-quoted-empty-artist")
        httpClient.givenJsonResponse("$entity?query", capture("karma-polise-empty-artist"))
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
        assertEquals("$entity:\"Karma Polise\" AND artistname:\"\"", queries().first())
        assertEquals("$entity:karma~ AND $entity:polise~", queries().last())
    }

    @Test
    fun `a short CJK typo returns no candidates without searching`() = runTest {
        // Given - the captured unrelated rows returned for a two-character fuzzy term
        httpClient.givenJsonResponse("$entity?query", capture("short-cjk-fuzzy"))

        // When - searching for the short CJK typo without an artist
        val titles = fuzzyTitles("東凉", "")

        // Then - the unsafe fallback is refused before any request is sent
        assertEquals(emptyList<String>(), titles)
        assertEquals(emptyList<String>(), httpClient.requestedUrls)
    }

    @Test
    fun `a symbol title returns no candidates without searching`() = runTest {
        // Given - the captured unrelated rows returned for fuzzy letters with musical symbols
        httpClient.givenJsonResponse("$entity?query", capture("symbols-fuzzy"))

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
        httpClient.givenJsonResponse("%22", capture("short-cjk-strict"))
        httpClient.givenJsonResponse("$entity?query", capture("short-cjk-fuzzy"))
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
        val query = "$entity:don't~ AND $entity:stpo~ AND $entity:me AND $entity:now"
        givenCapture(query, "dont-stpo-me-now")

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
        val query = "$entity:love~ AND $entity:hate~"
        givenCapture(query, "love-hate")

        // When - searching the correctly spelled title with a hyphen and with a slash
        val hyphenated = fuzzyTitles("Love-Hate", "")
        val slashed = fuzzyTitles("Love/Hate", "")

        // Then - both shapes send the same word query and retain the captured related titles
        assertEquals(listOf(query, query), queries())
        assertEquals(3, hyphenated.size)
        assertEquals(hyphenated, slashed)
        assertEquals(if (entity == "recording") "Love Hater (Hate for Love)" else "Love Hate Fate", hyphenated.first())
    }

    @Test
    fun `a title with two misspelled words still finds the intended title`() = runTest {
        // Given - captured fuzzy-only and exact-word-constrained pools for a double typo
        val query = "$entity:hotle~ AND $entity:califonia~"
        givenCapture(query, "double-typo")
        givenCapture("$query AND ($entity:hotle OR $entity:califonia)", "double-typo-exact-word")

        // When - every long word in the title is misspelled
        val titles = fuzzyTitles("hotle califonia", "")

        // Then - the intended title leads from the fuzzy-only query
        assertEquals(3, titles.size)
        assertTrue(titles.first().startsWith("Hotel California"))
        assertEquals(query, queries().single())
    }

    @Test
    fun `boolean title words remain lowercase terms`() = runTest {
        // Given - captured lowercase-term pools and unrelated pools from uppercase operators
        val inputs = listOf("Rokc AND Roll", "Now OR Nevr", "NOT Afriad")
        val expectedQueries = listOf(
            "$entity:rokc~ AND $entity:and AND $entity:roll~",
            "$entity:now AND $entity:or AND $entity:nevr~",
            "$entity:not AND $entity:afriad~",
        )
        listOf("and", "or", "not").zip(expectedQueries).forEach { (name, query) ->
            givenCapture(query, "$name-lowercase")
        }
        givenCapture("$entity:Rokc~ AND $entity:AND AND $entity:Roll~", "and-uppercase")
        givenCapture(
            "$entity:Rokc~ AND $entity:AND AND $entity:Roll~ AND ($entity:Rokc OR $entity:Roll)",
            "and-uppercase-exact-word",
        )
        givenCapture("$entity:Now AND $entity:OR AND $entity:Nevr~", "or-uppercase")
        givenCapture("$entity:NOT AND $entity:Afriad~", "not-uppercase")

        // When - uppercase Boolean words arrive as part of the title
        val titles = inputs.map { fuzzyTitles(it, "") }

        // Then - all three are literal lowercase terms and the captured related rows survive
        assertEquals(expectedQueries, queries())
        assertEquals(listOf(3, 3, 3), titles.map { it.size })
        val expectedFirst = if (entity == "recording") {
            listOf("Rock and Roll, Rock and Roll", "Now or Never Now", "I'm Not Afriad")
        } else {
            listOf("Rock and Roll", "Now or Never Now", "Not Afraid, Not Afraid")
        }
        assertEquals(expectedFirst, titles.map { it.first() })
    }

    @Test
    fun `Thai combining marks stay inside one fuzzy word`() = runTest {
        // Given - captures for the complete Thai word and the query that split its vowel mark
        val query = "$entity:ความรัก~"
        givenCapture(query, "thai")
        givenCapture("$entity:ความร~ AND $entity:ก", "thai-split")

        // When - searching for the Thai title without an artist
        val titles = fuzzyTitles("ความรัก", "")

        // Then - one word is queried and the recording pool retains its three exact-title rows
        assertEquals(query, queries().single())
        assertEquals(if (entity == "recording") listOf("ความรัก", "ความรัก", "ความรัก") else emptyList(), titles)
    }

    @Test
    fun `title lowercasing is independent of the default locale`() = runTest {
        // Given - a Turkish default locale and a captured pool for the invariant lowercase query
        val originalLocale = Locale.getDefault()
        val query = "$entity:not AND $entity:afraid~"
        givenCapture(query, "not-afraid-lowercase")
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))

            // When - searching an all-caps title containing the letter I
            val titles = fuzzyTitles("NOT AFRAID", "")

            // Then - the query uses i rather than dotless I and retains the captured rows
            assertEquals(query, queries().single())
            assertEquals(3, titles.size)
        } finally {
            Locale.setDefault(originalLocale)
        }
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
