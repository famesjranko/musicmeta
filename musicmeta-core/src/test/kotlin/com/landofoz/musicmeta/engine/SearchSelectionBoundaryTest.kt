package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentLogger
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.HttpResult
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.provider.itunes.ITunesApi
import com.landofoz.musicmeta.provider.itunes.ITunesProvider
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzProvider
import com.landofoz.musicmeta.testutil.FakeHttpClient
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchSelectionBoundaryTest {
    private val http = FakeHttpClient()
    private val warnings = mutableListOf<String>()
    private val logger = object : EnrichmentLogger {
        override fun debug(tag: String, message: String) = Unit
        override fun warn(tag: String, message: String, throwable: Throwable?) { warnings.add(message) }
    }

    private fun engine(client: HttpClient = http): EnrichmentEngine = EnrichmentEngine.Builder()
        .httpClient(client)
        .addProvider(MusicBrainzProvider(client, RateLimiter(0)))
        .addProvider(ITunesProvider(client, RateLimiter(0)))
        .logger(logger)
        .build()

    @Test
    fun `straight and curly apostrophe searches retain the Journey hint and main recording`() = runTest {
        // Given - captured Journey recordings have curly titles while the cover and iTunes use straight apostrophes.
        http.givenJsonResponse("artistname%3A%22Journey%22", fixture("apostrophe-canonical"))
        http.givenJsonResponse("recording?query", fixture("apostrophe"))
        http.givenJsonResponse("itunes.apple.com", fixture("apostrophe-itunes"))
        val engine = engine()

        // When - each spelling is searched through the engine.
        val straight = engine.search(EnrichmentRequest.forTrack("Don't Stop Believin'", ""), 2)
        val curly = engine.search(EnrichmentRequest.forTrack("Don’t Stop Believin’", ""), 2)

        // Then - both lead with the captured main recording and shared identity matching stays unchanged.
        assertEquals(listOf("Journey", "Journey"), listOf(straight.first().artist, curly.first().artist))
        assertEquals("d4b485be-f2be-4c97-9315-c57ab013c537", straight.first().identifiers.musicBrainzId)
        assertFalse(TitleMatcher.equivalent("Don't Stop Believin'", "Don’t Stop Believin’"))
        engine.close()
    }

    @Test
    fun `search hints fold typographic hyphens and ellipses without widening identity matching`() = runTest {
        // Given - a modelled song result uses ASCII punctuation for an otherwise identical title.
        http.givenJsonResponse("itunes.apple.com", """{"results":[{"trackName":"Rock-n-Roll...","artistName":"An Artist"}]}""")
        val api = ITunesApi(http, RateLimiter(0))

        // When - the artist hint is requested with a typographic hyphen and ellipsis.
        val found = api.searchTitleArtists("Rock‐n‐Roll…", false)

        // Then - search retains the hint while the shared matcher still treats those strings differently.
        assertEquals(listOf("An Artist"), found)
        assertFalse(TitleMatcher.equivalent("Rock‐n‐Roll…", "Rock-n-Roll..."))
    }

    @Test
    fun `engine supplements collapsed primary rows and preserves a different country`() = runTest {
        // Given - the primary provider repeats a visible row and the secondary offers that row plus another country.
        val base = SearchCandidate("Album", "primary", EnrichmentIdentifiers(musicBrainzId = "a"), 1f, artist = "Artist", year = 2000, country = "GB", releaseType = "Album")
        val requestedLimits = mutableListOf<Int>()
        val primary = object : FakeProvider(id = "primary", isIdentityProvider = true) {
            override suspend fun searchCandidates(request: EnrichmentRequest, limit: Int) = listOf(base, base.copy(identifiers = EnrichmentIdentifiers(musicBrainzId = "b")))
        }
        val secondary = object : FakeProvider(id = "secondary") {
            override suspend fun searchCandidates(request: EnrichmentRequest, limit: Int): List<SearchCandidate> {
                requestedLimits.add(limit)
                return listOf(base.copy(provider = id), base.copy(provider = id, country = "US")).take(limit)
            }
        }
        val engine = EnrichmentEngine.Builder().addProvider(primary).addProvider(secondary).build()

        // When - the caller requests up to three visible choices.
        val found = engine.search(EnrichmentRequest.forAlbum("Album", "Artist"), 3)

        // Then - the secondary gets the two freed slots and its different country survives deduplication.
        assertEquals(listOf(2), requestedLimits)
        assertEquals(listOf("GB", "US"), found.map { it.country })
        assertEquals(listOf("primary", "secondary"), found.map { it.provider })
        engine.close()
    }

    @Test
    fun `stalled popularity signal returns the general results within its budget and warns`() = runTest {
        // Given - the MusicBrainz pool answers but iTunes stalls beyond any useful interactive budget.
        http.givenJsonResponse("recording?query", fixture("duplicates"))
        val stalled = object : HttpClient by http {
            override suspend fun fetchJsonResult(url: String): HttpResult<JSONObject> {
                if (url.contains("itunes.apple.com")) delay(600_001)
                return http.fetchJsonResult(url)
            }
        }
        val engine = engine(stalled)

        // When - the caller makes a title-only search.
        val found = engine.search(EnrichmentRequest.forTrack("Karma Police", ""), 2)

        // Then - the signal timeout preserves real results and produces a warning within five virtual seconds.
        assertTrue(found.isNotEmpty())
        assertTrue(testScheduler.currentTime <= 5000)
        assertTrue(warnings.any { it.contains("iTunes") && it.contains("timed out") })
        engine.close()
    }

    private fun fixture(name: String): String = requireNotNull(javaClass.getResource("/find-selection/$name.json")).readText()
}
