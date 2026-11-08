package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentLogger
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.http.EnrichDeadline
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.provider.itunes.ITunesProvider
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzProvider
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SearchHintBudgetTest {
    private val http = FakeHttpClient()
    private val debug = mutableListOf<String>()
    private val warnings = mutableListOf<String>()
    private val logger = object : EnrichmentLogger {
        override fun debug(tag: String, message: String) { debug.add(message) }
        override fun warn(tag: String, message: String, throwable: Throwable?) { warnings.add(message) }
    }

    @Test
    fun `short-budget title searches skip the rate-limited iTunes hint`() = runTest {
        // Given - a recent direct iTunes request has warmed the three-second limiter interval.
        stubKarma()
        val limiter = RateLimiter(3000, clock = { 10_000L + testScheduler.currentTime })
        val itunes = ITunesProvider(http, limiter)
        itunes.searchTitleArtists("Karma Police", false)
        val engine = EnrichmentEngine.Builder()
            .httpClient(http)
            .config(EnrichmentConfig(enrichTimeoutMs = 4000))
            .addProvider(MusicBrainzProvider(http, RateLimiter(0)))
            .addProvider(itunes)
            .logger(logger)
            .buildOn(StandardTestDispatcher(testScheduler))

        // When - two title-only track searches run less than three virtual seconds apart.
        val found = try {
            listOf(
                engine.search(EnrichmentRequest.forTrack("Karma Police", ""), 8),
                engine.search(EnrichmentRequest.forTrack("Karma Police", ""), 8),
            )
        } finally {
            engine.close()
        }

        // Then - MusicBrainz answers both searches without an iTunes wait or timeout warning.
        assertTrue(found.all { candidates -> candidates.isNotEmpty() && candidates.all { it.provider == "musicbrainz" } })
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(1, http.requestedUrls.count { it.contains("itunes.apple.com") })
        assertEquals(2, debug.count { it == SKIP_MESSAGE })
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `long-budget title hints retain the captured artist order after the limiter wait`() = runTest {
        // Given - the real iTunes hint path has a thirty-second deadline and a captured response.
        val limiter = RateLimiter(3000, clock = { 10_000L + testScheduler.currentTime })
        val itunes = ITunesProvider(http, limiter)
        http.givenJsonResponse("itunes.apple.com", fixture("karma-itunes"))
        val policy = SearchArtistHints(ProviderRegistry(listOf(itunes)), logger)
        val request = EnrichmentRequest.forTrack("Karma Police", "")

        // When - the hint policy runs twice in the same deadline context.
        val found = withContext(EnrichDeadline(30_000)) {
            listOf(
                policy.artists(request, itunes, itunes::searchTitleArtists),
                policy.artists(request, itunes, itunes::searchTitleArtists),
            )
        }

        // Then - both calls return the captured order and the second call waits one limiter interval.
        assertEquals(listOf("Radiohead", "Pierce the Veil", "Julian Lennon"), found[0])
        assertEquals(found[0], found[1])
        assertEquals(2, http.requestedUrls.count { it.contains("itunes.apple.com") })
        assertEquals(3000L, testScheduler.currentTime)
        assertTrue(warnings.isEmpty())
    }

    private fun stubKarma() {
        http.givenJsonResponse("comment", fixture("karma-main"))
        http.givenJsonResponse("recording?query", fixture("karma-pool"))
        http.givenJsonResponse("itunes.apple.com", fixture("karma-itunes"))
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/find-ranking/$name.json")).readText()

    private companion object {
        const val SKIP_MESSAGE =
            "iTunes ranking signal skipped: budget does not exceed the 3000ms iTunes rate-limit interval"
    }
}
