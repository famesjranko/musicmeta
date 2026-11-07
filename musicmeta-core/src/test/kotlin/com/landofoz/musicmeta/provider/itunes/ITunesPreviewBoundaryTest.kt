package com.landofoz.musicmeta.provider.itunes

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ITunesPreviewBoundaryTest(private val requested: String, private val returned: String) {
    @Test
    fun `ordinary title words are not featured credits`() = runTest {
        // Given - a captured song row has its title replaced with a boundary-case title.
        val body = JSONObject(checkNotNull(javaClass.getResource("/fixtures/itunes-preview/search-song.json")).readText())
        val row = body.getJSONArray("results").getJSONObject(0).put("trackName", returned)
        body.put("results", org.json.JSONArray().put(row)).put("resultCount", 1)
        val http = FakeHttpClient()
        http.givenJsonResponse("itunes.apple.com/search", body.toString())
        val provider = ITunesProvider(http, RateLimiter(0))

        // When - requesting a different title by the same primary artist.
        val result = provider.enrich(EnrichmentRequest.forTrack(requested, row.getString("artistName")),
            EnrichmentType.TRACK_PREVIEW)

        // Then - a shared prefix or an empty stripped title cannot establish the same recording.
        assertTrue("$requested accepted $returned as $result", result is EnrichmentResult.NotFound)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} versus {1}")
        fun cases(): List<Array<String>> = listOf(
            arrayOf("Six Ft. Under", "Six"),
            arrayOf("Six Ft. Under", "Six Ft. Deep"),
            arrayOf("10 Ft Tall", "10"),
            arrayOf("A Feat of Strength", "A"),
            arrayOf("Welcome to Ft. Lauderdale", "Welcome to Ft. Worth"),
            arrayOf("Drum Solo Featuring John Bonham", "Drum Solo"),
            arrayOf("(Feat. You)", "(Feat. Me)"),
        )
    }
}

class ITunesPreviewUrlTest {
    @Test
    fun `a malformed preview URL does not hide a later matching recording`() = runTest {
        // Given - the first of two captured matching rows has a syntactically invalid URL.
        val body = JSONObject(checkNotNull(javaClass.getResource("/fixtures/itunes-preview/girls.json")).readText())
        val rows = body.getJSONArray("results")
        val first = rows.getJSONObject(1).put("previewUrl", "https://audio.example/[invalid")
        val second = rows.getJSONObject(2)
        body.put("results", org.json.JSONArray().put(first).put(second)).put("resultCount", 2)
        val http = FakeHttpClient()
        http.givenJsonResponse("itunes.apple.com/search", body.toString())
        val provider = ITunesProvider(http, RateLimiter(0))

        // When - requesting the credited recording.
        val result = provider.enrich(EnrichmentRequest.forTrack("Girls Like You (feat. Cardi B)", "Maroon 5"),
            EnrichmentType.TRACK_PREVIEW)

        // Then - the valid later row supplies the preview rather than a provider error.
        assertEquals(second.getLong("trackId").toString(),
            (result as? EnrichmentResult.Success)?.resolvedIdentifiers?.extra?.get("itunesTrackId"))
    }
}
