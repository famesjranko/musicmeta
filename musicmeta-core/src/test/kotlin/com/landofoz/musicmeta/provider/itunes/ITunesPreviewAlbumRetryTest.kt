package com.landofoz.musicmeta.provider.itunes

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ITunesPreviewAlbumRetryTest(private val firstPool: String, private val retryFindsTrack: Boolean) {
    @Test
    fun `an album search without an acceptable recording retries once through the shared limiter`() = runTest {
        // Given - the album-qualified search is empty or contains only captured live and other-artist rows.
        val captured = JSONObject(checkNotNull(javaClass.getResource("/fixtures/itunes-preview/journey-escape.json")).readText())
            .getJSONArray("results")
        val rows = (0 until captured.length()).map { captured.getJSONObject(it) }
            .filter { firstPool == "unacceptable" && (it.optString("artistName") != "Journey" || "Live" in it.optString("trackName")) }
        val response = JSONObject().put("resultCount", rows.size).put("results", JSONArray(rows)).toString()
        val broad = if (retryFindsTrack) {
            checkNotNull(javaClass.getResource("/fixtures/itunes-preview/journey-full.json")).readText()
        } else {
            response
        }
        val http = FakeHttpClient()
        http.givenJsonResponse(ITunesApi.previewSearchUrl("Journey Don't Stop Believin' Escape"), response)
        http.givenJsonResponse(ITunesApi.previewSearchUrl("Journey Don't Stop Believin'"), broad)
        val provider = ITunesProvider(http, RateLimiter(3000) { testScheduler.currentTime + 3000 })

        // When - the requested studio recording is searched with its album hint.
        val result = provider.enrich(EnrichmentRequest.forTrack("Don't Stop Believin'", "Journey", "Escape"),
            EnrichmentType.TRACK_PREVIEW)

        // Then - one broader search follows after the limiter interval and preserves the recording checks.
        assertEquals(listOf(ITunesApi.previewSearchUrl("Journey Don't Stop Believin' Escape"),
            ITunesApi.previewSearchUrl("Journey Don't Stop Believin'")), http.requestedUrls)
        assertEquals(3000L, testScheduler.currentTime)
        if (retryFindsTrack) {
            assertEquals("160024096", (result as? EnrichmentResult.Success)?.resolvedIdentifiers?.extra?.get("itunesTrackId"))
        } else {
            assertEquals(EnrichmentResult.NotFound(EnrichmentType.TRACK_PREVIEW, "itunes"), result)
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}, retry finds track: {1}")
        fun cases(): List<Array<Any>> = listOf(
            arrayOf("empty", true),
            arrayOf("unacceptable", true),
            arrayOf("unacceptable", false),
        )
    }
}
