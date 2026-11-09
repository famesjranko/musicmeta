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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder

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
    fun `search hints fold typography without widening identity matching`() = runTest {
        // Given - modelled song results use ASCII punctuation for otherwise identical titles.
        val titles = listOf(
            "Rock‐n‐Roll…" to "Rock-n-Roll...",
            "Rock‑n‑Roll…" to "Rock-n-Roll...",
            "Say “Hello”" to "Say \"Hello\"",
            "Don′t Stop" to "Don't Stop",
            "Donʼt Stop" to "Don't Stop",
        )
        val api = ITunesApi(http, RateLimiter(0))
        titles.forEach { (typographic, plain) ->
            val row = JSONObject().put("trackName", plain).put("artistName", "An Artist")
            val response = JSONObject().put("results", JSONArray().put(row)).toString()
            http.givenJsonResponse("term=${URLEncoder.encode(typographic, "UTF-8")}", response)
        }

        // When - each artist hint is requested with typographic punctuation.
        val found = titles.map { (typographic, _) -> api.searchTitleArtists(typographic, false) }

        // Then - search retains every hint while identity matching still distinguishes each spelling.
        assertEquals(titles.map { listOf("An Artist") }, found)
        titles.forEach { (typographic, plain) -> assertFalse(TitleMatcher.equivalent(typographic, plain)) }
    }

    @Test
    fun `album supplements do not repeat the captured MusicBrainz title and artist`() = runTest {
        // Given - both catalogs contain Radiohead's OK Computer with different scores and artwork.
        http.givenJsonResponse("release?query", requireNotNull(javaClass.getResource("/find-ranking/ok-pool.json")).readText())
        http.givenJsonResponse("itunes.apple.com", requireNotNull(javaClass.getResource("/find-ranking/ok-itunes.json")).readText())
        val engine = engine()

        // When - title-only discovery leaves room for secondary album results.
        val found = engine.search(EnrichmentRequest.forAlbum("OK Computer", ""), 8)

        // Then - the MusicBrainz album remains and iTunes contributes only other title and artist pairs.
        assertEquals("musicbrainz", found.first().provider)
        assertEquals("Radiohead", found.first().artist)
        assertTrue(http.requestedUrls.count { it.contains("itunes.apple.com") } >= 2)
        assertTrue(found.any { it.provider == "itunes" })
        assertFalse(found.any { it.provider == "itunes" && it.title == "OK Computer" && it.artist == "Radiohead" })
        engine.close()
    }

    @Test
    fun `engine supplements collapsed primary rows and preserves distinct editions`() = runTest {
        // Given - the primary provider repeats a visible row and the secondary offers that row plus distinct editions.
        val base = SearchCandidate("Album", "primary", EnrichmentIdentifiers(musicBrainzId = "a"), 1f, artist = "Artist", year = 2000, country = "GB", releaseType = "Album")
        val requestedLimits = mutableListOf<Int>()
        val primary = object : FakeProvider(id = "primary", isIdentityProvider = true) {
            override suspend fun searchCandidates(request: EnrichmentRequest, limit: Int) = listOf(base, base.copy(identifiers = EnrichmentIdentifiers(musicBrainzId = "b")))
        }
        val secondary = object : FakeProvider(id = "secondary") {
            override suspend fun searchCandidates(request: EnrichmentRequest, limit: Int): List<SearchCandidate> {
                requestedLimits.add(limit)
                return listOf(
                    base.copy(provider = id, title = " album ", artist = "ARTIST", matchScore = 0.7f, thumbnailUrl = "https://example.com/art.jpg"),
                    base.copy(provider = id, country = "US"),
                    base.copy(provider = id, year = 2001),
                    base.copy(provider = id, releaseType = "Single"),
                    base.copy(provider = id, disambiguation = "deluxe"),
                ).take(limit)
            }
        }
        val engine = EnrichmentEngine.Builder().addProvider(primary).addProvider(secondary).build()

        // When - the caller requests up to six visible choices.
        val found = engine.search(EnrichmentRequest.forAlbum("Album", "Artist"), 6)

        // Then - the secondary gets five free slots and explicit edition differences survive deduplication.
        assertEquals(listOf(5), requestedLimits)
        assertEquals(listOf("GB", "US", "GB", "GB", "GB"), found.map { it.country })
        assertEquals(listOf(2000, 2000, 2001, 2000, 2000), found.map { it.year })
        assertEquals(listOf("Album", "Album", "Album", "Single", "Album"), found.map { it.releaseType })
        assertEquals(listOf(null, null, null, null, "deluxe"), found.map { it.disambiguation })
        assertEquals(listOf("primary", "secondary", "secondary", "secondary", "secondary"), found.map { it.provider })
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

    @Test
    fun `engine search reports a canonical supplement failure and retains candidates`() = runTest {
        // Given - the general MusicBrainz pool succeeds and its canonical supplement fails.
        http.givenIoException("comment")
        http.givenJsonResponse("recording?query", fixture("duplicates"))
        val engine = EnrichmentEngine.Builder()
            .httpClient(http)
            .addProvider(MusicBrainzProvider(http, RateLimiter(0)))
            .logger(logger)
            .build()

        try {
            // When - the caller searches through the engine with a supplied artist.
            val found = engine.search(EnrichmentRequest.forTrack("Karma Police", "Radiohead"), 8)

            // Then - both expected general candidates survive and the engine logger reports the failed supplement.
            assertTrue(
                found.map { it.identifiers.musicBrainzId }.containsAll(
                    listOf(
                        "262bef76-1b10-4c8a-b2be-a9921a33b4fc",
                        "32d643f1-5650-434b-89fc-6aafc058c529",
                    ),
                ),
            )
            assertTrue(warnings.any { it.contains("Canonical candidate search failed") })
        } finally {
            engine.close()
        }
    }

    private fun fixture(name: String): String = requireNotNull(javaClass.getResource("/find-selection/$name.json")).readText()
}
