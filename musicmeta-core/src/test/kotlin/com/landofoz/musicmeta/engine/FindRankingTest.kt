package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentLogger
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.http.HttpResult
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.provider.itunes.ITunesProvider
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzProvider
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FindRankingTest {
    private val http = FakeHttpClient()
    private val warnings = mutableListOf<String>()
    private val logger = object : EnrichmentLogger {
        override fun debug(tag: String, message: String) = Unit
        override fun warn(tag: String, message: String, throwable: Throwable?) { warnings.add(message) }
    }

    private fun engine(itunesLimiter: RateLimiter = RateLimiter(0)): EnrichmentEngine = EnrichmentEngine.Builder()
        .httpClient(http)
        .addProvider(MusicBrainzProvider(http, RateLimiter(0)))
        .addProvider(ITunesProvider(http, itunesLimiter))
        .logger(logger)
        .build()

    @Test
    fun `title-only search retrieves the popular artist beyond the general pool`() = runTest {
        // Given - the general pool holds covers and iTunes leads with Radiohead.
        stubKarma()
        val engine = engine()

        // When - a visitor searches without an artist.
        val found = engine.search(EnrichmentRequest.forTrack("Karma Police", ""), 8)

        // Then - a MusicBrainz recording by the popular artist leads the covers.
        assertEquals("Radiohead", found.first().artist)
        assertEquals("musicbrainz", found.first().provider)
        assertTrue(http.requestedUrls.any { it.contains("-comment") || it.contains("%2Dcomment") })
        assertEquals(2, http.requestedUrls.count { it.contains("musicbrainz.org") })
        assertEquals(1, http.requestedUrls.count { it.contains("itunes.apple.com") })
        engine.close()
    }

    @Test
    fun `title-only search uses an artist whose popular track is a qualified version`() = runTest {
        // Given - iTunes leads with Radiohead's acoustic version before other artists' bare titles.
        http.givenJsonResponse("artistname%3A%22Radiohead%22", fixture("creep-main"))
        http.givenJsonResponse("recording?query", fixture("creep-pool"))
        http.givenJsonResponse("itunes.apple.com", fixture("creep-itunes"))
        val engine = engine()

        // When - a visitor searches for the unqualified track title.
        val found = engine.search(EnrichmentRequest.forTrack("Creep", ""), 8)

        // Then - the artist hint retrieves an exact-title main recording rather than the variant.
        assertEquals("Radiohead", found.first().artist)
        assertEquals("Creep", found.first().title)
        assertTrue(found.first().disambiguation.isNullOrBlank())
        engine.close()
    }

    @Test
    fun `exact title precedes higher-scoring partial titles`() = runTest {
        // Given - MusicBrainz puts score-100 partial titles above score-90 exact titles.
        http.givenJsonResponse("recording?query", fixture("creep-pool"))
        val engine = engine()

        // When - a visitor searches for the bare title.
        val found = engine.search(EnrichmentRequest.forTrack("Creep", ""), 4)

        // Then - the exact title wins despite its lower upstream score.
        assertEquals("Creep", found.first().title)
        engine.close()
    }

    @Test
    fun `main recording precedes live recordings for a supplied artist`() = runTest {
        // Given - the unfiltered pool contains live takes and the canonical pool contains main takes.
        val live = JSONObject(fixture("karma-pool")).getJSONArray("recordings")
        val main = JSONArray().put(JSONObject(fixture("karma-main")).getJSONArray("recordings").getJSONObject(1))
        val rows = (0 until live.length()).map { live.getJSONObject(it) }
            .filter {
                it.optString("disambiguation").startsWith("live") &&
                    it.getJSONArray("artist-credit").getJSONObject(0).getJSONObject("artist").getString("name") == "Radiohead"
            }
        val combined = JSONArray(rows + (0 until main.length()).map { main.getJSONObject(it) })
        http.givenJsonResponse("comment", JSONObject().put("recordings", main).toString())
        http.givenJsonResponse("recording?query", JSONObject().put("recordings", combined).toString())
        val engine = engine()

        // When - a visitor supplies the artist.
        val found = engine.search(EnrichmentRequest.forTrack("Karma Police", "Radiohead"), 8)

        // Then - the main take leads and live alternatives remain selectable.
        assertEquals("Radiohead", found.first().artist)
        assertTrue(found.first().disambiguation.isNullOrBlank())
        assertTrue(found.any { it.disambiguation?.startsWith("live") == true })
        assertFalse(http.requestedUrls.any { it.contains("itunes.apple.com") })
        engine.close()
    }

    @Test
    fun `iTunes throttle degrades to ranked MusicBrainz results with a warning`() = runTest {
        // Given - iTunes refuses the signal request while MusicBrainz returns a mixed-title pool.
        http.givenHttpResult("itunes.apple.com", HttpResult.ClientError(403, "Forbidden"))
        http.givenJsonResponse("recording?query", fixture("creep-pool"))
        val engine = engine()

        // When - a visitor searches without an artist.
        val found = engine.search(EnrichmentRequest.forTrack("Creep", ""), 4)

        // Then - the search survives in MusicBrainz order and reports the unavailable signal.
        assertEquals("Creep", found.first().title)
        assertTrue(warnings.any { it.contains("iTunes") && it.contains("403") })
        engine.close()
    }

    @Test
    fun `identical pools yield identical order despite upstream tie reordering`() = runTest {
        // Given - successive upstream responses contain the same tied recordings in reverse order.
        val pool = JSONObject(fixture("karma-main")).getJSONArray("recordings")
        val forward = JSONObject().put("recordings", pool).toString()
        val reverse = JSONObject().put("recordings", JSONArray((0 until pool.length()).reversed().map { pool.get(it) })).toString()
        http.givenJsonResponse("comment", """{"recordings":[]}""")
        http.givenJsonResponsesInTurn("recording?query", forward, reverse)
        val engine = engine()
        val request = EnrichmentRequest.forTrack("Karma Police", "Radiohead")

        // When - the same search is repeated.
        val first = engine.search(request, 8)
        val second = engine.search(request, 8)

        // Then - stable identifiers settle every remaining tie.
        assertEquals(first, second)
        engine.close()
    }

    @Test
    fun `ranked search honours the caller limit`() = runTest {
        // Given - the candidate pools contain more hits than the caller wants.
        stubKarma()
        val engine = engine()

        // When - only one result is requested.
        val found = engine.search(EnrichmentRequest.forTrack("Karma Police", ""), 1)

        // Then - ranking happens before the final truncation.
        assertEquals(1, found.size)
        assertEquals("Radiohead", found.single().artist)
        engine.close()
    }

    @Test
    fun `album title-only search uses the popular album artist`() = runTest {
        // Given - a homonymous album precedes Radiohead in MusicBrainz but follows it in iTunes.
        http.givenJsonResponse("release?query", fixture("ok-pool"))
        http.givenJsonResponse("itunes.apple.com", fixture("ok-itunes"))
        val engine = engine()

        // When - a visitor searches for the album without an artist.
        val found = engine.search(EnrichmentRequest.forAlbum("OK Computer", ""), 4)

        // Then - Radiohead leads the exact-title albums.
        assertEquals("Radiohead", found.first().artist)
        engine.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `repeated title-only searches retain popularity across the iTunes rate-limit wait`() = runTest {
        // Given - iTunes uses its normal three-second interval and the upstream pools stay unchanged.
        stubKarma()
        val limiter = RateLimiter(3000) { testScheduler.currentTime + 100_000 }
        val engine = engine(limiter)
        val request = EnrichmentRequest.forTrack("Karma Police", "")

        // When - the same title is searched again immediately.
        val first = engine.search(request, 8)
        val second = engine.search(request, 8)

        // Then - waiting for the healthy provider does not discard its popularity signal.
        assertEquals("Radiohead", first.first().artist)
        assertEquals(first, second)
        assertTrue(warnings.joinToString("\n"), warnings.isEmpty())
        engine.close()
    }

    private fun stubKarma() {
        http.givenJsonResponse("comment", fixture("karma-main"))
        http.givenJsonResponse("recording?query", fixture("karma-pool"))
        http.givenJsonResponse("itunes.apple.com", fixture("karma-itunes"))
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/find-ranking/$name.json")).readText()
}
